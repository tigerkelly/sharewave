package com.sharewave;

import org.mindrot.jbcrypt.BCrypt;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages all SQLite interactions: users, files, and per-file access control.
 */
public class Database {

    private final String dbPath;
    private Connection conn;

    public Database(String dbPath) {
        this.dbPath = dbPath;
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    public void open() throws SQLException {
        new File(dbPath).getParentFile().mkdirs();
        // Use a URL with busy_timeout so concurrent requests wait instead of throwing
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath + "?busy_timeout=5000");
        // PRAGMAs must run outside any transaction (autocommit = true, which is the default)
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
        }
        initSchema();
    }

    public void close() {
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id       INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT    NOT NULL UNIQUE,
                    password TEXT    NOT NULL,
                    created  INTEGER NOT NULL DEFAULT (strftime('%s','now'))
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS files (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    filename   TEXT    NOT NULL,
                    storedName TEXT    NOT NULL UNIQUE,
                    owner_id   INTEGER NOT NULL REFERENCES users(id),
                    size       INTEGER NOT NULL,
                    is_public  INTEGER NOT NULL DEFAULT 0,
                    uploaded   INTEGER NOT NULL DEFAULT (strftime('%s','now'))
                )
            """);
            // Migrations for older DBs
            try { st.execute("ALTER TABLE files ADD COLUMN is_public INTEGER NOT NULL DEFAULT 0"); }
            catch (SQLException ignored) {}
            // expires = Unix timestamp, 0 means never expires
            try { st.execute("ALTER TABLE files ADD COLUMN expires INTEGER NOT NULL DEFAULT 0"); }
            catch (SQLException ignored) {}
            st.execute("""
                CREATE TABLE IF NOT EXISTS file_access (
                    file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
                    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    PRIMARY KEY (file_id, user_id)
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS archived_files (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    filename    TEXT    NOT NULL,
                    storedName  TEXT    NOT NULL UNIQUE,
                    owner       TEXT    NOT NULL,
                    size        INTEGER NOT NULL,
                    expired_at  INTEGER NOT NULL DEFAULT (strftime('%s','now'))
                )
            """);
        }
    }

    // -----------------------------------------------------------------------
    // Users
    // -----------------------------------------------------------------------

    /** Returns the new user's id, or -1 if username is taken. */
    public int registerUser(String username, String plainPassword) throws SQLException {
        String hashed = BCrypt.hashpw(plainPassword, BCrypt.gensalt());
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users(username,password) VALUES(?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username.trim().toLowerCase());
            ps.setString(2, hashed);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            return keys.next() ? keys.getInt(1) : -1;
        } catch (SQLException e) {
            if (e.getMessage().contains("UNIQUE")) return -1;
            throw e;
        }
    }

    /**
     * Validates credentials. Returns the user id on success, -1 on failure.
     */
    public int login(String username, String plainPassword) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, password FROM users WHERE username=?")) {
            ps.setString(1, username.trim().toLowerCase());
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return -1;
            String stored = rs.getString("password");
            return BCrypt.checkpw(plainPassword, stored) ? rs.getInt("id") : -1;
        }
    }

    /** Returns all usernames ordered alphabetically. */
    public List<String> getAllUsernames() throws SQLException {
        List<String> list = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT username FROM users ORDER BY username");
            while (rs.next()) list.add(rs.getString("username"));
        }
        return list;
    }

    /** Deletes a user and their file access rows. Files they own remain on disk. */
    public void deleteUser(String username) throws SQLException {
        int uid = getUserId(username);
        if (uid < 0) return;
        try (Statement st = conn.createStatement()) { st.execute("BEGIN"); }
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM file_access WHERE user_id=?")) {
                ps.setInt(1, uid); ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM users WHERE id=?")) {
                ps.setInt(1, uid); ps.executeUpdate();
            }
            try (Statement st = conn.createStatement()) { st.execute("COMMIT"); }
        } catch (SQLException e) {
            try (Statement st = conn.createStatement()) { st.execute("ROLLBACK"); }
            catch (SQLException ignored) {}
            throw e;
        }
    }

    /** Replaces the password for an existing user. */
    public void setPassword(String username, String plainPassword) throws SQLException {
        String hashed = org.mindrot.jbcrypt.BCrypt.hashpw(
                plainPassword, org.mindrot.jbcrypt.BCrypt.gensalt());
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET password=? WHERE username=?")) {
            ps.setString(1, hashed);
            ps.setString(2, username.trim().toLowerCase());
            ps.executeUpdate();
        }
    }

    /** Returns username for a given id, or null. */
    public String getUsername(int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT username FROM users WHERE id=?")) {
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("username") : null;
        }
    }

    /** Returns all usernames except the given userId. */
    public List<String> getAllUsernamesExcept(int userId) throws SQLException {
        List<String> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT username FROM users WHERE id != ? ORDER BY username")) {
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(rs.getString("username"));
        }
        return list;
    }

    /** Resolves a username to its id, or -1. */
    public int getUserId(String username) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM users WHERE username=?")) {
            ps.setString(1, username.trim().toLowerCase());
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt("id") : -1;
        }
    }

    // -----------------------------------------------------------------------
    // Files
    // -----------------------------------------------------------------------

    /** Inserts a file record and its access list. Returns the new file id. */
    public int insertFile(String filename, String storedName, int ownerId,
                          long size, boolean isPublic, long expires,
                          List<Integer> allowedUserIds) throws SQLException {
        // Use explicit SQL transactions instead of setAutoCommit to avoid
        // leaving the connection in a non-autocommit state on error
        try (Statement st = conn.createStatement()) {
            st.execute("BEGIN");
        }
        try {
            int fileId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO files(filename,storedName,owner_id,size,is_public,expires) VALUES(?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, filename);
                ps.setString(2, storedName);
                ps.setInt(3, ownerId);
                ps.setLong(4, size);
                ps.setInt(5, isPublic ? 1 : 0);
                ps.setLong(6, expires);
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                fileId = keys.next() ? keys.getInt(1) : -1;
            }
            // Always grant owner access too
            grantAccess(fileId, ownerId);
            if (!isPublic) {
                for (int uid : allowedUserIds) {
                    if (uid != ownerId) grantAccess(fileId, uid);
                }
            }
            try (Statement st = conn.createStatement()) { st.execute("COMMIT"); }
            return fileId;
        } catch (SQLException e) {
            try (Statement st = conn.createStatement()) { st.execute("ROLLBACK"); }
            catch (SQLException ignored) {}
            throw e;
        }
    }

    private void grantAccess(int fileId, int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO file_access(file_id,user_id) VALUES(?,?)")) {
            ps.setInt(1, fileId);
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
    }

    /** Returns usernames that have access to a file (excluding the owner). */
    public List<String> getFileAccessUsernames(int fileId, int ownerId) throws SQLException {
        List<String> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT u.username FROM users u
                JOIN file_access fa ON fa.user_id = u.id
                WHERE fa.file_id = ? AND u.id != ?
                ORDER BY u.username
            """)) {
            ps.setInt(1, fileId);
            ps.setInt(2, ownerId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(rs.getString("username"));
        }
        return list;
    }

    /**
     * Replaces the access settings for a file (owner always keeps access).
     * @param isPublic   true = any logged-in user can download
     * @param usernames  specific users to grant access (used when isPublic=false)
     */
    /** Updates the expiry timestamp unconditionally (admin use). 0 = never expires. */
    public void setFileExpiry(int fileId, long expires) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE files SET expires=? WHERE id=?")) {
            ps.setLong(1, expires);
            ps.setInt(2, fileId);
            ps.executeUpdate();
        }
    }

    /** Updates the expiry timestamp for a file. 0 = never expires. */
    public void updateFileExpiry(int fileId, int ownerId, long expires) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE files SET expires=? WHERE id=? AND owner_id=?")) {
            ps.setLong(1, expires);
            ps.setInt(2, fileId);
            ps.setInt(3, ownerId);
            ps.executeUpdate();
        }
    }

    public record ArchivedRecord(int id, String filename, String storedName,
                                     String owner, long size, long expiredAt) {}

    /**
     * Moves expired file records from files → archived_files.
     * Returns the records so the caller can move the files on disk.
     */
    public List<ArchivedRecord> purgeExpiredFiles() throws SQLException {
        List<ArchivedRecord> list = new ArrayList<>();
        // Gather expired files with owner username
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT f.id, f.filename, f.storedName, u.username, f.size, f.expires
                FROM files f
                JOIN users u ON u.id = f.owner_id
                WHERE f.expires > 0 AND f.expires <= strftime('%s','now')
            """)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next())
                list.add(new ArchivedRecord(
                        rs.getInt("id"), rs.getString("filename"),
                        rs.getString("storedName"), rs.getString("username"),
                        rs.getLong("size"), rs.getLong("expires")));
        }
        if (list.isEmpty()) return list;

        // Insert into archive table
        for (ArchivedRecord r : list) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO archived_files(filename,storedName,owner,size,expired_at) VALUES(?,?,?,?,?)")) {
                ps.setString(1, r.filename());
                ps.setString(2, r.storedName());
                ps.setString(3, r.owner());
                ps.setLong(4, r.size());
                ps.setLong(5, r.expiredAt());
                ps.executeUpdate();
            }
        }
        // Remove from live files table
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM files WHERE expires > 0 AND expires <= strftime('%s','now')");
        }
        return list;
    }

    /** Returns all archived file records, newest first. */
    public List<ArchivedRecord> getArchivedFiles() throws SQLException {
        List<ArchivedRecord> list = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT id,filename,storedName,owner,size,expired_at FROM archived_files ORDER BY expired_at DESC");
            while (rs.next())
                list.add(new ArchivedRecord(
                        rs.getInt("id"), rs.getString("filename"),
                        rs.getString("storedName"), rs.getString("owner"),
                        rs.getLong("size"), rs.getLong("expired_at")));
        }
        return list;
    }

    /** Deletes an archived record by id. Returns storedName for disk cleanup. */
    public String deleteArchivedFile(int id) throws SQLException {
        String storedName = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT storedName FROM archived_files WHERE id=?")) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) storedName = rs.getString("storedName");
        }
        if (storedName != null) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM archived_files WHERE id=?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
        }
        return storedName;
    }

    public void updateFileAccess(int fileId, int ownerId,
                                  boolean isPublic,
                                  List<String> usernames) throws SQLException {
        try (Statement st = conn.createStatement()) { st.execute("BEGIN"); }
        try {
            // Update public flag
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE files SET is_public=? WHERE id=?")) {
                ps.setInt(1, isPublic ? 1 : 0);
                ps.setInt(2, fileId);
                ps.executeUpdate();
            }
            // Rebuild specific access list
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM file_access WHERE file_id=? AND user_id!=?")) {
                ps.setInt(1, fileId);
                ps.setInt(2, ownerId);
                ps.executeUpdate();
            }
            if (!isPublic) {
                for (String uname : usernames) {
                    int uid = getUserId(uname);
                    if (uid > 0 && uid != ownerId) grantAccess(fileId, uid);
                }
            }
            try (Statement st = conn.createStatement()) { st.execute("COMMIT"); }
        } catch (SQLException e) {
            try (Statement st = conn.createStatement()) { st.execute("ROLLBACK"); }
            catch (SQLException ignored) {}
            throw e;
        }
    }

    public static class FileRecord {
        public final int     id;
        public final String  filename;
        public final String  storedName;
        public final int     ownerId;
        public final long    size;
        public final boolean isPublic;
        public final long    uploaded;
        public final long    expires;   // Unix timestamp; 0 = never
        public FileRecord(int id, String filename, String storedName,
                          int ownerId, long size, boolean isPublic,
                          long uploaded, long expires) {
            this.id = id; this.filename = filename; this.storedName = storedName;
            this.ownerId = ownerId; this.size = size; this.isPublic = isPublic;
            this.uploaded = uploaded; this.expires = expires;
        }
        public boolean isExpired() {
            return expires > 0 && System.currentTimeMillis() / 1000 > expires;
        }
    }

    /** Returns files this user may download (public, owner, or explicitly allowed). */
    public List<FileRecord> getAccessibleFiles(int userId) throws SQLException {
        List<FileRecord> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT DISTINCT f.id, f.filename, f.storedName, f.owner_id,
                                f.size, f.is_public, f.uploaded, f.expires
                FROM files f
                LEFT JOIN file_access fa ON fa.file_id = f.id AND fa.user_id = ?
                WHERE (f.is_public = 1 OR fa.user_id IS NOT NULL)
                  AND (f.expires = 0 OR f.expires > strftime('%s','now'))
                ORDER BY f.uploaded DESC
            """)) {
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next())
                list.add(new FileRecord(rs.getInt("id"), rs.getString("filename"),
                        rs.getString("storedName"), rs.getInt("owner_id"),
                        rs.getLong("size"), rs.getInt("is_public") == 1,
                        rs.getLong("uploaded"), rs.getLong("expires")));
        }
        return list;
    }

    /** Returns a file owned by the given user with the given filename, or null. */
    public FileRecord findByOwnerAndFilename(int ownerId, String filename) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,filename,storedName,owner_id,size,is_public,uploaded,expires " +
                "FROM files WHERE owner_id=? AND filename=? LIMIT 1")) {
            ps.setInt(1, ownerId);
            ps.setString(2, filename);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            return new FileRecord(rs.getInt("id"), rs.getString("filename"),
                    rs.getString("storedName"), rs.getInt("owner_id"),
                    rs.getLong("size"), rs.getInt("is_public") == 1,
                    rs.getLong("uploaded"), rs.getLong("expires"));
        }
    }

    /** Returns a single FileRecord by id, or null. */
    public FileRecord getFile(int fileId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,filename,storedName,owner_id,size,is_public,uploaded,expires FROM files WHERE id=?")) {
            ps.setInt(1, fileId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            return new FileRecord(rs.getInt("id"), rs.getString("filename"),
                    rs.getString("storedName"), rs.getInt("owner_id"),
                    rs.getLong("size"), rs.getInt("is_public") == 1,
                    rs.getLong("uploaded"), rs.getLong("expires"));
        }
    }

    /** Returns true if this user may access the given file (public or explicitly allowed). */
    public boolean canAccess(int fileId, int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT is_public FROM files WHERE id=?")) {
            ps.setInt(1, fileId);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt("is_public") == 1) return true;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM file_access WHERE file_id=? AND user_id=?")) {
            ps.setInt(1, fileId);
            ps.setInt(2, userId);
            return ps.executeQuery().next();
        }
    }

    /** Deletes a file record (and its access rows via CASCADE). */
    public void deleteFile(int fileId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM files WHERE id=?")) {
            ps.setInt(1, fileId);
            ps.executeUpdate();
        }
    }

    /** All files (for admin log view). */
    public List<FileRecord> getAllFiles() throws SQLException {
        List<FileRecord> list = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT id,filename,storedName,owner_id,size,is_public,uploaded,expires FROM files ORDER BY uploaded DESC");
            while (rs.next())
                list.add(new FileRecord(rs.getInt("id"), rs.getString("filename"),
                        rs.getString("storedName"), rs.getInt("owner_id"),
                        rs.getLong("size"), rs.getInt("is_public") == 1,
                        rs.getLong("uploaded"), rs.getLong("expires")));
        }
        return list;
    }
}
