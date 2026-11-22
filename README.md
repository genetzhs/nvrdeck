# NVRDeck

Lightweight, open-source NVR viewer for IP cameras — a **Rust desktop app** and a **native Android app** (Kotlin / Jetpack Compose).
Watch all your cameras live over RTSP, without the vendor's cloud app.

## Features

| | Desktop (Rust / egui) | Android (Kotlin / Compose) |
|---|---|---|
| **Live view** | | |
| Live RTSP grid (Auto, 1, 2×2, 2×3, 1+4, 1+5, 3×3, 1+7) | ✓ | ✓ |
| Camera groups with per-group layout | ✓ | ✓ |
| Full-screen single camera with zoom/pan | ✓ (wheel, F11, 1–7, double-click) | ✓ (pinch, double-tap) |
| Sub stream in the grid, HD main stream in full view (auto-switch) | ✓ | ✓ |
| Live timestamp badge (turns red when the stream freezes) | ✓ | ✓ |
| Auto-reconnect | ✓ | ✓ |
| Monitor-wall mode (kiosk autostart, auto-login) | ✓ | — |
| Home-screen camera widget | — | ✓ |
| Camera health stats (uptime, FPS, resolution) | ✓ | ✓ |
| **Snapshots, clips & sound** | | |
| Snapshot to Pictures | ✓ | ✓ |
| Clip recording (MP4) | ✓ | — |
| Camera audio (listen) | ✓ | ✓ |
| Two-way audio: hold to talk through the camera | ✓ | ✓ |
| **Alerts** | | |
| On-device person/vehicle recognition (YOLOX-nano) — alerts with photo, parked objects ignored | ✓ | ✓ |
| AI alert area (zone) and line crossing | ✓ | ✓ |
| Alerts from the recorder's own events (motion, people, vehicles) | ✓ | ✓ |
| Arm/disarm (also from the notification) | ✓ | ✓ |
| Alert banner in-app with Play clip; notification opens the clip | ✓ | ✓ |
| Offline alerts after 30 minutes | ✓ | ✓ |
| Alert service survives reboots and battery saver; lightweight mode without photos | — | ✓ |
| **Recorder (NVR)** | | |
| Playback from the recorder: month calendar, day timeline, seek, zoom, speed | ✓ | ✓ |
| Motion-only playback (skips the gaps) | ✓ | ✓ |
| Timeline thumbnails with people/vehicle tags + event list with smart search | ✓ | ✓ |
| Clip export (mark a range → MP4, all visible channels) | ✓ | ✓ |
| Download whole recordings as MP4 | ✓ | — |
| Recorder settings: motion zones, sensitivity, schedule, clock sync, admin password, restart | ✓ | ✓ |
| Recorder health: disk usage, retention depth, "stopped recording" alerts | ✓ | ✓ |
| Auto-discover new recorder channels on sign-in; deleted channels stay deleted | ✓ | ✓ |
| **Platform & security** | | |
| Presets: XMeye/NetSurveillance, Jovision, Hikvision, Dahua, generic RTSP | ✓ | ✓ |
| Import all channels of an NVR | ✓ | ✓ |
| Network scan (RTSP probe + ONVIF WS-Discovery, vendor guess) | ✓ | — |
| Connection test with friendly errors | ✓ | ✓ |
| Encrypted QR transfer of the camera list (desktop → phone) | ✓ | ✓ |
| In-app updater (GitHub Releases, SHA256-verified; the AppImage updates itself) | ✓ | — |
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

The desktop app checks GitHub for a newer release when it starts (Settings → Updates, can be disabled) and verifies the download's SHA256 checksum before doing anything with it; the AppImage replaces itself in place, .deb/.dmg are opened for a normal install.

## Desktop

Requirements: Rust (stable), `ffmpeg` and `ffprobe` in `PATH`.

```bash
cd desktop
cargo build --release
./target/release/nvrdeck
```

On first start you create an admin account, then add cameras manually, via **Scan network**, or via **Add NVR**. On sign-in NVRDeck also asks each recorder how many channels it has and quietly adds the ones missing (channels you deleted stay deleted).
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

Everything on the original v0.1 roadmap (snapshots, clip recording, offline alerts, motion detection, zoom, QR export, stream switching, NVR playback, groups, Android widget, health stats, monitor-wall mode) has shipped. Nothing currently planned — the app does what its two users need; open an issue if you need something.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
The bundled Geist and Geist Mono fonts are licensed under the SIL Open Font License 1.1 — see [licenses/OFL-Geist.txt](licenses/OFL-Geist.txt).
