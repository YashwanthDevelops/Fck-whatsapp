#!/usr/bin/env python3
"""Remove non-runtime ELF symbols from Matrix Android FFI libraries in an AAR."""

from __future__ import annotations

import argparse
import os
import subprocess
import tempfile
import zipfile
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("aar", type=Path)
    parser.add_argument("--llvm-strip", required=True, type=Path)
    args = parser.parse_args()
    aar = args.aar.resolve(strict=True)
    strip = args.llvm_strip.resolve(strict=True)
    if aar.suffix.lower() != ".aar" or not strip.is_file():
        parser.error("provide an existing .aar and NDK llvm-strip executable")

    with tempfile.TemporaryDirectory(prefix="friendline-aar-strip-") as temp_dir:
        temp = Path(temp_dir)
        rebuilt = temp / aar.name
        changed = 0
        with zipfile.ZipFile(aar, "r") as source, zipfile.ZipFile(rebuilt, "w") as target:
            for entry in source.infolist():
                payload = source.read(entry.filename)
                if entry.filename.startswith("jni/") and entry.filename.endswith("/libmatrix_sdk_ffi.so"):
                    library = temp / f"abi-{changed}.so"
                    library.write_bytes(payload)
                    before = library.stat().st_size
                    subprocess.run([str(strip), "--strip-all", str(library)], check=True)
                    payload = library.read_bytes()
                    if len(payload) >= before:
                        raise RuntimeError(f"stripping did not reduce {entry.filename}")
                    changed += 1
                target.writestr(entry, payload)
        if changed != 4:
            raise RuntimeError(f"expected four Android ABI libraries, found {changed}")
        os.replace(rebuilt, aar)
    print(f"Stripped four FFI libraries in {aar}")


if __name__ == "__main__":
    main()
