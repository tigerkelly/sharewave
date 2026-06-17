#!/usr/bin/env bash
# =============================================================================
# ShareWave — jpackage build script
#
# Produces:
#   dist/ShareWave/          app-image (portable, no install needed)
#   dist/sharewave_*.deb     Debian/Raspberry Pi OS installer
#
# Requirements:
#   - Java 17+ JDK with jpackage  (jpackage --version)
#   - Maven 3.8+                  (mvn --version)
#   - fakeroot + dpkg-deb         (for .deb only, skip if absent)
#
# Usage:
#   chmod +x package.sh
#   ./package.sh             # build both app-image and .deb
#   ./package.sh app-image   # app-image only
#   ./package.sh deb         # .deb only
# =============================================================================
set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
APP_NAME="ShareWave"
APP_VERSION="1.0"
MAIN_CLASS="com.sharewave.MainApp"
MAIN_MODULE="com.sharewave"
DESCRIPTION="Self-hosted file sharing server with JavaFX admin panel"
VENDOR="ShareWave"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR="$SCRIPT_DIR/target"
DIST_DIR="$SCRIPT_DIR/dist"
INPUT_DIR="$TARGET_DIR/jpackage-input"

# ── Helpers ───────────────────────────────────────────────────────────────────
info()  { echo -e "\033[1;34m[INFO]\033[0m  $*"; }
ok()    { echo -e "\033[1;32m[ OK ]\033[0m  $*"; }
warn()  { echo -e "\033[1;33m[WARN]\033[0m  $*"; }
err()   { echo -e "\033[1;31m[ERR ]\033[0m  $*"; exit 1; }
hr()    { echo "─────────────────────────────────────────────────────"; }

hr
info "ShareWave jpackage builder"
hr

# ── Checks ────────────────────────────────────────────────────────────────────
command -v java    >/dev/null || err "java not found"
command -v jpackage >/dev/null || err "jpackage not found (JDK 14+ required)"
command -v mvn     >/dev/null || err "mvn not found"

JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    err "Java 17+ required (found $JAVA_VERSION)"
fi
info "Java version : $(java -version 2>&1 | head -1)"
info "jpackage     : $(jpackage --version)"

# ── Step 1: Maven build ───────────────────────────────────────────────────────
hr
info "Step 1/4 — Maven build"
cd "$SCRIPT_DIR"
mvn -q package -DskipTests
ok "Maven build complete"

# ── Step 2: Prepare input directory ──────────────────────────────────────────
# jpackage wants a directory containing the fat JAR (not the module path JAR).
# We use the shade plugin output which bundles all dependencies.
hr
info "Step 2/4 — Preparing input directory"
rm -rf "$INPUT_DIR" "$DIST_DIR"
mkdir -p "$INPUT_DIR" "$DIST_DIR"

FAT_JAR=$(ls "$TARGET_DIR"/sharewave-*.jar 2>/dev/null | grep -v original | head -1)
[ -z "$FAT_JAR" ] && err "Fat JAR not found in $TARGET_DIR"
info "Using JAR    : $FAT_JAR"
cp "$FAT_JAR" "$INPUT_DIR/sharewave.jar"
ok "Input directory ready"

# ── Step 3: Detect JavaFX module path ─────────────────────────────────────────
# JavaFX native libs must be on the module path for jpackage to bundle them.
# We pull them from the Maven local cache.
hr
info "Step 3/4 — Locating JavaFX modules"

# Detect OS classifier
OS_ARCH=$(uname -m)
case "$OS_ARCH" in
    aarch64|arm64) FX_CLASSIFIER="linux-aarch64" ;;
    armv7l|armhf)  FX_CLASSIFIER="linux-arm32-monocle" ;;
    x86_64)        FX_CLASSIFIER="linux" ;;
    *)             FX_CLASSIFIER="linux" ;;
esac
info "Platform     : $OS_ARCH → classifier=$FX_CLASSIFIER"

# Find JavaFX jars for this platform in the Maven repo
M2="$HOME/.m2/repository/org/openjfx"
FX_VERSION="21.0.1"
FX_JARS=""
for MODULE in javafx-controls javafx-fxml javafx-graphics javafx-base; do
    # Try platform-specific jar first, then fall back to generic
    JAR=$(find "$M2/$MODULE/$FX_VERSION" \
          -name "${MODULE}-${FX_VERSION}-${FX_CLASSIFIER}.jar" 2>/dev/null | head -1)
    if [ -z "$JAR" ]; then
        JAR=$(find "$M2/$MODULE/$FX_VERSION" \
              -name "${MODULE}-${FX_VERSION}.jar" 2>/dev/null | head -1)
    fi
    if [ -n "$JAR" ]; then
        FX_JARS="$FX_JARS:$JAR"
        info "  Found: $MODULE"
    else
        warn "  Not found: $MODULE (may already be bundled in fat JAR)"
    fi
done
# Strip leading colon
FX_JARS="${FX_JARS#:}"

# ── Step 4: jpackage ──────────────────────────────────────────────────────────
hr
info "Step 4/4 — Running jpackage"

# Common jpackage arguments
JPACKAGE_ARGS=(
    --name            "$APP_NAME"
    --app-version     "$APP_VERSION"
    --description     "$DESCRIPTION"
    --vendor          "$VENDOR"
    --input           "$INPUT_DIR"
    --main-jar        "sharewave.jar"
    --main-class      "$MAIN_CLASS"
    --dest            "$DIST_DIR"
    --java-options    "--enable-preview"
    --java-options    "-Dfile.encoding=UTF-8"
)

# Add JavaFX module path if we found native jars
if [ -n "$FX_JARS" ]; then
    JPACKAGE_ARGS+=(--java-options "--module-path $FX_JARS")
    JPACKAGE_ARGS+=(--java-options "--add-modules javafx.controls,javafx.fxml")
fi

# Add open-module flags needed by Jetty / JavaFX on Java 17+
JPACKAGE_ARGS+=(
    --java-options "--add-opens=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED"
    --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED"
)

BUILD_TARGETS="${1:-all}"

build_app_image() {
    info "Building app-image..."
    jpackage --type app-image "${JPACKAGE_ARGS[@]}" \
        --dest "$DIST_DIR"
    ok "app-image created: $DIST_DIR/$APP_NAME/"
    info "Run with: $DIST_DIR/$APP_NAME/bin/ShareWave"
}

build_deb() {
    if ! command -v fakeroot >/dev/null 2>&1; then
        warn "fakeroot not found — skipping .deb. Install with: sudo apt install fakeroot"
        return
    fi
    info "Building .deb package..."
    DEB_DEST="$DIST_DIR/deb"
    mkdir -p "$DEB_DEST"
    jpackage --type deb "${JPACKAGE_ARGS[@]}" \
        --dest              "$DEB_DEST"        \
        --linux-package-name "sharewave"       \
        --linux-deb-maintainer "admin@sharewave.local" \
        --linux-menu-group  "Network"          \
        --linux-shortcut
    DEB_FILE=$(ls "$DEB_DEST"/*.deb 2>/dev/null | head -1)
    [ -n "$DEB_FILE" ] && ok ".deb created: $DEB_FILE" \
                       || warn ".deb build may have failed"
}

case "$BUILD_TARGETS" in
    app-image) build_app_image ;;
    deb)       build_deb       ;;
    *)
        build_app_image
        build_deb
        ;;
esac

hr
ok "Build complete. Output in: $DIST_DIR/"
ls -lh "$DIST_DIR/" 2>/dev/null || true
hr
