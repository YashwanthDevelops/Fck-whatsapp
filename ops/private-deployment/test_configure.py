import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from configure import is_deployable_domain


class MatrixDomainValidationTests(unittest.TestCase):
    def test_accepts_well_formed_public_dns_name(self) -> None:
        self.assertTrue(is_deployable_domain("matrix.friendline.app"))

    def test_rejects_malformed_names(self) -> None:
        for domain in (
            "localhost",
            "single-label",
            "matrix.example.com:8448",
            "-bad.example",
            "192.0.2.1",
        ):
            with self.subTest(domain=domain):
                self.assertFalse(is_deployable_domain(domain))

    def test_rejects_reserved_documentation_and_local_suffixes(self) -> None:
        for domain in (
            "replace-with-your-owned-domain.invalid",
            "matrix.example",
            "matrix.test",
            "matrix.local",
            "matrix.localhost",
            "example.com",
            "homeserver.example.org",
        ):
            with self.subTest(domain=domain):
                self.assertFalse(is_deployable_domain(domain))


if __name__ == "__main__":
    unittest.main()
