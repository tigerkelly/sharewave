#!/usr/bin/env bash
# =============================================================================
# ShareWave — install script
#
# Installs ShareWave under /opt/sharewave:
#   /opt/sharewave/sharewave-server.jar   headless server JAR
#   /opt/sharewave/sharewave-gui.jar      JavaFX admin GUI JAR
#   /opt/sharewave/javafx/                JavaFX native libraries (for GUI)
#   /opt/sharewave/sharewave              wrapper script that launches the GUI
#
# Also installs and starts the sharewave-server systemd service, and links
# the "sharewave" wrapper into /usr/local/bin so the GUI can be launched
# from anywhere by typing: sharewave
#
# Usage:
#   ./build.sh              # build the JARs first
#   sudo ./install.sh        # then install (requires root)
#
# Re-running this script is safe — it overwrites the previous install.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

INSTALL_DIR="/opt/sharewave"
BIN_LINK="/usr/local/bin/sharewave"

info()  { echo -e "\033[1;34m[INFO]\033[0m  $*"; }
ok()    { echo -e "\033[1;32m[ OK ]\033[0m  $*"; }
warn()  { echo -e "\033[1;33m[WARN]\033[0m  $*"; }
err()   { echo -e "\033[1;31m[ERR ]\033[0m  $*"; exit 1; }
hr()    { echo "─────────────────────────────────────────────────────"; }

hr
info "ShareWave installer"
hr

[ "$(id -u)" -eq 0 ] || err "Must be run as root (try: sudo ./install.sh)"

SERVER_JAR="$SCRIPT_DIR/sharewave-server/target/sharewave-server.jar"
GUI_JAR="$SCRIPT_DIR/sharewave-gui/target/sharewave-gui.jar"

[ -f "$SERVER_JAR" ] || [ -f "$GUI_JAR" ] \
    || err "Neither JAR found — run ./build.sh first"

HAVE_SERVER=0; [ -f "$SERVER_JAR" ] && HAVE_SERVER=1
HAVE_GUI=0;    [ -f "$GUI_JAR" ]    && HAVE_GUI=1

[ "$HAVE_SERVER" -eq 1 ] || warn "Server JAR not found: $SERVER_JAR (skipping server install/service)"
[ "$HAVE_GUI"    -eq 1 ] || warn "GUI JAR not found: $GUI_JAR (skipping GUI install)"

# ── Step 1: Copy JARs ─────────────────────────────────────────────────────────
info "Step 1/5 — Installing JARs to $INSTALL_DIR"
mkdir -p "$INSTALL_DIR"
if [ "$HAVE_SERVER" -eq 1 ]; then
    cp "$SERVER_JAR" "$INSTALL_DIR/sharewave-server.jar"
    ok "  $INSTALL_DIR/sharewave-server.jar"
fi
if [ "$HAVE_GUI" -eq 1 ]; then
    cp "$GUI_JAR" "$INSTALL_DIR/sharewave-gui.jar"
    ok "  $INSTALL_DIR/sharewave-gui.jar"
fi

# Copy VERSION file alongside the JARs — AppVersion checks here first,
# so admins can update the displayed version post-install without rebuilding.
if [ -f "$SCRIPT_DIR/VERSION" ]; then
    cp "$SCRIPT_DIR/VERSION" "$INSTALL_DIR/VERSION"
    ok "  $INSTALL_DIR/VERSION ($(tr -d '[:space:]' < "$SCRIPT_DIR/VERSION"))"
fi

# ── Step 2: Bundle JavaFX native libraries for the GUI ────────────────────────
info "Step 2/5 — Bundling JavaFX native libraries"

FOUND_ANY=0
if [ "$HAVE_GUI" -eq 0 ]; then
    info "  Skipped (no GUI JAR)"
else

OS_ARCH=$(uname -m)
case "$OS_ARCH" in
    aarch64|arm64) FX_CLASSIFIER="linux-aarch64" ;;
    armv7l|armhf)  FX_CLASSIFIER="linux-arm32-monocle" ;;
    x86_64)        FX_CLASSIFIER="linux" ;;
    *)             FX_CLASSIFIER="linux" ;;
esac
info "  Platform: $OS_ARCH → FX classifier=$FX_CLASSIFIER"

# Find the real user's ~/.m2 (Maven downloads JavaFX jars there during build).
# When run with sudo, $HOME is root's home, so check SUDO_USER's home too.
M2_CANDIDATES=()
if [ -n "${SUDO_USER:-}" ]; then
    SUDO_HOME=$(getent passwd "$SUDO_USER" | cut -d: -f6)
    [ -n "$SUDO_HOME" ] && M2_CANDIDATES+=("$SUDO_HOME/.m2/repository/org/openjfx")
fi
M2_CANDIDATES+=("$HOME/.m2/repository/org/openjfx")

FX_VERSION="21.0.1"
FX_DIR="$INSTALL_DIR/javafx"
mkdir -p "$FX_DIR"

for MODULE in javafx-controls javafx-fxml javafx-graphics javafx-base; do
    JAR=""
    for M2 in "${M2_CANDIDATES[@]}"; do
        [ -d "$M2/$MODULE/$FX_VERSION" ] || continue
        JAR=$(find "$M2/$MODULE/$FX_VERSION" \
              -name "${MODULE}-${FX_VERSION}-${FX_CLASSIFIER}.jar" 2>/dev/null | head -1)
        [ -z "$JAR" ] && JAR=$(find "$M2/$MODULE/$FX_VERSION" \
              -name "${MODULE}-${FX_VERSION}.jar" 2>/dev/null \
              | grep -v sources | grep -v javadoc | head -1)
        [ -n "$JAR" ] && break
    done

    if [ -n "$JAR" ]; then
        cp "$JAR" "$FX_DIR/"
        ok "  $(basename "$JAR")"
        FOUND_ANY=1
    else
        warn "  Not found: $MODULE (searched: ${M2_CANDIDATES[*]})"
    fi
done

if [ "$FOUND_ANY" -eq 0 ]; then
    warn "  No JavaFX jars found — the 'sharewave' GUI command will not work."
    warn "  Build with ./build.sh as the same user whose ~/.m2 has JavaFX,"
    warn "  then re-run this installer."
fi

fi  # HAVE_GUI

# ── Step 3: Install the "sharewave" GUI launcher ──────────────────────────────
info "Step 3/5 — Installing GUI launcher"

if [ "$HAVE_GUI" -eq 0 ]; then
    info "  Skipped (no GUI JAR)"
else

cat > "$INSTALL_DIR/sharewave" <<'EOF'
#!/usr/bin/env bash
# =============================================================================
# ShareWave admin GUI launcher — installed by install.sh
#
# Launches /opt/sharewave/sharewave-gui.jar with the JavaFX native
# libraries bundled at install time in /opt/sharewave/javafx/.
# =============================================================================
set -euo pipefail

INSTALL_DIR="/opt/sharewave"
GUI_JAR="$INSTALL_DIR/sharewave-gui.jar"
FX_DIR="$INSTALL_DIR/javafx"

[ -f "$GUI_JAR" ] || { echo "Error: $GUI_JAR not found. Re-run install.sh."; exit 1; }

MODULE_PATH=""
if [ -d "$FX_DIR" ]; then
    for jar in "$FX_DIR"/*.jar; do
        [ -e "$jar" ] || continue
        MODULE_PATH="${MODULE_PATH}:${jar}"
    done
    MODULE_PATH="${MODULE_PATH#:}"
fi

if [ -z "$MODULE_PATH" ]; then
    echo "Error: No JavaFX libraries found in $FX_DIR."
    echo "Re-run install.sh on a machine where ./build.sh has populated ~/.m2 with JavaFX."
    exit 1
fi

java \
    --module-path "$MODULE_PATH" \
    --add-modules javafx.controls,javafx.fxml \
    --add-opens=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED \
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    -jar "$GUI_JAR" "$@" 2> >(grep -v '^WARNING' >&2)
exit $?
EOF
chmod +x "$INSTALL_DIR/sharewave"
ok "  $INSTALL_DIR/sharewave"

# Link into PATH so "sharewave" works from anywhere
ln -sf "$INSTALL_DIR/sharewave" "$BIN_LINK"
ok "  $BIN_LINK -> $INSTALL_DIR/sharewave"

fi  # HAVE_GUI

# ── Step 4: Install systemd service ───────────────────────────────────────────
info "Step 4/5 — Installing systemd service"

if [ "$HAVE_SERVER" -eq 0 ]; then
    info "  Skipped (no server JAR)"
else

cp "$SCRIPT_DIR/sharewave-server.service" /etc/systemd/system/sharewave-server.service
systemctl daemon-reload
ok "  /etc/systemd/system/sharewave-server.service"

fi  # HAVE_SERVER

# ── Step 5: Enable and (re)start the service ──────────────────────────────────
info "Step 5/5 — Enabling and starting sharewave-server"

if [ "$HAVE_SERVER" -eq 0 ]; then
    info "  Skipped (no server JAR)"
else

systemctl enable sharewave-server >/dev/null 2>&1 || true
if systemctl is-active --quiet sharewave-server; then
    systemctl restart sharewave-server
    ok "  Restarted sharewave-server"
else
    systemctl start sharewave-server
    ok "  Started sharewave-server"
fi

fi  # HAVE_SERVER

hr
ok "Install complete."
echo ""
if [ "$HAVE_SERVER" -eq 1 ]; then
    echo "  Server  : systemctl status sharewave-server"
    echo "            journalctl -u sharewave-server -f"
    echo ""
fi
if [ "$HAVE_GUI" -eq 1 ] && [ "$FOUND_ANY" -eq 1 ]; then
    echo "  Admin GUI: sharewave"
    echo ""
fi
echo "  Installed under: $INSTALL_DIR"
hr

if [ "$HAVE_SERVER" -eq 1 ]; then
    echo ""
    echo "NOTE: On first run, the server needs an admin password set on the"
    echo "console. If this is a fresh install, stop the service, run the JAR"
    echo "manually once to set the password, then restart:"
    echo ""
    echo "  sudo systemctl stop sharewave-server"
    echo "  java -jar $INSTALL_DIR/sharewave-server.jar   # enter password, then Ctrl+C"
    echo "  sudo systemctl start sharewave-server"
fi
