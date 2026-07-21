#!/usr/bin/env bash
# =============================================================================
# package-gui.sh — Build a native installer for the ShareWave Admin GUI.
#
# Produces a self-contained installer that bundles a private JRE + JavaFX
# so the admin's machine needs no Java installed at all.
#
# Must be run on the TARGET platform:
#   Linux   → .deb  (requires dpkg)  and/or  .rpm  (requires rpm-build)
#   macOS   → .dmg  and  .pkg
#   Windows → .msi  (requires WiX Toolset 3.x)  and/or  .exe
#
# Usage:
#   ./package-gui.sh              # auto-detects platform, builds default type
#   ./package-gui.sh --type deb   # force a specific package type
#   ./package-gui.sh --no-cache   # force a clean Maven build
#
# Output: sharewave-gui/target/installer/ShareWave-<version>.<ext>
# =============================================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ── Helpers ───────────────────────────────────────────────────────────────────
info() { echo -e "\033[1;34m[INFO]\033[0m  $*"; }
ok()   { echo -e "\033[1;32m[ OK ]\033[0m  $*"; }
warn() { echo -e "\033[1;33m[WARN]\033[0m  $*"; }
err()  { echo -e "\033[1;31m[ERR ]\033[0m  $*"; exit 1; }
hr()   { echo -e "\033[1;90m─────────────────────────────────────────────\033[0m"; }

# ── Parse arguments ───────────────────────────────────────────────────────────
PKG_TYPE=""
CLEAN=""
for arg in "$@"; do
    case "$arg" in
        --type) ;;              # value consumed by next iteration
        deb|rpm|dmg|pkg|msi|exe) PKG_TYPE="$arg" ;;
        --no-cache) CLEAN="clean" ;;
        --help|-h)
            echo "Usage: $0 [--type deb|rpm|dmg|pkg|msi|exe] [--no-cache]"
            exit 0 ;;
        *) err "Unknown argument: $arg" ;;
    esac
done

# ── Check prerequisites ───────────────────────────────────────────────────────
command -v java  >/dev/null || err "java not found — install JDK 17+"
command -v mvn   >/dev/null || err "mvn not found — install Maven 3.8+"
command -v jpackage >/dev/null || err "jpackage not found — install JDK 14+ (not just JRE)"

JAVA_VER=$(java -version 2>&1 | head -1 | sed 's/.*version "\([0-9]*\).*/\1/')
[ "$JAVA_VER" -ge 17 ] 2>/dev/null || err "JDK 17+ required (found Java $JAVA_VER)"

hr
info "ShareWave Admin GUI — Native Installer"
info "Java   : $(java -version 2>&1 | head -1)"
info "Maven  : $(mvn --version | head -1)"
info "jpackage: $(jpackage --version)"
hr

# ── Read version from VERSION file ────────────────────────────────────────────
SW_VERSION="1.0"
if [ -f "$SCRIPT_DIR/VERSION" ]; then
    SW_VERSION="$(tr -d '[:space:]' < "$SCRIPT_DIR/VERSION")"
fi
info "Version: $SW_VERSION"

# ── Detect platform and set defaults ──────────────────────────────────────────
OS="$(uname -s)"
ARCH="$(uname -m)"
case "$OS" in
    Linux*)
        PLATFORM="linux"
        [ -z "$PKG_TYPE" ] && { command -v dpkg >/dev/null 2>&1 && PKG_TYPE="deb" || PKG_TYPE="rpm"; }
        ICON="$SCRIPT_DIR/sharewave-gui/src/main/installer/icons/sharewave.png"
        ;;
    Darwin*)
        PLATFORM="mac"
        [ -z "$PKG_TYPE" ] && PKG_TYPE="dmg"
        ICON="$SCRIPT_DIR/sharewave-gui/src/main/installer/icons/sharewave.icns"
        # Fall back to PNG if .icns hasn't been created
        [ -f "$ICON" ] || ICON="$SCRIPT_DIR/sharewave-gui/src/main/installer/icons/sharewave.png"
        ;;
    CYGWIN*|MINGW*|MSYS*)
        PLATFORM="windows"
        [ -z "$PKG_TYPE" ] && PKG_TYPE="msi"
        ICON="$SCRIPT_DIR/sharewave-gui/src/main/installer/icons/sharewave.ico"
        # Fall back to PNG if .ico hasn't been created
        [ -f "$ICON" ] || ICON="$SCRIPT_DIR/sharewave-gui/src/main/installer/icons/sharewave.png"
        ;;
    *)
        PLATFORM="linux"
        [ -z "$PKG_TYPE" ] && PKG_TYPE="deb"
        ICON="$SCRIPT_DIR/sharewave-gui/src/main/installer/icons/sharewave.png"
        warn "Unrecognised OS '$OS', assuming Linux"
        ;;
esac

info "Platform  : $PLATFORM ($ARCH)"
info "Pkg type  : $PKG_TYPE"
info "Icon      : $ICON"

# Validate icon exists
[ -f "$ICON" ] || err "Icon file not found: $ICON — add one at that path and retry."

# ── Detect JavaFX native libraries in ~/.m2 ───────────────────────────────────
FX_VERSION="21.0.1"
M2="${HOME}/.m2/repository/org/openjfx"

case "$PLATFORM" in
    linux)   FX_CLASSIFIER="linux"   ;;
    mac)     FX_CLASSIFIER="mac"     ;;
    windows) FX_CLASSIFIER="win"     ;;
esac
[ "$ARCH" = "aarch64" ] && FX_CLASSIFIER="${FX_CLASSIFIER}-aarch64"

FX_PATH=""
for MODULE in javafx-controls javafx-fxml javafx-graphics javafx-base; do
    DIR="$M2/$MODULE/$FX_VERSION"
    JAR=$(find "$DIR" -name "${MODULE}-${FX_VERSION}-${FX_CLASSIFIER}.jar" 2>/dev/null | head -1)
    [ -z "$JAR" ] && JAR=$(find "$DIR" -name "${MODULE}-${FX_VERSION}.jar" 2>/dev/null | head -1)
    if [ -n "$JAR" ]; then
        DIR="$(dirname "$JAR")"
        FX_PATH="$DIR${FX_PATH:+:$FX_PATH}"
    fi
done

[ -n "$FX_PATH" ] || err \
    "JavaFX $FX_VERSION native jars not found in ~/.m2.
Run './build.sh gui' first to download them, then retry."

info "JavaFX    : $FX_PATH"

# ── Step 1: Build the GUI module ──────────────────────────────────────────────
hr
info "Step 1/3 — Building GUI JAR..."
mvn $CLEAN package -pl sharewave-gui -am -DskipTests -B -q
ok "Build complete"

# ── Step 2: Stage the unshaded app JAR into the deps directory ────────────────
#
# jpackage needs:
#   --input <dir>     all JARs on the class-path (dependencies + app)
#   --main-jar        which JAR in --input contains the main class
#
# maven-shade creates  target/sharewave-gui.jar  (fat, shaded)
# Maven also creates   target/sharewave-gui-<version>.jar  (plain, class-only)
# We use the plain one as --main-jar and have all deps in target/deps/
#
info "Step 2/3 — Staging JARs for jpackage..."
DEPS_DIR="$SCRIPT_DIR/sharewave-gui/target/deps"
APP_VER_JAR="$SCRIPT_DIR/sharewave-gui/target/sharewave-gui-${SW_VERSION}-SNAPSHOT.jar"
# Also try without -SNAPSHOT
[ -f "$APP_VER_JAR" ] || APP_VER_JAR="$SCRIPT_DIR/sharewave-gui/target/sharewave-gui-${SW_VERSION}.jar"
# Final fallback: any non-shaded app jar
[ -f "$APP_VER_JAR" ] || \
    APP_VER_JAR=$(find "$SCRIPT_DIR/sharewave-gui/target" -maxdepth 1 \
        -name "sharewave-gui-*.jar" ! -name "sharewave-gui.jar" | sort | tail -1)

[ -f "$APP_VER_JAR" ] || err "Could not find unshaded app JAR in sharewave-gui/target/. Re-run the build."

cp "$APP_VER_JAR" "$DEPS_DIR/"
ok "Staged $(basename "$APP_VER_JAR") into deps/"

# Copy VERSION into deps so AppVersion can find it at runtime
[ -f "$SCRIPT_DIR/VERSION" ] && cp "$SCRIPT_DIR/VERSION" "$DEPS_DIR/"

APP_JAR_NAME="$(basename "$APP_VER_JAR")"

# ── Step 3: Run jpackage ──────────────────────────────────────────────────────
hr
info "Step 3/3 — Running jpackage ($PKG_TYPE)..."
OUT_DIR="$SCRIPT_DIR/sharewave-gui/target/installer"
mkdir -p "$OUT_DIR"

jpackage \
    --name         "ShareWave" \
    --app-version  "$SW_VERSION" \
    --description  "ShareWave Admin GUI — manage your ShareWave server" \
    --vendor       "Kelly Wiles" \
    --input        "$DEPS_DIR" \
    --main-jar     "$APP_JAR_NAME" \
    --main-class   "com.sharewave.gui.Launcher" \
    --module-path  "$FX_PATH" \
    --add-modules  "javafx.controls,javafx.fxml" \
    --java-options "--add-opens=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED" \
    --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" \
    --dest         "$OUT_DIR" \
    --type         "$PKG_TYPE" \
    --icon         "$ICON" \
    2>&1

# Find and report the output file
INSTALLER=$(find "$OUT_DIR" -maxdepth 1 -type f \
    \( -name "*.deb" -o -name "*.rpm" -o -name "*.dmg" \
       -o -name "*.pkg" -o -name "*.msi" -o -name "*.exe" \) | head -1)

hr
if [ -n "$INSTALLER" ]; then
    SIZE=$(du -sh "$INSTALLER" | cut -f1)
    ok "Installer built: $INSTALLER  ($SIZE)"
    echo ""
    info "To install on this machine:"
    case "$PKG_TYPE" in
        deb) echo "    sudo dpkg -i '$INSTALLER'" ;;
        rpm) echo "    sudo rpm -i '$INSTALLER'" ;;
        dmg) echo "    Open '$INSTALLER' and drag ShareWave to Applications" ;;
        pkg) echo "    Double-click '$INSTALLER' to run the installer" ;;
        msi) echo "    Double-click '$INSTALLER' to run the installer" ;;
        exe) echo "    Double-click '$INSTALLER' to run the installer" ;;
    esac
    echo ""
    info "To install on another machine: copy '$INSTALLER' there and install."
    info "No Java required on the target machine — the JRE is bundled inside."
else
    err "jpackage ran but no installer file found in $OUT_DIR"
fi
hr
