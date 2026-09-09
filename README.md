# Remote Inspection Camera（遠端檢測相機）

[English](README.en.md)

Android Camera2 相機、手機內建 Web 控制頁與 Windows Python 控制器，可調整相機參數、預覽、拍照及 ROI 位移量測。

## 運行環境

- Android 建置：Windows 10/11、JDK 17、Android SDK 35、Gradle 8.11.1
- App：Android 8.0（API 26）以上，需 Camera2 與 Wi-Fi
- Windows 控制器：Python 3.11+、Tk、`opencv-python`、`numpy`、`Pillow`

## 安裝

```powershell
git clone <repository-url>
cd remote-inspection-camera
.\bootstrap-android.ps1
$env:ANDROID_SDK_ROOT = 'C:\Android\Sdk'
.\build-android.ps1 -RunLint
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
```

在 Android SDK 路徑建立 `local.properties`，內容如 `sdk.dir=C\:\\Android\\Sdk`，或設定 `ANDROID_SDK_ROOT`。APK 位於 `app/build/outputs/apk/debug/app-debug.apk`。本專案不需要 API key、資料庫或 `.env`。

## 使用方式

安裝並啟動 App，允許相機與網路權限，確認手機和電腦在同一私人網路，再執行 `python windows-controller/remote_camera_control.py` 並輸入手機顯示的位址。防火牆若詢問，僅允許私人網路。實際 Camera2 功能依手機 OEM 能力而異。

## Build / Test

`build-android.ps1 -RunLint` 執行 Android compile 與 lint。Windows controller 目前以 `python -m py_compile windows-controller/remote_camera_control.py` 做基本驗證；repository 尚無自動化硬體測試。

## 專案結構

- `app/`：Android Camera2 App 與內建 Web UI
- `windows-controller/`：Windows GUI
- `protocol/`：控制協定 JSON Schema
- `tools/`、`docs/`：文件產生工具與開發說明

## 授權與聲明

Source code 依 [MIT License](LICENSE) 開源，第三方元件授權請見 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。本專案定位為非營利研究與學習，主要由 OpenAI Codex 協助生成。不是手機、相機或第三方品牌的官方產品。Repository 不含 credentials、本機 dependency、安裝包、測試照片或 build artifacts。若權利人提出具體侵權項目，將先暫停相關內容並處理。
