package com.sharewave.server;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Provides the ShareWave version string, read from the single VERSION
 * file at the top of the project tree (or /opt/sharewave/VERSION once
 * installed) — there is exactly one VERSION file; both sharewave-server
 * and sharewave-gui read it the same way, nothing is bundled into the JAR.
 *
 * Candidate locations, checked relative to wherever the running JAR is,
 * in order:
 *   1. <jar-dir>/VERSION                — installed layout: install.sh
 *      copies sharewave-server.jar and VERSION into the same directory
 *      (/opt/sharewave/).
 *   2. <jar-dir>/../../VERSION          — running straight from a source
 *      checkout, e.g. java -jar sharewave-server/target/sharewave-server.jar,
 *      where VERSION lives at the project root, two levels above target/.
 *
 * Returns "dev" if no VERSION file is found in any candidate location.
 *
 * To change the version: edit the VERSION file at the project root and
 * rebuild, or edit /opt/sharewave/VERSION on an installed system (takes
 * effect on next server start / next page load — no rebuild needed for
 * the installed case).
 */
public final class AppVersion {
    private AppVersion() {}

    private static final String VERSION = load();

    /** Returns the version string, e.g. "1.0", or "dev" if unavailable. */
    public static String get() { return VERSION; }

    private static String load() {
        Path jarDir = jarDirectory();
        if (jarDir == null) return "dev";

        Path[] candidates = {
            jarDir.resolve("VERSION"),
            jarDir.resolve("../../VERSION").normalize(),
        };

        for (Path candidate : candidates) {
            try {
                if (Files.isReadable(candidate)) {
                    String v = Files.readString(candidate).trim();
                    if (!v.isEmpty()) return v;
                }
            } catch (Exception ignored) {
                // try next candidate
            }
        }
        return "dev";
    }

    private static Path jarDirectory() {
        try {
            Path jarPath = Path.of(URI.create(
                    AppVersion.class.getProtectionDomain().getCodeSource().getLocation().toURI().toString()));
            return jarPath.getParent();
        } catch (Exception e) {
            return null;
        }
    }
}
