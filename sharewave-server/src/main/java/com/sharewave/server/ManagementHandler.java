package com.sharewave.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Processes management commands arriving as JSON strings from a connected GUI.
 *
 * Protocol: newline-delimited JSON.
 *   Request:  {"cmd":"<command>", ...params}
 *   Response: {"ok":true, ...data}  or  {"ok":false,"error":"<message>"}
 *   Event:    {"event":"log","msg":"<text>"}   (pushed by server to GUI)
 *
 * Commands:
 *   login            {"password":"..."}
 *   get_config       {}
 *   set_config       {"webPort":"...","mgmtPort":"...","uploadDir":"...","dbPath":"...","keystore":"..."}
 *   list_files       {}
 *   delete_file      {"fileId":123}
 *   set_file_expiry  {"fileId":123,"expires":0}
 *   list_users       {}
 *   create_user      {"username":"...","password":"..."}
 *   delete_user      {"username":"..."}
 *   reset_password   {"username":"...","password":"..."}
 *   list_archive     {}
 *   delete_archive   {"archiveId":123}
 *   ping             {}
 */
public class ManagementHandler {

    private final Database      db;
    private final ServerConfig  config;
    private final AdminConfig   adminConfig;
    private final Path          uploadDir;
    private final Path          archiveDir;
    private final Gson          gson = new Gson();
    private final Consumer<String> logger;

    /** Called by ManagementServer to push log lines to the connected GUI. */
    private Consumer<String> logPush;

    private boolean authenticated = false;

    public ManagementHandler(Database db, ServerConfig config, AdminConfig adminConfig,
                             Path uploadDir, Consumer<String> logger) {
        this.db          = db;
        this.config      = config;
        this.adminConfig = adminConfig;
        this.uploadDir   = uploadDir;
        this.archiveDir  = uploadDir.resolve("archive");
        this.logger      = logger;
    }

    public void setLogPush(Consumer<String> push) { this.logPush = push; }

    /**
     * Handles one JSON line from the GUI. Returns the JSON response line to send back.
     */
    public String handle(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            JsonObject req = JsonParser.parseString(line.trim()).getAsJsonObject();
            String cmd = req.has("cmd") ? req.get("cmd").getAsString() : "";
            Integer seq = req.has("_seq") ? req.get("_seq").getAsInt() : null;

            String result;
            // Login is always allowed
            if ("ping".equals(cmd))       result = ok(Map.of("pong", true));
            else if ("login".equals(cmd)) result = handleLogin(req);
            // All other commands require authentication
            else if (!authenticated)      result = err("Not authenticated");
            else result = switch (cmd) {
                case "get_config"      -> handleGetConfig();
                case "set_config"      -> handleSetConfig(req);
                case "list_files"      -> handleListFiles();
                case "delete_file"     -> handleDeleteFile(req);
                case "set_file_expiry" -> handleSetFileExpiry(req);
                case "list_users"      -> handleListUsers();
                case "create_user"     -> handleCreateUser(req);
                case "delete_user"     -> handleDeleteUser(req);
                case "reset_password"  -> handleResetPassword(req);
                case "list_archive"    -> handleListArchive();
                case "delete_archive"  -> handleDeleteArchive(req);
                case "get_file_access" -> handleGetFileAccess(req);
                case "set_file_access" -> handleSetFileAccess(req);
                case "get_log"         -> handleGetLog();
                case "get_disk_usage"  -> handleGetDiskUsage();
                default                -> err("Unknown command: " + cmd);
            };

            return withSeq(result, seq);
        } catch (Exception e) {
            return err("Server error: " + e.getMessage());
        }
    }

    /** Re-attaches the request's _seq value to a JSON response string, if present. */
    private String withSeq(String json, Integer seq) {
        if (seq == null) return json;
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        obj.addProperty("_seq", seq);
        return gson.toJson(obj);
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    private String handleLogin(JsonObject req) {
        String pw = req.has("password") ? req.get("password").getAsString() : "";
        if (adminConfig.checkPassword(pw)) {
            authenticated = true;
            logger.accept("MGMT login from GUI");
            return ok(Map.of("authenticated", true));
        }
        return err("Invalid password");
    }

    // ── Config ────────────────────────────────────────────────────────────────

    private String handleGetConfig() {
        return ok(Map.of(
            "webPort",     config.getWebPort(),
            "mgmtPort",    config.getMgmtPort(),
            "uploadDir",   config.getUploadDir(),
            "dbPath",      config.getDbPath(),
            "keystore",    config.getKeystore(),
            "theme",       config.getTheme(),
            "siteTitle",   config.getSiteTitle(),
            "siteVersion", AppVersion.get()
        ));
    }

    private String handleSetConfig(JsonObject req) {
        if (req.has("webPort"))     config.setWebPort(req.get("webPort").getAsString());
        if (req.has("mgmtPort"))    config.setMgmtPort(req.get("mgmtPort").getAsString());
        if (req.has("uploadDir"))   config.setUploadDir(req.get("uploadDir").getAsString());
        if (req.has("dbPath"))      config.setDbPath(req.get("dbPath").getAsString());
        if (req.has("keystore"))    config.setKeystore(req.get("keystore").getAsString());
        if (req.has("theme"))       config.setTheme(req.get("theme").getAsString());
        if (req.has("siteTitle"))   config.setSiteTitle(req.get("siteTitle").getAsString());
        config.save();
        logger.accept("MGMT config updated by GUI");
        return ok(Map.of("saved", true));
    }

    // ── Files ─────────────────────────────────────────────────────────────────

    private String handleListFiles() throws Exception {
        List<Database.FileRecord> files = db.getAllFiles();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Database.FileRecord f : files) {
            String owner = db.getUsername(f.ownerId);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id",             f.id);
            entry.put("filename",       f.filename);
            entry.put("size",           f.size);
            entry.put("owner",          owner != null ? owner : "?");
            entry.put("isPublic",       f.isPublic);
            entry.put("uploaded",       f.uploaded);
            entry.put("expires",        f.expires);
            entry.put("lastDownloaded", f.lastDownloaded);
            result.add(entry);
        }
        return ok(Map.of("files", result));
    }

    /**
     * get_log — returns the contents of the current log file
     * (uploadDir/logs/sharewave.log), capped to the last MAX_LOG_BYTES bytes
     * to avoid sending an excessively large response.
     */
    private String handleGetLog() throws Exception {
        final long MAX_LOG_BYTES = 512 * 1024; // 512 KB
        Path logFile = uploadDir.resolve("logs").resolve("sharewave.log");
        String content = "";
        if (Files.exists(logFile)) {
            long size = Files.size(logFile);
            if (size <= MAX_LOG_BYTES) {
                content = Files.readString(logFile);
            } else {
                // Read only the last MAX_LOG_BYTES bytes
                try (var raf = new RandomAccessFile(logFile.toFile(), "r")) {
                    long skip = size - MAX_LOG_BYTES;
                    raf.seek(skip);
                    byte[] buf = new byte[(int) MAX_LOG_BYTES];
                    raf.readFully(buf);
                    content = "... (log truncated) ...\n" + new String(buf, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return ok(Map.of("log", content));
    }

    /**
     * get_disk_usage — returns filesystem space info for the volume hosting
     * uploadDir, plus the total size of all files currently stored under
     * uploadDir (uploads + archive).
     */
    private String handleGetDiskUsage() throws Exception {
        File volume = uploadDir.toFile();
        // Ensure the directory exists so File space queries return real values
        if (!volume.exists()) volume.mkdirs();

        long totalBytes = volume.getTotalSpace();
        long freeBytes  = volume.getUsableSpace();   // space available to this process
        long usedBytes  = totalBytes - freeBytes;

        long shareWaveBytes = dirSize(uploadDir);

        return ok(Map.of(
                "totalBytes",      totalBytes,
                "freeBytes",       freeBytes,
                "usedBytes",       usedBytes,
                "shareWaveBytes",  shareWaveBytes,
                "uploadDir",       uploadDir.toAbsolutePath().toString()
        ));
    }

    /** Recursively sums the size of all regular files under the given directory. */
    private long dirSize(Path dir) {
        long[] total = {0L};
        if (!Files.exists(dir)) return 0L;
        try {
            Files.walk(dir).forEach(p -> {
                try {
                    if (Files.isRegularFile(p)) total[0] += Files.size(p);
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
        return total[0];
    }

    private String handleDeleteFile(JsonObject req) throws Exception {
        int fileId = req.get("fileId").getAsInt();
        Database.FileRecord rec = db.getFile(fileId);
        if (rec == null) return err("File not found");
        String userDirName = ShareWaveHandler.sanitizeUsername(db.getUsername(rec.ownerId));
        Files.deleteIfExists(uploadDir.resolve(userDirName).resolve(rec.storedName));
        db.deleteFile(fileId);
        logger.accept("MGMT deleted file: " + rec.filename);
        return ok(Map.of("deleted", true));
    }

    private String handleSetFileExpiry(JsonObject req) throws Exception {
        int  fileId  = req.get("fileId").getAsInt();
        long expires = req.get("expires").getAsLong();
        db.setFileExpiry(fileId, expires);
        logger.accept("MGMT set expiry for file#" + fileId);
        return ok(Map.of("updated", true));
    }

    // ── Users ─────────────────────────────────────────────────────────────────

    private String handleListUsers() throws Exception {
        return ok(Map.of("users", db.getAllUsernames()));
    }

    private String handleCreateUser(JsonObject req) throws Exception {
        String username = req.get("username").getAsString().trim();
        String password = req.get("password").getAsString();
        if (username.isEmpty() || password.length() < 4)
            return err("Username required and password >= 4 chars");
        int uid = db.registerUser(username, password);
        if (uid < 0) return err("Username already taken");
        logger.accept("MGMT created user: " + username);
        return ok(Map.of("created", true, "username", username));
    }

    private String handleDeleteUser(JsonObject req) throws Exception {
        String username = req.get("username").getAsString();
        db.deleteUser(username);
        logger.accept("MGMT deleted user: " + username);
        return ok(Map.of("deleted", true));
    }

    private String handleResetPassword(JsonObject req) throws Exception {
        String username = req.get("username").getAsString();
        String password = req.get("password").getAsString();
        if (password.length() < 4) return err("Password >= 4 chars");
        db.setPassword(username, password);
        logger.accept("MGMT reset password for: " + username);
        return ok(Map.of("updated", true));
    }

    // ── Archive ───────────────────────────────────────────────────────────────

    private String handleListArchive() throws Exception {
        List<Database.ArchivedRecord> recs = db.getArchivedFiles();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Database.ArchivedRecord r : recs)
            result.add(Map.of(
                "id",         r.id(),
                "filename",   r.filename(),
                "storedName", r.storedName(),
                "owner",      r.owner(),
                "size",       r.size(),
                "expiredAt",  r.expiredAt()
            ));
        return ok(Map.of("archive", result));
    }

    private String handleDeleteArchive(JsonObject req) throws Exception {
        int archiveId = req.get("archiveId").getAsInt();
        String storedName = db.deleteArchivedFile(archiveId);
        if (storedName != null) Files.deleteIfExists(archiveDir.resolve(storedName));
        logger.accept("MGMT deleted archived file id=" + archiveId);
        return ok(Map.of("deleted", true));
    }

    // ── File access management ───────────────────────────────────────────────

    /**
     * get_file_access {"fileId":123}
     * Returns current access settings for a file: isPublic, list of allowed usernames,
     * and a list of all other registered users (for the "add user" dropdown).
     */
    private String handleGetFileAccess(JsonObject req) throws Exception {
        int fileId = req.get("fileId").getAsInt();
        Database.FileRecord rec = db.getFile(fileId);
        if (rec == null) return err("File not found");
        List<String> accessUsers = db.getFileAccessUsernames(fileId, rec.ownerId);
        List<String> allUsers    = db.getAllUsernames();
        return ok(Map.of(
            "fileId",   fileId,
            "isPublic", rec.isPublic,
            "users",    accessUsers,
            "allUsers", allUsers
        ));
    }

    /**
     * set_file_access {"fileId":123,"isPublic":false,"users":["alice","bob"]}
     * Replaces the access list for a file. Admin can update any file.
     */
    private String handleSetFileAccess(JsonObject req) throws Exception {
        int fileId = req.get("fileId").getAsInt();
        Database.FileRecord rec = db.getFile(fileId);
        if (rec == null) return err("File not found");
        boolean isPublic = req.has("isPublic") && req.get("isPublic").getAsBoolean();
        List<String> users = new ArrayList<>();
        if (req.has("users")) {
            req.getAsJsonArray("users").forEach(e -> users.add(e.getAsString()));
        }
        db.updateFileAccess(fileId, rec.ownerId, isPublic, users);
        logger.accept("MGMT updated access for file#" + fileId +
                " isPublic=" + isPublic + " users=" + users);
        return ok(Map.of("updated", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String ok(Map<String, Object> data) {
        Map<String, Object> resp = new LinkedHashMap<>(data);
        resp.put("ok", true);
        return gson.toJson(resp);
    }

    private String err(String msg) {
        return gson.toJson(Map.of("ok", false, "error", msg));
    }

    /** Formats a log push event for sending to the GUI. */
    public static String logEvent(String msg) {
        return new Gson().toJson(Map.of("event", "log", "msg", msg));
    }
}
