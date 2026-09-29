import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from configure import caddy_proxy_ip_range, is_deployable_domain


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


class PushProxyNetworkTests(unittest.TestCase):
    def test_allows_only_the_caddy_host_address(self) -> None:
        self.assertEqual(
            caddy_proxy_ip_range("172.30.255.2", "172.30.255.0/29"),
            "172.30.255.2/32",
        )
        self.assertEqual(
            caddy_proxy_ip_range("10.23.40.9", "10.23.40.0/24"),
            "10.23.40.9/32",
        )

    def test_rejects_proxy_addresses_outside_the_private_network(self) -> None:
        for address, subnet in (
            ("172.30.255.2", "172.30.254.0/29"),
            ("8.8.8.8", "8.8.8.0/24"),
            ("172.30.255.2", "169.254.1.0/24"),
            ("2001:db8::1", "2001:db8::/64"),
            ("172.30.255.2", "not-a-network"),
            ("172.30.255.2", "172.30.255.0/30"),
        ):
            with self.subTest(address=address, subnet=subnet):
                with self.assertRaises(SystemExit):
                    caddy_proxy_ip_range(address, subnet)

    def test_rejects_network_and_broadcast_addresses(self) -> None:
        for address in ("172.30.255.0", "172.30.255.1", "172.30.255.7"):
            with self.subTest(address=address):
                with self.assertRaises(SystemExit):
                    caddy_proxy_ip_range(address, "172.30.255.0/29")

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
