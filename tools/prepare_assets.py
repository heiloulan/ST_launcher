#!/usr/bin/env python3
"""Prepare large Android assets without storing them in Git.

The v0.21 APK is used as a pinned bootstrap source for the Android Node,
FFmpeg, Git and SillyTavern bundles. The source-controlled runtime overlay is
applied by NodeService when the app starts.
"""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import sys
import tempfile
import urllib.request
import zipfile


RELEASE_APK_URL = (
    "https://github.com/heiloulan/ST_launcher/releases/download/"
    "beta/ST-Launcher-Android-v0.21-beta.apk"
)
RELEASE_APK_SHA256 = "4d724b0f233b53e11758000d0ea7f20f80ee48cd184191ae8d94e75919b03f91"

ASSET_MEMBERS = (
    "assets/git_bundle.tar",
    "assets/sillytavern.tar",
    "assets/node_bundle/ffmpeg",
    "assets/node_bundle/libc++_shared.so",
    "assets/node_bundle/libcares.so",
    "assets/node_bundle/libcrypto.so.3",
    "assets/node_bundle/libicudata.so.78.2",
    "assets/node_bundle/libicui18n.so.78.2",
    "assets/node_bundle/libicuuc.so.78.2",
    "assets/node_bundle/libnghttp2.so",
    "assets/node_bundle/libnghttp3.so",
    "assets/node_bundle/libsqlite3.so.3.52.0",
    "assets/node_bundle/libssl.so.3",
    "assets/node_bundle/libz.so.1.3.1",
    "assets/node_bundle/node",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_apk(destination: Path) -> None:
    request = urllib.request.Request(
        RELEASE_APK_URL,
        headers={"User-Agent": "STLauncher asset preparation"},
    )
    with urllib.request.urlopen(request) as response, destination.open("wb") as output:
        total = int(response.headers.get("Content-Length", "0"))
        downloaded = 0
        next_report = 10
        while chunk := response.read(1024 * 1024):
            output.write(chunk)
            downloaded += len(chunk)
            if total:
                percent = downloaded * 100 // total
                if percent >= next_report:
                    print(f"Downloaded {percent}% ({downloaded}/{total} bytes)")
                    next_report = percent + 10


def extract_assets(apk_path: Path, assets_directory: Path) -> None:
    with zipfile.ZipFile(apk_path) as archive:
        names = set(archive.namelist())
        missing = [name for name in ASSET_MEMBERS if name not in names]
        if missing:
            raise RuntimeError(f"APK is missing required assets: {', '.join(missing)}")

        for member in ASSET_MEMBERS:
            relative = Path(member).relative_to("assets")
            destination = assets_directory / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            temporary_path: Path | None = None
            try:
                with archive.open(member) as source:
                    with tempfile.NamedTemporaryFile(
                        dir=destination.parent,
                        prefix=f".{destination.name}.",
                        suffix=".part",
                        delete=False,
                    ) as output:
                        temporary_path = Path(output.name)
                        shutil.copyfileobj(source, output, length=1024 * 1024)

                os.replace(temporary_path, destination)
                print(f"Prepared {relative}")
            finally:
                if temporary_path:
                    temporary_path.unlink(missing_ok=True)


def main() -> int:
    repository_root = Path(__file__).resolve().parents[1]
    default_assets = repository_root / "android/app/src/main/assets"

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--apk",
        type=Path,
        help="Use an existing v0.21 APK instead of downloading the pinned release",
    )
    parser.add_argument(
        "--assets-dir",
        type=Path,
        default=default_assets,
        help=f"Asset output directory (default: {default_assets})",
    )
    args = parser.parse_args()

    temporary_apk: Path | None = None
    try:
        if args.apk:
            apk_path = args.apk.expanduser().resolve()
        else:
            handle, name = tempfile.mkstemp(prefix="st-launcher-v021-", suffix=".apk")
            os.close(handle)
            temporary_apk = Path(name)
            apk_path = temporary_apk
            print(f"Downloading pinned asset bundle from {RELEASE_APK_URL}")
            download_apk(apk_path)

        if not apk_path.is_file():
            raise FileNotFoundError(apk_path)

        actual_digest = sha256(apk_path)
        if actual_digest != RELEASE_APK_SHA256:
            raise RuntimeError(
                "APK SHA-256 mismatch: "
                f"expected {RELEASE_APK_SHA256}, received {actual_digest}"
            )

        extract_assets(apk_path, args.assets_dir.resolve())
        print("Android runtime assets are ready.")
        return 0
    except Exception as error:
        print(f"Asset preparation failed: {error}", file=sys.stderr)
        return 1
    finally:
        if temporary_apk:
            temporary_apk.unlink(missing_ok=True)


if __name__ == "__main__":
    raise SystemExit(main())
