package com.sharewave.gui;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Persists GUI-only settings: last server host, management port, theme.
 * Stored in ~/.sharewave-gui.conf — separate from the server config.
 */
public class GuiConfig {

    private static final Path CONFIG_FILE =
            Path.of(System.getProperty("user.home"), ".sharewave-gui.conf");

    private final Properties props = new Properties();

    public void load() {
        if (Files.exists(CONFIG_FILE)) {
            try (Reader r = Files.newBufferedReader(CONFIG_FILE)) {
                props.load(r);
            } catch (IOException e) {
                System.err.println("GuiConfig load error: " + e.getMessage());
            }
        }
    }

    public void save() {
        try (Writer w = Files.newBufferedWriter(CONFIG_FILE)) {
            props.store(w, "ShareWave GUI configuration");
        } catch (IOException e) {
            System.err.println("GuiConfig save error: " + e.getMessage());
        }
    }

    public String getHost()     { return props.getProperty("host",      "127.0.0.1"); }
    public String getMgmtPort() { return props.getProperty("mgmt_port", "9443");      }
    public String getTheme()    { return props.getProperty("theme",     "dark");       }

    public void setHost(String v)     { props.setProperty("host",      v); }
    public void setMgmtPort(String v) { props.setProperty("mgmt_port", v); }
    public void setTheme(String v)    { props.setProperty("theme",     v); }
}
