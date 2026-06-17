package com.sharewave;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Ensures a PKCS12 keystore containing a self-signed TLS certificate exists
 * at the given path.  Uses the JDK {@code keytool} command so there are no
 * extra dependencies.
 *
 * The keystore password is fixed to {@link #KEYSTORE_PASSWORD}.  It is
 * embedded in the config rather than being a secret because the certificate
 * is self-signed and the keystore lives on the server itself — the password
 * only protects the file at rest.
 */
public class CertUtil {

    public static final String KEYSTORE_PASSWORD = "sharewave";

    private CertUtil() {}

    /**
     * Creates the keystore at {@code keystorePath} if it does not already
     * exist.  Logs progress/errors via {@code logger}.
     *
     * @return true if the keystore is ready to use, false on error.
     */
    public static boolean ensureKeystore(String keystorePath, Consumer<String> logger) {
        Path ks = Path.of(keystorePath);
        if (Files.exists(ks)) {
            logger.accept("Keystore   : " + ks.toAbsolutePath() + " (existing)");
            return true;
        }

        logger.accept("Keystore   : generating self-signed cert at " + ks.toAbsolutePath());
        try {
            // keytool is bundled with every JDK/JRE
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool")
                    .toAbsolutePath().toString();

            // Fallback: just "keytool" if the above doesn't exist (e.g. on some distros)
            if (!new File(keytool).exists()) keytool = "keytool";

            ProcessBuilder pb = new ProcessBuilder(
                    keytool,
                    "-genkeypair",
                    "-keyalg",    "RSA",
                    "-keysize",   "2048",
                    "-validity",  "3650",          // 10 years
                    "-alias",     "sharewave",
                    "-dname",     "CN=sharewave,OU=sharewave,O=sharewave,L=Local,ST=Local,C=US",
                    "-keystore",  keystorePath,
                    "-storetype", "PKCS12",
                    "-storepass", KEYSTORE_PASSWORD,
                    "-keypass",   KEYSTORE_PASSWORD,
                    "-noprompt"
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            // Capture output for the log
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) out.append(line).append('\n');
            }
            int exit = proc.waitFor();
            if (exit != 0) {
                logger.accept("ERROR: keytool exited " + exit + ": " + out);
                return false;
            }
            logger.accept("Keystore   : self-signed cert generated OK");
            return true;

        } catch (Exception e) {
            logger.accept("ERROR generating keystore: " + e.getMessage());
            return false;
        }
    }
}
