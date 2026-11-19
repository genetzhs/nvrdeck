#!/bin/bash
# Φτιάχνει NVRDeck-<ver>-x86_64.AppImage. Χρειάζεται το appimagetool στο PATH (το κατεβάζει το CI).
set -euo pipefail
cd "$(dirname "$0")/.."
VER=$(grep -m1 '^version' desktop/Cargo.toml | cut -d'"' -f2)
(cd desktop && cargo build --release)
AD=$(mktemp -d)/NVRDeck.AppDir
install -Dm755 desktop/target/release/nvrdeck "$AD/usr/bin/nvrdeck"
install -Dm644 packaging/nvrdeck.desktop "$AD/nvrdeck.desktop"
install -Dm644 packaging/nvrdeck.png "$AD/nvrdeck.png"
install -Dm644 packaging/nvrdeck.png "$AD/usr/share/icons/hicolor/512x512/apps/nvrdeck.png"
cat > "$AD/AppRun" <<'RUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/bin/nvrdeck" "$@"
RUN
chmod +x "$AD/AppRun"
mkdir -p dist
ARCH=x86_64 appimagetool "$AD" "dist/NVRDeck-${VER}-x86_64.AppImage"
echo "dist/NVRDeck-${VER}-x86_64.AppImage"
