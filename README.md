# Remote Inspection Camera（遠端檢測相機）

[English](README.en.md)

Android Camera2 相機、手機內建 Web 控制頁與 Windows Python 控制器，可調整相機參數、預覽、拍照及 ROI 位移量測。

預覽最高為 30 FPS；照片與預覽解析度會依目前選取鏡頭的 Camera2 capability 自動產生，不綁定特定手機尺寸。進階 physical camera、RAW、手動控制與實際幀率仍取決於各手機 OEM 實作。

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

安裝並啟動 App，允許相機與網路權限，確認手機和電腦在同一私人網路，再執行 `python windows-controller/remote_camera_control.py`。App 每次啟動會先顯示新的 6 位數 PIN；只有電腦以既有 token 驗證成功，或新電腦以 PIN 配對成功後，畫面才改為「已配對」。輸入手機目前的 IP（程式不內建固定 IP）、連線，再輸入 PIN 並按 **Pair once**。PIN 有效 5 分鐘且成功後立即失效；Windows 會將隨機 token 保存在目前使用者的 `%LOCALAPPDATA%\RemoteInspectionCamera\config.json`，同一個 Windows 使用者連接這個 App 安裝日後不需重複輸入 PIN，即使手機 DHCP 位址改變也可沿用。新裝置可從 App 選單另外產生 PIN，不會撤銷既有裝置；更換電腦、清除本機設定、重裝／清除 App 資料，或選擇「撤銷所有配對」後需重新配對。

Web 控制頁第一次也要輸入 PIN，token 僅保存在該瀏覽器的 local storage。TCP 控制、照片事件、SSE 與 MJPEG 都要求 token。傳輸目前仍是私人 LAN 上的純 HTTP/TCP，沒有 TLS 加密，因此不要直接暴露到公網；防火牆若詢問，僅允許私人網路。實際 Camera2 功能依手機 OEM 能力而異。

JPEG 依相機影像直向後的實際比例擷取完整 TextureView 內容（例如 720×1280），不裁切、不加入黑色畫布，也不額外旋轉。Windows 的 `AUTO · PHONE STREAM` 按收到的寬高等比例 FIT，放大至影像的一邊接觸視窗邊緣；比例不同時只在視窗留下必要黑邊。YUV 與原尺寸拍照維持原有流程。

即時 JPEG 預覽採最新幀優先：Android 與 PC 都只有一個可覆寫的最新幀槽，網路接收、JPEG 解碼與 UI 顯示分離執行。網路或顯示速度不足時會直接覆寫過期預覽幀，不讓舊畫面在應用程式佇列中持續累積；控制指令與原尺寸拍照事件不受此丟幀策略影響。已配對的 PC 重新開啟時會自動用儲存的 IP/token 重新連線。

Windows 儲存照片時直接寫入手機傳來的原始 JPEG bytes，保留完整解析度與 EXIF Orientation，不重新縮放或壓縮。支援 EXIF 的一般相片檢視器會依方向標記正確顯示直向照片。

## Build / Test

`build-android.ps1 -RunLint` 執行 Android compile 與 lint。Windows controller 目前以 `python -m py_compile windows-controller/remote_camera_control.py` 做基本驗證；repository 尚無自動化硬體測試。

## 專案結構

- `app/`：Android Camera2 App 與內建 Web UI
- `windows-controller/`：Windows GUI
- `protocol/`：控制協定 JSON Schema
- `tools/`、`docs/`：文件產生工具與開發說明

## 授權與聲明

Source code 依 [MIT License](LICENSE) 開源，第三方元件授權請見 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。本專案定位為非營利研究與學習，主要由 OpenAI Codex 協助生成。不是手機、相機或第三方品牌的官方產品。Repository 不含 credentials、本機 dependency、安裝包、測試照片或 build artifacts。若權利人提出具體侵權項目，將先暫停相關內容並處理。
