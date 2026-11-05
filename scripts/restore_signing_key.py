"""Restore the fixed CI signing key from Actions step environment variables."""

import base64
import binascii
import os
from pathlib import Path


SECRET_NAMES = ("KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_PASSWORD", "KEY_ALIAS")


def property_value(value):
    """Escape a value for Java Properties.load(InputStream), including Unicode."""
    escapes = {"\\": "\\\\", "\n": "\\n", "\r": "\\r", "\t": "\\t", "\f": "\\f",
               " ": "\\ ", "=": "\\=", ":": "\\:", "#": "\\#", "!": "\\!"}
    result = []
    for character in value:
        if character in escapes:
            result.append(escapes[character])
        elif ord(character) > 127:
            encoded = character.encode("utf-16-be")
            result.extend("\\u" + encoded[index:index + 2].hex() for index in range(0, len(encoded), 2))
        else:
            result.append(character)
    return "".join(result)


def restore(android_dir, variables):
    missing = [name for name in SECRET_NAMES if not variables.get(name)]
    if missing:
        raise ValueError("Missing Actions repository secrets: " + ", ".join(missing))
    try:
        keystore = base64.b64decode("".join(variables["KEYSTORE_BASE64"].split()), validate=True)
    except (ValueError, binascii.Error):
        raise ValueError("KEYSTORE_BASE64 is not valid Base64; copy the entire value file.") from None
    if not keystore:
        raise ValueError("KEYSTORE_BASE64 decoded to an empty keystore.")

    properties = {
        "storeFile": "release.keystore",
        "storeType": "PKCS12",
        "storePassword": variables["KEYSTORE_PASSWORD"],
        "keyPassword": variables["KEY_PASSWORD"],
        "keyAlias": variables["KEY_ALIAS"],
    }
    android_dir = Path(android_dir)
    keystore_path = android_dir / "app" / "release.keystore"
    properties_path = android_dir / "key.properties"
    if keystore_path.exists() or properties_path.exists():
        raise ValueError("Signing files already exist; refusing to replace them.")
    keystore_path.parent.mkdir(parents=True, exist_ok=True)
    with os.fdopen(os.open(keystore_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as stream:
        stream.write(keystore)
    with os.fdopen(os.open(properties_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w", encoding="ascii") as stream:
        stream.write("".join(f"{name}={property_value(value)}\n" for name, value in properties.items()))


if __name__ == "__main__":
    try:
        restore(Path(__file__).resolve().parent.parent / "android", os.environ)
    except ValueError as error:
        raise SystemExit(f"::error::{error}") from None
    print("Fixed signing keystore restored. Secret values were not printed.")
