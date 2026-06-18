#!/usr/bin/env bash
# =============================================================================
# ShareWave — multi-module build script
#
# Produces:
#   sharewave-server/target/sharewave-server.jar   (headless server)
#   sharewave-gui/target/sharewave-gui.jar         (JavaFX admin GUI)
#
# Usage:
#   ./build.sh           build both modules
#   ./build.sh server    build server only
#   ./build.sh gui       build GUI only
#
# To install after building, see ./install.sh
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

info()  { echo -e "\033[1;34m[INFO]\033[0m  $*"; }
ok()    { echo -e "\033[1;32m[ OK ]\033[0m  $*"; }
warn()  { echo -e "\033[1;33m[WARN]\033[0m  $*"; }
err()   { echo -e "\033[1;31m[ERR ]\033[0m  $*"; exit 1; }
hr()    { echo "─────────────────────────────────────────────────────"; }

hr
info "ShareWave Build"
hr

command -v java >/dev/null || err "java not found"
command -v mvn  >/dev/null || err "mvn not found"
info "Java : $(java -version 2>&1 | head -1)"
info "Maven: $(mvn --version | head -1)"
hr

# The VERSION file at the project root is the single source of truth —
# both modules read it directly at runtime (see AppVersion.java in each).
# Nothing needs to be copied or bundled into the JARs at build time.
if [ -f "$SCRIPT_DIR/VERSION" ]; then
    info "Version: $(tr -d '[:space:]' < "$SCRIPT_DIR/VERSION") (from VERSION file)"
else
    warn "No VERSION file found at project root — will report version 'dev'"
fi
hr

TARGET="${1:-all}"

build_server() {
    info "Building sharewave-server..."
    mvn -q package -DskipTests -pl sharewave-server -am
    ok "sharewave-server.jar → sharewave-server/target/sharewave-server.jar"
}

build_gui() {
    info "Building sharewave-gui..."
    mvn -q package -DskipTests -pl sharewave-gui -am
    ok "sharewave-gui.jar → sharewave-gui/target/sharewave-gui.jar"
}

case "$TARGET" in
    server)  build_server ;;
    gui)     build_gui    ;;
    *)       build_server; build_gui ;;
esac

hr
ok "Build complete."
ls -lh sharewave-server/target/sharewave-server.jar 2>/dev/null || true
ls -lh sharewave-gui/target/sharewave-gui.jar       2>/dev/null || true
echo ""
echo "To install: sudo ./install.sh"
hr
