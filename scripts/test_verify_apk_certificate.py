"""Regression tests for the preview's signing-certificate gate."""

import contextlib
import io
import unittest

from verify_apk_certificate import verify_report


class CertificateReportTest(unittest.TestCase):
    expected = "ab" * 32
    other = "cd" * 32

    def verify(self, report):
        with contextlib.redirect_stdout(io.StringIO()):
            verify_report(report, self.expected)

    def test_supported_signer_labels(self):
        for label in ["Signer #1", "V1 Signer #1:", "V2 Signer:", "V3.0 Signer:",
                      "V3.1 Signer:", "V3.2 Signer:",
                      "Signer (minSdkVersion=33, maxSdkVersion=2147483647)",
                      "Signer (minSdkVersion=36 (dev release=true), maxSdkVersion=2147483647)"]:
            with self.subTest(label=label):
                self.verify(f"{label} certificate SHA-256 digest: {self.expected}\n")

    def test_duplicate_schemes_with_same_key(self):
        self.verify("\n".join(f"{label} certificate SHA-256 digest: {self.expected}"
                              for label in ["V1 Signer:", "V2 Signer:", "V3.0 Signer:"]))

    def test_uppercase_colon_separated_fingerprint(self):
        digest = ":".join(["AB"] * 32)
        self.verify(f"Signer #1 certificate SHA-256 digest: {digest}\r\n")

    def test_other_key_fails(self):
        with self.assertRaisesRegex(ValueError, "does not match"):
            self.verify(f"Signer #1 certificate SHA-256 digest: {self.other}")

    def test_additional_different_signer_fails(self):
        with self.assertRaisesRegex(ValueError, "does not match"):
            self.verify(f"Signer #1 certificate SHA-256 digest: {self.expected}\n"
                        f"Signer #2 certificate SHA-256 digest: {self.other}")

    def test_different_key_in_another_scheme_fails(self):
        with self.assertRaisesRegex(ValueError, "does not match"):
            self.verify(f"V2 Signer: certificate SHA-256 digest: {self.expected}\n"
                        f"V3.0 Signer: certificate SHA-256 digest: {self.other}")

    def test_source_stamp_is_not_an_app_signer(self):
        self.verify(f"Signer #1 certificate SHA-256 digest: {self.expected}\n"
                    f"Source Stamp Signer certificate SHA-256 digest: {self.other}")
        with self.assertRaisesRegex(ValueError, "No APK signer"):
            self.verify(f"Source Stamp Signer certificate SHA-256 digest: {self.expected}")

    def test_empty_unknown_and_malformed_reports_fail(self):
        for report in ["", "Verified", "Signer #1 certificate SHA-1 digest: abcd",
                       f"Unknown certificate SHA-256 digest: {self.expected}",
                       f"Signer #1 certificate SHA-256 digest: {self.expected}\n"
                       f"Unknown certificate SHA-256 digest: {self.other}",
                       "Signer #1 certificate SHA-256 digest: 1234",
                       "Signer #1 certificate SHA-256 digest: not-a-fingerprint"]:
            with self.subTest(report=report), self.assertRaises(ValueError):
                self.verify(report)


if __name__ == "__main__":
    unittest.main()
