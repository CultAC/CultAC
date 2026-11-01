#!/usr/bin/env python3
"""Download and verify pinned runtime jars while trusted networking is available."""
import hashlib
import json
import re
import sys
import tempfile
import urllib.request
from pathlib import Path


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def prepare(workspace):
    workspace = Path(workspace).resolve()
    lock_file = workspace / "ci/scripts/smoketests/bedrock-replay-runtime.json"
    lock = json.loads(lock_file.read_text())
    if lock.get("schema") != "cultac-bedrock-replay-runtime/v1":
        raise ValueError("Unexpected Bedrock runtime lock schema")
    if set(lock.get("artifacts", {})) != {"paper", "geyser", "floodgate", "viaversion", "viabackwards"}:
        raise ValueError("Unexpected Bedrock runtime artifact list")
    destination = workspace / "replay/.ci-runtime-downloads"
    destination.mkdir(parents=True, exist_ok=True)
    for name, item in lock["artifacts"].items():
        sha, url = item.get("sha256", ""), item.get("url", "")
        if not re.fullmatch(r"[a-f0-9]{64}", sha) or not url.startswith("https://") or "latest" in url:
            raise ValueError(f"Unpinned Bedrock runtime artifact: {name}")
        target = destination / (sha + ".jar")
        if not target.is_file() or digest(target) != sha:
            with tempfile.NamedTemporaryFile(dir=destination, suffix=".part", delete=False) as output:
                temporary = Path(output.name)
                try:
                    request = urllib.request.Request(url, headers={"User-Agent": "CultAC-offline-smoketest/1"})
                    with urllib.request.urlopen(request, timeout=120) as response:
                        copied = 0
                        while chunk := response.read(1024 * 1024):
                            copied += len(chunk)
                            if copied > 400 * 1024 * 1024:
                                raise ValueError(f"Runtime artifact too large: {name}")
                            output.write(chunk)
                except BaseException:
                    temporary.unlink(missing_ok=True)
                    raise
            if digest(temporary) != sha:
                temporary.unlink(missing_ok=True)
                raise ValueError(f"Runtime SHA-256 mismatch: {name}")
            temporary.replace(target)
        item.pop("url")
        item["path"] = str(target)
    (workspace / "replay/.ci-runtime-offline.json").write_text(json.dumps(lock, indent=2) + "\n")


if __name__ == "__main__":
    prepare(sys.argv[1])
