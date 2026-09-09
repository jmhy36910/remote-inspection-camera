# -*- mode: python ; coding: utf-8 -*-

import cv2
import os

cv2_dir = os.path.dirname(cv2.__file__)

a = Analysis(
    ['windows-controller/remote_camera_control.py'],
    pathex=[],
    binaries=[],
    # OpenCV's bootstrap loader imports these at runtime. Explicitly bundle
    # them so a clean PyInstaller build remains portable.
    datas=[
        (os.path.join(cv2_dir, 'config.py'), 'cv2'),
        (os.path.join(cv2_dir, 'config-3.py'), 'cv2'),
    ],
    hiddenimports=[],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='Machine Vision Camera Controller',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name='Machine Vision Camera Controller',
)
