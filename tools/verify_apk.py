#!/usr/bin/env python3
"""Verify ST Launcher release size, ABI and embedded runtime integrity."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import struct
import sys
import zipfile
from pathlib import Path, PurePosixPath

MAXIMUM_APK_BYTES = 250 * 1024 * 1024
REQUIRED_APK_ENTRIES = {
    "assets/runtime.bundle",
    "assets/runtime-manifest.json",
    "assets/web/launcher-bridge.js",
    "lib/arm64-v8a/libstnode.so",
}
REQUIRED_RUNTIME_ENTRIES = {
    "THIRD_PARTY_NOTICES.txt",
    "app/server.js",
    "app/plugins/st-launcher/index.js",
    "app/public/scripts/extensions/third-party/st-launcher/index.js",
}


def require_arm64_android_elf(payload: bytes) -> None:
    if len(payload) < 64 or payload[:4] != b"\x7fELF":
        raise RuntimeError("Packaged Node.js payload is not an ELF binary")
    if payload[4:7] != bytes((2, 1, 1)):
        raise RuntimeError("Packaged Node.js payload is not a little-endian ELF64 binary")
    elf_type, machine = struct.unpack_from("<HH", payload, 16)
    if elf_type != 3 or machine != 183:
        raise RuntimeError("Packaged Node.js payload is not an AArch64 PIE")
    if b"/system/bin/linker64\x00" not in payload:
        raise RuntimeError("Packaged Node.js payload does not use Android linker64")


def require_16k_segments(payload: bytes, name: str) -> None:
    if len(payload) < 64 or payload[:7] != b"\x7fELF\x02\x01\x01":
        raise RuntimeError(f"Invalid ELF64 library: {name}")
    if struct.unpack_from("<H", payload, 18)[0] != 183:
        raise RuntimeError(f"Not an AArch64 library: {name}")
    offset = struct.unpack_from("<Q", payload, 32)[0]
    size, count = struct.unpack_from("<HH", payload, 54)
    if size < 56 or not count or offset + size * count > len(payload):
        raise RuntimeError(f"Invalid ELF program headers: {name}")
    load_segments = 0
    for index in range(count):
        header = offset + index * size
        kind, _, file_offset, address, _, _, _, alignment = struct.unpack_from(
            "<IIQQQQQQ", payload, header
        )
        if kind != 1:  # PT_LOAD
            continue
        load_segments += 1
        if alignment < 16384 or (address - file_offset) % 16384:
            raise RuntimeError(f"Native payload is not compatible with 16 KB pages: {name}")
    if not load_segments:
        raise RuntimeError(f"ELF has no loadable segments: {name}")


def verify_apk(apk_path: Path, maximum_bytes: int = MAXIMUM_APK_BYTES) -> dict:
    if not apk_path.is_file():
        raise RuntimeError(f"APK does not exist: {apk_path}")
    apk_bytes = apk_path.stat().st_size
    if apk_bytes > maximum_bytes:
        raise RuntimeError(
            f"APK is {apk_bytes / 1024 / 1024:.1f} MiB; "
            f"limit is {maximum_bytes / 1024 / 1024:.0f} MiB",
        )

    with zipfile.ZipFile(apk_path) as apk:
        names = set(apk.namelist())
        missing = REQUIRED_APK_ENTRIES - names
        if missing:
            raise RuntimeError(f"APK is missing required entries: {sorted(missing)}")

        native_entries = sorted(
            name for name in names if name.startswith("lib/") and not name.endswith("/")
        )
        native_abis = {PurePosixPath(name).parts[1] for name in native_entries}
        if native_abis != {"arm64-v8a"}:
            raise RuntimeError(f"APK declares unexpected native ABIs: {sorted(native_abis)}")
        if native_entries != ["lib/arm64-v8a/libstnode.so"]:
            raise RuntimeError(f"APK contains unexpected native payloads: {native_entries}")

        manifest = json.loads(apk.read("assets/runtime-manifest.json"))
        bundle_bytes = apk.read("assets/runtime.bundle")
        node_bytes = apk.read("lib/arm64-v8a/libstnode.so")

    bundle_digest = hashlib.sha256(bundle_bytes).hexdigest()
    if bundle_digest != manifest.get("bundleSha256"):
        raise RuntimeError("Embedded runtime bundle does not match its manifest digest")
    require_arm64_android_elf(node_bytes)
    require_16k_segments(node_bytes, "libstnode.so")
    if hashlib.sha256(node_bytes).hexdigest() != manifest.get("nodeSha256"):
        raise RuntimeError("Packaged Node.js payload does not match its manifest digest")

    with zipfile.ZipFile(io.BytesIO(bundle_bytes)) as runtime:
        runtime_names = {
            name.rstrip("/") for name in runtime.namelist() if name and not name.endswith("/")
        }
        missing_runtime = REQUIRED_RUNTIME_ENTRIES - runtime_names
        if missing_runtime:
            raise RuntimeError(
                f"Runtime is missing integration entries: {sorted(missing_runtime)}",
            )
        for name in runtime_names:
            if name.startswith("lib/"):
                require_16k_segments(runtime.read(name), name)
            path = PurePosixPath(name)
            if path.is_absolute() or ".." in path.parts:
                raise RuntimeError(f"Runtime contains an unsafe path: {name}")
            lowered = name.lower()
            if (
                "/.git/" in f"/{lowered}/"
                or "/node_modules/.cache/" in f"/{lowered}/"
                or lowered.endswith(
                    (".map", ".node", "/.package-lock.json", "/package-lock.json"),
                )
                or lowered.endswith("/npm-shrinkwrap.json")
                or "ffmpeg" in lowered
            ):
                raise RuntimeError(f"Runtime contains a forbidden development payload: {name}")

    manifest_names = {entry["path"] for entry in manifest.get("files", [])}
    if manifest_names != runtime_names:
        raise RuntimeError("Runtime manifest file list does not match the embedded bundle")
    uncompressed_bytes = sum(entry["size"] for entry in manifest["files"])
    if uncompressed_bytes != manifest.get("uncompressedBytes"):
        raise RuntimeError("Runtime manifest uncompressed byte count is inconsistent")

    return {
        "apkBytes": apk_bytes,
        "runtimeBundleBytes": len(bundle_bytes),
        "runtimeFiles": len(runtime_names),
        "abis": sorted(native_abis),
        "runtimeId": manifest.get("runtimeId"),
        "native16kPages": True,
        "nodeSha256": manifest.get("nodeSha256"),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--maximum-bytes", type=int, default=MAXIMUM_APK_BYTES)
    arguments = parser.parse_args()
    try:
        result = verify_apk(arguments.apk, arguments.maximum_bytes)
        print(json.dumps(result, indent=2))
        return 0
    except Exception as error:
        print(f"APK verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
