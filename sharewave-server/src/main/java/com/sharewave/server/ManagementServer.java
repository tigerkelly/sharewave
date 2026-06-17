package com.sharewave.server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * TCP server that listens on the management port for a single GUI connection.
 *
 * - Only one client is served at a time; a second connection is refused
 *   until the first disconnects.
 * - Each line received is handed to {@link ManagementHandler}.
 * - Log messages are forwarded to the connected GUI via {@code pushLog()}.
 */
public class ManagementServer implements Closeable {

    private final int               port;
    private final Database          db;
    private final ServerConfig      config;
    private final AdminConfig       adminConfig;
    private final Path              uploadDir;
    private final Consumer<String>  sysLogger;   // writes to FileLogger + console

    private ServerSocket            serverSocket;
    private volatile PrintWriter    guiWriter;   // non-null when a GUI is connected
    private final AtomicBoolean     running = new AtomicBoolean(false);

    public ManagementServer(int port, Database db, ServerConfig config,
                            AdminConfig adminConfig, Path uploadDir,
                            Consumer<String> sysLogger) {
        this.port        = port;
        this.db          = db;
        this.config      = config;
        this.adminConfig = adminConfig;
        this.uploadDir   = uploadDir;
        this.sysLogger   = sysLogger;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() throws IOException {
        serverSocket = new ServerSocket(port, 1, InetAddress.getByName("0.0.0.0"));
        serverSocket.setReuseAddress(true);
        running.set(true);

        Thread acceptThread = new Thread(this::acceptLoop, "mgmt-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        sysLogger.accept("Management port: " + port);
    }

    @Override
    public void close() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }

    // ── Accept loop ───────────────────────────────────────────────────────────

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                client.setSoTimeout(0);  // no read timeout — GUI may be idle
                sysLogger.accept("MGMT GUI connected from " + client.getInetAddress());
                handleClient(client);
                sysLogger.accept("MGMT GUI disconnected");
            } catch (IOException e) {
                if (running.get()) sysLogger.accept("MGMT accept error: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket client) {
        ManagementHandler handler = new ManagementHandler(
                db, config, adminConfig, uploadDir, sysLogger);

        // Wire log push: when the server logs something, forward to GUI
        handler.setLogPush(msg -> pushLog(msg));

        try (BufferedReader  in  = new BufferedReader(
                 new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter     out = new PrintWriter(
                 new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true)) {

            guiWriter = out;

            String line;
            while ((line = in.readLine()) != null) {
                String response = handler.handle(line);
                if (response != null) out.println(response);
            }
        } catch (IOException e) {
            if (running.get()) sysLogger.accept("MGMT client error: " + e.getMessage());
        } finally {
            guiWriter = null;
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    // ── Log forwarding ────────────────────────────────────────────────────────

    /**
     * Called by the server's log consumer to forward a message to the GUI.
     * Safe to call from any thread; no-ops when no GUI is connected.
     */
    public void pushLog(String msg) {
        PrintWriter w = guiWriter;
        if (w != null) {
            try {
                w.println(ManagementHandler.logEvent(msg));
            } catch (Exception ignored) {}
        }
    }

    public boolean isGuiConnected() { return guiWriter != null; }
}
