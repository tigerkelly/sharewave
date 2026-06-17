package com.sharewave.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * TCP client for the ShareWave management port.
 *
 * - Sends JSON command lines, reads JSON response lines.
 * - Pushes {"event":"log",...} lines to the registered log listener.
 * - Runs a background reader thread so the GUI stays responsive.
 *
 * Usage:
 *   ManagementClient c = new ManagementClient("192.168.1.10", 9443);
 *   c.setLogListener(msg -> Platform.runLater(() -> logArea.appendText(msg)));
 *   c.connect();
 *   JsonObject resp = c.send(Map.of("cmd","login","password","secret"));
 */
public class ManagementClient implements Closeable {

    private final String   host;
    private final int      port;
    private final Gson     gson = new Gson();

    private Socket         socket;
    private PrintWriter    out;
    private BufferedReader in;
    private Thread         readerThread;
    private volatile boolean connected = false;

    /** Receives log event messages from the server. */
    private Consumer<String> logListener = msg -> {};

    /** Receives connection state changes (true=connected, false=disconnected). */
    private Consumer<Boolean> stateListener = b -> {};

    /** Pending responses keyed by a sequence counter. */
    private final Map<Integer, CompletableFuture<JsonObject>> pending =
            new ConcurrentHashMap<>();
    private int seq = 0;

    public ManagementClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void setLogListener(Consumer<String> l)   { this.logListener   = l; }
    public void setStateListener(Consumer<Boolean> l){ this.stateListener = l; }
    public boolean isConnected() { return connected; }

    // ── Connect / Disconnect ──────────────────────────────────────────────────

    public void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setTcpNoDelay(true);
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),
                              StandardCharsets.UTF_8), true);
        in  = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                              StandardCharsets.UTF_8));
        connected = true;
        stateListener.accept(true);

        readerThread = new Thread(this::readLoop, "mgmt-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    @Override
    public void close() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        // Fail all pending futures
        pending.values().forEach(f -> f.completeExceptionally(
                new IOException("Disconnected")));
        pending.clear();
        stateListener.accept(false);
    }

    // ── Send a command ────────────────────────────────────────────────────────

    /**
     * Sends a command and waits up to 10 seconds for the response.
     * Returns the parsed JSON response object, or throws on timeout/error.
     */
    public JsonObject send(Map<String, Object> command) throws Exception {
        if (!connected) throw new IOException("Not connected");
        int id = ++seq;
        Map<String, Object> req = new LinkedHashMap<>(command);
        req.put("_seq", id);

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);

        out.println(gson.toJson(req));

        try {
            return future.get(10, TimeUnit.SECONDS);
        } finally {
            pending.remove(id);
        }
    }

    /** Convenience: send command and return the response, or throw with error message. */
    public JsonObject cmd(String command, Object... kvPairs) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cmd", command);
        for (int i = 0; i + 1 < kvPairs.length; i += 2)
            map.put(kvPairs[i].toString(), kvPairs[i + 1]);
        JsonObject resp = send(map);
        if (resp.has("ok") && !resp.get("ok").getAsBoolean())
            throw new Exception(resp.has("error") ? resp.get("error").getAsString() : "Server error");
        return resp;
    }

    // ── Reader loop ───────────────────────────────────────────────────────────

    private void readLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonObject obj = JsonParser.parseString(line).getAsJsonObject();

                // Log event — push to listener, not to pending futures
                if (obj.has("event") && "log".equals(obj.get("event").getAsString())) {
                    String msg = obj.has("msg") ? obj.get("msg").getAsString() : "";
                    logListener.accept(msg);
                    continue;
                }

                // Match to pending request by _seq
                if (obj.has("_seq")) {
                    int id = obj.get("_seq").getAsInt();
                    CompletableFuture<JsonObject> f = pending.remove(id);
                    if (f != null) f.complete(obj);
                } else {
                    // No seq — complete oldest pending if any
                    pending.values().stream().findFirst().ifPresent(f -> {
                        pending.values().remove(f);
                        f.complete(obj);
                    });
                }
            }
        } catch (IOException e) {
            if (connected) logListener.accept("[Disconnected from server: " + e.getMessage() + "]");
        } finally {
            connected = false;
            pending.values().forEach(f -> f.completeExceptionally(new IOException("Disconnected")));
            pending.clear();
            stateListener.accept(false);
        }
    }
}
