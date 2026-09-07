#!/usr/bin/env python3
"""Build the pinned, production-only ST Launcher Android runtime.

The build consumes only sources declared in runtime.lock.json. It never
extracts artifacts from an older ST Launcher APK.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "runtime.lock.json"
CACHE_ROOT = ROOT / ".cache" / "runtime"
ANDROID_ASSETS = ROOT / "runtime" / "android-assets"
GENERATED_ROOT = ROOT / "runtime" / "generated"
JNI_ROOT = GENERATED_ROOT / "jniLibs" / "arm64-v8a"
PLUGIN_SOURCE = ROOT / "runtime" / "plugin-st-launcher"
EXTENSION_SOURCE = ROOT / "runtime" / "frontend-extension"
UPSTREAM_ADAPTERS = ROOT / "runtime" / "upstream"
SYSTEM_ANDROID_LIBRARIES = {
    "libandroid.so",
    "libc.so",
    "libdl.so",
    "liblog.so",
    "libm.so",
}
ROOT_PRUNE_NAMES = {
    ".dockerignore",
    ".gemini",
    ".github",
    ".gitignore",
    ".npmignore",
    ".replit",
    ".vscode",
    "backups",
    "colab",
    "CONTRIBUTING.md",
    "data",
    "docker",
    "Dockerfile",
    "README.md",
    "Remote-Link.cmd",
    "replit.nix",
    "package-lock.json",
    "SECURITY.md",
    "Start.bat",
    "tests",
    "Update-Instructions.txt",
    "UpdateAndStart.bat",
    "UpdateForkAndStart.bat",
}
NESTED_PRUNE_NAMES = {
    ".github",
    "__tests__",
    "docs",
    "example",
    "examples",
    "test",
    "tests",
}


def validate_lock(lock: dict) -> None:
    if lock.get("formatVersion") != 1:
        raise RuntimeError("Unsupported runtime lock format")
    digest_pattern = re.compile(r"^[0-9a-f]{64}$")
    commit = lock["sillyTavern"]["commit"]
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise RuntimeError("SillyTavern commit is not immutable")
    if commit not in lock["sillyTavern"]["archiveUrl"]:
        raise RuntimeError("SillyTavern archive URL does not use the pinned commit")
    if not digest_pattern.fullmatch(lock["sillyTavern"]["sha256"]):
        raise RuntimeError("SillyTavern archive digest is invalid")
    if not str(lock["sillyTavern"].get("license", "")).strip():
        raise RuntimeError("SillyTavern license is missing")
    if not str(lock["node"].get("license", "")).strip():
        raise RuntimeError("Node.js license is missing")
    for extension in lock.get("extensions", []):
        if (
            not re.fullmatch(r"[A-Za-z0-9._-]+", extension["name"])
            or not re.fullmatch(r"[0-9a-f]{40}", extension["commit"])
            or extension["commit"] not in extension["archiveUrl"]
            or not digest_pattern.fullmatch(extension["sha256"])
        ):
            raise RuntimeError("Preinstalled extension is not pinned to an immutable archive")
    expected_runtime_prefix = (
        f"st-{lock['sillyTavern']['version']}-node-{lock['node']['version']}-r"
    )
    if not lock["runtimeId"].startswith(expected_runtime_prefix):
        raise RuntimeError("Runtime ID does not describe the pinned ST and Node versions")

    names = set()
    for package in lock["node"]["packages"]:
        if package["name"] in names:
            raise RuntimeError(f"Duplicate pinned Node package: {package['name']}")
        names.add(package["name"])
        if not digest_pattern.fullmatch(package["sha256"]):
            raise RuntimeError(f"Invalid package digest: {package['name']}")
        if not package["path"].endswith(".deb") or ".." in PurePosixPath(package["path"]).parts:
            raise RuntimeError(f"Unsafe pinned package path: {package['path']}")
    if "nodejs-lts" not in names:
        raise RuntimeError("Pinned Node package set does not include nodejs-lts")
    for identity, license_name in lock["build"].get("npmLicenseOverrides", {}).items():
        if (
            not re.fullmatch(
                r"(?:@[^/\s]+/)?[^@\s]+@[^@\s]+",
                identity,
            )
            or not str(license_name).strip()
        ):
            raise RuntimeError(f"Invalid npm license override: {identity}")

    base_images = lock["build"].get("baseImages", [])
    if not base_images or any(
        not re.fullmatch(r"[^@\s]+@sha256:[0-9a-f]{64}", image) for image in base_images
    ):
        raise RuntimeError("Build container images must use immutable SHA-256 digests")
    container_images = re.findall(
        r"(?m)^FROM\s+([^\s]+)(?:\s+AS\s+\S+)?$",
        (ROOT / lock["build"]["container"]).read_text(encoding="utf-8"),
    )
    if container_images != base_images:
        raise RuntimeError("Containerfile base images do not match runtime.lock.json")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, destination: Path, expected_sha256: str) -> Path:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_file() and sha256(destination) == expected_sha256:
        return destination

    temporary = destination.with_suffix(destination.suffix + ".part")
    temporary.unlink(missing_ok=True)
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "ST Launcher reproducible runtime builder"},
    )
    try:
        with (
            urllib.request.urlopen(request, timeout=60) as response,
            temporary.open("wb") as output,
        ):
            shutil.copyfileobj(response, output, length=1024 * 1024)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"Download failed ({error.code}): {url}") from error
    actual = sha256(temporary)
    if actual != expected_sha256:
        temporary.unlink(missing_ok=True)
        raise RuntimeError(
            f"SHA-256 mismatch for {url}: expected {expected_sha256}, received {actual}",
        )
    os.replace(temporary, destination)
    return destination


def safe_extract_tar(archive: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:gz") as source:
        for member in source.getmembers():
            value = PurePosixPath(member.name)
            if value.is_absolute() or ".." in value.parts or member.issym() or member.islnk():
                raise RuntimeError(f"Unsafe source archive member: {member.name}")
        source.extractall(destination, filter="data")


def run(command: list[str], cwd: Path | None = None) -> None:
    print("+", " ".join(command))
    subprocess.run(command, cwd=cwd, check=True)


def install_production_dependencies(source_root: Path) -> None:
    run(["npm", "ci", "--omit=dev", "--no-audit", "--no-fund"], source_root)
    plugin_target = source_root / "plugins" / "st-launcher"
    if plugin_target.exists():
        shutil.rmtree(plugin_target)
    shutil.copytree(PLUGIN_SOURCE, plugin_target)
    run(
        [
            "npm",
            "ci",
            "--omit=dev",
            "--ignore-scripts",
            "--no-audit",
            "--no-fund",
        ],
        plugin_target,
    )

    extension_target = (
        source_root / "public" / "scripts" / "extensions" / "third-party" / "st-launcher"
    )
    if extension_target.exists():
        shutil.rmtree(extension_target)
    shutil.copytree(EXTENSION_SOURCE, extension_target)


def install_launcher_extensions(source_root: Path, lock: dict) -> None:
    run(
        [
            "patch",
            "--batch",
            "--forward",
            "-p1",
            "-i",
            str(ROOT / "tools/patches/sillytavern-launcher.patch"),
        ],
        source_root,
    )
    shutil.copy2(
        UPSTREAM_ADAPTERS / "launcher-extensions.js", source_root / "src/git/launcher-extensions.js"
    )
    extensions_root = source_root / "public/scripts/extensions/third-party"
    for extension in lock.get("extensions", []):
        archive = download(
            extension["archiveUrl"],
            CACHE_ROOT / f"{extension['name']}-{extension['commit']}.tar.gz",
            extension["sha256"],
        )
        with tempfile.TemporaryDirectory(prefix="st-extension-") as temporary:
            extracted = Path(temporary)
            safe_extract_tar(archive, extracted)
            directories = [entry for entry in extracted.iterdir() if entry.is_dir()]
            if len(directories) != 1:
                raise RuntimeError("Unexpected extension archive layout")
            source = directories[0]
            manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
            if manifest.get("version") != extension["version"]:
                raise RuntimeError(f"Extension version mismatch: {extension['name']}")
            for asset in (manifest.get("js"), manifest.get("css")):
                if asset and (
                    not (source / asset).is_file()
                    or not (source / asset).resolve().is_relative_to(source.resolve())
                ):
                    raise RuntimeError(
                        f"Extension declared asset is missing: {extension['name']}/{asset}"
                    )
            target = extensions_root / extension["name"]
            shutil.copytree(
                source,
                target,
                ignore=shutil.ignore_patterns(
                    ".git", ".github", "node_modules", "src", "@types", "*.map"
                ),
            )
            if extension["name"] == "JS-Slash-Runner":
                localize_helper_assets(source_root, source, target, extension)
            manifest["auto_update"] = False
            (target / "manifest.json").write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )


def localize_helper_assets(application: Path, source: Path, target: Path, extension: dict) -> None:
    """Keep the bundled helper's iframe dependencies available on first offline launch."""
    cdn = "https://testingcf.jsdelivr.net/"
    local_root = "/scripts/extensions/third-party/JS-Slash-Runner/lib"
    replacements = {
        cdn + "npm/jquery/dist/jquery.min.js": "/lib/jquery-3.5.1.min.js",
        cdn + "npm/jquery-ui/dist/jquery-ui.min.js": "/lib/jquery-ui.min.js",
        cdn + "npm/jquery-ui-touch-punch": "/lib/jquery.ui.touch-punch.min.js",
        cdn + "npm/jquery-ui/themes/base/theme.min.css": "/css/jquery-ui.min.css",
        cdn + "npm/@fortawesome/fontawesome-free/css/all.min.css": "/css/fontawesome.min.css",
        cdn + "gh/N0VI028/JS-Slash-Runner/src/iframe/node_modules/log.js": local_root + "/log.js",
    }
    for local in replacements.values():
        if (
            local.startswith(("/lib/", "/css/"))
            and not (application / "public" / local[1:]).is_file()
        ):
            raise RuntimeError(f"Missing SillyTavern browser asset: {local}")
    shutil.copy2(source / "src/iframe/node_modules/log.js", target / "lib/log.js")
    for package in extension["browserPackages"]:
        archive = download(
            package["archiveUrl"],
            CACHE_ROOT / f"{package['name']}-{package['version']}.tar.gz",
            package["sha256"],
        )
        with tempfile.TemporaryDirectory(prefix="helper-browser-") as temporary:
            extracted = Path(temporary)
            safe_extract_tar(archive, extracted)
            root = extracted / "package"
            vendor = target / "lib/vendor" / package["name"]
            vendor.mkdir(parents=True, exist_ok=True)
            asset = Path(package["asset"]).name
            shutil.copy2(root / package["asset"], vendor / asset)
            shutil.copy2(root / "LICENSE", vendor / "LICENSE")
            replacements[cdn + f"npm/{package['name']}/dist/{asset.replace('.js', '.min.js')}"] = (
                f"{local_root}/vendor/{package['name']}/{asset}"
            )
    bundle = target / "dist/index.js"
    contents = bundle.read_text(encoding="utf-8")
    for remote, local in replacements.items():
        if remote not in contents:
            raise RuntimeError(f"Pinned helper dependency changed: {remote}")
        contents = contents.replace(remote, local)
    bundle.write_text(contents, encoding="utf-8")


def prune_runtime(source_root: Path) -> None:
    for name in ROOT_PRUNE_NAMES:
        target = source_root / name
        if target.is_dir():
            shutil.rmtree(target)
        else:
            target.unlink(missing_ok=True)

    node_module_roots = sorted(path for path in source_root.rglob("node_modules") if path.is_dir())
    for node_modules in node_module_roots:
        for directory, directories, files in os.walk(node_modules, topdown=True):
            current = Path(directory)
            is_package_container = current.name == "node_modules" or (
                current.parent.name == "node_modules" and current.name.startswith("@")
            )
            removable = (
                []
                if is_package_container
                else [name for name in directories if name in NESTED_PRUNE_NAMES]
            )
            for name in removable:
                shutil.rmtree(current / name)
                directories.remove(name)
            for name in files:
                is_license = name.lower().startswith(("license", "copying", "notice"))
                if name in {".package-lock.json", "package-lock.json", "npm-shrinkwrap.json"} or (
                    name.endswith((".md", ".markdown", ".map", ".ts"))
                    and not name.endswith(".json")
                    and not is_license
                ):
                    (current / name).unlink(missing_ok=True)

    for path in source_root.rglob("*"):
        if not path.is_file():
            continue
        if path.name in {".package-lock.json", "package-lock.json", "npm-shrinkwrap.json"} or (
            path.name.endswith((".map", ".ts"))
        ):
            path.unlink()

    native_modules = list(source_root.rglob("*.node"))
    if native_modules:
        names = ", ".join(str(path.relative_to(source_root)) for path in native_modules[:10])
        raise RuntimeError(f"Native Node modules are not allowed in the runtime: {names}")


def verify_media_binary_independence(source_root: Path) -> None:
    forbidden_packages = []
    for package_json in source_root.rglob("package.json"):
        try:
            package = json.loads(package_json.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        name = str(package.get("name", "")).lower()
        if "ffmpeg" in name or "ffprobe" in name:
            forbidden_packages.append(name)
    if forbidden_packages:
        raise RuntimeError(
            "Runtime has an external FFmpeg package dependency: "
            + ", ".join(sorted(set(forbidden_packages))),
        )

    invocation = re.compile(
        r"(?is)(?:child_process|execFile|spawn|exec)\s*[\s\S]{0,200}\b(?:ffmpeg|ffprobe)\b"
    )
    for source in source_root.rglob("*.js"):
        if "/node_modules/" in f"/{source.relative_to(source_root).as_posix()}":
            continue
        text = source.read_text(encoding="utf-8", errors="ignore")
        if invocation.search(text):
            raise RuntimeError(f"Runtime source invokes an external media binary: {source}")


def extract_deb_packages(
    lock: dict,
    destination: Path,
) -> tuple[Path, dict[str, Path], dict[str, list[Path]]]:
    base_url = lock["node"]["packagesBaseUrl"]
    node_binary: Path | None = None
    library_index: dict[str, Path] = {}
    package_licenses: dict[str, list[Path]] = {}
    extracted_packages: list[tuple[dict, Path, Path]] = []

    for package in lock["node"]["packages"]:
        safe_name = package["name"].replace("+", "plus")
        archive = download(
            base_url + package["path"],
            CACHE_ROOT / f"{safe_name}-{package['version']}-aarch64.deb",
            package["sha256"],
        )
        package_root = destination / safe_name
        package_root.mkdir(parents=True, exist_ok=True)
        run(["dpkg-deb", "-x", str(archive), str(package_root)])
        prefix = package_root / "data" / "data" / "com.termux" / "files" / "usr"
        candidate_node = prefix / "bin" / "node"
        if candidate_node.is_file():
            node_binary = candidate_node
        library_root = prefix / "lib"
        if library_root.is_dir():
            for candidate in library_root.iterdir():
                if candidate.is_file() or candidate.is_symlink():
                    library_index.setdefault(candidate.name, candidate)
        extracted_packages.append((package, package_root, prefix))

    shared_licenses: dict[str, Path] = {}
    for _, package_root, prefix in extracted_packages:
        shared_root = prefix / "share" / "LICENSES"
        if not shared_root.is_dir():
            continue
        for candidate in sorted(shared_root.rglob("*")):
            if not candidate.is_file() or candidate.is_symlink():
                continue
            resolved = candidate.resolve()
            try:
                resolved.relative_to(package_root.resolve())
            except ValueError as error:
                raise RuntimeError(
                    f"Termux license escapes its package root: {candidate}",
                ) from error
            existing = shared_licenses.get(candidate.name)
            if existing is not None and sha256(existing) != sha256(resolved):
                raise RuntimeError(f"Conflicting Termux license file: {candidate.name}")
            shared_licenses[candidate.name] = resolved

    for package, package_root, prefix in extracted_packages:
        document_root = prefix / "share" / "doc" / package["name"]
        license_files: list[Path] = []
        if document_root.is_dir():
            for candidate in sorted(document_root.rglob("*")):
                if not candidate.name.lower().startswith(
                    ("license", "copying", "copyright", "notice"),
                ):
                    continue
                if candidate.is_symlink():
                    target = shared_licenses.get(candidate.resolve(strict=False).name)
                    if target is not None:
                        license_files.append(target)
                    continue
                if candidate.is_file():
                    resolved = candidate.resolve()
                    try:
                        resolved.relative_to(package_root.resolve())
                    except ValueError as error:
                        raise RuntimeError(
                            f"Termux license escapes its package root: {candidate}",
                        ) from error
                    license_files.append(resolved)
        if package["name"] == "termux-licenses":
            license_files.extend(shared_licenses.values())
        license_files = list(dict.fromkeys(license_files))
        if not license_files:
            raise RuntimeError(
                f"Pinned Termux package has no packaged license notice: {package['name']}",
            )
        package_licenses[package["name"]] = license_files

    if node_binary is None:
        raise RuntimeError("Pinned Node package did not contain usr/bin/node")
    return node_binary, library_index, package_licenses


def needed_libraries(binary: Path) -> set[str]:
    output = subprocess.check_output(["readelf", "-d", str(binary)], text=True)
    result = set()
    for line in output.splitlines():
        if "(NEEDED)" not in line:
            continue
        start = line.find("[")
        end = line.find("]", start)
        if start >= 0 and end > start:
            result.add(line[start + 1 : end])
    return result


def assemble_node_runtime(lock: dict, package_root: Path, stage_lib: Path) -> Path:
    node_binary, library_index, package_licenses = extract_deb_packages(lock, package_root)
    stage_lib.mkdir(parents=True, exist_ok=True)
    pending = list(needed_libraries(node_binary) - SYSTEM_ANDROID_LIBRARIES)
    copied: set[str] = set()
    while pending:
        name = pending.pop()
        if name in copied or name in SYSTEM_ANDROID_LIBRARIES:
            continue
        source = library_index.get(name)
        if source is None:
            raise RuntimeError(f"Node dependency is not pinned or missing: {name}")
        resolved = source.resolve()
        destination = stage_lib / name
        shutil.copy2(resolved, destination)
        copied.add(name)
        pending.extend(needed_libraries(destination) - copied - SYSTEM_ANDROID_LIBRARIES)

    license_root = stage_lib.parent / "licenses" / "termux"
    for package_name, license_files in sorted(package_licenses.items()):
        destination_root = license_root / package_name
        destination_root.mkdir(parents=True, exist_ok=True)
        for index, source in enumerate(license_files):
            suffix = "" if index == 0 else f"-{index + 1}"
            shutil.copy2(source, destination_root / f"{source.name}{suffix}")

    JNI_ROOT.mkdir(parents=True, exist_ok=True)
    node_output = JNI_ROOT / "libstnode.so"
    temporary = node_output.with_suffix(".so.part")
    shutil.copy2(node_binary, temporary)
    temporary.chmod(temporary.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP)
    os.replace(temporary, node_output)
    return node_output


def copy_application(source_root: Path, stage_root: Path) -> Path:
    application = stage_root / "app"
    shutil.copytree(source_root, application, symlinks=False)
    return application


def iter_runtime_files(stage_root: Path):
    for path in sorted(stage_root.rglob("*")):
        if path.is_file():
            yield path, path.relative_to(stage_root).as_posix()


def build_bundle(stage_root: Path, output: Path) -> tuple[str, list[dict], int]:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(".bundle.part")
    temporary.unlink(missing_ok=True)
    files = []
    uncompressed_bytes = 0
    with zipfile.ZipFile(
        temporary,
        "w",
        compression=zipfile.ZIP_DEFLATED,
        compresslevel=6,
        allowZip64=True,
    ) as archive:
        for path, relative in iter_runtime_files(stage_root):
            size = path.stat().st_size
            info = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o600 & 0xFFFF) << 16
            with path.open("rb") as source, archive.open(info, "w", force_zip64=True) as target:
                shutil.copyfileobj(source, target, length=1024 * 1024)
            files.append({"path": relative, "size": size, "sha256": sha256(path)})
            uncompressed_bytes += size
    os.replace(temporary, output)
    return sha256(output), files, uncompressed_bytes


def iter_npm_packages(application_root: Path):
    """Yield each installed production package once, including scoped packages."""
    node_modules = application_root / "node_modules"
    seen: set[tuple[str, str]] = set()
    for package_json in sorted(node_modules.rglob("package.json")):
        parent = package_json.parent
        is_package_root = parent.parent.name == "node_modules" or (
            parent.parent.name.startswith("@") and parent.parent.parent.name == "node_modules"
        )
        if not is_package_root:
            continue
        try:
            payload = json.loads(package_json.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        identity = (
            str(payload.get("name", parent.name)),
            str(payload.get("version", "unknown")),
        )
        if identity in seen:
            continue
        seen.add(identity)
        yield package_json, payload


def package_license(
    package_json: Path,
    payload: dict,
    overrides: dict[str, str] | None = None,
) -> str:
    value = payload.get("license")
    if isinstance(value, str) and value.strip() and value.upper() != "UNLICENSED":
        return value.strip()
    if value:
        return json.dumps(value, ensure_ascii=False, sort_keys=True)
    license_files = sorted(package_json.parent.glob("LICEN[CS]E*"))
    if license_files:
        return f"SEE {license_files[0].name}"
    identity = f"{payload.get('name', package_json.parent.name)}@{payload.get('version', '')}"
    if identity in (overrides or {}):
        return (overrides or {})[identity]
    raise RuntimeError(
        f"Production package has no license metadata or license file: {package_json.parent}",
    )


def create_notices(application_root: Path, lock: dict) -> str:
    packages = []
    overrides = lock.get("build", {}).get("npmLicenseOverrides", {})
    for package_json, payload in iter_npm_packages(application_root):
        license_value = package_license(package_json, payload, overrides)
        packages.append(
            (
                payload.get("name", package_json.parent.name),
                payload.get("version", "unknown"),
                license_value,
            ),
        )
    lines = [
        "ST Launcher third-party components",
        "",
        (f"SillyTavern {lock['sillyTavern']['version']} — {lock['sillyTavern']['license']}"),
        f"Node.js {lock['node']['version']} — {lock['node']['license']}",
        "",
        "Termux aarch64 packages (license texts are under licenses/termux/):",
    ]
    lines.extend(
        f"- {package['name']} {package['version']}" for package in lock["node"]["packages"]
    )
    lines.extend(["", "Preinstalled extensions (license texts included with each extension):"])
    lines.extend(
        f"- {item['name']} {item['version']} — {item['license']} — {item['repository']}"
        for item in lock.get("extensions", [])
    )
    lines.extend(
        f"- {package['name']} {package['version']} — {package['license']} (offline browser asset)"
        for extension in lock.get("extensions", [])
        for package in extension.get("browserPackages", [])
    )
    lines.extend(
        [
            "",
            "Production npm packages:",
        ],
    )
    lines.extend(
        f"- {name} {version} — {license_name}" for name, version, license_name in sorted(packages)
    )
    return "\n".join(lines) + "\n"


def create_sbom(application_root: Path, lock: dict) -> dict:
    overrides = lock.get("build", {}).get("npmLicenseOverrides", {})
    components = [
        {
            "type": "application",
            "name": "SillyTavern",
            "version": lock["sillyTavern"]["version"],
            "licenses": [{"license": {"id": lock["sillyTavern"]["license"]}}],
        },
        {
            "type": "framework",
            "name": "Node.js",
            "version": lock["node"]["version"],
            "licenses": [{"license": {"id": lock["node"]["license"]}}],
        },
    ]
    components.extend(
        {
            "type": "library",
            "name": f"termux:{package['name']}",
            "version": package["version"],
            "licenses": [
                {"license": {"name": "See packaged Termux copyright notice"}},
            ],
        }
        for package in lock["node"]["packages"]
    )
    components.extend(
        {
            "type": "library",
            "name": item["name"],
            "version": item["version"],
            "licenses": [{"license": {"name": item["license"]}}],
        }
        for item in lock.get("extensions", [])
    )
    components.extend(
        {
            "type": "library",
            "name": package["name"],
            "version": package["version"],
            "licenses": [{"license": {"id": package["license"]}}],
        }
        for extension in lock.get("extensions", [])
        for package in extension.get("browserPackages", [])
    )
    for package_json, package in iter_npm_packages(application_root):
        license_name = package_license(package_json, package, overrides)
        components.append(
            {
                "type": "library",
                "name": package.get("name", package_json.parent.name),
                "version": package.get("version", "unknown"),
                "licenses": [{"license": {"name": license_name}}],
            },
        )
    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "version": 1,
        "components": components,
    }


def verify_generated(lock: dict) -> None:
    manifest_path = ANDROID_ASSETS / "runtime-manifest.json"
    bundle_path = ANDROID_ASSETS / "runtime.bundle"
    node_path = JNI_ROOT / "libstnode.so"
    notices_path = ANDROID_ASSETS / "THIRD_PARTY_NOTICES.txt"
    sbom_path = GENERATED_ROOT / "sbom.cdx.json"
    for required in (manifest_path, bundle_path, node_path, notices_path, sbom_path):
        if not required.is_file():
            raise RuntimeError(f"Generated runtime file is missing: {required}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest["runtimeId"] != lock["runtimeId"]:
        raise RuntimeError("Generated runtime ID does not match runtime.lock.json")
    if sha256(bundle_path) != manifest["bundleSha256"]:
        raise RuntimeError("Generated runtime bundle digest is invalid")
    if sha256(node_path) != manifest.get("nodeSha256"):
        raise RuntimeError("Generated Node.js payload digest is invalid")
    if bundle_path.stat().st_size > lock["build"]["maximumBundleBytes"]:
        raise RuntimeError("Generated runtime bundle exceeds its size budget")
    if sum(file["size"] for file in manifest["files"]) != manifest["uncompressedBytes"]:
        raise RuntimeError("Generated runtime manifest has an inconsistent size total")
    expected_paths = {
        "app/server.js",
        "app/plugins/st-launcher/index.js",
        "app/public/scripts/extensions/third-party/st-launcher/index.js",
    }
    manifest_paths = {file["path"] for file in manifest["files"]}
    if not expected_paths <= manifest_paths:
        raise RuntimeError("Generated runtime is missing Launcher integration files")
    if not any(path.startswith("licenses/termux/") for path in manifest_paths):
        raise RuntimeError("Generated runtime is missing Termux license texts")
    sbom = json.loads(sbom_path.read_text(encoding="utf-8"))
    if sbom.get("bomFormat") != "CycloneDX" or not sbom.get("components"):
        raise RuntimeError("Generated CycloneDX SBOM is invalid")
    notices = notices_path.read_text(encoding="utf-8")
    if "SillyTavern" not in notices or "Node.js" not in notices:
        raise RuntimeError("Generated third-party notices are incomplete")
    print(
        f"Verified {manifest['runtimeId']}: "
        f"{bundle_path.stat().st_size / 1024 / 1024:.1f} MiB bundle",
    )


def build(lock: dict) -> None:
    CACHE_ROOT.mkdir(parents=True, exist_ok=True)
    source_archive = download(
        lock["sillyTavern"]["archiveUrl"],
        CACHE_ROOT / f"sillytavern-{lock['sillyTavern']['commit']}.tar.gz",
        lock["sillyTavern"]["sha256"],
    )

    with tempfile.TemporaryDirectory(prefix="st-launcher-runtime-") as temporary_name:
        work = Path(temporary_name)
        source_extract = work / "source"
        safe_extract_tar(source_archive, source_extract)
        source_directories = [path for path in source_extract.iterdir() if path.is_dir()]
        if len(source_directories) != 1:
            raise RuntimeError("Unexpected SillyTavern source archive layout")
        source_root = source_directories[0]
        package = json.loads((source_root / "package.json").read_text(encoding="utf-8"))
        if package.get("version") != lock["sillyTavern"]["version"]:
            raise RuntimeError("SillyTavern package version does not match the lock")

        install_production_dependencies(source_root)
        install_launcher_extensions(source_root, lock)
        prune_runtime(source_root)
        verify_media_binary_independence(source_root)

        stage_root = work / "runtime"
        application = copy_application(source_root, stage_root)
        node_output = assemble_node_runtime(lock, work / "packages", stage_root / "lib")

        notices = create_notices(application, lock)
        (stage_root / "THIRD_PARTY_NOTICES.txt").write_text(notices, encoding="utf-8")
        bundle_path = ANDROID_ASSETS / "runtime.bundle"
        bundle_sha, files, uncompressed_bytes = build_bundle(stage_root, bundle_path)
        manifest = {
            "formatVersion": 1,
            "runtimeId": lock["runtimeId"],
            "sillyTavernVersion": lock["sillyTavern"]["version"],
            "sillyTavernCommit": lock["sillyTavern"]["commit"],
            "nodeVersion": lock["node"]["version"],
            "nodeSha256": sha256(node_output),
            "bundleSha256": bundle_sha,
            "uncompressedBytes": uncompressed_bytes,
            "files": files,
        }
        manifest_path = ANDROID_ASSETS / "runtime-manifest.json"
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        (ANDROID_ASSETS / "THIRD_PARTY_NOTICES.txt").write_text(notices, encoding="utf-8")
        GENERATED_ROOT.mkdir(parents=True, exist_ok=True)
        (GENERATED_ROOT / "sbom.cdx.json").write_text(
            json.dumps(create_sbom(application, lock), indent=2) + "\n",
            encoding="utf-8",
        )

    verify_generated(lock)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--verify-only",
        action="store_true",
        help="Verify generated assets without downloading or rebuilding them",
    )
    args = parser.parse_args()
    try:
        lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
        validate_lock(lock)
        if args.verify_only:
            verify_generated(lock)
        else:
            build(lock)
        return 0
    except Exception as error:
        print(f"Runtime build failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
