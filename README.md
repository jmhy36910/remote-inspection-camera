# Remote Inspection Camera（遠端檢測相機）

[English](README.en.md) · [最新版下載](https://github.com/jmhy36910/remote-inspection-camera/releases/latest) · [授權](LICENSE)

適用於私人區域網路的 Android Camera2 檢測相機：手機端提供相機預覽與拍照，並可用內建 Web 控制頁或 Windows 控制器遠端操作。支援相機參數、ROI 位移量測、原尺寸拍照、JPEG 即時預覽與可選 YUV 灰階預覽。

## 功能與限制

- 預覽最高要求為 30 FPS；實際 FPS、可用解析度、實體鏡頭、RAW、手動控制、EIS/OIS 均依手機 Camera2/OEM 實作而定。
- 預覽與拍照尺寸依目前選取鏡頭的 capability 產生，不綁定特定手機型號。
- JPEG 預覽使用手機實際直向比例；Windows `AUTO · PHONE STREAM` 會等比例 FIT，不裁切、不拉伸。
- 即時預覽採最新幀優先：網路接收、解碼、UI 顯示分離；來不及處理的過期預覽會被覆寫，不阻塞控制或拍照。
- 原尺寸照片直接保留手機 Camera2 JPEG bytes 與 EXIF Orientation，不由 Windows 重新縮放或壓縮。

## 快速開始

### 1. 下載或建置 Android APK

直接使用 [最新版 Release](https://github.com/jmhy36910/remote-inspection-camera/releases/latest) 的 APK，或依下方「從原始碼建置」產生：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安裝後啟動 App，允許相機與網路權限，開啟相機。

### 2. 連接 Windows 控制器

手機與 Windows 電腦必須在同一個**私人**網路。可下載 Release 的 Windows ZIP，解壓後執行 `Machine Vision Camera Controller.exe`；或從原始碼執行：

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\python windows-controller\remote_camera_control.py
```

在控制器輸入手機目前 IP 與 TCP port `8765`，按 **Connect**。

### 3. 首次配對

1. App 啟動時會顯示新的 6 位數 PIN。
2. Windows 控制器輸入 PIN，按 **Pair once**。
3. 成功後，該 Windows 使用者將 token 儲存在 `%LOCALAPPDATA%\RemoteInspectionCamera\config.json`。

已配對的控制器重開時會使用儲存的 IP/token 自動連線；手機的 DHCP IP 改變後只需更新 IP，不必重新配對。下列情況才需要 PIN：新電腦、刪除本機設定、清除/重裝 App 資料，或在 App 選擇「撤銷所有配對」。PIN 有效 5 分鐘，成功配對後立即失效。

## 網頁控制器

在同一私人網路以瀏覽器開啟：

```text
http://手機IP:8787/
```

首次也必須輸入 App 顯示的 PIN。token 僅存於該瀏覽器的 local storage。

## 安全性

- TCP 控制、照片事件、SSE、MJPEG 都需要配對 token。
- 傳輸是私人 LAN 上的 HTTP/TCP，**未使用 TLS**；不可把 port `8765` 或 `8787` 暴露到 Internet。
- 防火牆提示時僅允許私人網路。
- repository 不包含 token、credentials、本機 dependency、APK/EXE、測試照片或 build artifacts。

## 從原始碼建置

### Requirements

- Windows 10/11
- Android SDK Platform 36、適當 Build Tools、JDK 17；Kotlin 編譯需要可用的 JVM 21 toolchain
- Android 9（API 28）以上、Camera2、Wi-Fi
- Python 3.11+ 與 Tk（Windows 控制器）

### Android

```powershell
git clone https://github.com/jmhy36910/remote-inspection-camera.git
cd remote-inspection-camera
$env:ANDROID_SDK_ROOT = 'C:\Android\Sdk'
.\bootstrap-android.ps1
.\build-android.ps1 -RunLint
```

若不使用 `ANDROID_SDK_ROOT`，在根目錄建立未追蹤的 `local.properties`，例如：

```properties
sdk.dir=C\:\\Android\\Sdk
```

### Windows 控制器

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\python windows-controller\remote_camera_control.py
```

## 驗證

```powershell
# Android compile + lint
.\build-android.ps1 -RunLint

# Windows 顯示比例單元測試
.\.venv\Scripts\python -m unittest discover -s windows-controller -p 'test*.py' -v
```

硬體、Wi-Fi 延遲、鏡頭 capability 與實際 30 FPS 仍需在目標手機/網路上驗證。

## 專案結構

| 目錄/檔案 | 用途 |
| --- | --- |
| `app/` | Android Camera2 App 與內建 Web UI |
| `windows-controller/` | Windows Tkinter 控制器、預覽與測試 |
| `protocol/` | 控制協定 JSON Schema |
| `docs/` | 建置、功耗與開發說明 |
| `tools/` | 文件產生等開發工具 |

## 授權與聲明

原始碼採用 [MIT License](LICENSE)；第三方元件授權見 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。本專案為非營利研究與學習用途，主要由 OpenAI Codex 協助生成；不代表或隸屬任何手機、相機或第三方品牌。若權利人提出具體侵權項目，將先暫停受影響內容並處理。
