# Remote Inspection Camera

> Android Camera2 遠端檢測相機，提供 Windows 與 Web 控制介面。

[English](README.en.md) · [下載最新版](https://github.com/jmhy36910/remote-inspection-camera/releases/latest) · [MIT License](LICENSE)

## 專案簡介

手機負責相機預覽與原尺寸拍照；同一私人網路內的電腦可調整相機參數、查看即時畫面及進行 ROI 位移量測。

| 功能 | 說明 |
| --- | --- |
| 遠端控制 | Windows GUI 或手機內建 Web 頁面 |
| 即時預覽 | JPEG／YUV 灰階，最高要求 30 FPS |
| 低延遲 | 接收、解碼、顯示分離，只保留最新預覽幀 |
| 拍照 | 保留 Camera2 原始 JPEG 與 EXIF Orientation |
| 裝置相容性 | 解析度與鏡頭功能依 Camera2 capability 自動提供 |

> 實際 FPS、RAW、實體鏡頭、手動控制及 EIS/OIS 取決於手機 OEM 實作，不限定特定手機型號。

## 快速開始

1. 從 [Releases](https://github.com/jmhy36910/remote-inspection-camera/releases/latest) 下載 Android APK 與 Windows ZIP。
2. 安裝 APK，允許相機與網路權限，並開啟相機。
3. 解壓 Windows ZIP，執行 `Machine Vision Camera Controller.exe`。
4. 確認手機與電腦位於同一個**私人網路**。
5. 輸入手機 IP 與 port `8765`，按 **Connect**。
6. 首次連線輸入 App 顯示的 6 位數 PIN，再按 **Pair once**。

配對成功後，Windows 會將 token 儲存在：

```text
%LOCALAPPDATA%\RemoteInspectionCamera\config.json
```

同一個 Windows 使用者之後會自動連線。手機 IP 改變時只需更新 IP；新電腦、清除設定、重裝 App 或撤銷所有配對後，才需要重新輸入 PIN。PIN 有效 5 分鐘，成功後立即失效。

### Web 控制

同一私人網路內可直接開啟：

```text
http://手機IP:8787/
```

Web 控制器首次使用也需要 PIN，token 只儲存在該瀏覽器的 local storage。

## 預覽與照片

- Windows 的 `AUTO · PHONE STREAM` 會依收到的實際尺寸等比例 FIT，不裁切、不拉伸。
- 網路或電腦來不及處理時，過期預覽幀會被新幀覆寫，不會形成應用程式佇列。
- 控制命令與原尺寸拍照不使用預覽丟幀策略。
- Windows 儲存手機傳來的原始照片，不重新縮放或壓縮。

## 從原始碼建置

### 環境需求

- Windows 10/11
- Android SDK Platform 36 與適當 Build Tools
- JDK 17，以及可用的 JVM 21 Kotlin toolchain
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

APK 輸出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

若不設定 `ANDROID_SDK_ROOT`，可建立不納入 Git 的 `local.properties`：

```properties
sdk.dir=C\:\\Android\\Sdk
```

### Windows 控制器

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\python windows-controller\remote_camera_control.py
```

### 驗證

```powershell
.\build-android.ps1 -RunLint
.\.venv\Scripts\python -m unittest discover -s windows-controller -p 'test*.py' -v
```

編譯與單元測試不代表所有手機硬體功能皆通過；請在目標手機與網路上驗證鏡頭 capability、延遲及實際 FPS。

## 專案結構

| 路徑 | 用途 |
| --- | --- |
| `app/` | Android App 與內建 Web UI |
| `windows-controller/` | Windows 控制器、預覽與測試 |
| `protocol/` | 控制協定 JSON Schema |
| `docs/` | 建置、功耗與開發說明 |
| `tools/` | 文件產生工具 |

## 安全性

本專案的 TCP 控制、照片事件、SSE 與 MJPEG 都需要配對 token，但傳輸本身是**未加密的 HTTP/TCP**。僅限私人 LAN 使用，不要將 port `8765` 或 `8787` 暴露到 Internet；防火牆提示時只允許私人網路。

Repository 不包含 credentials、token、本機 dependencies、APK/EXE、測試照片、cache 或 build artifacts。

## 授權與免責聲明

原始碼採用 [MIT License](LICENSE)，第三方元件授權見 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。本專案為非營利研究與學習用途，主要由 OpenAI Codex 協助生成，不代表或隸屬任何手機、相機或第三方品牌。若權利人提出具體侵權項目，將先暫停受影響內容並進行處理。
