# NVRDeck

Lightweight, open-source NVR viewer for IP cameras — a **Rust desktop app** and a **native Android app** (Kotlin / Jetpack Compose).
Watch all your cameras live over RTSP, without the vendor's cloud app.

## Features

| | Desktop (Rust / egui) | Android (Kotlin / Compose) |
|---|---|---|
| Live RTSP grid (Auto, 1, 2×2, 2×3, 1+4, 1+5, 3×3, 1+7) | ✓ | ✓ |
| Full-screen single camera | ✓ (F11, 1–7, double-click) | ✓ (landscape, immersive) |
| Live timestamp badge (turns red when the stream freezes) | ✓ | ✓ |
| Auto-reconnect | ✓ | ✓ |
| Presets: XMeye/NetSurveillance, Jovision, Hikvision, Dahua, generic RTSP | ✓ | ✓ |
| Import all channels of an NVR | ✓ | ✓ |
| Network scan (RTSP probe + ONVIF WS-Discovery, vendor guess) | ✓ | — |
| Connection test with friendly errors | ✓ | ✓ |
| Login | admin account (salted SHA-256) | PIN + fingerprint |
| Credential storage | config file, `chmod 600` | EncryptedSharedPreferences (Android Keystore) |
| Themes: Cinematic (dark), Light, Green | ✓ | ✓ |

Remote access is expected to go through **your own VPN** (WireGuard, OpenVPN, Tailscale…) — NVRDeck never exposes cameras to the internet.

## Install

Download the latest packages from the [Releases](https://github.com/genetzhs/nvrdeck/releases) page.

| Platform | File | Notes |
|---|---|---|
| Ubuntu / Debian | `nvrdeck_<ver>_amd64.deb` | `sudo apt install ./nvrdeck_<ver>_amd64.deb` (pulls in `ffmpeg`) |
| Any Linux | `NVRDeck-<ver>-x86_64.AppImage` | `chmod +x` and run; needs `ffmpeg` installed |
| macOS (Intel + Apple Silicon) | `NVRDeck-<ver>-universal.dmg` | needs `brew install ffmpeg`; ad-hoc signed — first launch: right-click → Open |
| Android 8.0+ | `NVRDeck-<ver>.apk` | allow "Install unknown apps" |

Packages are built by GitHub Actions (`.github/workflows/release.yml`) when a `v*` tag is pushed.
Local builds: `packaging/build-deb.sh`, `packaging/build-appimage.sh`, `packaging/macos-bundle.sh` (on a Mac).

## Desktop

Requirements: Rust (stable), `ffmpeg` and `ffprobe` in `PATH`.

```bash
cd desktop
cargo build --release
./target/release/nvrdeck
```

On first start you create an admin account, then add cameras manually, via **Scan network**, or via **Add NVR**.
Settings are stored in `~/.config/nvrdeck/nvrdeck.toml` (file mode `600`). Camera passwords are stored there in plain text because they must be sent to the cameras — keep that file private.

## Android

Requirements: Android SDK (API 35), JDK 17. Min Android 8.0.

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Tools

`tools/rtsp-test.sh HOST [PORT] [CHANNEL]` tries common RTSP paths against a camera/NVR. The password is read with a hidden prompt and never printed.

## Roadmap

1. Snapshot button
2. Clip recording (MP4)
3. Offline alerts (desktop notification / push)
4. Motion detection with alerts
5. Pinch / wheel zoom in full-screen view
6. Encrypted QR export of the camera list (desktop → phone)
7. Automatic sub stream in the grid, main (HD) stream in full screen
8. NVR playback / recorded footage
9. Camera groups with per-group layout
10. Android home-screen widget
11. Camera health stats (uptime, FPS, resolution)
12. Autostart in full-screen "monitor wall" mode

## License

Apache License 2.0 — see [LICENSE](LICENSE).
The bundled Geist and Geist Mono fonts are licensed under the SIL Open Font License 1.1 — see [licenses/OFL-Geist.txt](licenses/OFL-Geist.txt).
