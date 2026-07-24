import tempfile
from pathlib import Path
import unittest
import zipfile

from tools.prepare_assets import ASSET_MEMBERS, extract_assets, sha256


class PrepareAssetsTests(unittest.TestCase):
    def test_extracts_only_required_runtime_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "fixture.apk"
            assets = root / "output"

            with zipfile.ZipFile(apk, "w") as archive:
                for member in ASSET_MEMBERS:
                    archive.writestr(member, member.encode())
                archive.writestr("assets/loading.html", b"must not overwrite tracked assets")

            extract_assets(apk, assets)

            for member in ASSET_MEMBERS:
                relative = Path(member).relative_to("assets")
                self.assertEqual((assets / relative).read_bytes(), member.encode())
            self.assertFalse((assets / "loading.html").exists())

    def test_sha256_is_stable(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "fixture"
            fixture.write_bytes(b"ST Launcher")
            self.assertEqual(
                sha256(fixture),
                "27496c6b4671d2501e9c4962c1d149e0d623fe209e7d6ba6c0db42e795500dfd",
            )


if __name__ == "__main__":
    unittest.main()
