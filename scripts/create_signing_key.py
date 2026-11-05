"""Create a local Android PKCS12 signing identity once, without requiring Java.

Requires OpenSSL (Git for Windows includes it). Never prints secret values and
refuses to replace an existing .signing directory. Keep that directory backed up.
"""

import base64
import hashlib
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile


def main():
    workspace_root = Path(__file__).resolve().parent.parent
    signing_dir = workspace_root / ".signing"
    if signing_dir.exists():
        raise SystemExit(".signing already exists; keeping the existing signing key unchanged.")
    openssl = shutil.which("openssl")
    if openssl is None:
        candidates = [
            Path("C:/Program Files/Git/mingw64/bin/openssl.exe"),
            Path("C:/Program Files/Git/usr/bin/openssl.exe"),
        ]
        openssl = next((str(path) for path in candidates if path.is_file()), None)
    if openssl is None:
        raise SystemExit("OpenSSL was not found. Install Git for Windows or provide openssl on PATH.")

    # Generate a high-entropy password locally; pass it through the subprocess
    # environment rather than exposing it in process arguments or terminal logs.
    password = secrets.token_urlsafe(32)
    alias = "openlock"
    command_env = os.environ.copy()
    command_env["OPENLOCK_SIGNING_PASSWORD"] = password
    temporary_dir = Path(tempfile.mkdtemp(prefix=".signing-new-", dir=workspace_root))

    def run(*arguments, binary=False):
        result = subprocess.run(
            [openssl, *map(str, arguments)],
            env=command_env,
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            # No private material is requested on stdout/stderr by these commands.
            raise RuntimeError(result.stderr.decode("utf-8", errors="replace"))
        return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")

    try:
        private_key = temporary_dir / "encrypted-private-key.pem"
        certificate = temporary_dir / "certificate.pem"
        keystore = temporary_dir / "openlock-release.keystore"
        run("req", "-new", "-x509", "-newkey", "rsa:3072", "-sha256",
            "-days", "10000", "-subj", "/CN=OpenLock/O=OpenLock/C=KR",
            "-keyout", private_key, "-out", certificate,
            "-passout", "env:OPENLOCK_SIGNING_PASSWORD")
        run("pkcs12", "-export", "-inkey", private_key, "-in", certificate,
            "-name", alias, "-out", keystore, "-iter", "100000",
            "-passin", "env:OPENLOCK_SIGNING_PASSWORD",
            "-passout", "env:OPENLOCK_SIGNING_PASSWORD")
        run("pkcs12", "-in", keystore, "-info", "-noout",
            "-passin", "env:OPENLOCK_SIGNING_PASSWORD")
        der = run("x509", "-in", certificate, "-outform", "DER", binary=True)
        fingerprint = hashlib.sha256(der).hexdigest()
        values = {
            "KEYSTORE_BASE64": base64.b64encode(keystore.read_bytes()).decode("ascii"),
            "KEYSTORE_PASSWORD": password,
            "KEY_PASSWORD": password,
            "KEY_ALIAS": alias,
        }
        for name, value in values.items():
            (temporary_dir / f"{name}.txt").write_text(value, encoding="ascii")
        (temporary_dir / "certificate-sha256.txt").write_text(fingerprint + "\n", encoding="ascii")
        # The encrypted PKCS12 contains the key and certificate; no separate PEM
        # private-key copy is needed after successful packaging and validation.
        private_key.unlink()
        temporary_dir.rename(signing_dir)
        print("Created and validated a fixed signing key in .signing (Git-ignored).")
        print("Four Actions secret value files are ready. No secret values were printed.")
        print("Certificate SHA-256 (public):", fingerprint)
        print("Back up the entire .signing directory privately; do not regenerate it for updates.")
    finally:
        # Check the resolved absolute target before recursive cleanup on Windows.
        if temporary_dir.exists():
            resolved = temporary_dir.resolve()
            if resolved.parent != workspace_root or not resolved.name.startswith(".signing-new-"):
                raise RuntimeError("Refusing to clean up an unexpected temporary path.")
            shutil.rmtree(resolved)


if __name__ == "__main__":
    main()
