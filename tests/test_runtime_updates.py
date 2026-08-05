import unittest

from tools.check_runtime_updates import (
    TERMUX_RUNTIME_ROOTS,
    dependency_names,
    numeric_version,
    parse_debian_control,
    update_container_node_image,
)


class RuntimeUpdateTests(unittest.TestCase):
    def test_debian_package_metadata_and_dependencies_are_parsed(self):
        records = parse_debian_control(
            """
Package: nodejs-lts
Version: 24.18.0
Architecture: aarch64
Depends: libc++ (>= 29), openssl | openssl-tool
Filename: pool/node.deb
SHA256: abc

Package: libc++
Version: 29
Architecture: aarch64
Description: first line
 continued line
""".strip(),
        )

        self.assertEqual(records[0]["Package"], "nodejs-lts")
        self.assertEqual(dependency_names(records[0]["Depends"]), ["libc++", "openssl"])
        self.assertEqual(records[1]["Description"], "first line continued line")

    def test_numeric_versions_compare_without_lexical_errors(self):
        self.assertGreater(numeric_version("24.10.0"), numeric_version("24.9.9"))

    def test_runtime_roots_include_the_shared_termux_license_package(self):
        self.assertEqual(TERMUX_RUNTIME_ROOTS, ("nodejs-lts", "termux-licenses"))

    def test_container_update_keeps_the_digest_pinned(self):
        updated = update_container_node_image(
            "FROM node:24.18.0-bookworm-slim@sha256:old\n",
            "node:24.19.0-bookworm-slim@sha256:" + "a" * 64,
        )

        self.assertIn("node:24.19.0-bookworm-slim@sha256:", updated)


if __name__ == "__main__":
    unittest.main()
