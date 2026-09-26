#!/usr/bin/env python3
"""Verify the prepared client identity before a local smoketest reuses it."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

OFFICIAL_CLIENT_SHA1 = {
    "26.2": "2dc72797acbc1b63fc16a11c4ac393605f453754",
    "26.3-rc-1": "a64d116707456e8c6069776a558936d11a2674e1",
}


def verify(checkout: Path, expected: str) -> dict:
    build = checkout / "projects/mcp/build/mcp"
    resource = build / "downloadClient/client.jar"
    stripped = build / "stripClient/output.jar"
    with zipfile.ZipFile(resource) as jar:
        version = json.loads(jar.read("version.json"))
    if version["id"] != expected:
        raise ValueError(f"client cache is {version['id']}, requested {expected}")
    client_sha1 = hashlib.sha1(resource.read_bytes()).hexdigest()
    if expected in OFFICIAL_CLIENT_SHA1 and client_sha1 != OFFICIAL_CLIENT_SHA1[expected]:
        raise ValueError(f"client cache does not match the official {expected} jar")
    # The stripped jar must contain the same game classes as the official jar.
    # Checking only version.json allows a stale stripClient result to survive setup.
    classes = 0
    with zipfile.ZipFile(resource) as original, zipfile.ZipFile(stripped) as prepared:
        for name in prepared.namelist():
            if name.endswith(".class"):
                if prepared.read(name) != original.read(name):
                    raise ValueError(f"prepared client differs from {expected}: {name}")
                classes += 1
    if classes == 0:
        raise ValueError("prepared client contains no classes")
    return {
        "version": version,
        "client_sha1": client_sha1,
        "client_sha256": hashlib.sha256(resource.read_bytes()).hexdigest(),
        "prepared_sha256": hashlib.sha256(stripped.read_bytes()).hexdigest(),
        "verified_classes": classes,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path)
    parser.add_argument("version")
    args = parser.parse_args()
    print(json.dumps(verify(args.checkout, args.version), indent=2))
