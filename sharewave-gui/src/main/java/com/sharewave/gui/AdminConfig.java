package com.sharewave.gui;

import org.mindrot.jbcrypt.BCrypt;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Stores the JavaFX admin password (bcrypt-hashed) separately from
 * the server config so it never appears in plain text on disk.
 *
 * File: ~/.sharewave-admin.conf
 */
public class AdminConfig {

    private static final Path CONFIG_FILE =
            Path.of(System.getProperty("user.home"), ".sharewave-admin.conf");

    private final Properties props = new Properties();

    public void load() {
        if (Files.exists(CONFIG_FILE)) {
            try (Reader r = Files.newBufferedReader(CONFIG_FILE)) {
                props.load(r);
            } catch (IOException e) {
                System.err.println("AdminConfig load error: " + e.getMessage());
            }
        }
    }

    public void save() {
        try (Writer w = Files.newBufferedWriter(CONFIG_FILE)) {
            props.store(w, "ShareWave admin credentials — do not share");
        } catch (IOException e) {
            System.err.println("AdminConfig save error: " + e.getMessage());
        }
    }

    /** Returns true if no admin password has been set yet. */
    public boolean isFirstRun() {
        return props.getProperty("admin_hash") == null;
    }

    /**
     * Sets (or replaces) the admin password.
     * The plain-text password is hashed immediately and never stored.
     */
    public void setPassword(String plainPassword) {
        String hash = BCrypt.hashpw(plainPassword, BCrypt.gensalt(12));
        props.setProperty("admin_hash", hash);
        save();
    }

    /** Returns true if the given plain-text password matches the stored hash. */
    public boolean checkPassword(String plainPassword) {
        String hash = props.getProperty("admin_hash");
        if (hash == null) return false;
        try {
            return BCrypt.checkpw(plainPassword, hash);
        } catch (Exception e) {
            return false;
        }
    }
}
