# Remote Inspection Camera

[繁體中文](README.md) · [Latest release](https://github.com/jmhy36910/remote-inspection-camera/releases/latest) · [License](LICENSE)

A private-LAN Android Camera2 inspection camera. The phone provides camera preview and capture, while the embedded web controller or Windows controller provides remote operation. It includes camera controls, ROI displacement measurement, original-size capture, live JPEG preview, and optional grayscale YUV preview.

## Features and limits

- Preview requests are capped at 30 FPS. Actual FPS, resolutions, physical cameras, RAW, manual controls, EIS/OIS, and stabilization behavior depend on each device's Camera2/OEM implementation.
- Preview and photo sizes come from the selected camera's reported capabilities; no phone model is hard-coded.
- JPEG preview uses the phone's actual portrait aspect ratio. Windows `AUTO · PHONE STREAM` uses proportional FIT with no crop or stretch.
- Live preview is newest-frame-first. Network reception, decoding, and UI rendering are separate; obsolete preview frames are overwritten rather than blocking controls or capture.
- Original-size photos preserve the Camera2 JPEG bytes and EXIF Orientation; Windows does not resize or recompress them.

## Quick start

### 1. Install the Android APK

Download the APK from the [latest release](https://github.com/jmhy36910/remote-inspection-camera/releases/latest), or build it from source as described below:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it, start the app, grant camera/network permissions, and turn the camera on.

### 2. Connect the Windows controller

Put phone and PC on the same **private** network. Download the Windows ZIP from the Release page, extract it, and run `Machine Vision Camera Controller.exe`; or run it from source:

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\python windows-controller\remote_camera_control.py
```

Enter the phone's current IP and TCP port `8765`, then select **Connect**.

### 3. Pair once

1. The app displays a fresh six-digit PIN at startup.
2. Enter it in the Windows controller and select **Pair once**.
3. The controller saves its token at `%LOCALAPPDATA%\RemoteInspectionCamera\config.json` for that Windows user.

After pairing, the controller reconnects on restart with its stored IP/token. If the phone's DHCP address changes, update the IP only; pairing remains valid. A PIN is needed again only for a new PC, deleted PC settings, cleared/reinstalled Android app data, or **Revoke all pairings**. PINs expire after five minutes and are invalidated immediately after a successful pairing.

## Web controller

From the same private network, open:

```text
http://PHONE_IP:8787/
```

It also requires a one-time PIN on first use. Its token stays only in that browser's local storage.

## Security

- TCP controls, photo events, SSE, and MJPEG require a paired token.
- Transport is private-LAN HTTP/TCP with **no TLS**. Never expose ports `8765` or `8787` to the Internet.
- Allow only private networks if the firewall asks.
- This repository excludes tokens, credentials, installed dependencies, APK/EXE files, test photos, and build artifacts.

## Build from source

### Requirements

- Windows 10/11
- Android SDK Platform 36, suitable Build Tools, JDK 17, and an available JVM 21 Kotlin toolchain
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

Alternatively, create an untracked `local.properties` in the project root:

```properties
sdk.dir=C\:\\Android\\Sdk
```

### Windows controller

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\python windows-controller\remote_camera_control.py
```

## Verification

```powershell
# Android compile + lint
.\build-android.ps1 -RunLint

# Windows proportional-display unit tests
.\.venv\Scripts\python -m unittest discover -s windows-controller -p 'test*.py' -v
```

Hardware behavior, Wi-Fi latency, camera capabilities, and achieved 30 FPS still need verification on the target phone and network.

## Project structure

| Path | Purpose |
| --- | --- |
| `app/` | Android Camera2 app and embedded web UI |
| `windows-controller/` | Windows Tkinter controller, preview, and tests |
| `protocol/` | JSON schema for the control protocol |
| `docs/` | Build, power, and development notes |
| `tools/` | Development utilities such as documentation generation |

## License and notice

The source is released under the [MIT License](LICENSE). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for dependency licenses. This is non-profit research and learning work, primarily generated with OpenAI Codex assistance; it is not affiliated with or endorsed by phone, camera, or third-party brands. If a rights holder raises a specific credible infringement concern, affected content will be paused while it is reviewed.
