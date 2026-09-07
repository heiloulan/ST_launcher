import hashlib
import io
import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.verify_apk import require_16k_segments, verify_apk


class ApkVerifierTests(unittest.TestCase):
    def test_valid_arm64_apk_and_runtime_are_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = self.create_apk(Path(directory) / "app.apk")

            result = verify_apk(apk)

            self.assertEqual(result["abis"], ["arm64-v8a"])
            self.assertEqual(result["runtimeId"], "test-runtime")

    def test_extra_abi_and_tampered_bundle_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            extra_abi = self.create_apk(root / "extra.apk", extra_abi=True)
            with self.assertRaises(RuntimeError):
                verify_apk(extra_abi)

            tampered = self.create_apk(root / "tampered.apk", tampered=True)
            with self.assertRaises(RuntimeError):
                verify_apk(tampered)

            invalid_elf = self.create_apk(root / "invalid-elf.apk", invalid_elf=True)
            with self.assertRaises(RuntimeError):
                verify_apk(invalid_elf)

    def create_apk(
        self,
        destination: Path,
        *,
        extra_abi: bool = False,
        tampered: bool = False,
        invalid_elf: bool = False,
    ) -> Path:
        runtime_files = {
            "THIRD_PARTY_NOTICES.txt": b"notices",
            "app/server.js": b"server",
            "app/plugins/st-launcher/index.js": b"plugin",
            "app/public/scripts/extensions/third-party/st-launcher/index.js": b"extension",
        }
        runtime_buffer = io.BytesIO()
        with zipfile.ZipFile(runtime_buffer, "w") as runtime:
            for name, contents in runtime_files.items():
                runtime.writestr(name, contents)
        bundle = runtime_buffer.getvalue()
        node = b"not-elf" if invalid_elf else self.arm64_android_elf()
        manifest = {
            "runtimeId": "test-runtime",
            "bundleSha256": hashlib.sha256(bundle).hexdigest(),
            "nodeSha256": hashlib.sha256(node).hexdigest(),
            "uncompressedBytes": sum(map(len, runtime_files.values())),
            "files": [
                {"path": name, "size": len(contents)} for name, contents in runtime_files.items()
            ],
        }
        if tampered:
            bundle += b"tampered"

        with zipfile.ZipFile(destination, "w") as apk:
            apk.writestr("assets/runtime.bundle", bundle)
            apk.writestr("assets/runtime-manifest.json", json.dumps(manifest))
            apk.writestr("assets/web/launcher-bridge.js", "bridge")
            apk.writestr("lib/arm64-v8a/libstnode.so", node)
            if extra_abi:
                apk.writestr("lib/x86_64/libstnode.so", b"node")
        return destination

    @staticmethod
    def arm64_android_elf() -> bytes:
        payload = bytearray(120)
        payload[:7] = b"\x7fELF\x02\x01\x01"
        struct.pack_into("<HH", payload, 16, 3, 183)
        struct.pack_into("<Q", payload, 32, 64)
        struct.pack_into("<HH", payload, 54, 56, 1)
        struct.pack_into("<IIQQQQQQ", payload, 64, 1, 5, 0, 0, 0, 120, 120, 16384)
        payload.extend(b"/system/bin/linker64\x00")
        return bytes(payload)

    def test_four_kilobyte_library_segments_are_rejected(self):
        payload = bytearray(self.arm64_android_elf())
        struct.pack_into("<Q", payload, 112, 4096)
        with self.assertRaisesRegex(RuntimeError, "16 KB"):
            require_16k_segments(payload, "lib/example.so")


if __name__ == "__main__":
    unittest.main()
