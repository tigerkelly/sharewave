#!/usr/bin/env bash
# =============================================================================
# ShareWave GUI launcher
#
# Finds the JavaFX native JARs in ~/.m2 (put there by Maven during build)
# and launches sharewave-gui.jar with the correct module path.
#
# Usage:
#   ./run-gui.sh                        # uses GUI JAR from build output
#   ./run-gui.sh /path/to/custom.jar    # uses a specific JAR
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUI_JAR="${1:-$SCRIPT_DIR/sharewave-gui/target/sharewave-gui.jar}"

[ -f "$GUI_JAR" ] || { echo "Error: GUI JAR not found: $GUI_JAR"; echo "Run ./build.sh gui first."; exit 1; }

# ── Detect JavaFX classifier ──────────────────────────────────────────────────
ARCH=$(uname -m)
case "$ARCH" in
    aarch64|arm64) CLASSIFIER="linux-aarch64"    ;;
    armv7l)        CLASSIFIER="linux-arm32-monocle" ;;
    x86_64)        CLASSIFIER="linux"             ;;
    *)             CLASSIFIER="linux"             ;;
esac

FX_VERSION="21.0.1"
M2="$HOME/.m2/repository/org/openjfx"
MODULE_PATH=""

for MOD in javafx-controls javafx-fxml javafx-graphics javafx-base; do
    JAR="$M2/$MOD/$FX_VERSION/${MOD}-${FX_VERSION}-${CLASSIFIER}.jar"
    # Fall back to generic jar if platform-specific not present
    [ -f "$JAR" ] || JAR="$M2/$MOD/$FX_VERSION/${MOD}-${FX_VERSION}.jar"
    if [ -f "$JAR" ]; then
        MODULE_PATH="${MODULE_PATH}:${JAR}"
    else
        echo "Warning: $MOD not found in ~/.m2 — run ./build.sh gui first" >&2
    fi
done
MODULE_PATH="${MODULE_PATH#:}"   # strip leading colon

if [ -z "$MODULE_PATH" ]; then
    echo "Error: No JavaFX JARs found."
    echo "Run ./build.sh gui to download them, then try again."
    exit 1
fi

java \
    --module-path "$MODULE_PATH" \
    --add-modules javafx.controls,javafx.fxml \
    --add-opens=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED \
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    -jar "$GUI_JAR" "$@" 2> >(grep -v '^WARNING' >&2)
exit $?
