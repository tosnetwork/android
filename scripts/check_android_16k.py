#!/usr/bin/env python3
"""Check every 64-bit APK library's LOAD and RELRO alignment using the NDK."""
import argparse
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path


def check(apk: Path, readelf: Path) -> None:
    checked = 0
    failures = []
    with tempfile.TemporaryDirectory(prefix="tos-elf-") as directory, zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not name.endswith(".so") or not name.startswith(("lib/arm64-v8a/", "lib/x86_64/")):
                continue
            checked += 1
            target = Path(directory, name)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
            headers = subprocess.check_output([str(readelf), "-Wl", str(target)], text=True)
            sections = subprocess.check_output([str(readelf), "-SW", str(target)], text=True)
            writable_sections = []
            for line in sections.splitlines():
                section = re.search(r"\[\s*\d+\]\s+(\S+)\s+\S+\s+([0-9a-fA-F]+)\s+[0-9a-fA-F]+\s+([0-9a-fA-F]+)\s+[0-9a-fA-F]+\s+(\S+)", line)
                if section and "W" in section[4] and "A" in section[4]:
                    writable_sections.append((section[1], int(section[2], 16), int(section[3], 16)))
            loads = 0
            for line in headers.splitlines():
                fields = line.split()
                if fields and fields[0] == "LOAD":
                    loads += 1
                    if int(fields[-1], 16) < 0x4000:
                        failures.append(f"{name}: LOAD alignment {fields[-1]} is below 16KB")
                    if (int(fields[1], 16) - int(fields[2], 16)) % 0x4000:
                        failures.append(f"{name}: LOAD offset and address are not 16KB congruent")
                elif fields and fields[0] == "GNU_RELRO":
                    virtual_address, memory_size = int(fields[2], 16), int(fields[5], 16)
                    end = virtual_address + memory_size
                    if end % 0x4000:
                        rounded_end = (end + 0x3fff) & ~0x3fff
                        # A padded partial last RELRO page is safe when it contains
                        # no following writable section. Current official AARs use
                        # this layout; modulo-only checks would reject them falsely.
                        overlap = [label for label, address, size in writable_sections
                                   if size and address < rounded_end and address + size > end]
                        if overlap:
                            failures.append(f"{name}: rounded RELRO range covers writable sections {', '.join(overlap)}")
                        else:
                            print(f"android-16k: {name}: RELRO end {end:#x}, safe padding to {rounded_end:#x}")
            if not loads:
                failures.append(f"{name}: no ELF LOAD segment")
    if failures:
        raise SystemExit("\n".join(failures))
    if not checked:
        raise SystemExit(f"{apk}: no 64-bit native libraries checked")
    print(f"android-16k: PASS ({apk.name}, {checked} 64-bit libraries)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("readelf", type=Path)
    args = parser.parse_args()
    check(args.apk, args.readelf)
