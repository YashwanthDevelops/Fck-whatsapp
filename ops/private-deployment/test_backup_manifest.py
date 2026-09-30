"""Keep locally built service inputs recoverable from encrypted backups."""

from __future__ import annotations

import re
import shlex
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent


class BackupManifestTests(unittest.TestCase):
    def test_archives_every_file_used_by_local_compose_build_contexts(self) -> None:
        script = (ROOT / "backup.sh").read_text(encoding="utf-8")
        match = re.search(r"^host_paths=\(([^\n]*)\)$", script, re.MULTILINE)
        self.assertIsNotNone(match, "backup.sh must declare its host configuration archive inputs")
        assert match is not None
        archived = set(shlex.split(match.group(1)))

        compose = (ROOT / "compose.yaml").read_text(encoding="utf-8")
        contexts = re.findall(r"(?m)^\s+context:\s+\./([^\s]+)\s*$", compose)
        self.assertTrue(contexts, "Compose should declare at least one local build context")
        for context in contexts:
            dockerfile = ROOT / context / "Dockerfile"
            self.assertIn(f"{context}/Dockerfile", archived)
            self.assertTrue(dockerfile.is_file(), f"Missing local Dockerfile: {dockerfile}")

            for source in re.findall(
                r"(?m)^\s*COPY\s+(\S+)\s+\S+\s*$",
                dockerfile.read_text(encoding="utf-8"),
            ):
                self.assertIn(
                    f"{context}/{source}",
                    archived,
                    f"Backup omits local build input {context}/{source}",
                )
                self.assertTrue((ROOT / context / source).is_file())


if __name__ == "__main__":
    unittest.main()
