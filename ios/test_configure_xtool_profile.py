import unittest

from configure_xtool_profile import build_profile_documents, normalize_matrix_domain


class XtoolProfileTests(unittest.TestCase):
    def setUp(self):
        self.base_info = {
            "CFBundleDisplayName": "Private Messenger",
            "MatrixPushEnabled": False,
            "MatrixPushDomain": "",
        }
        self.base_entitlements = {
            "com.apple.developer.default-data-protection": "NSFileProtectionComplete",
        }

    def test_debug_profile_enables_push_and_keeps_complete_file_protection(self):
        info, entitlements = build_profile_documents(
            self.base_info,
            self.base_entitlements,
            "debug",
            "  matrix.example.org. ",
        )

        self.assertTrue(info["MatrixPushEnabled"])
        self.assertEqual(info["MatrixPushDomain"], "matrix.example.org")
        self.assertEqual(info["MatrixPushAppID"], "dev.friendline.messenger.ios.dev")
        self.assertEqual(entitlements["aps-environment"], "development")
        self.assertEqual(
            entitlements["com.apple.developer.default-data-protection"],
            "NSFileProtectionComplete",
        )

    def test_release_profile_uses_production_pusher_and_apns_environment(self):
        info, entitlements = build_profile_documents(
            self.base_info,
            self.base_entitlements,
            "release",
            "matrix.example.org",
        )

        self.assertEqual(info["MatrixPushAppID"], "dev.friendline.messenger.ios")
        self.assertEqual(entitlements["aps-environment"], "production")

    def test_default_profile_disables_push_and_removes_apns_entitlement(self):
        base_entitlements = dict(self.base_entitlements, **{"aps-environment": "production"})
        info, entitlements = build_profile_documents(
            self.base_info,
            base_entitlements,
            "debug",
            None,
        )

        self.assertFalse(info["MatrixPushEnabled"])
        self.assertEqual(info["MatrixPushDomain"], "")
        self.assertNotIn("aps-environment", entitlements)
        self.assertEqual(
            entitlements["com.apple.developer.default-data-protection"],
            "NSFileProtectionComplete",
        )

    def test_matrix_domain_validation_rejects_url_components(self):
        for invalid_domain in ("https://matrix.example.org", "matrix.example.org/path", "user@matrix.org", "matrix.org:8448"):
            with self.subTest(domain=invalid_domain):
                with self.assertRaises(ValueError):
                    normalize_matrix_domain(invalid_domain)

    def test_internationalized_domain_is_normalized_for_url_configuration(self):
        self.assertEqual(normalize_matrix_domain("münich.example"), "xn--mnich-kva.example")


if __name__ == "__main__":
    unittest.main()
