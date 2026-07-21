package com.sharewave.server;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * All server configuration — web port, upload dir, DB path, keystore,
 * management port, and theme — persisted to ~/.sharewave/sharewave.conf.
 *
 * All ShareWave data files live under ~/.sharewave/ so nothing is
 * scattered across the user's home directory.
 */
public class ServerConfig {

    /** Root directory for all ShareWave data files: ~/.sharewave/ */
    public static final Path SHAREWAVE_DIR =
            Path.of(System.getProperty("user.home"), ".sharewave");

    private static final Path CONFIG_FILE = SHAREWAVE_DIR.resolve("sharewave.conf");

    private final Properties props = new Properties();

    private static final String DEF_WEB_PORT   = "8443";
    private static final String DEF_MGMT_PORT  = "9443";
    private static final String DEF_UPLOAD_DIR = SHAREWAVE_DIR.resolve("uploads").toString();
    private static final String DEF_DB_PATH    = SHAREWAVE_DIR.resolve("sharewave.db").toString();
    private static final String DEF_KEYSTORE   = SHAREWAVE_DIR.resolve("sharewave.keystore").toString();
    private static final String DEF_THEME       = "dark";
    private static final String DEF_SITE_TITLE  = "ShareWave";
    private static final String DEF_SESSION_TIMEOUT_MIN = "5";

    public void load() {
        try {
            Files.createDirectories(SHAREWAVE_DIR);
        } catch (IOException e) {
            System.err.println("Cannot create ShareWave directory: " + e.getMessage());
        }
        if (Files.exists(CONFIG_FILE)) {
            try (Reader r = Files.newBufferedReader(CONFIG_FILE)) {
                props.load(r);
            } catch (IOException e) {
                System.err.println("Config load error: " + e.getMessage());
            }
        }
    }

    public void save() {
        try {
            Files.createDirectories(SHAREWAVE_DIR);
            try (Writer w = Files.newBufferedWriter(CONFIG_FILE)) {
                props.store(w, "ShareWave configuration");
            }
        } catch (IOException e) {
            System.err.println("Config save error: " + e.getMessage());
        }
    }

    public String getWebPort()   { return props.getProperty("port",        DEF_WEB_PORT);   }
    public String getMgmtPort()  { return props.getProperty("mgmt_port",   DEF_MGMT_PORT);  }
    public String getUploadDir() { return props.getProperty("upload_dir",  DEF_UPLOAD_DIR); }
    public String getDbPath()    { return props.getProperty("db_path",     DEF_DB_PATH);    }
    public String getKeystore()  { return props.getProperty("keystore",    DEF_KEYSTORE);   }
    public String getTheme()     { return props.getProperty("theme",       DEF_THEME);      }
    public String getSiteTitle() { return props.getProperty("site_title",  DEF_SITE_TITLE); }

    /** Session inactivity timeout, in minutes. Always a positive integer (falls back to the default if stored value is invalid). */
    public int getSessionTimeoutMinutes() {
        String raw = props.getProperty("session_timeout_min", DEF_SESSION_TIMEOUT_MIN);
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : Integer.parseInt(DEF_SESSION_TIMEOUT_MIN);
        } catch (NumberFormatException e) {
            return Integer.parseInt(DEF_SESSION_TIMEOUT_MIN);
        }
    }

    public void setWebPort(String v)   { props.setProperty("port",       v); }
    public void setMgmtPort(String v)  { props.setProperty("mgmt_port",  v); }
    public void setUploadDir(String v) { props.setProperty("upload_dir", v); }
    public void setDbPath(String v)    { props.setProperty("db_path",    v); }
    public void setKeystore(String v)  { props.setProperty("keystore",   v); }
    public void setTheme(String v)     { props.setProperty("theme",      v); }
    public void setSiteTitle(String v) { props.setProperty("site_title", (v == null || v.isBlank()) ? DEF_SITE_TITLE : v.trim()); }

    /** Sets the session timeout in minutes. Ignores non-positive or unparsable values, keeping whatever was set before. */
    public void setSessionTimeoutMinutes(String v) {
        if (v == null || v.isBlank()) return;
        try {
            int parsed = Integer.parseInt(v.trim());
            if (parsed > 0) props.setProperty("session_timeout_min", String.valueOf(parsed));
        } catch (NumberFormatException ignored) {
        }
    }
}
