package com.sharewave.server;

import com.google.gson.Gson;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Jetty HttpServlet — handles all ShareWave API and page endpoints.
 *
 * Multipart uploads are handled by Jetty's built-in multipart support which
 * spools parts larger than a threshold directly to a temp file on disk,
 * so arbitrarily large uploads never blow the heap.
 */
public class ShareWaveHandler extends HttpServlet {

    // Jetty multipart limits — adjust as needed
    private static final long   MAX_FILE_SIZE    = 50L  * 1024 * 1024 * 1024; // 50 GB per file
    private static final long   MAX_REQUEST_SIZE = 50L  * 1024 * 1024 * 1024; // 50 GB per request
    private static final int    SPOOL_THRESHOLD  = 64   * 1024;                // spool to disk > 64 KB
    private static final int    BUF              = 64   * 1024;                // copy buffer

    private final Database         db;
    private final SessionManager   sessions;
    private final Path             uploadDir;
    private final Path             tempDir;
    private final ServerConfig     config;
    private final Gson             gson = new Gson();
    private final Consumer<String> logger;

    public ShareWaveHandler(Database db, SessionManager sessions,
                            Path uploadDir, ServerConfig config, Consumer<String> logger) {
        this.db        = db;
        this.sessions  = sessions;
        this.uploadDir = uploadDir;
        this.config    = config;
        this.logger    = logger;

        // Prefer a _tmp dir on the same filesystem as the upload dir so that
        // completing an upload is a cheap rename rather than a full copy.
        // Fall back to the system temp dir if _tmp cannot be created/written.
        Path preferred = uploadDir.resolve("_tmp");
        Path resolved  = preferred;
        try {
            Files.createDirectories(uploadDir);
            Files.createDirectories(preferred);
            // Verify we can actually write a file there
            Path probe = preferred.resolve(".write_test");
            Files.write(probe, new byte[]{0});
            Files.delete(probe);
            logger.accept("Spool dir  : " + preferred.toAbsolutePath());
        } catch (IOException e) {
            logger.accept("WARN: cannot write to spool dir " + preferred
                    + " (" + e.getMessage() + ") — falling back to system temp dir");
            resolved = Path.of(System.getProperty("java.io.tmpdir"), "sharewave-spool");
            try {
                Files.createDirectories(resolved);
                logger.accept("Spool dir  : " + resolved.toAbsolutePath() + " (fallback)");
            } catch (IOException ex) {
                logger.accept("ERROR: cannot create fallback spool dir: " + ex.getMessage());
            }
        }
        this.tempDir = resolved;
    }

    /**
     * Returns the MultipartConfigElement Jetty needs to enable multipart parsing.
     * Called by MainApp when registering the servlet.
     */
    public MultipartConfigElement multipartConfig() {
        return new MultipartConfigElement(
                tempDir.toAbsolutePath().toString(),
                MAX_FILE_SIZE,
                MAX_REQUEST_SIZE,
                SPOOL_THRESHOLD
        );
    }

    /**
     * Returns the on-disk path for a stored file owned by the given user.
     * Each user's uploads live in their own subdirectory under uploadDir:
     *   uploadDir/<sanitized-username>/<storedName>
     */
    private Path resolveStoredPath(int ownerId, String storedName) throws Exception {
        String username = db.getUsername(ownerId);
        return uploadDir.resolve(sanitizeUsername(username)).resolve(storedName);
    }

    // -----------------------------------------------------------------------
    // Dispatch
    // -----------------------------------------------------------------------

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type,Authorization");
        if ("OPTIONS".equals(req.getMethod())) { resp.setStatus(204); return; }
        try {
            super.service(req, resp);
        } catch (Exception e) {
            logger.accept("ERROR " + e.getMessage());
            sendJson(resp, 500, Map.of("error", "Internal server error"));
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
        if (path.equals("/") || path.equals("/index.html")) {
            serveHtml(resp);
        } else if (path.equals("/api/users")) {
            handleUsers(req, resp);
        } else if (path.equals("/api/files")) {
            handleListFiles(req, resp);
        } else if (path.startsWith("/api/files/") && path.endsWith("/access")) {
            handleGetAccess(req, resp, path);
        } else if (path.startsWith("/api/download/")) {
            handleDownload(req, resp, path.substring("/api/download/".length()));
        } else if (path.equals("/api/download-bundle")) {
            handleDownloadBundle(req, resp);
        } else {
            sendJson(resp, 404, Map.of("error", "Not found"));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
        switch (path) {
            case "/api/register" -> handleRegister(req, resp);
            case "/api/login"    -> handleLogin(req, resp);
            case "/api/logout"   -> handleLogout(req, resp);
            case "/api/upload"   -> handleUpload(req, resp);
            default              -> sendJson(resp, 404, Map.of("error", "Not found"));
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
        if (path.startsWith("/api/files/") && path.endsWith("/access"))
            handlePutAccess(req, resp, path);
        else
            sendJson(resp, 404, Map.of("error", "Not found"));
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
        if (path.startsWith("/api/delete/"))
            handleDelete(req, resp, path.substring("/api/delete/".length()));
        else
            sendJson(resp, 404, Map.of("error", "Not found"));
    }

    // -----------------------------------------------------------------------
    // Auth helpers
    // -----------------------------------------------------------------------

    private String tokenFromRequest(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7);
        if (req.getCookies() != null) {
            for (var c : req.getCookies())
                if ("session".equals(c.getName()))
                    return URLDecoder.decode(c.getValue(), StandardCharsets.UTF_8);
        }
        return null;
    }

    /** Returns userId, or -1 after writing a 401. */
    private int requireAuth(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        int uid = sessions.resolve(tokenFromRequest(req));
        if (uid < 0) sendJson(resp, 401, Map.of("error", "Not authenticated"));
        return uid;
    }

    // -----------------------------------------------------------------------
    // Endpoints
    // -----------------------------------------------------------------------

    private void handleRegister(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        var body = parseJsonBody(req);
        String username = body.getOrDefault("username", "").toString().trim();
        String password = body.getOrDefault("password", "").toString();
        if (username.isEmpty() || password.length() < 4) {
            sendJson(resp, 400, Map.of("error", "Username required; password >= 4 chars"));
            return;
        }
        try {
            int uid = db.registerUser(username, password);
            if (uid < 0) { sendJson(resp, 409, Map.of("error", "Username already taken")); return; }
            String token = sessions.createSession(uid);
            logger.accept("REGISTER " + username);
            sendJson(resp, 200, Map.of("token", token, "username", username.toLowerCase(),
                    "expiresInSeconds", sessions.timeoutSeconds()));
        } catch (Exception e) {
            sendJson(resp, 500, Map.of("error", e.getMessage()));
        }
    }

    private void handleLogin(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        var body = parseJsonBody(req);
        String username = body.getOrDefault("username", "").toString().trim();
        String password = body.getOrDefault("password", "").toString();
        try {
            int uid = db.login(username, password);
            if (uid < 0) { sendJson(resp, 401, Map.of("error", "Invalid credentials")); return; }
            String token = sessions.createSession(uid);
            logger.accept("LOGIN  " + username);
            sendJson(resp, 200, Map.of("token", token, "username", username.toLowerCase(),
                    "expiresInSeconds", sessions.timeoutSeconds()));
        } catch (Exception e) {
            sendJson(resp, 500, Map.of("error", e.getMessage()));
        }
    }

    private void handleLogout(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        sessions.invalidate(tokenFromRequest(req));
        sendJson(resp, 200, Map.of("ok", true));
    }

    private void handleUsers(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        int uid = requireAuth(req, resp); if (uid < 0) return;
        try {
            sendJson(resp, 200, Map.of("users", db.getAllUsernamesExcept(uid)));
        } catch (Exception e) { sendJson(resp, 500, Map.of("error", e.getMessage())); }
    }

    private void handleListFiles(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        int uid = requireAuth(req, resp); if (uid < 0) return;
        try {
            List<Database.FileRecord> files = db.getAccessibleFiles(uid);
            List<Map<String, Object>> result = new ArrayList<>();
            for (var f : files) {
                String owner = db.getUsername(f.ownerId);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id",             f.id);
                entry.put("filename",       f.filename);
                entry.put("size",           f.size);
                entry.put("owner",          owner != null ? owner : "?");
                entry.put("isOwner",        f.ownerId == uid);
                entry.put("isPublic",       f.isPublic);
                entry.put("uploaded",       f.uploaded);
                entry.put("expires",        f.expires);
                entry.put("lastDownloaded", f.lastDownloaded);
                entry.put("message",        f.message != null ? f.message : "");
                result.add(entry);
            }
            sendJson(resp, 200, Map.of("files", result));
        } catch (Exception e) { sendJson(resp, 500, Map.of("error", e.getMessage())); }
    }

    /**
     * Upload handler.
     *
     * Jetty spools the multipart "file" part to a temp file on disk (via
     * MultipartConfigElement) before this method is called, so getInputStream()
     * on the Part reads from disk — no large in-memory buffer.
     *
     * We stream from that spooled file into the final destination file.
     */
    private void handleUpload(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        int uid = requireAuth(req, resp); if (uid < 0) return;

        String ct = req.getContentType();
        if (ct == null || !ct.contains("multipart/form-data")) {
            sendJson(resp, 400, Map.of("error", "Expected multipart/form-data")); return;
        }

        Path dest = null;
        try {
            Part filePart    = req.getPart("file");
            Part allowedPart = req.getPart("allowedUsers");

            if (filePart == null || filePart.getSize() == 0) {
                sendJson(resp, 400, Map.of("error", "No file data received")); return;
            }

            // Build a safe filename — never blank, never contains path separators
            String origFilename = sanitizeFilename(filePart.getSubmittedFileName());
            if (origFilename.isBlank()) origFilename = "upload";

            // storedName is always a plain filename (no path separators).
            // Each user has their own subdirectory under uploadDir, created
            // on first upload: uploadDir/<sanitized-username>/<storedName>
            String storedName = UUID.randomUUID().toString() + "_" + origFilename;
            String userDirName = sanitizeUsername(db.getUsername(uid));
            Path userDir = uploadDir.resolve(userDirName);
            Files.createDirectories(userDir);
            dest = userDir.resolve(storedName);

            logger.accept("UPLOAD starting: " + origFilename
                    + " size=" + fmtBytes(filePart.getSize())
                    + " spool=" + tempDir + " dest=" + dest);

            // Check for existing file with same name owned by this user
            boolean replace = "true".equalsIgnoreCase(req.getParameter("replace"));
            Database.FileRecord existing = db.findByOwnerAndFilename(uid, origFilename);
            if (existing != null && !replace) {
                // Tell the client a duplicate exists; it will ask the user to confirm
                sendJson(resp, 409, Map.of(
                        "conflict",  true,
                        "filename",  existing.filename,
                        "size",      existing.size,
                        "uploaded",  existing.uploaded
                ));
                return;
            }

            // Jetty has already spooled the Part to a temp file in tempDir.
            // Obtain that spooled path via the Jetty-specific Part API so we
            // can do a cheap same-filesystem rename instead of a second copy.
            Path spooled = null;
            if (filePart instanceof org.eclipse.jetty.server.MultiPartFormInputStream.MultiPart mp) {
                spooled = mp.getFile() != null ? mp.getFile().toPath() : null;
            }

            if (spooled != null && Files.exists(spooled)) {
                // Same filesystem → atomic rename, no data copy at all
                Files.move(spooled, dest, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } else {
                // Fallback: stream from Part InputStream (works on any servlet container)
                try (InputStream  in  = new BufferedInputStream(filePart.getInputStream(), BUF);
                     OutputStream out = new BufferedOutputStream(
                             Files.newOutputStream(dest), BUF)) {
                    in.transferTo(out);
                }
            }

            long fileSize = Files.size(dest);

            // If replacing, delete the old file from disk and DB
            if (existing != null && replace) {
                Files.deleteIfExists(resolveStoredPath(existing.ownerId, existing.storedName));
                db.deleteFile(existing.id);
                logger.accept("REPLACE  old copy of " + origFilename + " deleted");
            }

            // Resolve access settings
            Part isPublicPart = req.getPart("isPublic");
            boolean isPublic  = isPublicPart != null &&
                    new String(isPublicPart.getInputStream().readAllBytes(),
                               StandardCharsets.UTF_8).trim().equals("true");

            String allowedCsv = allowedPart != null
                    ? new String(allowedPart.getInputStream().readAllBytes(),
                                 StandardCharsets.UTF_8).trim()
                    : "";
            List<Integer> allowedIds = new ArrayList<>();
            if (!isPublic && !allowedCsv.isBlank()) {
                for (String uname : allowedCsv.split(",")) {
                    uname = uname.trim();
                    if (!uname.isEmpty()) {
                        int auid = db.getUserId(uname);
                        if (auid > 0) allowedIds.add(auid);
                    }
                }
            }

            // expires: 0 = never, else Unix timestamp
            Part expiresPart = req.getPart("expires");
            long expires = 0;
            if (expiresPart != null) {
                String expiresStr = new String(expiresPart.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8).trim();
                if (!expiresStr.isEmpty() && !expiresStr.equals("0")) {
                    try { expires = Long.parseLong(expiresStr); } catch (NumberFormatException ignored) {}
                }
            }

            // Optional note from the uploader, shown to downloaders
            Part messagePart = req.getPart("message");
            String message = messagePart != null
                    ? sanitizeMessage(new String(messagePart.getInputStream().readAllBytes(),
                                                  StandardCharsets.UTF_8))
                    : null;

            int fid = db.insertFile(origFilename, storedName, uid, fileSize, isPublic, expires, allowedIds, message);
            logger.accept("UPLOAD ok: " + origFilename + " (" + fmtBytes(fileSize) + ") by "
                    + db.getUsername(uid));
            sendJson(resp, 200, Map.of("id", fid, "filename", origFilename, "size", fileSize));

        } catch (Exception e) {
            // Log class + message + first sharewave stack frame for diagnosis
            StringBuilder sb = new StringBuilder();
            sb.append("UPLOAD ERROR [").append(e.getClass().getSimpleName())
              .append("]: ").append(e.getMessage());
            for (StackTraceElement el : e.getStackTrace()) {
                if (el.getClassName().startsWith("com.sharewave")) {
                    sb.append(" @ ").append(el.getMethodName())
                      .append(":").append(el.getLineNumber());
                    break;
                }
            }
            logger.accept(sb.toString());
            // Clean up partial file
            if (dest != null) try { Files.deleteIfExists(dest); } catch (IOException ignored) {}
            sendJson(resp, 500, Map.of("error",
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    /**
     * Download handler — streams from disk to the client in 64 KB chunks.
     * Content-Length is set so the browser shows a real progress bar.
     */
    private void handleDownload(HttpServletRequest req, HttpServletResponse resp,
                                  String fileIdStr)
            throws IOException {
        int uid = requireAuth(req, resp); if (uid < 0) return;
        int fileId;
        try { fileId = Integer.parseInt(fileIdStr); }
        catch (NumberFormatException e) { sendJson(resp, 400, Map.of("error", "Bad id")); return; }

        try {
            if (!db.canAccess(fileId, uid)) {
                sendJson(resp, 403, Map.of("error", "Access denied")); return;
            }
            Database.FileRecord rec = db.getFile(fileId);
            if (rec == null) { sendJson(resp, 404, Map.of("error", "File not found")); return; }
            if (rec.isExpired()) {
                sendJson(resp, 410, Map.of("error", "This file has expired and is no longer available"));
                return;
            }

            Path filePath = resolveStoredPath(rec.ownerId, rec.storedName);
            if (!Files.exists(filePath)) {
                sendJson(resp, 404, Map.of("error", "File missing on disk")); return;
            }

            long fileSize = Files.size(filePath);
            String encoded = URLEncoder.encode(rec.filename, StandardCharsets.UTF_8)
                    .replace("+", "%20");

            resp.setContentType("application/octet-stream");
            resp.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
            resp.setContentLengthLong(fileSize);

            try (InputStream  in  = new BufferedInputStream(Files.newInputStream(filePath), BUF);
                 OutputStream out = new BufferedOutputStream(resp.getOutputStream(), BUF)) {
                in.transferTo(out);
            }
            db.updateLastDownloaded(fileId);
            logger.accept("DOWNLOAD " + rec.filename + " (" + fmtBytes(fileSize) + ") by "
                    + db.getUsername(uid));
        } catch (Exception e) {
            logger.accept("DOWNLOAD ERROR: " + e.getMessage());
        }
    }

    /**
     * GET /api/download-bundle?ids=1,2,3&format=zip|tar
     * Bundles multiple files the caller has access to into a single
     * .zip or .tar and streams it as one download. Files the caller can't
     * access, that are expired, or missing on disk are silently skipped
     * (rather than failing the whole bundle) so one bad id doesn't block
     * downloading the rest.
     */
    private void handleDownloadBundle(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        int uid = requireAuth(req, resp); if (uid < 0) return;

        String idsParam = req.getParameter("ids");
        if (idsParam == null || idsParam.isBlank()) {
            sendJson(resp, 400, Map.of("error", "No file ids provided")); return;
        }
        String format = req.getParameter("format");
        boolean useTar = "tar".equalsIgnoreCase(format);

        List<Integer> ids = new ArrayList<>();
        for (String part : idsParam.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try { ids.add(Integer.parseInt(part)); }
            catch (NumberFormatException ignored) { /* skip malformed id */ }
        }
        if (ids.isEmpty()) {
            sendJson(resp, 400, Map.of("error", "No valid file ids provided")); return;
        }

        // Collect the records we can actually serve, deduplicating filenames
        // within the archive (e.g. two different owners both named a file
        // "report.pdf") by suffixing " (2)", " (3)", etc.
        List<Database.FileRecord> toBundle = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        List<String> entryNames = new ArrayList<>();

        try {
            for (int fileId : ids) {
                if (!db.canAccess(fileId, uid)) continue;
                Database.FileRecord rec = db.getFile(fileId);
                if (rec == null || rec.isExpired()) continue;
                Path filePath = resolveStoredPath(rec.ownerId, rec.storedName);
                if (!Files.exists(filePath)) continue;

                toBundle.add(rec);
                entryNames.add(uniqueEntryName(rec.filename, usedNames));
            }
        } catch (Exception e) {
            sendJson(resp, 500, Map.of("error", "Failed to prepare bundle")); return;
        }

        if (toBundle.isEmpty()) {
            sendJson(resp, 404, Map.of("error", "None of the requested files are available")); return;
        }

        String archiveName = "sharewave-files-" + System.currentTimeMillis() / 1000
                + (useTar ? ".tar.gz" : ".zip");
        String encoded = URLEncoder.encode(archiveName, StandardCharsets.UTF_8).replace("+", "%20");
        resp.setContentType(useTar ? "application/gzip" : "application/zip");
        resp.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        // Size isn't known up front (zip/tar overhead varies), so this is
        // streamed without a Content-Length header (chunked transfer).

        try {
            if (useTar) {
                // Wrap with GZIPOutputStream to produce a compressed .tar.gz;
                // TarWriter writes to the gzip stream, which compresses on the fly.
                try (GZIPOutputStream gzip = new GZIPOutputStream(new BufferedOutputStream(resp.getOutputStream(), BUF));
                     TarWriter tw = new TarWriter(gzip)) {
                    for (int i = 0; i < toBundle.size(); i++) {
                        Database.FileRecord rec = toBundle.get(i);
                        Path filePath = resolveStoredPath(rec.ownerId, rec.storedName);
                        try (InputStream in = new BufferedInputStream(Files.newInputStream(filePath), BUF)) {
                            tw.putEntry(entryNames.get(i), Files.size(filePath), in);
                        }
                        db.updateLastDownloaded(rec.id);
                    }
                    tw.finish();
                }
            } else {
                try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(resp.getOutputStream(), BUF))) {
                    for (int i = 0; i < toBundle.size(); i++) {
                        Database.FileRecord rec = toBundle.get(i);
                        Path filePath = resolveStoredPath(rec.ownerId, rec.storedName);
                        ZipEntry entry = new ZipEntry(entryNames.get(i));
                        entry.setSize(Files.size(filePath));
                        zos.putNextEntry(entry);
                        try (InputStream in = new BufferedInputStream(Files.newInputStream(filePath), BUF)) {
                            in.transferTo(zos);
                        }
                        zos.closeEntry();
                        db.updateLastDownloaded(rec.id);
                    }
                }
            }
            logger.accept("DOWNLOAD-BUNDLE " + toBundle.size() + " file(s) as "
                    + (useTar ? "tar.gz" : "zip") + " by " + db.getUsername(uid));
        } catch (Exception e) {
            logger.accept("DOWNLOAD-BUNDLE ERROR: " + e.getMessage());
        }
    }

    /** Returns a filename guaranteed not to collide with anything already in {@code used}, adding " (2)", " (3)", etc. as needed. */
    private static String uniqueEntryName(String filename, Set<String> used) {
        if (used.add(filename)) return filename;
        String base = filename;
        String ext = "";
        int dot = filename.lastIndexOf('.');
        if (dot > 0) { base = filename.substring(0, dot); ext = filename.substring(dot); }
        int n = 2;
        String candidate;
        do {
            candidate = base + " (" + n + ")" + ext;
            n++;
        } while (!used.add(candidate));
        return candidate;
    }


    private void handleGetAccess(HttpServletRequest req, HttpServletResponse resp,
                                  String path) throws IOException {
        int uid = requireAuth(req, resp); if (uid < 0) return;
        int fileId = parseFileIdFromAccessPath(path);
        if (fileId < 0) { sendJson(resp, 400, Map.of("error", "Bad id")); return; }
        try {
            Database.FileRecord rec = db.getFile(fileId);
            if (rec == null) { sendJson(resp, 404, Map.of("error", "File not found")); return; }
            if (rec.ownerId != uid) { sendJson(resp, 403, Map.of("error", "Not owner")); return; }
            List<String> users = db.getFileAccessUsernames(fileId, uid);
            Database.FileRecord rec2 = db.getFile(fileId);
            sendJson(resp, 200, Map.of("fileId", fileId, "users", users,
                    "isPublic", rec2 != null && rec2.isPublic,
                    "expires",  rec2 != null ? rec2.expires : 0,
                    "message",  rec2 != null && rec2.message != null ? rec2.message : ""));
        } catch (Exception e) { sendJson(resp, 500, Map.of("error", e.getMessage())); }
    }

    /** PUT /api/files/{id}/access — replaces access list. Body: {"users":["alice","bob"]} */
    private void handlePutAccess(HttpServletRequest req, HttpServletResponse resp,
                                  String path) throws IOException {
        int uid = requireAuth(req, resp); if (uid < 0) return;
        int fileId = parseFileIdFromAccessPath(path);
        if (fileId < 0) { sendJson(resp, 400, Map.of("error", "Bad id")); return; }
        try {
            Database.FileRecord rec = db.getFile(fileId);
            if (rec == null) { sendJson(resp, 404, Map.of("error", "File not found")); return; }
            if (rec.ownerId != uid) { sendJson(resp, 403, Map.of("error", "Not owner")); return; }
            var body = parseJsonBody(req);
            boolean isPublic = Boolean.TRUE.equals(body.get("isPublic"));
            @SuppressWarnings("unchecked")
            List<String> users = body.containsKey("users")
                    ? (List<String>) body.get("users")
                    : List.of();
            // Update expiry if provided
            if (body.containsKey("expires")) {
                long exp = ((Number) body.get("expires")).longValue();
                db.updateFileExpiry(fileId, uid, exp);
            }
            // Update the uploader's note if provided
            if (body.containsKey("message")) {
                Object msg = body.get("message");
                db.updateFileMessage(fileId, uid, sanitizeMessage(msg != null ? msg.toString() : null));
            }
            db.updateFileAccess(fileId, uid, isPublic, users);
            logger.accept("ACCESS UPDATE file#" + fileId + " -> " + users
                    + " by " + db.getUsername(uid));
            sendJson(resp, 200, Map.of("ok", true));
        } catch (Exception e) { sendJson(resp, 500, Map.of("error", e.getMessage())); }
    }

    /** Extracts the numeric file id from /api/files/{id}/access */
    private static int parseFileIdFromAccessPath(String path) {
        try {
            // path = /api/files/123/access
            String[] parts = path.split("/");
            return Integer.parseInt(parts[parts.length - 2]);
        } catch (Exception e) { return -1; }
    }

    private void handleDelete(HttpServletRequest req, HttpServletResponse resp,
                               String fileIdStr)
            throws IOException {
        int uid = requireAuth(req, resp); if (uid < 0) return;
        int fileId;
        try { fileId = Integer.parseInt(fileIdStr); }
        catch (NumberFormatException e) { sendJson(resp, 400, Map.of("error", "Bad id")); return; }

        try {
            Database.FileRecord rec = db.getFile(fileId);
            if (rec == null) { sendJson(resp, 404, Map.of("error", "File not found")); return; }
            if (rec.ownerId != uid) {
                sendJson(resp, 403, Map.of("error", "Only owner can delete")); return;
            }
            Files.deleteIfExists(resolveStoredPath(rec.ownerId, rec.storedName));
            db.deleteFile(fileId);
            logger.accept("DELETE " + rec.filename + " by " + db.getUsername(uid));
            sendJson(resp, 200, Map.of("ok", true));
        } catch (Exception e) {
            sendJson(resp, 500, Map.of("error", e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // HTML
    // -----------------------------------------------------------------------

    private void serveHtml(HttpServletResponse resp) throws IOException {
        byte[] html = HtmlPage.get(config.getSiteTitle(), AppVersion.get()).getBytes(StandardCharsets.UTF_8);
        resp.setContentType("text/html; charset=utf-8");
        resp.setContentLength(html.length);
        resp.getOutputStream().write(html);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonBody(HttpServletRequest req) throws IOException {
        String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return gson.fromJson(body, Map.class);
    }

    private void sendJson(HttpServletResponse resp, int status, Object obj) throws IOException {
        byte[] data = gson.toJson(obj).getBytes(StandardCharsets.UTF_8);
        resp.setStatus(status);
        resp.setContentType("application/json; charset=utf-8");
        resp.setContentLength(data.length);
        resp.getOutputStream().write(data);
    }

    /**
     * Returns a safe plain filename. Takes only the last path component
     * (handles both / and \ separators), then strips control characters.
     * Returns "" if input is null or results in an empty string.
     */
    private static String sanitizeFilename(String name) {
        if (name == null) return "";
        // Take only the last component after any path separator
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash >= 0) name = name.substring(lastSlash + 1);
        // Strip null bytes and control characters
        name = name.replaceAll("[\\x00-\\x1f]", "").trim();
        return name;
    }

    /**
     * Sanitizes a username for use as a filesystem directory name.
     * Usernames are normally already restricted at registration time, but
     * this provides defense-in-depth against path traversal or unsafe
     * characters reaching the filesystem.
     */
    static String sanitizeUsername(String username) {
        if (username == null || username.isBlank()) return "_unknown";
        String safe = username.replaceAll("[^a-zA-Z0-9._-]", "_");
        // Avoid "." / ".." / hidden-file-like or empty results
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) return "_unknown";
        return safe;
    }

    private static final int MAX_MESSAGE_LENGTH = 500;

    /**
     * Cleans up an uploader-supplied note shown to downloaders: trims
     * whitespace, strips control characters (keeping newlines/tabs), and
     * caps length. Returns null if the result is empty (no note).
     */
    static String sanitizeMessage(String raw) {
        if (raw == null) return null;
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) return null;
        // Strip control characters except \n and \t
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\n' || c == '\t' || !Character.isISOControl(c)) sb.append(c);
        }
        String cleaned = sb.toString().strip();
        if (cleaned.isEmpty()) return null;
        if (cleaned.length() > MAX_MESSAGE_LENGTH) cleaned = cleaned.substring(0, MAX_MESSAGE_LENGTH);
        return cleaned;
    }

    private static String fmtBytes(long b) {
        if (b < 1024)                return b + " B";
        if (b < 1024 * 1024)         return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.2f MB", b / (1024.0 * 1024));
        return String.format("%.2f GB", b / (1024.0 * 1024 * 1024));
    }
}
