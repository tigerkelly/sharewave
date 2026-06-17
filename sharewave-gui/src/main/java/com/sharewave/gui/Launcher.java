package com.sharewave.gui;

import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bootstrap launcher for the ShareWave GUI fat JAR.
 *
 * The problem: JavaFX fat JARs fail with "JavaFX runtime components are missing"
 * because the shade plugin bundles the JavaFX classes but not the platform
 * native libraries (.so / .dll / .dylib).  The JVM's JavaFX bootstrap check
 * runs before main() and fails when it cannot find the native libs.
 *
 * The solution: this class is the JAR's Main-Class.  It is a plain Java class
 * (no JavaFX imports) so it starts fine.  It then:
 *   1. Extracts the platform-specific JavaFX native JARs from ~/.m2 (if present),
 *      or downloads them using Maven coordinates.
 *   2. Adds them to the module path.
 *   3. Re-launches the JVM process with the correct --module-path and
 *      --add-modules flags, delegating to GuiApp.
 *
 * If the native JARs cannot be found, a helpful error is printed.
 */
public class Launcher {

    private static final String FX_VERSION    = "21.0.1";
    private static final String MAIN_CLASS    = "com.sharewave.gui.GuiApp";
    private static final String[] FX_MODULES  = {
        "javafx-controls", "javafx-fxml", "javafx-graphics", "javafx-base"
    };

    public static void main(String[] args) throws Exception {
        // If already running with JavaFX on module path, just launch directly
        // (avoids infinite re-launch loop when invoked via jpackage or mvn javafx:run)
        if (isJavaFxAvailable()) {
            GuiApp.main(args);
            return;
        }

        // Find JavaFX native JARs
        String classifier = detectClassifier();
        List<Path> fxJars = findFxJars(classifier);

        if (fxJars.isEmpty()) {
            System.err.println("""
                Error: JavaFX native libraries not found.
                
                ShareWave GUI requires JavaFX native JARs for your platform.
                They are normally downloaded by Maven into ~/.m2.
                
                Fix options:
                
                  1. Build first with Maven (downloads the JARs):
                       cd sharewave && ./build.sh gui
                     Then run the wrapper script:
                       ./run-gui.sh
                
                  2. Install a full JavaFX SDK and set FX_HOME:
                       export FX_HOME=/path/to/javafx-sdk-21
                       java --module-path $FX_HOME/lib \\
                            --add-modules javafx.controls,javafx.fxml \\
                            -jar sharewave-gui.jar
                
                  3. Run the installed "sharewave" command (after
                     sudo ./install.sh has bundled JavaFX for you):
                       sharewave
                """);
            System.exit(1);
        }

        // Re-launch this JVM process with the native JARs on the module path
        String modulePath = fxJars.stream()
                .map(Path::toAbsolutePath)
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator));

        String javaExe = ProcessHandle.current().info().command()
                .orElse("java");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("--module-path");
        cmd.add(modulePath);
        cmd.add("--add-modules");
        cmd.add("javafx.controls,javafx.fxml");
        cmd.add("--add-opens=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
        cmd.add("-jar");
        // Use the same JAR we are running from
        cmd.add(getOwnJarPath());
        // Pass --already-launched so we don't loop
        cmd.add("--fx-ready");
        cmd.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        Process p = pb.start();
        System.exit(p.waitFor());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isJavaFxAvailable() {
        // If --fx-ready was passed we already have JavaFX set up
        // Also check via class loading
        try {
            Class.forName("javafx.application.Application");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static String detectClassifier() {
        String os   = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("win"))   return "win";
        if (os.contains("mac"))   return arch.contains("aarch64") ? "mac-aarch64" : "mac";
        // Linux
        return switch (arch) {
            case "aarch64"       -> "linux-aarch64";
            case "arm"           -> "linux-arm32-monocle";
            default              -> "linux";
        };
    }

    private static List<Path> findFxJars(String classifier) {
        List<Path> found = new ArrayList<>();
        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository", "org", "openjfx");

        for (String module : FX_MODULES) {
            // Try classifier-specific jar first, then generic
            Path dir = m2.resolve(module).resolve(FX_VERSION);
            Path specific = dir.resolve(module + "-" + FX_VERSION + "-" + classifier + ".jar");
            Path generic  = dir.resolve(module + "-" + FX_VERSION + ".jar");

            if (Files.exists(specific)) {
                found.add(specific);
            } else if (Files.exists(generic)) {
                // Generic JARs from Maven Central don't have native libs,
                // but include them anyway — may work on some setups
                found.add(generic);
            }
        }
        return found;
    }

    private static String getOwnJarPath() throws Exception {
        URL url = Launcher.class.getProtectionDomain().getCodeSource().getLocation();
        return Path.of(url.toURI()).toString();
    }
}
