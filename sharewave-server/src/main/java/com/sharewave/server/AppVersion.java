package com.sharewave.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Provides the ShareWave version string, read from a VERSION file.
 *
 * Lookup order:
 *   1. VERSION file next to the running JAR (e.g. /opt/sharewave/VERSION) —
 *      lets an admin update the version without rebuilding.
 *   2. VERSION file bundled inside the JAR (copied from the project root's
 *      VERSION file at build time by build.sh).
 *   3. "dev" if neither is found.
 *
 * To change the version: edit the VERSION file at the project root and
 * rebuild, or edit /opt/sharewave/VERSION on an installed system (takes
 * effect on next server start / next page load).
 */
public final class AppVersion {
    private AppVersion() {}

    private static final String VERSION = load();

    /** Returns the version string, e.g. "1.0", or "dev" if unavailable. */
    public static String get() { return VERSION; }

    private static String load() {
        // 1. External VERSION file next to the running JAR
        try {
            Path jarPath = Path.of(URI.create(
                    AppVersion.class.getProtectionDomain().getCodeSource().getLocation().toURI().toString()));
            Path external = jarPath.getParent() != null
                    ? jarPath.getParent().resolve("VERSION")
                    : null;
            if (external != null && Files.isReadable(external)) {
                String v = Files.readString(external).trim();
                if (!v.isEmpty()) return v;
            }
        } catch (Exception ignored) {
            // Fall through to bundled resource
        }

        // 2. Bundled VERSION resource (copied into the JAR at build time)
        try (InputStream in = AppVersion.class.getResourceAsStream("/VERSION")) {
            if (in != null) {
                String v = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!v.isEmpty()) return v;
            }
        } catch (IOException ignored) {
        }

        return "dev";
    }
}
