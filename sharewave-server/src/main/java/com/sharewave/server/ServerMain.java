package com.sharewave.server;

import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.*;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.*;

/**
 * Headless server entry point.
 *
 * Usage:
 *   java -jar sharewave-server.jar
 *
 * Reads configuration from ~/.sharewave.conf.
 * Admin password from ~/.sharewave-admin.conf.
 *
 * Starts:
 *   1. Jetty HTTPS server  (web users)
 *   2. Management TCP server  (GUI connection)
 *   3. Hourly expiry purge scheduler
 */
public class ServerMain {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private static FileLogger    fileLogger;
    private static ManagementServer mgmtServer;

    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig();
        config.load();

        AdminConfig adminConfig = new AdminConfig();
        adminConfig.load();

        // If no admin password set, prompt on console (first run headless)
        if (adminConfig.isFirstRun()) {
            System.out.println("[ShareWave] No admin password set.");
            System.out.print("[ShareWave] Enter new admin password: ");
            String pw = new java.io.BufferedReader(
                    new java.io.InputStreamReader(System.in)).readLine();
            if (pw == null || pw.length() < 4) {
                System.err.println("Password must be at least 4 characters.");
                System.exit(1);
            }
            adminConfig.setPassword(pw);
            System.out.println("[ShareWave] Admin password set.");
        }

        int webPort  = Integer.parseInt(config.getWebPort());
        int mgmtPort = Integer.parseInt(config.getMgmtPort());
        Path uploadDir = Path.of(config.getUploadDir());
        Path archiveDir = uploadDir.resolve("archive");

        Files.createDirectories(uploadDir);
        Files.createDirectories(archiveDir);
        Files.createDirectories(uploadDir.resolve("_tmp"));

        // File logger
        fileLogger = new FileLogger(uploadDir);

        // Combined log consumer: console + file + GUI push
        // mgmtServer is set below; lambda captures the field reference
        java.util.function.Consumer<String> log = msg -> {
            String ts = "[" + LocalTime.now().format(TS) + "] " + msg;
            System.out.println(ts);
            if (fileLogger != null) fileLogger.log(msg);
            if (mgmtServer != null) mgmtServer.pushLog(msg);
        };

        log.accept("ShareWave Server starting...");
        log.accept("Upload dir : " + uploadDir.toAbsolutePath());
        log.accept("Database   : " + config.getDbPath());
        log.accept("Web port   : " + webPort);
        log.accept("Mgmt port  : " + mgmtPort);

        // Database
        Database db = new Database(config.getDbPath());
        db.open();

        // Session manager
        SessionManager sessions = new SessionManager(config.getSessionTimeoutMinutes());
        log.accept("Session timeout: " + config.getSessionTimeoutMinutes() + " minute(s)");

        // ShareWave web handler
        ShareWaveHandler servlet = new ShareWaveHandler(db, sessions, uploadDir, config, log);

        // TLS keystore
        if (!CertUtil.ensureKeystore(config.getKeystore(), log)) {
            System.err.println("Failed to create keystore. Exiting.");
            System.exit(1);
        }

        SslContextFactory.Server sslCtx = new SslContextFactory.Server();
        sslCtx.setKeyStorePath(config.getKeystore());
        sslCtx.setKeyStorePassword(CertUtil.KEYSTORE_PASSWORD);
        sslCtx.setKeyManagerPassword(CertUtil.KEYSTORE_PASSWORD);
        sslCtx.setKeyStoreType("PKCS12");

        HttpConfiguration httpsConfig = new HttpConfiguration();
        httpsConfig.setSecureScheme("https");
        httpsConfig.setSecurePort(webPort);
        SecureRequestCustomizer src = new SecureRequestCustomizer();
        src.setSniHostCheck(false);
        httpsConfig.addCustomizer(src);

        org.eclipse.jetty.server.Server jetty = new org.eclipse.jetty.server.Server();
        ServerConnector connector = new ServerConnector(jetty,
                new SslConnectionFactory(sslCtx, "http/1.1"),
                new HttpConnectionFactory(httpsConfig));
        connector.setPort(webPort);
        jetty.addConnector(connector);

        ServletContextHandler ctx = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        ctx.setContextPath("/");
        ServletHolder holder = new ServletHolder(servlet);
        holder.getRegistration().setMultipartConfig(servlet.multipartConfig());
        ctx.addServlet(holder, "/*");
        jetty.setHandler(ctx);
        jetty.start();
        log.accept("Web server started → https://localhost:" + webPort + "/");

        // Management server
        mgmtServer = new ManagementServer(mgmtPort, db, config, adminConfig, uploadDir, log);
        mgmtServer.start();

        // Hourly expiry purge
        ScheduledExecutorService purge = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "expiry-purge");
            t.setDaemon(true);
            return t;
        });
        purge.scheduleAtFixedRate(() -> {
            try {
                List<Database.ArchivedRecord> expired = db.purgeExpiredFiles();
                for (Database.ArchivedRecord rec : expired) {
                    String userDirName = ShareWaveHandler.sanitizeUsername(rec.owner());
                    Path src2  = uploadDir.resolve(userDirName).resolve(rec.storedName());
                    Path dest2 = archiveDir.resolve(rec.storedName());
                    try {
                        if (Files.exists(src2))
                            Files.move(src2, dest2, StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception ignored) {}
                    log.accept("ARCHIVED expired: " + rec.filename() + " (owner: " + rec.owner() + ")");
                }
            } catch (Exception e) {
                log.accept("PURGE ERROR: " + e.getMessage());
            }
        }, 0, 60, TimeUnit.MINUTES);

        log.accept("Server ready.");

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.accept("Shutting down...");
            purge.shutdownNow();
            try { mgmtServer.close(); } catch (Exception ignored) {}
            try { jetty.stop();       } catch (Exception ignored) {}
            try { db.close();         } catch (Exception ignored) {}
            if (fileLogger != null) fileLogger.close();
            System.out.println("[ShareWave] Stopped.");
        }, "shutdown"));

        // Block main thread
        jetty.join();
    }
}
