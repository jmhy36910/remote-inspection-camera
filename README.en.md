# Remote Inspection Camera

> An Android Camera2 inspection camera with Windows and web remote controls.

[繁體中文](README.md) · [Download latest](https://github.com/jmhy36910/remote-inspection-camera/releases/latest) · [MIT License](LICENSE)

## Overview

The phone handles live camera preview and original-size capture. A PC on the same private network can control camera parameters, view the stream, and perform ROI displacement measurement.

| Feature | Description |
| --- | --- |
| Remote control | Windows GUI or embedded web page |
| Live preview | JPEG or grayscale YUV, up to a requested 30 FPS |
| Low latency | Separate receive, decode, and display stages; only the newest frame is retained |
| Capture | Preserves Camera2 JPEG bytes and EXIF Orientation |
| Device support | Resolutions and camera features come from reported Camera2 capabilities |

> Actual FPS, RAW, physical-camera access, manual controls, and EIS/OIS depend on the phone's OEM implementation. No phone model is hard-coded.

## Quick start

1. Download the Android APK and Windows ZIP from [Releases](https://github.com/jmhy36910/remote-inspection-camera/releases/latest).
2. Install the APK, grant camera/network permissions, and turn the camera on.
3. Extract the Windows ZIP and run `Machine Vision Camera Controller.exe`.
4. Put the phone and PC on the same **private network**.
5. Enter the phone IP and port `8765`, then select **Connect**.
6. On first connection, enter the six-digit PIN shown by the app and select **Pair once**.

After pairing, Windows stores the token at:

```text
%LOCALAPPDATA%\RemoteInspectionCamera\config.json
```

The same Windows user reconnects automatically. If the phone IP changes, update only the IP. A new PIN is required after moving to a new PC, deleting local settings, reinstalling/clearing the app, or revoking all pairings. PINs expire after five minutes and become invalid immediately after success.

### Web control

From the same private network, open:

```text
http://PHONE_IP:8787/
```

The web controller also requires a PIN on first use. Its token remains in that browser's local storage.

## Preview and capture

- Windows `AUTO · PHONE STREAM` proportionally FITs the received dimensions with no crop or stretch.
- If the network or PC falls behind, obsolete preview frames are overwritten instead of accumulating in an application queue.
- Control commands and original-size captures are not subject to preview-frame dropping.
- Windows saves the original photo received from the phone without resizing or recompression.

## Build from source

### Requirements

- Windows 10/11
- Android SDK Platform 36 and suitable Build Tools
- JDK 17 and an available JVM 21 Kotlin toolchain
- Android 9 / API 28 or newer with Camera2 and Wi-Fi
- Python 3.11+ with Tk for the Windows controller

### Android

```powershell
git clone https://github.com/jmhy36910/remote-inspection-camera.git
cd remote-inspection-camera
$env:ANDROID_SDK_ROOT = 'C:\Android\Sdk'
.\bootstrap-android.ps1
.\build-android.ps1 -RunLint
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Instead of `ANDROID_SDK_ROOT`, you may create an untracked `local.properties`:

```properties
sdk.dir=C\:\\Android\\Sdk
```

### Windows controller

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\python windows-controller\remote_camera_control.py
```

### Verification

```powershell
.\build-android.ps1 -RunLint
.\.venv\Scripts\python -m unittest discover -s windows-controller -p 'test*.py' -v
```

Builds and unit tests do not prove support for every phone. Verify camera capabilities, latency, and achieved FPS on the target device and network.

## Project structure

| Path | Purpose |
| --- | --- |
| `app/` | Android app and embedded web UI |
| `windows-controller/` | Windows controller, preview, and tests |
| `protocol/` | Control-protocol JSON Schema |
| `docs/` | Build, power, and development notes |
| `tools/` | Documentation utilities |

## Security

TCP controls, photo events, SSE, and MJPEG require a paired token, but transport is **unencrypted HTTP/TCP**. Use it only on a private LAN. Never expose ports `8765` or `8787` to the Internet, and allow only private networks if the firewall asks.

The repository excludes credentials, tokens, installed dependencies, APK/EXE files, test photos, caches, and build artifacts.

## License and disclaimer

Source code is released under the [MIT License](LICENSE). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for dependency licenses. This is non-profit research and learning work, primarily generated with OpenAI Codex assistance. It is not affiliated with or endorsed by any phone, camera, or third-party brand. If a rights holder reports a specific credible infringement concern, affected content will be paused while it is reviewed.
