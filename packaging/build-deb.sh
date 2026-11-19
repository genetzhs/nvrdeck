#!/bin/bash
# Φτιάχνει .deb για Ubuntu/Debian χωρίς εξωτερικά εργαλεία (μόνο dpkg-deb).
set -euo pipefail
cd "$(dirname "$0")/.."
VER=$(grep -m1 '^version' desktop/Cargo.toml | cut -d'"' -f2)
ARCH=$(dpkg --print-architecture)
(cd desktop && cargo build --release)
D=$(mktemp -d)/nvrdeck_${VER}_${ARCH}
install -Dm755 desktop/target/release/nvrdeck "$D/usr/bin/nvrdeck"
install -Dm644 packaging/nvrdeck.desktop "$D/usr/share/applications/nvrdeck.desktop"
install -Dm644 packaging/nvrdeck.png "$D/usr/share/icons/hicolor/512x512/apps/nvrdeck.png"
install -Dm644 LICENSE "$D/usr/share/doc/nvrdeck/copyright"
mkdir -p "$D/DEBIAN"
cat > "$D/DEBIAN/control" <<CTL
Package: nvrdeck
Version: ${VER}
Section: video
Priority: optional
Architecture: ${ARCH}
Depends: ffmpeg, libc6, libgl1, libxkbcommon0
Maintainer: genetzhs <genetzhs@users.noreply.github.com>
Homepage: https://github.com/genetzhs/nvrdeck
Description: Lightweight NVR viewer for IP cameras
 Live RTSP grid, NVR channel import, network scan, themes.
CTL
mkdir -p dist
dpkg-deb --root-owner-group --build "$D" "dist/nvrdeck_${VER}_${ARCH}.deb"
echo "dist/nvrdeck_${VER}_${ARCH}.deb"
