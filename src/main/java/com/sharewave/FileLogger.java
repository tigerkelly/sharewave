package com.sharewave;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes log messages to uploadDir/logs/sharewave.log.
 * Rotates at 2 MB, keeping at most 2 rotated files:
 *   sharewave.log.1  (most recent rotation)
 *   sharewave.log.2  (oldest, deleted on next rotation)
 */
public class FileLogger implements Closeable {

    private static final long   MAX_BYTES       = 2L * 1024 * 1024; // 2 MB
    private static final int    MAX_ROTATED     = 2;
    private static final DateTimeFormatter TS   =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path      logDir;
    private final Path      currentLog;
    private BufferedWriter  writer;
    private long            bytesWritten;
    private final Object    lock = new Object();

    public FileLogger(Path uploadDir) throws IOException {
        this.logDir     = uploadDir.resolve("logs");
        this.currentLog = logDir.resolve("sharewave.log");
        Files.createDirectories(logDir);
        openAppend();
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public void log(String message) {
        String line = "[" + LocalDateTime.now().format(TS) + "] " + message + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        synchronized (lock) {
            try {
                if (bytesWritten + bytes.length > MAX_BYTES) rotate();
                writer.write(line);
                writer.flush();
                bytesWritten += bytes.length;
            } catch (IOException e) {
                System.err.println("FileLogger write error: " + e.getMessage());
            }
        }
    }

    /**
     * Returns the names of all available log files, current first.
     * e.g. ["sharewave.log", "sharewave.log.1", "sharewave.log.2"]
     */
    public List<String> availableLogFiles() {
        List<String> list = new ArrayList<>();
        if (Files.exists(currentLog)) list.add("sharewave.log");
        for (int i = 1; i <= MAX_ROTATED; i++) {
            Path p = logDir.resolve("sharewave.log." + i);
            if (Files.exists(p)) list.add("sharewave.log." + i);
        }
        return list;
    }

    /**
     * Reads and returns the full text of the named log file.
     * Name must be one of the values returned by availableLogFiles().
     */
    public String readLogFile(String name) throws IOException {
        // Safety: only allow known names
        if (!name.matches("sharewave\\.log(\\.\\d+)?"))
            throw new IOException("Invalid log filename: " + name);
        Path p = logDir.resolve(name);
        if (!Files.exists(p)) return "(file not found)";
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    public Path getLogDir() { return logDir; }

    @Override
    public void close() {
        synchronized (lock) {
            try { if (writer != null) writer.close(); }
            catch (IOException ignored) {}
        }
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private void openAppend() throws IOException {
        writer = Files.newBufferedWriter(currentLog,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        bytesWritten = Files.exists(currentLog) ? Files.size(currentLog) : 0;
    }

    private void rotate() throws IOException {
        writer.close();

        // Shift existing rotated files: .2 deleted, .1 → .2, current → .1
        Path oldest = logDir.resolve("sharewave.log." + MAX_ROTATED);
        Files.deleteIfExists(oldest);

        for (int i = MAX_ROTATED - 1; i >= 1; i--) {
            Path src  = logDir.resolve("sharewave.log." + i);
            Path dest = logDir.resolve("sharewave.log." + (i + 1));
            if (Files.exists(src)) Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
        }

        Path rotated = logDir.resolve("sharewave.log.1");
        Files.move(currentLog, rotated, StandardCopyOption.REPLACE_EXISTING);

        // Open fresh log
        openAppend();
        bytesWritten = 0;
        log("--- Log rotated ---");
    }
}
