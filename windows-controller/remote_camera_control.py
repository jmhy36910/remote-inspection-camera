"""Windows control panel for Machine Vision Camera (control-v1)."""
import json
import base64
import io
import os
from datetime import datetime
import socket
import threading
import math
import time
import tkinter as tk
import cv2
import numpy as np
from PIL import Image, ImageTk
from tkinter import ttk, messagebox


class Controller(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Machine Vision Camera - Windows Controller")
        self.geometry("720x900")
        self.minsize(640, 760)
        self.sock = None
        self.reader = None
        self.write_lock = threading.Lock()
        self.preview_lock = threading.Lock()
        self.latest_preview = None
        self.latest_frame = None
        self.display_scale = 1.0
        self.display_scale_x = 1.0
        self.display_scale_y = 1.0
        self.display_offset = (0, 0)
        self.display_image_position = (0, 0)
        self.roi_rect = None
        self.roi_drag_start = None
        self.roi_move_start = None
        self.roi_move_origin = None
        self.roi_original = None
        self.ball_template = None
        self.ball_is_dark = False
        self.ball_dark_threshold = 0
        self.ball_target_level = 128.0
        self.ball_inner_level = 128.0
        self.ball_edge_contrast = 0.0
        self.ball_center = None
        self.track_velocity = (0.0, 0.0)
        self.track_misses = 0
        self.track_confidence = 0.0
        self.track_prev_gray = None
        self.track_points = None
        self.track_frame_index = 0
        self.reference_center = None
        self.max_displacement_px = 0.0
        self.calibration_points = []
        self.pixels_per_cm = None
        self.pixels_per_mm = None
        self.current_zoom = 1.0
        self.rotation_degrees = 0
        self.display_mode = tk.StringVar(value="FIT")
        self.display_zoom = tk.DoubleVar(value=1.0)
        self.flip_horizontal = tk.BooleanVar(value=False)
        self.flip_vertical = tk.BooleanVar(value=False)
        self._last_flip_horizontal = False
        self._last_flip_vertical = False
        self.tracking_enabled = False
        self.calibration_mode = False
        self.state_poll_job = None
        self.local_edit_until = {}
        self.preview_times = []
        self.preview_update_pending = False
        self.request_no = 0
        self.host = tk.StringVar(value="192.168.194.186")
        self.port = tk.StringVar(value="8765")
        self.status = tk.StringVar(value="Disconnected")
        self.camera = tk.StringVar(value="0")
        self.camera_selection_dirty = False
        self.phone_preview_var = tk.BooleanVar(value=True)
        self.pc_preview_var = tk.BooleanVar(value=True)
        self.yuv_preview_var = tk.BooleanVar(value=False)
        self.photo_config = tk.StringVar(value="Original size")
        self.photo_original_var = tk.BooleanVar(value=True)
        self.raw_mode_var = tk.BooleanVar(value=False)
        self.photo_sizes = ()
        self.values = {"iso": 100, "exposure_us": 1000, "focus": 1.35, "zoom": 1.0, "temperature": 4000}
        self.control_limits = {"zoom_max": 10.0, "focus_max": 10.0}
        self.value_entries = {}
        self.slider_vars = {}
        self.slider_widgets = {}
        self.auto_vars = {}
        self.overall_mode = tk.StringVar(value="Mixed")
        self._build()

    def _build(self):
        top = ttk.Frame(self, padding=10); top.pack(fill="x")
        ttk.Label(top, text="Phone IP").pack(side="left")
        ttk.Entry(top, textvariable=self.host, width=18).pack(side="left", padx=5)
        ttk.Label(top, text="Port").pack(side="left")
        ttk.Entry(top, textvariable=self.port, width=7).pack(side="left", padx=5)
        ttk.Button(top, text="Connect", command=self.connect).pack(side="left", padx=5)
        ttk.Button(top, text="Disconnect", command=self.disconnect).pack(side="left")
        camera_bar = ttk.Frame(self, padding=(10, 0, 10, 4)); camera_bar.pack(fill="x")
        ttk.Label(camera_bar, text="Camera").pack(side="left")
        self.camera_combo = ttk.Combobox(camera_bar, textvariable=self.camera, width=12, state="readonly", values=("0",))
        self.camera_combo.pack(side="left", padx=5)
        self.camera_combo.bind("<<ComboboxSelected>>", self._camera_selection_changed)
        ttk.Button(camera_bar, text="Select camera", command=self.select_camera).pack(side="left")
        ttk.Button(camera_bar, text="Search hidden", command=self.scan_hidden).pack(side="left", padx=5)
        ttk.Label(camera_bar, text="Preview").pack(side="left", padx=(12, 3))
        self.preview_config = tk.StringVar(value="Auto · 30 FPS")
        self.preview_combo = ttk.Combobox(camera_bar, textvariable=self.preview_config, state="readonly", width=20, values=("Auto · 30 FPS",))
        self.preview_combo.pack(side="left")
        ttk.Button(camera_bar, text="Apply", command=self.apply_preview_config).pack(side="left", padx=3)
        ttk.Button(camera_bar, text="Open preview window", command=self.show_preview_window).pack(side="left", padx=5)
        preview_switches = ttk.Frame(self, padding=(10, 0, 10, 4)); preview_switches.pack(fill="x")
        ttk.Checkbutton(preview_switches, text="Phone Preview", variable=self.phone_preview_var, command=lambda: self.set_preview_target("phone")).pack(side="left")
        ttk.Checkbutton(preview_switches, text="PC Preview", variable=self.pc_preview_var, command=lambda: self.set_preview_target("pc")).pack(side="left", padx=12)
        ttk.Checkbutton(preview_switches, text="YUV source", variable=self.yuv_preview_var, command=self.apply_yuv_preview).pack(side="left")
        photo_bar = ttk.Frame(self, padding=(10, 0, 10, 4)); photo_bar.pack(fill="x")
        ttk.Label(photo_bar, text="Photo size").pack(side="left")
        self.photo_combo = ttk.Combobox(photo_bar, textvariable=self.photo_config, state="readonly", width=20, values=("Original size",))
        self.photo_combo.pack(side="left", padx=5)
        ttk.Button(photo_bar, text="Apply", command=self.apply_photo_config).pack(side="left", padx=3)
        ttk.Checkbutton(photo_bar, text="Original size output", variable=self.photo_original_var, command=self.apply_photo_original).pack(side="left", padx=6)
        ttk.Checkbutton(photo_bar, text="Inspection RAW", variable=self.raw_mode_var, command=self.apply_raw_mode).pack(side="left", padx=6)
        rotate_bar = ttk.Frame(self, padding=(10, 0, 10, 4)); rotate_bar.pack(fill="x")
        ttk.Label(rotate_bar, text="PC rotation").pack(side="left")
        self.rotation_var = tk.StringVar(value="0°")
        ttk.Combobox(rotate_bar, textvariable=self.rotation_var, state="readonly", width=12, values=("0°", "90° clockwise", "180°", "270° clockwise")).pack(side="left", padx=5)
        self.rotation_combo = rotate_bar.winfo_children()[-1]
        self.rotation_combo.bind("<<ComboboxSelected>>", self.apply_rotation)
        ttk.Label(self, textvariable=self.status, padding=(10, 0)).pack(anchor="w")

        # Keep the live image in its own native window.  This lets the user
        # move it to another monitor without squeezing the control/ROI pane.
        self.preview_window = tk.Toplevel(self)
        self.preview_window.title("Machine Vision Camera - Live Preview")
        self.preview_window.geometry("760x760")
        self.preview_window.minsize(420, 360)
        self.preview_window.protocol("WM_DELETE_WINDOW", self.hide_preview_window)
        preview_tools = ttk.Frame(self.preview_window, padding=(10, 6, 10, 0))
        preview_tools.pack(fill="x")
        ttk.Label(preview_tools, text="Display").pack(side="left")
        mode_combo = ttk.Combobox(preview_tools, textvariable=self.display_mode, state="readonly", width=12,
                                  values=("STRETCH", "FIT", "MANUAL", "ORIGINAL"))
        mode_combo.pack(side="left", padx=5)
        mode_combo.bind("<<ComboboxSelected>>", lambda _e: self.refresh_preview_transform())
        ttk.Label(preview_tools, text="Zoom").pack(side="left")
        zoom_entry = ttk.Entry(preview_tools, textvariable=self.display_zoom, width=6)
        zoom_entry.pack(side="left", padx=(3, 2))
        zoom_entry.bind("<Return>", lambda _e: self.refresh_preview_transform())
        ttk.Label(preview_tools, text="x").pack(side="left")
        ttk.Checkbutton(preview_tools, text="Flip LR", variable=self.flip_horizontal,
                        command=self.refresh_preview_transform).pack(side="left", padx=(10, 0))
        ttk.Checkbutton(preview_tools, text="Flip UD", variable=self.flip_vertical,
                        command=self.refresh_preview_transform).pack(side="left", padx=5)
        self.preview = tk.Canvas(self.preview_window, background="black", highlightthickness=0)
        self.preview.pack(fill="both", expand=True, padx=10, pady=8)
        self.preview.bind("<ButtonPress-1>", self._preview_press)
        self.preview.bind("<B1-Motion>", self._preview_drag)
        self.preview.bind("<ButtonRelease-1>", self._preview_release)
        self.preview_info = tk.StringVar(value="Preview: -- FPS · --×--")
        ttk.Label(self.preview_window, textvariable=self.preview_info, padding=(10, 0)).pack(anchor="w")
        roi = ttk.LabelFrame(self, text="TCP ball ROI / displacement", padding=6); roi.pack(fill="x", padx=10)
        roi_buttons = ttk.Frame(roi); roi_buttons.pack(fill="x")
        ttk.Button(roi_buttons, text="Clear circle", command=self.clear_roi).pack(side="left")
        ttk.Button(roi_buttons, text="Circle -", command=lambda: self.resize_roi(0.9)).pack(side="left", padx=2)
        ttk.Button(roi_buttons, text="Circle +", command=lambda: self.resize_roi(1.1)).pack(side="left")
        ttk.Button(roi_buttons, text="Set reference", command=self.set_reference).pack(side="left")
        ttk.Button(roi_buttons, text="2-point calibrate", command=self.begin_calibration).pack(side="left", padx=4)
        ttk.Button(roi_buttons, text="Clear data", command=self.clear_tracking).pack(side="left")
        self.track_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(roi_buttons, text="Track", variable=self.track_var, command=self._toggle_tracking).pack(side="right")
        cal = ttk.Frame(roi); cal.pack(fill="x", pady=(5, 0))
        ttk.Label(cal, text="Ball diameter (mm)").pack(side="left")
        self.ball_mm = tk.StringVar(value="10.00")
        ttk.Entry(cal, textvariable=self.ball_mm, width=8).pack(side="left", padx=4)
        ttk.Label(cal, text="Diameter px L/R").pack(side="left", padx=(8, 2))
        self.ball_width_px = tk.StringVar(value="")
        width_entry = ttk.Entry(cal, textvariable=self.ball_width_px, width=7)
        width_entry.pack(side="left")
        width_entry.bind("<Return>", lambda _e: self.apply_circle_pixel_size())
        ttk.Label(cal, text="U/D").pack(side="left", padx=(6, 2))
        self.ball_height_px = tk.StringVar(value="")
        height_entry = ttk.Entry(cal, textvariable=self.ball_height_px, width=7)
        height_entry.pack(side="left")
        height_entry.bind("<Return>", lambda _e: self.apply_circle_pixel_size())
        self.roi_status = tk.StringVar(value="Drag on preview to select ROI")
        ttk.Label(roi, textvariable=self.roi_status).pack(anchor="w")
        controls = ttk.LabelFrame(self, text="Camera controls", padding=8); controls.pack(fill="x", padx=10)
        mode = ttk.Frame(controls); mode.pack(fill="x", pady=(0, 5))
        ttk.Label(mode, text="Overall mode").pack(side="left")
        ttk.Button(mode, text="AUTO", command=lambda: self._set_overall_mode(False)).pack(side="left", padx=5)
        ttk.Button(mode, text="MANUAL", command=lambda: self._set_overall_mode(True)).pack(side="left")
        ttk.Label(mode, textvariable=self.overall_mode).pack(side="left", padx=8)
        self._slider(controls, "ISO", "iso", 50, 3200, 50, "ISO")
        self._slider(controls, "Exposure", "exposure_us", 100, 1000000, 100, "us")
        self._slider(controls, "Focus D", "focus", 0, 10, 0.01, "D")
        self._slider(controls, "Zoom", "zoom", 0.6, 10, 0.01, "x")
        self._slider(controls, "White balance", "temperature", 2000, 8000, 100, "K")
        buttons = ttk.Frame(self, padding=10); buttons.pack(fill="x")
        ttk.Button(buttons, text="Camera ON", command=lambda: self.send("camera_power", enabled=True)).pack(side="left", padx=3)
        ttk.Button(buttons, text="Camera OFF", command=lambda: self.send("camera_power", enabled=False)).pack(side="left", padx=3)
        ttk.Button(buttons, text="Take photo", command=lambda: self.send("capture")).pack(side="right", padx=3)

    def show_preview_window(self):
        if getattr(self, "preview_window", None) is not None:
            self.preview_window.deiconify()
            self.preview_window.lift()

    def hide_preview_window(self):
        if getattr(self, "preview_window", None) is not None:
            self.preview_window.withdraw()

    def _slider(self, parent, label, key, low, high, step, unit):
        row = ttk.Frame(parent); row.pack(fill="x", pady=2)
        ttk.Label(row, text=label, width=15).pack(side="left")
        var = tk.DoubleVar(value=self.values[key])
        self.slider_vars[key] = var
        scale = ttk.Scale(row, from_=low, to=high, variable=var, command=lambda _: self._changed(key, var.get(), unit))
        scale.pack(side="left", fill="x", expand=True)
        self.slider_widgets[key] = scale
        value = ttk.Entry(row, width=12); value.insert(0, str(self.values[key])); value.pack(side="left", padx=5)
        self.value_entries[key] = value
        ttk.Label(row, text=unit, width=4).pack(side="left")
        value.bind("<Return>", lambda _e: self._typed(key, value.get(), var, unit))
        auto_parameter = {"iso": "iso", "exposure_us": "exposure", "focus": "focus", "temperature": "white_balance"}.get(key)
        if auto_parameter:
            auto = tk.BooleanVar(value=True)
            self.auto_vars[auto_parameter] = auto
            ttk.Checkbutton(row, text="Auto", variable=auto, command=lambda p=auto_parameter, v=auto: self._set_auto(p, v.get())).pack(side="left", padx=(4, 0))

    def _set_auto(self, parameter, enabled):
        self.send("set_auto", parameter=parameter, enabled=bool(enabled))
        self.status.set("%s: %s" % (parameter, "Auto" if enabled else "Manual"))

    def _set_overall_mode(self, manual):
        self.send("set_mode", manual=bool(manual))
        for value in self.auto_vars.values():
            value.set(not manual)
        self.overall_mode.set("MANUAL" if manual else "AUTO")
        self.status.set("Overall mode: " + ("MANUAL" if manual else "AUTO"))

    def _changed(self, key, value, unit):
        self.local_edit_until[key] = time.monotonic() + 0.6
        if key == "zoom":
            self._apply_zoom_roi(float(value))
        self.values[key] = value
        auto_parameter = {"iso": "iso", "exposure_us": "exposure", "focus": "focus", "temperature": "white_balance"}.get(key)
        if auto_parameter and auto_parameter in self.auto_vars and self.auto_vars[auto_parameter].get():
            self.auto_vars[auto_parameter].set(False)
        entry = self.value_entries.get(key)
        if entry is not None and self.focus_get() is not entry:
            entry.delete(0, tk.END)
            entry.insert(0, self._format_value(key, value))
        typ = {"iso": "set_iso", "exposure_us": "set_exposure_us", "focus": "set_focus", "zoom": "set_zoom", "temperature": "set_temperature_k"}[key]
        self.send(typ, value=value)

    @staticmethod
    def _format_value(key, value):
        if key == "iso": return str(int(round(value)))
        if key == "temperature": return str(int(round(value)))
        if key == "exposure_us": return str(int(round(value)))
        return "%.2f" % value

    def _typed(self, key, text, var, unit):
        try: value = float(text)
        except ValueError: return
        bounds = {
            "iso": (50.0, 3200.0),
            "exposure_us": (100.0, 1000000.0),
            "focus": (0.0, self.control_limits.get("focus_max", 10.0)),
            "zoom": (0.6, self.control_limits.get("zoom_max", 10.0)),
            "temperature": (2000.0, 8000.0),
        }
        low, high = bounds[key]
        value = max(low, min(high, value))
        self.local_edit_until[key] = time.monotonic() + (1.5 if key in ("focus", "zoom") else 0.8)
        var.set(value); self._changed(key, value, unit)
        self.focus()

    def connect(self):
        try:
            self.sock = socket.create_connection((self.host.get(), int(self.port.get())), timeout=3)
            self.sock.settimeout(3); self.status.set("Connected")
            self.reader = self.sock.makefile("r", encoding="utf-8")
            threading.Thread(target=self._listen, daemon=True).start()
            self.send("get_state")
            self.state_poll_job = self.after(250, self._poll_state)
        except OSError as exc:
            self.status.set("Connection failed")
            messagebox.showerror("Connection", str(exc))

    def _camera_selection_changed(self, _event=None):
        self.camera_selection_dirty = True

    def select_camera(self):
        self.send("set_camera", cameraId=self.camera.get())
        self.camera_selection_dirty = False
        self.status.set("Camera selection requested: " + self.camera.get())

    def disconnect(self):
        if self.sock:
            try: self.sock.close()
            except OSError: pass
        self.sock = None; self.status.set("Disconnected")
        self.reader = None
        if self.state_poll_job:
            self.after_cancel(self.state_poll_job)
            self.state_poll_job = None

    def _poll_state(self):
        self.state_poll_job = None
        if self.sock:
            self.send("get_state")
            self.state_poll_job = self.after(250, self._poll_state)

    def scan_hidden(self):
        self.send("scan_hidden")
        self.after(1500, lambda: self.send("get_state"))

    def apply_preview_config(self):
        text = self.preview_config.get().replace("×", "x")
        if text.startswith("Auto"):
            self.send("set_preview_config", fps=30)
            self.status.set("Adaptive preview @ 30 FPS")
            return
        try:
            size, fps_text = text.split("·")
            width, height = [int(v.strip()) for v in size.strip().split("x")]
            fps = int(fps_text.replace("FPS", "").strip())
            self.send("set_preview_config", width=width, height=height, quality=45, fps=fps)
            self.status.set("Preview config applied: %dx%d @ %d FPS" % (width, height, fps))
        except ValueError:
            self.status.set("Invalid preview config")

    def set_preview_target(self, target):
        enabled = self.phone_preview_var.get() if target == "phone" else self.pc_preview_var.get()
        self.send("set_preview", target=target, enabled=enabled)
        if target == "pc" and not enabled:
            with self.preview_lock:
                self.latest_preview = None
            self.preview.delete("all")
            self.preview_info.set("Preview: PC OFF")

    def apply_yuv_preview(self):
        self.send("set_preview_format", format=("yuv" if self.yuv_preview_var.get() else "jpeg"))

    def apply_photo_original(self):
        self.send("set_photo_original", enabled=self.photo_original_var.get())

    def apply_photo_config(self):
        text = self.photo_config.get().replace("×", "x")
        if text.lower().startswith("original"):
            self.photo_original_var.set(True)
            self.apply_photo_original()
            return
        try:
            width, height = [int(v.strip()) for v in text.split("x")]
            self.photo_original_var.set(False)
            self.send("set_photo_size", width=width, height=height)
        except ValueError:
            self.status.set("Invalid photo size")

    def apply_raw_mode(self):
        self.send("set_raw_mode", enabled=self.raw_mode_var.get())
        self.status.set("Inspection RAW: " + ("ON" if self.raw_mode_var.get() else "OFF"))

    def send(self, command, **kwargs):
        if not self.sock: return
        self.request_no += 1
        payload = {"version": 1, "requestId": str(self.request_no), "type": command, **kwargs}
        try:
            with self.write_lock: self.sock.sendall((json.dumps(payload) + "\n").encode())
            if command == "capture": self.status.set("Capture requested")
        except OSError as exc:
            self.status.set("Connection lost: " + str(exc)); self.disconnect()

    def _listen(self):
        try:
            while self.sock and self.reader:
                event = json.loads(self.reader.readline())
                if event.get("requestId") and event.get("cameraIds") is not None:
                    ids = [str(x) for x in event.get("cameraIds", []) + event.get("hiddenIds", [])]
                    self.after(0, lambda values=tuple(ids): self.camera_combo.configure(values=values))
                    if not self.camera_selection_dirty:
                        self.after(0, lambda current=str(event.get("cameraId", "0")): self.camera.set(current))
                    self.after(0, lambda state=dict(event): self._apply_phone_state(state))
                    self.after(0, lambda z=float(event.get("zoom", self.current_zoom)): self._apply_zoom_roi(z))
                elif event.get("event") == "photo":
                    folder = os.path.join(os.environ.get("USERPROFILE", os.path.expanduser("~")), "Pictures", "Machine Vision Camera App")
                    os.makedirs(folder, exist_ok=True)
                    extension = str(event.get("extension", "dng" if event.get("mime") == "image/x-adobe-dng" else "jpg"))
                    path = os.path.join(folder, "MVC_%s.%s" % (datetime.now().strftime("%Y%m%d_%H%M%S"), extension))
                    with open(path, "wb") as output: output.write(base64.b64decode(event["data"]))
                    self.after(0, lambda p=path: self.status.set("Photo saved: " + p))
                elif event.get("event") == "preview":
                    if not self.pc_preview_var.get():
                        continue
                    with self.preview_lock:
                        self.latest_preview = event["data"]
                        if self.preview_update_pending:
                            continue
                        self.preview_update_pending = True
                    self.after(1, self._drain_preview)
                elif event.get("event") == "preview_h264":
                    continue
                elif False:
                    return
                    try:
                        if self.h264_decoder is None:
                            self.h264_decoder = av.CodecContext.create("h264", "r")
                        decoded = [image.to_ndarray(format="bgr24") for image in self.h264_decoder.decode(av.Packet(base64.b64decode(event["data"])))]
                        if decoded:
                            with self.preview_lock:
                                self.latest_h264_frame = decoded[-1]
                                if self.h264_update_pending:
                                    continue
                                self.h264_update_pending = True
                            self.after(0, self._drain_h264)
                    except Exception as exc:
                        self.after(0, lambda e=str(exc): self.status.set("H.264 decode failed: " + e))
        except (OSError, json.JSONDecodeError, TypeError):
            if self.sock: self.after(0, lambda: self.status.set("Preview/control connection lost"))

    def _apply_phone_state(self, state):
        preview_sizes = state.get("previewSizes") or []
        if preview_sizes:
            choices = tuple("%s · 30 FPS" % str(size).replace("x", "×") for size in preview_sizes)
            try:
                self.preview_combo.configure(values=choices)
                current = "%s×%s · 30 FPS" % (state.get("previewWidth"), state.get("previewHeight"))
                if current in choices and self.preview_config.get() not in choices:
                    self.preview_config.set(current)
            except tk.TclError:
                pass
        # Camera2 focus capability is lens-dependent. Do not leave the PC
        # slider at a hard-coded 10D when the phone reports a larger range.
        try:
            focus_max = float(state.get("focusMax", 10.0))
            focus_max = max(0.01, focus_max)
            self.slider_widgets["focus"].configure(to=focus_max)
            self.control_limits["focus_max"] = focus_max
            zoom_max = max(0.6, float(state.get("zoomMax", 10.0)))
            self.slider_widgets["zoom"].configure(to=zoom_max)
            self.control_limits["zoom_max"] = zoom_max
        except (TypeError, ValueError, tk.TclError, KeyError):
            pass
        values = {
            "iso": state.get("iso"),
            "exposure_us": (float(state["exposureMs"]) * 1000.0) if state.get("exposureMs") is not None else None,
            "focus": state.get("focusDiopter"),
            "zoom": state.get("zoom"),
            "temperature": state.get("temperatureK"),
        }
        for key, value in values.items():
            if value is None or key not in self.slider_vars:
                continue
            if time.monotonic() < self.local_edit_until.get(key, 0.0):
                continue
            try:
                self.values[key] = float(value)
                self.slider_vars[key].set(self.values[key])
                entry = self.value_entries.get(key)
                if entry is not None and self.focus_get() is not entry:
                    entry.delete(0, tk.END)
                    entry.insert(0, self._format_value(key, self.values[key]))
            except (TypeError, ValueError, tk.TclError):
                pass
        auto_states = {
            "iso": state.get("autoIso"),
            "exposure": state.get("autoExposure"),
            "focus": state.get("autoFocus"),
            "white_balance": state.get("autoWhiteBalance"),
        }
        for parameter, enabled in auto_states.items():
            if enabled is not None and parameter in self.auto_vars:
                self.auto_vars[parameter].set(bool(enabled))
        modes = [bool(v) for v in auto_states.values() if v is not None]
        if modes and all(modes):
            self.overall_mode.set("AUTO")
        elif modes and not any(modes):
            self.overall_mode.set("MANUAL")
        else:
            self.overall_mode.set("Mixed")
        self.status.set("Connected · phone settings loaded")
        if state.get("phonePreviewEnabled") is not None:
            self.phone_preview_var.set(bool(state.get("phonePreviewEnabled")))
        if state.get("pcPreviewEnabled") is not None:
            self.pc_preview_var.set(bool(state.get("pcPreviewEnabled")))
        if state.get("yuvPreviewMode") is not None:
            self.yuv_preview_var.set(bool(state.get("yuvPreviewMode")))
        if state.get("rawMode") is not None:
            self.raw_mode_var.set(bool(state.get("rawMode")))
        if state.get("photoOriginal") is not None:
            original = bool(state.get("photoOriginal"))
            self.photo_original_var.set(original)
            if original:
                self.photo_config.set("Original size")
            elif state.get("photoWidth") and state.get("photoHeight"):
                self.photo_config.set("%sx%s" % (state["photoWidth"], state["photoHeight"]))
        sizes = state.get("photoSizes")
        if sizes:
            self.photo_sizes = tuple(str(size) for size in sizes)
            self.after(0, lambda values=("Original size",) + self.photo_sizes: self.photo_combo.configure(values=values))
    def _show_preview(self, encoded):
        try:
            image = ImageTk.PhotoImage(encoded)
            self.preview.delete("all")
            self.preview.create_image(*self.display_image_position, image=image, anchor="nw", tags="preview")
            self.preview.image = image
            self._draw_overlay()
        except tk.TclError as exc:
            self.status.set("Preview decode failed: " + str(exc))

    def _drain_preview(self):
        with self.preview_lock:
            encoded_jpeg = self.latest_preview
            self.latest_preview = None
        if encoded_jpeg:
            raw = base64.b64decode(encoded_jpeg)
            frame = None
            display = None
            try:
                display = Image.open(io.BytesIO(raw)).convert("RGB")
                frame = cv2.cvtColor(np.asarray(display), cv2.COLOR_RGB2BGR)
            except Exception:
                frame = cv2.imdecode(np.frombuffer(raw, dtype=np.uint8), cv2.IMREAD_COLOR)
            if display is not None or frame is not None:
                if display is None:
                    display = Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB), mode="RGB")
                if self.rotation_degrees == 90:
                    frame = cv2.rotate(frame, cv2.ROTATE_90_CLOCKWISE)
                    display = display.transpose(Image.Transpose.ROTATE_270)
                elif self.rotation_degrees == 180:
                    frame = cv2.rotate(frame, cv2.ROTATE_180)
                    display = display.transpose(Image.Transpose.ROTATE_180)
                elif self.rotation_degrees == 270:
                    frame = cv2.rotate(frame, cv2.ROTATE_90_COUNTERCLOCKWISE)
                    display = display.transpose(Image.Transpose.ROTATE_90)
                if self.flip_horizontal.get():
                    frame = cv2.flip(frame, 1)
                    display = display.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
                if self.flip_vertical.get():
                    frame = cv2.flip(frame, 0)
                    display = display.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
                frame_width, frame_height = display.size
                if frame is not None:
                    self.latest_frame = frame
                now = time.monotonic()
                self.preview_times.append(now)
                self.preview_times = [t for t in self.preview_times if now - t <= 1.0]
                self.preview_info.set("Preview: %d FPS · %d×%d · %s" % (
                    len(self.preview_times), frame_width, frame_height,
                    "PC ON" if self.pc_preview_var.get() else "PC OFF"))
                if frame is not None and self.tracking_enabled:
                    self._track_ball(frame)
                self._set_display_transform(frame_width, frame_height)
                target_w = max(1, self.preview.winfo_width())
                target_h = max(1, self.preview.winfo_height())
                mode = self.display_mode.get().upper()
                if mode == "STRETCH":
                    sx, sy = target_w / frame_width, target_h / frame_height
                elif mode == "MANUAL":
                    scale = max(0.05, min(20.0, float(self.display_zoom.get())))
                    sx = sy = scale
                elif mode == "ORIGINAL":
                    sx = sy = 1.0
                else:  # FIT: preserve aspect ratio, no crop, fill one dimension.
                    sx = sy = min(target_w / frame_width, target_h / frame_height)
                new_w = max(1, int(frame_width * sx)); new_h = max(1, int(frame_height * sy))
                display = display.resize((new_w, new_h), Image.Resampling.BILINEAR)
                self.display_scale_x = sx
                self.display_scale_y = sy
                self.display_scale = sx
                self.display_offset = ((target_w - new_w) // 2, (target_h - new_h) // 2)
                self.display_image_position = self.display_offset
                self._show_preview(display)
        with self.preview_lock:
            if self.latest_preview:
                self.after(1, self._drain_preview)
            else:
                self.preview_update_pending = False

    def apply_rotation(self, _event=None):
        labels = {"0°": 0, "90° clockwise": 90, "180°": 180, "270° clockwise": 270}
        selected = labels.get(self.rotation_var.get(), 0)
        if selected == self.rotation_degrees:
            return
        self.rotation_degrees = selected
        self.clear_roi()
        self.calibration_points.clear()
        self.status.set("PC preview rotation: %d° · ROI reset" % selected)

    def _set_display_transform(self, _frame_width, _frame_height):
        """Invalidate the current display mapping before the next frame."""
        self._draw_overlay()

    def refresh_preview_transform(self):
        # Keep ROI/tracking coordinates attached to the same physical image
        # point when the user toggles a mirror direction.
        changed_h = self.flip_horizontal.get() != self._last_flip_horizontal
        changed_v = self.flip_vertical.get() != self._last_flip_vertical
        if (changed_h or changed_v) and self.latest_frame is not None:
            height, width = self.latest_frame.shape[:2]
            if self.roi_original:
                x0, y0, x1, y1 = self.roi_original
                if changed_h: x0, x1 = width - x1, width - x0
                if changed_v: y0, y1 = height - y1, height - y0
                self.roi_original = (x0, y0, x1, y1)
            if self.ball_center:
                x, y = self.ball_center
                if changed_h: x = width - x
                if changed_v: y = height - y
                self.ball_center = (x, y)
            if self.reference_center:
                x, y = self.reference_center
                if changed_h: x = width - x
                if changed_v: y = height - y
                self.reference_center = (x, y)
            if self.ball_template is not None:
                if changed_h: self.ball_template = cv2.flip(self.ball_template, 1)
                if changed_v: self.ball_template = cv2.flip(self.ball_template, 0)
            self._last_flip_horizontal = self.flip_horizontal.get()
            self._last_flip_vertical = self.flip_vertical.get()
        # The next decoded frame recalculates the mapping using the new mode.
        self._draw_overlay()

    def _preview_press(self, event):
        if self.calibration_mode:
            self.calibration_points.append((event.x, event.y))
            if len(self.calibration_points) == 2:
                self.calibration_mode = False
                try:
                    mm = float(self.ball_mm.get())
                    p1, p2 = self.calibration_points
                    px = math.hypot(p2[0] - p1[0], p2[1] - p1[1])
                    if mm > 0 and px > 0:
                        self.pixels_per_mm = px / mm
                        self.pixels_per_cm = self.pixels_per_mm * 10.0
                        self.roi_status.set("Scale %.2f px/mm" % self.pixels_per_mm)
                except ValueError:
                    self.roi_status.set("Invalid calibration distance")
                self._draw_overlay()
            return
        current = self._current_roi_display()
        if current and current[0] <= event.x <= current[2] and current[1] <= event.y <= current[3]:
            self.roi_move_start = (event.x, event.y)
            self.roi_move_origin = current
            self.roi_drag_start = None
        else:
            self.roi_drag_start = (event.x, event.y)
            self.roi_move_start = None
            self.roi_move_origin = None
            self.roi_rect = (event.x, event.y, event.x, event.y)
        self._draw_overlay()

    def _preview_drag(self, event):
        if self.roi_move_start and self.roi_move_origin and not self.calibration_mode:
            dx = event.x - self.roi_move_start[0]
            dy = event.y - self.roi_move_start[1]
            x0, y0, x1, y1 = self.roi_move_origin
            self.roi_rect = (x0 + dx, y0 + dy, x1 + dx, y1 + dy)
        elif self.roi_drag_start and not self.calibration_mode:
            x0, y0 = self.roi_drag_start
            dx, dy = event.x - x0, event.y - y0
            diameter = max(1.0, abs(dx) if abs(dx) >= abs(dy) else abs(dy))
            if abs(dx) >= abs(dy):
                cx = (x0 + event.x) / 2.0
                self.roi_rect = (cx - diameter / 2, y0 - diameter / 2, cx + diameter / 2, y0 + diameter / 2)
            else:
                cy = (y0 + event.y) / 2.0
                self.roi_rect = (x0 - diameter / 2, cy - diameter / 2, x0 + diameter / 2, cy + diameter / 2)
        if self.roi_move_start or self.roi_drag_start:
            self._draw_overlay()

    def _preview_release(self, event):
        if self.latest_frame is None:
            return
        if self.roi_move_start and self.roi_move_origin:
            left, top = self.display_offset
            scale_x = max(self.display_scale_x, 1e-6)
            scale_y = max(self.display_scale_y, 1e-6)
            old_x0, old_y0, old_x1, old_y1 = self.roi_original
            old_cx = (old_x0 + old_x1) / 2.0
            old_cy = (old_y0 + old_y1) / 2.0
            x0, y0, x1, y1 = self.roi_rect
            ax, bx = sorted(((x0 - left) / scale_x, (x1 - left) / scale_x))
            ay, by = sorted(((y0 - top) / scale_y, (y1 - top) / scale_y))
            self.roi_original = (int(ax), int(ay), int(bx), int(by))
            new_cx = (ax + bx) / 2.0
            new_cy = (ay + by) / 2.0
            # Re-anchor all markers to the moved ROI center.
            self.ball_template = None
            self.ball_center = None
            self.reference_center = None
            self.max_displacement_px = 0.0
            self.tracking_enabled = False
            self.track_var.set(False)
            self.roi_move_start = None; self.roi_move_origin = None
            self.roi_rect = self._current_roi_display()
            self._draw_overlay()
            return
        if not self.roi_drag_start:
            return
        self.roi_drag_start = None
        x0, y0, x1, y1 = self.roi_rect
        left, top = self.display_offset
        scale_x = max(self.display_scale_x, 1e-6)
        scale_y = max(self.display_scale_y, 1e-6)
        ax, bx = sorted((max(0, x0 - left) / scale_x, max(0, x1 - left) / scale_x))
        ay, by = sorted((max(0, y0 - top) / scale_y, max(0, y1 - top) / scale_y))
        h, w = self.latest_frame.shape[:2]
        self.roi_original = (int(ax), int(ay), int(bx), int(by))
        rx0, ry0, rx1, ry1 = self.roi_original
        if rx1 > rx0 and ry1 > ry0:
            self.ball_template = None
            self.ball_center = None
            self.reference_center = None
            self.tracking_enabled = False
            self.track_var.set(False)
            try:
                diameter_mm = float(self.ball_mm.get())
                diameter_px = ((rx1 - rx0) + (ry1 - ry0)) / 2.0
                if diameter_mm > 0 and diameter_px > 0:
                    self.pixels_per_mm = diameter_px / diameter_mm
                    self.pixels_per_cm = self.pixels_per_mm * 10.0
            except ValueError:
                self.pixels_per_mm = None
            self.max_displacement_px = 0.0
            self._sync_circle_size_fields(rx1 - rx0, ry1 - ry0)
        scale_text = " · %.2f px/mm" % self.pixels_per_mm if self.pixels_per_mm else ""
        self.roi_status.set("Circle selected: %dx%d px%s · press Track to capture" % (max(0, rx1 - rx0), max(0, ry1 - ry0), scale_text))
        self._draw_overlay()

    def _current_roi_display(self):
        if not self.roi_original:
            return self.roi_rect
        x0, y0, x1, y1 = self.roi_original
        left, top = self.display_offset
        return (left + x0 * self.display_scale_x, top + y0 * self.display_scale_y,
                left + x1 * self.display_scale_x, top + y1 * self.display_scale_y)

    def _sync_circle_size_fields(self, width, height):
        self.ball_width_px.set(str(max(1, int(round(width)))))
        self.ball_height_px.set(str(max(1, int(round(height)))))

    def apply_circle_pixel_size(self):
        if not self.roi_original or self.latest_frame is None:
            return
        try:
            width = max(1, int(float(self.ball_width_px.get())))
            height = max(1, int(float(self.ball_height_px.get())))
        except ValueError:
            self.roi_status.set("Invalid diameter px")
            return
        x0, y0, x1, y1 = self.roi_original
        cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
        self.roi_original = (int(cx - width / 2), int(cy - height / 2),
                             int(cx + width / 2), int(cy + height / 2))
        self.ball_template = None
        self.ball_center = None
        self.reference_center = None
        self.tracking_enabled = False
        self.track_var.set(False)
        self.max_displacement_px = 0.0
        self._draw_overlay()

    def _draw_overlay(self):
        if not hasattr(self, "preview"):
            return
        self.preview.delete("overlay")
        roi_display = self.roi_rect
        if self.roi_original and not self.roi_drag_start and not self.roi_move_start:
            x0, y0, x1, y1 = self.roi_original
            left, top = self.display_offset
            roi_display = (left + x0 * self.display_scale_x, top + y0 * self.display_scale_y,
                           left + x1 * self.display_scale_x, top + y1 * self.display_scale_y)
        if roi_display:
            self.preview.create_oval(*roi_display, outline="#00ff66", width=2, tags="overlay")
        if self.ball_center:
            x, y = self.ball_center
            left, top = self.display_offset
            x = left + x * self.display_scale_x; y = top + y * self.display_scale_y
            self.preview.create_oval(x - 5, y - 5, x + 5, y + 5, outline="#ff3030", width=2, tags="overlay")
        if self.reference_center:
            x, y = self.reference_center
            left, top = self.display_offset
            x = left + x * self.display_scale_x; y = top + y * self.display_scale_y
            self.preview.create_line(x - 10, y, x + 10, y, fill="#ffff00", tags="overlay")
            self.preview.create_line(x, y - 10, x, y + 10, fill="#ffff00", tags="overlay")
        for p in self.calibration_points:
            self.preview.create_oval(p[0] - 4, p[1] - 4, p[0] + 4, p[1] + 4, outline="#00ccff", tags="overlay")

    def clear_roi(self):
        self.roi_rect = None; self.roi_original = None; self.ball_template = None; self.ball_center = None; self.reference_center = None; self.pixels_per_mm = None
        self.roi_status.set("Drag on preview to select ROI")
        self._draw_overlay()

    def clear_tracking(self):
        self.max_displacement_px = 0.0
        self.roi_status.set("Maximum displacement reset")
        self._draw_overlay()

    def _toggle_tracking(self):
        self.tracking_enabled = self.track_var.get()
        if not self.tracking_enabled:
            self.track_prev_gray = None
            self.track_points = None
            self.roi_status.set("Tracking paused")
            self._draw_overlay()
            return
        if self.latest_frame is None or not self.roi_original:
            self.tracking_enabled = False
            self.track_var.set(False)
            self.roi_status.set("Draw a circle before tracking")
            return
        h, w = self.latest_frame.shape[:2]
        x0, y0, x1, y1 = self.roi_original
        x0, x1 = sorted((max(0, x0), min(w, x1)))
        y0, y1 = sorted((max(0, y0), min(h, y1)))
        if x1 <= x0 or y1 <= y0:
            self.tracking_enabled = False
            self.track_var.set(False)
            self.roi_status.set("Invalid circle area")
            return
        self.ball_template = self.latest_frame[y0:y1, x0:x1].copy()
        captured_gray = cv2.cvtColor(self.ball_template, cv2.COLOR_BGR2GRAY)
        template_level = float(np.percentile(captured_gray, 60))
        self.ball_target_level = template_level
        self.ball_is_dark = template_level < 110.0
        self.ball_dark_threshold = int(np.clip(template_level + 32.0, 25.0, 145.0))
        center = ((x0 + x1) / 2.0, (y0 + y1) / 2.0)
        self.ball_inner_level, self.ball_edge_contrast = self._circle_signature(
            cv2.cvtColor(self.latest_frame, cv2.COLOR_BGR2GRAY), center, (min(x1 - x0, y1 - y0) / 2.0)
        )
        self.ball_center = center
        self.reference_center = center
        self.track_velocity = (0.0, 0.0)
        self.track_misses = 0
        self.track_confidence = 1.0
        self.track_prev_gray = cv2.cvtColor(self.latest_frame, cv2.COLOR_BGR2GRAY)
        self.track_points = self._seed_track_points(self.track_prev_gray, center, x1 - x0, y1 - y0)
        self.track_frame_index = 0
        self.max_displacement_px = 0.0
        self.roi_status.set("Tracking armed · feature captured")
        self._draw_overlay()

    def begin_calibration(self):
        self.calibration_points = []; self.calibration_mode = True
        self.roi_status.set("Click two points with the known ball diameter in mm")

    def set_reference(self):
        if self.ball_center:
            self.reference_center = self.ball_center
            self.max_displacement_px = 0.0
            self.roi_status.set("Reference set at (%.1f, %.1f) px" % self.reference_center)
            self._draw_overlay()

    def resize_roi(self, factor):
        if not self.roi_rect or self.latest_frame is None:
            return
        x0, y0, x1, y1 = self.roi_rect
        cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
        hw, hh = abs(x1 - x0) * factor / 2.0, abs(y1 - y0) * factor / 2.0
        self.roi_rect = (cx - hw, cy - hh, cx + hw, cy + hh)
        left, top = self.display_offset
        scale_x = max(self.display_scale_x, 1e-6); scale_y = max(self.display_scale_y, 1e-6)
        self.roi_original = (int((cx - hw - left) / scale_x), int((cy - hh - top) / scale_y), int((cx + hw - left) / scale_x), int((cy + hh - top) / scale_y))
        self._sync_circle_size_fields(abs(self.roi_original[2] - self.roi_original[0]), abs(self.roi_original[3] - self.roi_original[1]))
        self._refresh_template()
        self._draw_overlay()

    def _refresh_template(self):
        if self.latest_frame is None or not self.roi_original:
            return
        h, w = self.latest_frame.shape[:2]
        x0, y0, x1, y1 = self.roi_original
        x0, x1 = sorted((max(0, x0), min(w, x1))); y0, y1 = sorted((max(0, y0), min(h, y1)))
        if x1 > x0 and y1 > y0:
            self.ball_template = self.latest_frame[y0:y1, x0:x1].copy()

    def _apply_zoom_roi(self, zoom):
        if zoom <= 0 or self.current_zoom <= 0:
            return
        if self.roi_rect:
            ratio = zoom / self.current_zoom
            x0, y0, x1, y1 = self.roi_rect
            left, top = self.display_offset
            if self.latest_frame is not None:
                frame_h, frame_w = self.latest_frame.shape[:2]
                image_cx = left + frame_w * self.display_scale_x / 2.0
                image_cy = top + frame_h * self.display_scale_y / 2.0
            else:
                image_cx = self.preview.winfo_width() / 2.0
                image_cy = self.preview.winfo_height() / 2.0
            old_cx, old_cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
            new_cx = image_cx + (old_cx - image_cx) * ratio
            new_cy = image_cy + (old_cy - image_cy) * ratio
            half_w, half_h = abs(x1 - x0) * ratio / 2.0, abs(y1 - y0) * ratio / 2.0
            self.roi_rect = (new_cx - half_w, new_cy - half_h, new_cx + half_w, new_cy + half_h)
            if self.ball_center and self.latest_frame is not None:
                frame_h, frame_w = self.latest_frame.shape[:2]
                self.ball_center = (frame_w / 2.0 + (self.ball_center[0] - frame_w / 2.0) * ratio, frame_h / 2.0 + (self.ball_center[1] - frame_h / 2.0) * ratio)
            if self.reference_center and self.latest_frame is not None:
                frame_h, frame_w = self.latest_frame.shape[:2]
                self.reference_center = (frame_w / 2.0 + (self.reference_center[0] - frame_w / 2.0) * ratio, frame_h / 2.0 + (self.reference_center[1] - frame_h / 2.0) * ratio)
            if self.pixels_per_mm:
                self.pixels_per_mm *= ratio
                self.pixels_per_cm = self.pixels_per_mm * 10.0
            if self.roi_original:
                self._sync_circle_size_fields(abs(self.roi_original[2] - self.roi_original[0]), abs(self.roi_original[3] - self.roi_original[1]))
            scale_x = max(self.display_scale_x, 1e-6); scale_y = max(self.display_scale_y, 1e-6)
            self.roi_original = (int((self.roi_rect[0] - left) / scale_x), int((self.roi_rect[1] - top) / scale_y), int((self.roi_rect[2] - left) / scale_x), int((self.roi_rect[3] - top) / scale_y))
            self._refresh_template()
            self._draw_overlay()
        self.current_zoom = zoom

    def _track_ball(self, frame):
        """Fast, continuous rim-only optical flow; never auto-jump to a new circle."""
        if self.ball_template is None or self.ball_center is None:
            return
        th, tw = self.ball_template.shape[:2]
        radius = max(4.0, (tw + th) * 0.25)
        gray_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        flow_point = self._try_optical_flow(gray_frame, radius)
        self.track_frame_index += 1
        if flow_point is not None:
            self._accept_track_point(flow_point[0], flow_point[1], flow_point[2])
            self._update_displacement()
            self._draw_overlay()
            return
        # A weak frame is held rather than re-acquired from unrelated label
        # circles. This restores stable continuity; center calibration is kept
        # separate from tracking/reacquisition.
        self._track_missed()
        self._draw_overlay()

    def _find_circle_center(self, gray, predicted, expected_radius):
        """Find a circle from its edge arc in a minimum half-frame window."""
        h, w = gray.shape[:2]
        search_x = min(w * 0.5, max(w * 0.25, expected_radius * 4.0))
        search_y = min(h * 0.5, max(h * 0.25, expected_radius * 4.0))
        x0, y0 = max(0, int(predicted[0] - search_x)), max(0, int(predicted[1] - search_y))
        x1, y1 = min(w, int(predicted[0] + search_x)), min(h, int(predicted[1] + search_y))
        crop = gray[y0:y1, x0:x1]
        if crop.shape[0] < 24 or crop.shape[1] < 24:
            return None
        # Hough operates on a compact edge image; the final center is mapped
        # back to the full-resolution preview. 220 px keeps this bounded even
        # when the incoming preview uses a different phone-reported size.
        scale = min(1.0, 220.0 / max(crop.shape[:2]))
        work = cv2.resize(crop, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA) if scale < 1.0 else crop
        blur = cv2.GaussianBlur(work, (3, 3), 0)
        edges = cv2.Canny(blur, 45, 120)
        circles = cv2.HoughCircles(
            blur, cv2.HOUGH_GRADIENT, dp=1.2,
            minDist=max(8, int(expected_radius * scale * 1.1)),
            param1=120, param2=18,
            minRadius=max(3, int(expected_radius * scale * 0.68)),
            maxRadius=max(4, int(expected_radius * scale * 1.35)),
        )
        if circles is None:
            return None
        best = None
        for cx, cy, radius in circles[0]:
            coverage = self._circle_edge_coverage(edges, cx, cy, radius)
            if coverage < 0.28:
                continue
            gx, gy, gr = x0 + float(cx) / scale, y0 + float(cy) / scale, float(radius) / scale
            inner, contrast = self._circle_signature(gray, (gx, gy), gr)
            level_similarity = max(0.0, 1.0 - abs(inner - self.ball_inner_level) / 95.0)
            contrast_similarity = max(0.0, 1.0 - abs(contrast - self.ball_edge_contrast) / 95.0)
            radius_similarity = max(0.0, 1.0 - abs(gr - expected_radius) / max(expected_radius * 0.35, 1.0))
            distance = math.hypot(gx - predicted[0], gy - predicted[1]) / max(math.hypot(search_x, search_y), 1.0)
            score = coverage * 0.42 + level_similarity * 0.20 + contrast_similarity * 0.18 + radius_similarity * 0.16 - distance * 0.04
            if best is None or score > best[0]:
                best = (score, gx, gy)
        if best is None or best[0] < 0.50:
            return None
        return best[1], best[2], min(0.98, best[0])

    @staticmethod
    def _circle_edge_coverage(edges, cx, cy, radius):
        hits = 0
        samples = 40
        h, w = edges.shape[:2]
        for index in range(samples):
            angle = 2.0 * math.pi * index / samples
            x, y = int(round(cx + radius * math.cos(angle))), int(round(cy + radius * math.sin(angle)))
            if 1 <= x < w - 1 and 1 <= y < h - 1 and edges[y - 1:y + 2, x - 1:x + 2].max() > 0:
                hits += 1
        return hits / samples

    @staticmethod
    def _circle_signature(gray, center, radius):
        x, y = int(round(center[0])), int(round(center[1]))
        outer = max(3, int(radius * 1.25))
        x0, y0 = max(0, x - outer), max(0, y - outer)
        x1, y1 = min(gray.shape[1], x + outer + 1), min(gray.shape[0], y + outer + 1)
        patch = gray[y0:y1, x0:x1]
        yy, xx = np.ogrid[y0:y1, x0:x1]
        distance = np.sqrt((xx - x) ** 2 + (yy - y) ** 2)
        inner = patch[distance <= radius * 0.55]
        ring = patch[(distance >= radius * 0.82) & (distance <= radius * 1.22)]
        inner_level = float(np.median(inner)) if inner.size else 0.0
        ring_level = float(np.median(ring)) if ring.size else inner_level
        return inner_level, inner_level - ring_level

    def _seed_track_points(self, gray, center, tw, th):
        """Seed points on the ball rim, not on its textureless interior/background."""
        mask = np.zeros_like(gray, dtype=np.uint8)
        radius = max(4, int(min(tw, th) * 0.46))
        cv2.circle(mask, (int(round(center[0])), int(round(center[1]))), radius, 255, -1)
        cv2.circle(mask, (int(round(center[0])), int(round(center[1]))), max(1, int(radius * 0.56)), 0, -1)
        points = cv2.goodFeaturesToTrack(
            gray, maxCorners=24, qualityLevel=0.01, minDistance=3,
            blockSize=5, mask=mask
        )
        return points.reshape(-1, 2).astype(np.float32) if points is not None else None

    def _try_optical_flow(self, gray, radius):
        """Fast median optical-flow step with a brightness sanity check."""
        previous = self.track_prev_gray
        points = self.track_points
        self.track_prev_gray = gray
        if previous is None or points is None or len(points) < 4:
            self.track_points = self._seed_track_points(gray, self.ball_center, radius * 2, radius * 2)
            return None
        next_points, status, errors = cv2.calcOpticalFlowPyrLK(
            previous, gray, points.reshape(-1, 1, 2), None,
            winSize=(15, 15), maxLevel=2,
            criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 12, 0.03)
        )
        if next_points is None or status is None:
            self.track_points = self._seed_track_points(gray, self.ball_center, radius * 2, radius * 2)
            return None
        good = status.reshape(-1).astype(bool)
        old = points[good]
        new = next_points.reshape(-1, 2)[good]
        if len(new) < 4:
            self.track_points = self._seed_track_points(gray, self.ball_center, radius * 2, radius * 2)
            return None
        delta = new - old
        median_delta = np.median(delta, axis=0)
        residual = np.linalg.norm(delta - median_delta, axis=1)
        inliers = residual <= max(1.5, radius * 0.18)
        if int(inliers.sum()) < 4:
            self.track_points = self._seed_track_points(gray, self.ball_center, radius * 2, radius * 2)
            return None
        median_delta = np.median(delta[inliers], axis=0)
        candidate = (self.ball_center[0] + float(median_delta[0]),
                     self.ball_center[1] + float(median_delta[1]))
        h, w = gray.shape[:2]
        if not (0 <= candidate[0] < w and 0 <= candidate[1] < h):
            self.track_points = self._seed_track_points(gray, self.ball_center, radius * 2, radius * 2)
            return None
        # A fast local luminance check prevents optical flow from following a
        # background edge after the ball leaves the old position.
        cx, cy = int(round(candidate[0])), int(round(candidate[1]))
        rr = max(2, int(radius * 0.45))
        patch = gray[max(0, cy - rr):min(h, cy + rr + 1), max(0, cx - rr):min(w, cx + rr + 1)]
        if patch.size == 0 or abs(float(np.median(patch)) - self.ball_target_level) > 120.0:
            self.track_points = self._seed_track_points(gray, self.ball_center, radius * 2, radius * 2)
            return None
        self.track_points = new[inliers].reshape(-1, 2).astype(np.float32)
        if len(self.track_points) < 8:
            fresh = self._seed_track_points(gray, candidate, radius * 2, radius * 2)
            if fresh is not None:
                self.track_points = fresh
        confidence = min(0.95, 0.62 + float(inliers.sum()) / max(len(new), 1) * 0.33)
        return candidate[0], candidate[1], confidence

    def _accept_track_point(self, x, y, confidence):
        old_x, old_y = self.ball_center
        dx, dy = x - old_x, y - old_y
        # Optical flow with enough inliers already represents the current
        # frame's displacement. Do not smooth it into the middle of the old
        # and new positions; that creates visible tracking lag during a TCP
        # step. Keep smoothing only for uncertain shape/template reacquisition.
        alpha = 1.0 if confidence >= 0.80 else (0.72 if confidence >= 0.45 else 0.55)
        new_x = old_x + dx * alpha
        new_y = old_y + dy * alpha
        self.track_velocity = (dx * 0.55 + self.track_velocity[0] * 0.45,
                               dy * 0.55 + self.track_velocity[1] * 0.45)
        self.ball_center = (new_x, new_y)
        self.track_misses = 0
        self.track_confidence = confidence

    def _track_missed(self):
        self.track_misses = min(self.track_misses + 1, 8)
        self.track_confidence *= 0.75
        # Hold the last stable position briefly. Do not jump to an unrelated object.
        self._draw_overlay()

    def _update_displacement(self):
        if self.reference_center:
            displacement = math.hypot(self.ball_center[0] - self.reference_center[0], self.ball_center[1] - self.reference_center[1])
            self.max_displacement_px = max(self.max_displacement_px, displacement)
            mm = displacement / self.pixels_per_mm if self.pixels_per_mm else None
            fixed = "FIXED" if self.max_displacement_px < 2.0 else "MOVED"
            text = "Center (%.1f, %.1f) px · Now %.2f px" % (self.ball_center[0], self.ball_center[1], displacement)
            text += " / %.3f mm" % mm if mm is not None else " · ball scale not set"
            self.roi_status.set("%s · Max %.2f px · %s" % (text, self.max_displacement_px, fixed))
        self._draw_overlay()


if __name__ == "__main__":
    Controller().mainloop()
