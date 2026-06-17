package com.sharewave.server;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * All server configuration — web port, upload dir, DB path, keystore,
 * management port, and theme — persisted to ~/.sharewave.conf.
 */
public class ServerConfig {

    private static final Path CONFIG_FILE =
            Path.of(System.getProperty("user.home"), ".sharewave.conf");

    private final Properties props = new Properties();

    private static final String DEF_WEB_PORT   = "8443";
    private static final String DEF_MGMT_PORT  = "9443";
    private static final String DEF_UPLOAD_DIR =
            System.getProperty("user.home") + File.separator + "sharewave-uploads";
    private static final String DEF_DB_PATH    =
            System.getProperty("user.home") + File.separator + "sharewave.db";
    private static final String DEF_KEYSTORE   =
            System.getProperty("user.home") + File.separator + "sharewave.keystore";
    private static final String DEF_THEME       = "dark";
    private static final String DEF_SITE_TITLE  = "ShareWave";

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

    public String getWebPort()   { return props.getProperty("port",        DEF_WEB_PORT);   }
    public String getMgmtPort()  { return props.getProperty("mgmt_port",   DEF_MGMT_PORT);  }
    public String getUploadDir() { return props.getProperty("upload_dir",  DEF_UPLOAD_DIR); }
    public String getDbPath()    { return props.getProperty("db_path",     DEF_DB_PATH);    }
    public String getKeystore()  { return props.getProperty("keystore",    DEF_KEYSTORE);   }
    public String getTheme()     { return props.getProperty("theme",       DEF_THEME);      }
    public String getSiteTitle()  { return props.getProperty("site_title",   DEF_SITE_TITLE);   }

    public void setWebPort(String v)   { props.setProperty("port",       v); }
    public void setMgmtPort(String v)  { props.setProperty("mgmt_port",  v); }
    public void setUploadDir(String v) { props.setProperty("upload_dir", v); }
    public void setDbPath(String v)    { props.setProperty("db_path",    v); }
    public void setKeystore(String v)  { props.setProperty("keystore",   v); }
    public void setTheme(String v)     { props.setProperty("theme",      v); }
    public void setSiteTitle(String v)  { props.setProperty("site_title",   (v == null || v.isBlank()) ? DEF_SITE_TITLE   : v.trim()); }
}
