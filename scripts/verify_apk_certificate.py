"""Compare an already verified apksigner report with the fixed DER certificate.

Run apksigner verify successfully before invoking this script. Only public
certificate fingerprints are printed; no keystore or password is read here.
"""

import argparse
import hashlib
import os
from pathlib import Path
import re


CERTIFICATE_LINE = re.compile(r"^(.+?) certificate SHA-256 digest:\s*(.*?)\s*$", re.MULTILINE)
SIGNER_LABEL = re.compile(r"(?:V\d+(?:\.\d+)?\s+)?Signer(?:\s+#\d+)?(?:\s*\(.*\))?:?", re.IGNORECASE)


def verify_report(report, expected):
    """Accept repeated scheme entries only when every APK signer has our key."""
    actual = []
    for label, digest in CERTIFICATE_LINE.findall(report):
        label = label.strip()
        if label.rstrip(":").lower() == "source stamp signer":
            continue  # A source stamp is not the app's signing identity.
        if not SIGNER_LABEL.fullmatch(label):
            raise ValueError(f"Unrecognized APK certificate label: {label}")
        digest = digest.replace(":", "").lower()
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError(f"Invalid SHA-256 fingerprint for {label}.")
        print(f"APK {label} SHA-256: {digest}")
        actual.append(digest)
    if not actual:
        raise ValueError("No APK signer fingerprints found; apksigner output was not recognized.")
    if set(actual) != {expected.lower()}:
        raise ValueError("APK certificate does not match the fixed signing key.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--certificate", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    expected = hashlib.sha256(args.certificate.read_bytes()).hexdigest()
    print("Expected fixed certificate SHA-256:", expected)
    try:
        verify_report(args.report.read_text(encoding="utf-8"), expected)
    except ValueError as error:
        raise SystemExit(f"::error::{error}") from None
    print("Fixed APK certificate verified.")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write(f"Fixed APK signing certificate SHA-256: `{expected}`\n")


if __name__ == "__main__":
    main()
