# 程式結構、編譯打包與省電檢查

更新：2026-09-08。依目前原始碼整理；省電候選尚未實作或量測。

## 程式結構

Android Kotlin 位於 `app/src/main/java/tw/com/upr/remoteinspection/`：

| 檔案 | 職責 |
| --- | --- |
| MainActivity.kt | Compose 介面、參數狀態、遠端命令 |
| CameraControls.kt | 滑桿、輸入框、選單 |
| CameraSessionController.kt | Camera2 工作階段、拍照、測光、預覽編碼 |
| NetworkControlServer.kt | TCP 控制及照片／預覽事件，8765 |
| WebControlServer.kt | HTTP 控制、MJPEG、SSE，8787 |

`app/src/main/assets/index.html` 是網頁端；`windows-controller/remote_camera_control.py` 包含 Tkinter、獨立預覽視窗、解碼及 ROI 追蹤。`Machine Vision Camera Controller.spec` 為 PyInstaller 資料夾打包設定。`tools/make_manual.py` 產生操作 PDF。`dist*`、`build`、`app/build` 為輸出或舊版，不是主要原始碼。

PC 連線為手機目前的 IP:8765；網頁為 `http://手機IP:8787/`。程式不預填固定 IP，兩種控制端皆須以 App 顯示的一次性 PIN 首次配對。ADB 偵錯埠由手機設定提供，不能混用。

## Android 編譯與安裝

從專案根目錄操作。Gradle 8.11.1；`bootstrap-android.ps1` 只下載 Gradle，不安裝 Java 或 Android SDK。

- `local.properties` 的 `sdk.dir` 指向本機另一專案內的 SDK，換電腦必須更新。
- `gradle.properties` 的 Gradle Java 為 `C:\Program Files\Java\jdk-17`；Kotlin toolchain 要求 JDK 21，也需有可用的安裝或 toolchain 解析環境。
- compileSdk/targetSdk 36、minSdk 28；準備 SDK Platform 36 與 Gradle 所需 Build Tools。

```powershell
powershell -ExecutionPolicy Bypass -File .\bootstrap-android.ps1
powershell -ExecutionPolicy Bypass -File .\build-android.ps1
# 交付前加入靜態檢查
powershell -ExecutionPolicy Bypass -File .\build-android.ps1 -RunLint
```

輸出 `app/build/outputs/apk/debug/app-debug.apk`，使用 debug 簽章。尚未配置正式 release 簽章；versionName 仍是 `0.1.0-phase1`，不代表當前功能進度。`-SkipBootstrap` 目前只是相容參數，沒有實際分支。

已啟用 daemon、parallel、cache、最多 8 workers、4 GB heap；一般增量建置不要先 clean。增加核心不代表每一項編譯工作能線性加速。

使用 SDK 的 `platform-tools/adb.exe`；把佔位值替換為當下手機位址：

```powershell
adb connect PHONE_IP:ADB_PORT
adb -s PHONE_IP:ADB_PORT install -r .\app\build\outputs\apk\debug\app-debug.apk
adb -s PHONE_IP:ADB_PORT shell am start -n tw.com.upr.remoteinspection/.MainActivity
```

簽章不同時 `install -r` 會失敗，不要自動解除安裝清資料。

## Windows 原始碼啟動與打包

現有建置使用 Python 3.12、PyInstaller 6.22.2；核心依賴 Pillow、NumPy、opencv-python。目前未鎖定完整依賴版本。

```powershell
py -3.12 -m venv .venv-pc
& .\.venv-pc\Scripts\python.exe -m pip install Pillow numpy opencv-python pyinstaller
& .\.venv-pc\Scripts\python.exe windows-controller/remote_camera_control.py
```

從根目錄打包至新目錄，保留舊版，通常不需 `--clean`：

```powershell
$stamp = Get-Date -Format yyyyMMdd-HHmmss
$distPath = Join-Path $PWD "dist-$stamp"
& .\.venv-pc\Scripts\python.exe -m PyInstaller --distpath $distPath 'Machine Vision Camera Controller.spec'
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed' }
$bundle = Join-Path $distPath 'Machine Vision Camera Controller'
Copy-Item -LiteralPath 'windows-controller/Start-Machine-Vision-Camera-Controller.cmd' -Destination $bundle
Copy-Item -LiteralPath 'Machine-Vision-Camera-使用說明.pdf' -Destination $bundle
Compress-Archive -LiteralPath $bundle -DestinationPath "Machine-Vision-Camera-Controller-$stamp.zip"
```

沿用本機已有依賴的環境時，可把 `.venv-pc/Scripts/python.exe` 換成 `py`。解壓後執行 EXE，必須攜帶 `_internal` 整個資料夾，不需另裝 Python。

CMD 現在優先啟動同目錄 EXE，沒有 EXE 才執行 Python。先前 ZIP 內的舊 CMD 仍依賴 Python；該 ZIP 未重包，請直接開 EXE。

打包完成還需測試啟動、連線／斷線、預覽、照片接收、預覽開關與 AUTO/MANUAL 同步。編譯成功不等於實機或追蹤精度通過。

## PDF

`tools/make_manual.py` 需要 ReportLab 和 `C:/Windows/Fonts/msjh.ttc`。執行會覆寫根目錄的 `Machine-Vision-Camera-使用說明.pdf`。這份 PDF 是操作簡介，開發細節以本文件為準；產生後應逐頁轉圖檢查。

## 已有省電措施

| 現況 | 限制 |
| --- | --- |
| 沒有 TCP 客戶端／MJPEG 觀看者時略過編碼 | TCP 只連控制也可能被當成預覽需求 |
| 測光約每 250 ms、32×24 取樣 | ISO／曝光單項 AUTO 仍需測光，不可直接移除 |
| YUV 緩衝區與 JPEG 輸出串流重用 | YUV 來源仍 JPEG 壓縮傳送，不是 RAW 預覽 |
| 網頁直接用 JPEG bytes | TCP 仍有 Base64 JSON 開銷 |
| 黑屏與最低亮度 | 不是真正關屏；TextureView、感測器及 ISP 仍可能運作 |
| 設定幀率上限與最新幀傳送 | 不保證實際達 30 FPS |

## 保持功能的優化候選

1. **JPEG Bitmap 重用**：目前每次 `getBitmap(width,height)` 分配後 recycle。改為尺寸改變才重建，保持解析度／品質／節奏，減少 GC。需驗證切鏡頭和格式的生命週期。
2. **只壓縮更新幀**：依 TextureView 更新序號避免感測器低幀率時重複壓縮同畫面；保留必要測光及最新幀，無需逐像素比較。
3. **MJPEG 新幀通知**：目前每個觀看者沒新圖時每 10 ms 醒來檢查。改用通知與有界斷線檢查，可減少無效喚醒。
4. **減少不可見 UI 工作**：值不變時略過格式化；黑屏時停止不可見文字更新，但控制狀態與 PC 讀回保持正常，恢復時立即顯示最新值。
5. **每客戶端預覽訂閱**：區分只控制與真的看圖，避免空編碼。需要相容舊 PC 協議及多客戶端，不能直接使用全域預覽開關代替。

上述尚未實作，不承諾耗電比例。降解析度／品質／FPS、停感測器、直接關屏或改曝光設定會改變功能或體驗，不列為本次無功能影響方案。

## 驗證標準

改前後保持鏡頭、曝光、預覽尺寸、幀率、Wi-Fi、亮度、充電狀態及起始溫度一致，比較只連控制、持續預覽、黑屏遠端預覽。記錄 CPU、電池及 thermal 狀態、實收 FPS、延遲、拍照尺寸及控制反應。短期溫度或充電電流不能單獨證明耗電改善；未量測時只能宣稱減少工作量。
