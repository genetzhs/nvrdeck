#!/bin/bash
# Φτιάχνει NVRDeck.app και NVRDeck-<ver>-<arch>.dmg (τρέχει σε macOS).
set -euo pipefail
cd "$(dirname "$0")/.."
VER=$(grep -m1 '^version' desktop/Cargo.toml | cut -d'"' -f2)
# Καθολικό (Intel + Apple Silicon) με UNIVERSAL=1, αλλιώς η αρχιτεκτονική του μηχανήματος
if [ "${UNIVERSAL:-0}" = 1 ]; then
  ARCH=universal
  rustup target add x86_64-apple-darwin aarch64-apple-darwin
  (cd desktop && cargo build --release --target x86_64-apple-darwin && cargo build --release --target aarch64-apple-darwin)
  mkdir -p desktop/target/release
  lipo -create -output desktop/target/release/nvrdeck desktop/target/x86_64-apple-darwin/release/nvrdeck desktop/target/aarch64-apple-darwin/release/nvrdeck
  lipo -info desktop/target/release/nvrdeck
else
  ARCH=$(uname -m)
  (cd desktop && cargo build --release)
fi
APP=dist/NVRDeck.app
rm -rf "$APP"; mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp desktop/target/release/nvrdeck "$APP/Contents/MacOS/nvrdeck"
ICONSET=$(mktemp -d)/nvrdeck.iconset; mkdir -p "$ICONSET"
for s in 16 32 128 256 512; do
  sips -z $s $s packaging/nvrdeck.png --out "$ICONSET/icon_${s}x${s}.png" >/dev/null
  sips -z $((s*2)) $((s*2)) packaging/nvrdeck.png --out "$ICONSET/icon_${s}x${s}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/nvrdeck.icns"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>NVRDeck</string>
  <key>CFBundleDisplayName</key><string>NVRDeck</string>
  <key>CFBundleIdentifier</key><string>io.github.genetzhs.nvrdeck</string>
  <key>CFBundleVersion</key><string>${VER}</string>
  <key>CFBundleShortVersionString</key><string>${VER}</string>
  <key>CFBundleExecutable</key><string>nvrdeck</string>
  <key>CFBundleIconFile</key><string>nvrdeck</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>LSMinimumSystemVersion</key><string>10.15</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSLocalNetworkUsageDescription</key><string>NVRDeck connects to IP cameras on your local network.</string>
</dict></plist>
PLIST
codesign --force --deep -s - "$APP"   # ad-hoc υπογραφή (χωρίς Apple Developer ID)
STAGE=$(mktemp -d); cp -R "$APP" "$STAGE/"; ln -s /Applications "$STAGE/Applications"
hdiutil create -volname NVRDeck -srcfolder "$STAGE" -ov -format UDZO "dist/NVRDeck-${VER}-${ARCH}.dmg"
echo "dist/NVRDeck-${VER}-${ARCH}.dmg"
