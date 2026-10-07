#!/usr/bin/env python3
"""Verify the directly linked private codec dependency in a production plugin jar."""
import argparse
from pathlib import Path
from zipfile import ZipFile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--library", required=True, type=Path)
parser.add_argument("plugin", type=Path)
args = parser.parse_args()
with ZipFile(args.library) as library, ZipFile(args.plugin) as plugin:
    entries = set(plugin.namelist())
    required = {name for name in library.namelist()
                if name.startswith(("ac/cult/shaded/vialib/", "assets/ac/cult/shaded/vialib/"))
                and not name.endswith("/")}
    required.update({"ac/cult/cultac/codec/PrivateCodecService.class",
                     "ac/cult/cultac/protocol/ProtocolCodecs.class"})
    missing = sorted(required - entries)
    forbidden = sorted(name for name in entries
                       if name.startswith(("com/viaversion/", "assets/viaversion/", "assets/viabackwards/"))
                       or name == "runtime/protocol-codecs.jar"
                       or name.endswith("ProtocolCodecs$CodecLoader.class"))
    reflective = []
    for name in entries:
        if name.endswith(".class") and (name.startswith("ac/cult/cultac/codec/")
                or name == "ac/cult/cultac/protocol/ProtocolCodecs.class"):
            data = plugin.read(name)
            if b"java/lang/reflect/" in data or b"java/net/URLClassLoader" in data or b"java/lang/ClassLoader" in data:
                reflective.append(name)
    if missing or forbidden or reflective:
        parser.exit(1, f"Invalid codec packaging in {args.plugin}: missing={len(missing)} {missing[:12]}, forbidden={forbidden[:12]}, reflective={sorted(reflective)}\n")
    print(f"Verified private codec packaging: {args.plugin.name} ({len(required)} required entries)")
