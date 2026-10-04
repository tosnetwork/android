#!/usr/bin/env python3
"""Require explicit frozen APK hashes before locally skipping a repeated build."""
import hashlib
import os
import sys
from pathlib import Path

if len(sys.argv) != 3:
    raise SystemExit("usage: check_prebuilt_apks.py APP_APK TEST_APK")
for path, variable in zip(sys.argv[1:], ("TOS_EMULATOR_APP_SHA256", "TOS_EMULATOR_TEST_SHA256")):
    expected = os.environ.get(variable)
    actual = hashlib.sha256(Path(path).read_bytes()).hexdigest()
    if not expected or expected != actual:
        raise SystemExit(f"prebuilt-apks: missing or mismatched {variable}: {path}")
    print(f"prebuilt-apks: {path}: {actual}")
