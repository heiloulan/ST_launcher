import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path

from tools.build_runtime import (
    build_bundle,
    create_notices,
    create_sbom,
    package_license,
    prune_runtime,
    safe_extract_tar,
    sha256,
    validate_lock,
    verify_media_binary_independence,
)


class RuntimeBuilderTests(unittest.TestCase):
    def test_source_archive_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "source.tar.gz"
            with tarfile.open(archive, "w:gz") as output:
                value = b"escape"
                member = tarfile.TarInfo("../escape")
                member.size = len(value)
                output.addfile(member, io.BytesIO(value))

            with self.assertRaises(RuntimeError):
                safe_extract_tar(archive, root / "output")

    def test_runtime_bundle_is_deterministic_and_manifest_ready(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stage = root / "stage"
            stage.mkdir()
            (stage / "app").mkdir()
            (stage / "app/server.js").write_text("console.log('ready')", encoding="utf-8")
            (stage / "lib").mkdir()
            (stage / "lib/libexample.so.1").write_bytes(b"\x00\x01\x02")

            first = root / "first.bundle"
            second = root / "second.bundle"
            first_digest, first_files, first_size = build_bundle(stage, first)
            second_digest, second_files, second_size = build_bundle(stage, second)

            self.assertEqual(first_digest, second_digest)
            self.assertEqual(first_files, second_files)
            self.assertEqual(first_size, second_size)
            self.assertEqual(first_digest, sha256(first))
            self.assertEqual(
                [entry["path"] for entry in first_files],
                ["app/server.js", "lib/libexample.so.1"],
            )

    def test_runtime_lock_has_pinned_sources(self):
        root = Path(__file__).resolve().parents[1]
        lock = json.loads((root / "runtime.lock.json").read_text(encoding="utf-8"))
        validate_lock(lock)
        self.assertEqual(lock["formatVersion"], 1)
        self.assertRegex(lock["sillyTavern"]["commit"], r"^[0-9a-f]{40}$")
        self.assertRegex(lock["sillyTavern"]["sha256"], r"^[0-9a-f]{64}$")
        for package in lock["node"]["packages"]:
            self.assertRegex(package["sha256"], r"^[0-9a-f]{64}$")

    def test_notices_and_sbom_include_scoped_and_nested_packages(self):
        with tempfile.TemporaryDirectory() as directory:
            application = Path(directory)
            modules = application / "node_modules"
            packages = {
                modules / "plain/package.json": {
                    "name": "plain",
                    "version": "1.0.0",
                    "license": "MIT",
                },
                modules / "@scope/scoped/package.json": {
                    "name": "@scope/scoped",
                    "version": "2.0.0",
                    "license": "Apache-2.0",
                },
                modules / "plain/node_modules/nested/package.json": {
                    "name": "nested",
                    "version": "3.0.0",
                    "license": "ISC",
                },
            }
            for package_json, payload in packages.items():
                package_json.parent.mkdir(parents=True, exist_ok=True)
                package_json.write_text(json.dumps(payload), encoding="utf-8")

            lock = {
                "sillyTavern": {"version": "1.18.0", "license": "AGPL-3.0-only"},
                "node": {"version": "24.18.0", "license": "MIT", "packages": []},
            }
            notices = create_notices(application, lock)
            sbom = create_sbom(application, lock)

            self.assertIn("@scope/scoped 2.0.0", notices)
            self.assertIn("nested 3.0.0", notices)
            names = {component["name"] for component in sbom["components"]}
            self.assertTrue({"SillyTavern", "Node.js", "plain", "@scope/scoped", "nested"} <= names)

    def test_explicit_license_override_handles_bundled_legacy_package(self):
        with tempfile.TemporaryDirectory() as directory:
            package_json = Path(directory) / "package.json"
            payload = {"name": "legacy", "version": "1.0.0"}
            package_json.write_text(json.dumps(payload), encoding="utf-8")

            self.assertEqual(
                package_license(
                    package_json,
                    payload,
                    {"legacy@1.0.0": "BSD-2-Clause"},
                ),
                "BSD-2-Clause",
            )
            with self.assertRaises(RuntimeError):
                package_license(package_json, payload)

    def test_pruning_removes_development_metadata_but_keeps_license_texts(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            module = source / "node_modules/example"
            module.mkdir(parents=True)
            (module / "index.js").write_text("export {}", encoding="utf-8")
            (module / "index.js.map").write_text("map", encoding="utf-8")
            (module / "README.markdown").write_text("docs", encoding="utf-8")
            (module / "LICENSE.md").write_text("license", encoding="utf-8")
            (source / "node_modules/.package-lock.json").write_text("{}", encoding="utf-8")
            (source / "public").mkdir()
            (source / "public/global.d.ts").write_text("type X = string", encoding="utf-8")

            prune_runtime(source)

            self.assertTrue((module / "index.js").is_file())
            self.assertTrue((module / "LICENSE.md").is_file())
            self.assertFalse((module / "index.js.map").exists())
            self.assertFalse((module / "README.markdown").exists())
            self.assertFalse((source / "node_modules/.package-lock.json").exists())
            self.assertFalse((source / "public/global.d.ts").exists())

    def test_external_ffmpeg_dependencies_block_the_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            package = source / "node_modules/fluent-ffmpeg/package.json"
            package.parent.mkdir(parents=True)
            package.write_text(
                json.dumps({"name": "fluent-ffmpeg", "version": "1.0.0"}),
                encoding="utf-8",
            )

            with self.assertRaises(RuntimeError):
                verify_media_binary_independence(source)


if __name__ == "__main__":
    unittest.main()
