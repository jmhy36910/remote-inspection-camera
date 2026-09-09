# Windows controller

```powershell
py .\remote_camera_control.py
```

OpenCV is used for the low-latency portrait preview. The APK keeps the selected full-resolution JPEG for capture and sends it as an asynchronous `photo` event; the controller stores it under `%USERPROFILE%\Pictures\Machine Vision Camera App`.
