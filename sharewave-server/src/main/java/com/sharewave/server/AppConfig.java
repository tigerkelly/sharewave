package com.sharewave.server;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Persists ShareWave server settings to ~/.sharewave.conf across restarts.
 */
public class AppConfig {

    private static final Path CONFIG_FILE =
            Path.of(System.getProperty("user.home"), ".sharewave.conf");

    private final Properties props = new Properties();

    // ── Defaults ─────────────────────────────────────────────────────────

    private static final String DEF_PORT       = "8443";
    private static final String DEF_UPLOAD_DIR =
            System.getProperty("user.home") + File.separator + "sharewave-uploads";
    private static final String DEF_DB_PATH    =
            System.getProperty("user.home") + File.separator + "sharewave.db";
    private static final String DEF_KEYSTORE   =
            System.getProperty("user.home") + File.separator + "sharewave.keystore";
    private static final String DEF_THEME      = "dark";

    // ── Load / Save ───────────────────────────────────────────────────────

    public void load() {
        if (Files.exists(CONFIG_FILE)) {
            try (Reader r = Files.newBufferedReader(CONFIG_FILE)) {
                props.load(r);
            } catch (IOException e) {
                System.err.println("Config load error: " + e.getMessage());
            }
        }
    }

    public void save() {
        try (Writer w = Files.newBufferedWriter(CONFIG_FILE)) {
            props.store(w, "ShareWave configuration");
        } catch (IOException e) {
            System.err.println("Config save error: " + e.getMessage());
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    public String getPort()      { return props.getProperty("port",       DEF_PORT);       }
    public String getUploadDir() { return props.getProperty("upload_dir", DEF_UPLOAD_DIR); }
    public String getDbPath()    { return props.getProperty("db_path",    DEF_DB_PATH);    }
    public String getKeystore()  { return props.getProperty("keystore",   DEF_KEYSTORE);   }
    public String getTheme()     { return props.getProperty("theme",      DEF_THEME);      }

    public void setPort(String v)      { props.setProperty("port",       v); }
    public void setUploadDir(String v) { props.setProperty("upload_dir", v); }
    public void setDbPath(String v)    { props.setProperty("db_path",    v); }
    public void setKeystore(String v)  { props.setProperty("keystore",   v); }
    public void setTheme(String v)     { props.setProperty("theme",      v); }
}
