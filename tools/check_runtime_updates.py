#!/usr/bin/env python3
"""Resolve pinned SillyTavern and Termux Node updates for a CI pull request."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "runtime.lock.json"
CONTAINERFILE = ROOT / "runtime" / "Containerfile"
TERMUX_PACKAGES_URL = (
    "https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz"
)
GITHUB_API = "https://api.github.com"
OCI_INDEX_TYPES = ", ".join(
    (
        "application/vnd.oci.image.index.v1+json",
        "application/vnd.docker.distribution.manifest.list.v2+json",
    ),
)
TERMUX_RUNTIME_ROOTS = ("nodejs-lts", "termux-licenses")


def request(url: str, *, token: str | None = None, method: str = "GET"):
    headers = {"User-Agent": "ST Launcher runtime update checker"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return urllib.request.urlopen(
        urllib.request.Request(url, headers=headers, method=method),
        timeout=90,
    )


def request_json(url: str, *, token: str | None = None):
    with request(url, token=token) as response:
        return json.load(response)


def numeric_version(value: str) -> tuple[int, ...]:
    match = re.search(r"\d+(?:\.\d+)+", value)
    if not match:
        raise RuntimeError(f"Version is not numeric: {value}")
    return tuple(int(part) for part in match.group().split("."))


def parse_debian_control(source: str) -> list[dict[str, str]]:
    records = []
    current: dict[str, str] = {}
    last_key: str | None = None
    for line in source.splitlines() + [""]:
        if not line:
            if current:
                records.append(current)
                current = {}
                last_key = None
            continue
        if line.startswith((" ", "\t")) and last_key:
            current[last_key] += " " + line.strip()
            continue
        key, separator, value = line.partition(":")
        if not separator:
            continue
        last_key = key
        current[key] = value.strip()
    return records


def dependency_names(value: str) -> list[str]:
    names = []
    for group in value.split(","):
        first_alternative = group.split("|", 1)[0]
        match = re.match(r"\s*([a-zA-Z0-9+.-]+)", first_alternative)
        if match:
            names.append(match.group(1))
    return names


def resolve_termux_packages() -> tuple[str, list[dict]]:
    with request(TERMUX_PACKAGES_URL) as response:
        records = parse_debian_control(gzip.decompress(response.read()).decode())
    index = {
        record["Package"]: record
        for record in records
        if record.get("Package")
        and record.get("Architecture") in {"aarch64", "all"}
        and all(key in record for key in ("Version", "Filename", "SHA256"))
    }
    node = index.get("nodejs-lts")
    if node is None:
        raise RuntimeError("Termux repository does not contain nodejs-lts for aarch64")

    ordered_names = []
    seen = set()
    for root_name in TERMUX_RUNTIME_ROOTS:
        pending = [root_name]
        while pending:
            name = pending.pop(0)
            if name in seen:
                continue
            record = index.get(name)
            if record is None:
                raise RuntimeError(f"Termux runtime dependency is missing: {name}")
            seen.add(name)
            ordered_names.append(name)
            pending.extend(dependency_names(record.get("Depends", "")))

    packages = [
        {
            "name": name,
            "version": index[name]["Version"],
            "path": index[name]["Filename"],
            "sha256": index[name]["SHA256"].lower(),
        }
        for name in ordered_names
    ]
    version_match = re.search(r"\d+\.\d+\.\d+", node["Version"])
    if version_match is None:
        raise RuntimeError(f"Unexpected Termux Node version: {node['Version']}")
    return version_match.group(), packages


def github_commit_for_tag(tag: str, token: str | None) -> str:
    encoded_tag = urllib.parse.quote(tag, safe="")
    reference = request_json(
        f"{GITHUB_API}/repos/SillyTavern/SillyTavern/git/ref/tags/{encoded_tag}",
        token=token,
    )
    target = reference["object"]
    while target["type"] == "tag":
        target = request_json(target["url"], token=token)["object"]
    if target["type"] != "commit" or not re.fullmatch(r"[0-9a-f]{40}", target["sha"]):
        raise RuntimeError(f"Unable to resolve SillyTavern release tag {tag}")
    return target["sha"]


def remote_sha256(url: str, token: str | None = None) -> str:
    digest = hashlib.sha256()
    with request(url, token=token) as response:
        for chunk in iter(lambda: response.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def latest_silly_tavern(current_version: str, token: str | None):
    release = request_json(
        f"{GITHUB_API}/repos/SillyTavern/SillyTavern/releases/latest",
        token=token,
    )
    version_match = re.search(r"\d+\.\d+\.\d+", release["tag_name"])
    if version_match is None:
        raise RuntimeError("Latest SillyTavern release does not have a semantic version")
    version = version_match.group()
    if numeric_version(version) <= numeric_version(current_version):
        return None
    commit = github_commit_for_tag(release["tag_name"], token)
    archive_url = f"https://github.com/SillyTavern/SillyTavern/archive/{commit}.tar.gz"
    return {
        "version": version,
        "commit": commit,
        "archiveUrl": archive_url,
        "sha256": remote_sha256(archive_url),
        "license": "AGPL-3.0-only",
    }


def docker_node_image(version: str) -> str:
    token_payload = request_json(
        "https://auth.docker.io/token?"
        + urllib.parse.urlencode(
            {
                "service": "registry.docker.io",
                "scope": "repository:library/node:pull",
            },
        ),
    )
    token = token_payload["token"]
    tag = f"{version}-bookworm-slim"
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": OCI_INDEX_TYPES,
        "User-Agent": "ST Launcher runtime update checker",
    }
    registry_request = urllib.request.Request(
        f"https://registry-1.docker.io/v2/library/node/manifests/{tag}",
        headers=headers,
        method="HEAD",
    )
    with urllib.request.urlopen(registry_request, timeout=90) as response:
        digest = response.headers.get("Docker-Content-Digest")
    if not digest or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        raise RuntimeError(f"Docker did not return an immutable digest for node:{tag}")
    return f"node:{tag}@{digest}"


def update_container_node_image(source: str, image: str) -> str:
    updated, replacements = re.subn(
        r"(?m)^FROM node:[^\s]+$",
        f"FROM {image}",
        source,
        count=1,
    )
    if replacements != 1:
        raise RuntimeError("Unable to update the Node base image in runtime/Containerfile")
    return updated


def resolve_updates(lock: dict, container_source: str, token: str | None):
    updated_lock = json.loads(json.dumps(lock))
    updated_container = container_source
    changes = []

    silly_tavern = latest_silly_tavern(lock["sillyTavern"]["version"], token)
    if silly_tavern:
        updated_lock["sillyTavern"] = silly_tavern
        changes.append(
            f"SillyTavern {lock['sillyTavern']['version']} -> {silly_tavern['version']}",
        )

    node_version, packages = resolve_termux_packages()
    node_is_newer = numeric_version(node_version) > numeric_version(lock["node"]["version"])
    packages_changed = packages != lock["node"]["packages"]
    if node_is_newer or packages_changed:
        updated_lock["node"]["version"] = node_version
        updated_lock["node"]["packages"] = packages
        if node_is_newer:
            node_image = docker_node_image(node_version)
            updated_lock["build"]["baseImages"] = [
                node_image if image.startswith("node:") else image
                for image in updated_lock["build"]["baseImages"]
            ]
            updated_container = update_container_node_image(updated_container, node_image)
            changes.append(f"Node {lock['node']['version']} -> {node_version}")
        else:
            changes.append(f"Termux dependencies refreshed for Node {node_version}")

    if changes:
        updated_lock["runtimeId"] = (
            f"st-{updated_lock['sillyTavern']['version']}-node-{updated_lock['node']['version']}-r1"
        )
    return updated_lock, updated_container, changes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--write",
        action="store_true",
        help="Write resolved pins for a CI-created pull request",
    )
    arguments = parser.parse_args()
    try:
        lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
        container_source = CONTAINERFILE.read_text(encoding="utf-8")
        updated_lock, updated_container, changes = resolve_updates(
            lock,
            container_source,
            os.environ.get("GH_TOKEN"),
        )
        if arguments.write and changes:
            LOCK_PATH.write_text(
                json.dumps(updated_lock, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            CONTAINERFILE.write_text(updated_container, encoding="utf-8")
        print(json.dumps({"changed": bool(changes), "changes": changes}, indent=2))
        return 0
    except Exception as error:
        print(f"Runtime update check failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
