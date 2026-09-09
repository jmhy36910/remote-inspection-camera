# Remote Inspection Camera

[繁體中文](README.md)

An Android Camera2 app with an embedded web controller and a Windows Python controller for camera settings, preview, capture, and ROI displacement measurement.

Preview is capped at 30 FPS. Photo and preview resolution choices are generated from the selected camera's reported Camera2 capabilities instead of device-specific dimensions. Physical-camera access, RAW, manual controls, and achieved frame rate still depend on the OEM implementation.

## Requirements and installation

- Windows 10/11, JDK 17, Android SDK 35, Gradle 8.11.1
- Android 8.0/API 26+, Camera2, and Wi-Fi
- Python 3.11+ with Tk

```powershell
git clone <repository-url>
cd remote-inspection-camera
.\bootstrap-android.ps1
$env:ANDROID_SDK_ROOT = 'C:\Android\Sdk'
.\build-android.ps1 -RunLint
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
```

Create `local.properties` for the SDK if needed. No API key, database, or `.env` is required. Install `app/build/outputs/apk/debug/app-debug.apk`, place phone and PC on the same private network, then run `python windows-controller/remote_camera_control.py` and enter the address shown by the app. OEM Camera2 support varies.

## Build and test

`build-android.ps1 -RunLint` compiles and lints Android. Run `python -m py_compile windows-controller/remote_camera_control.py` for a basic controller check. There is no automated hardware test. `app`, `windows-controller`, `protocol`, `tools`, and `docs` contain the main components.

## License and notice

Source code is MIT-licensed; dependency licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). This is non-profit research/learning work primarily generated with OpenAI Codex assistance. It is not an official third-party product. Credentials, installed dependencies, packages, test photos, and build outputs are excluded. Credible specific infringement reports will pause affected content pending review.
