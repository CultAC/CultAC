#!/usr/bin/env python3
"""Reject Minecraft code/data and ASM in distributables, including nested archives."""
import io
from pathlib import Path, PurePosixPath
import sys
import zipfile


def verify(source, label):
    with zipfile.ZipFile(source) as archive:
        for entry in archive.infolist():
            name = entry.filename
            parts = PurePosixPath(name).parts
            if any(parts[index:index + 2] in (("net", "minecraft"), ("data", "minecraft"), ("assets", "minecraft"))
                   for index in range(len(parts) - 1)):
                raise ValueError(f"Bundled Minecraft payload: {label}!/{name}")
            if name.endswith(".class") and (
                    any(parts[index:index + 2] == ("com", "mojang") for index in range(len(parts) - 1))
                    or any(parts[index:index + 3] == ("org", "objectweb", "asm") for index in range(len(parts) - 2))
                    or "ac/cult/cultac/shaded/asm/" in name):
                raise ValueError(f"Bundled Mojang or ASM class: {label}!/{name}")
            if name.startswith(("placement-26.3/", "interaction-26.3/")):
                raise ValueError(f"Bundled vanilla profile: {label}!/{name}")
            if entry.is_dir():
                continue
            with archive.open(entry) as stream:
                header = stream.read(4)
            # A renamed jar must not evade the check by becoming a .bin resource.
            if name.lower().endswith((".jar", ".zip")) or header in (b"PK\x03\x04", b"PK\x05\x06"):
                verify(io.BytesIO(archive.read(entry)), f"{label}!/{name}")


def main(arguments):
    for argument in arguments:
        path = Path(argument)
        verify(path, str(path))
        print(f"Verified no bundled Minecraft: {path} ({path.stat().st_size / 2**20:.2f} MiB)")


if __name__ == "__main__":
    main(sys.argv[1:])
