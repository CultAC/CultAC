#!/usr/bin/env python3
"""Run the isolated compatibility tests on both pinned Mojang runtimes (JDK 25).

This does not replace the full Gradle build or the live anticheat smoketests.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile


SERVERS = {
    "26.2": "823e2250d24b3ddac457a60c92a6a941943fcd6a",
    "26.3-rc-1": "7ae096efda2563d58a0c01575256f3db7669d386",
}
JUNIT_URL = ("https://repo1.maven.org/maven2/org/junit/platform/"
             "junit-platform-console-standalone/1.11.4/"
             "junit-platform-console-standalone-1.11.4.jar")
JUNIT_SHA256 = "b016ef6b1c3454d6d7c2c88ce081dabf289699686af6622d6e4e2e1b54b4a2fc"
CLASSES = [
    "network/protocol/ClientVersion",
    "network/packet/SwingPacketUtil",
    "utils/nmsutil/MiningFatigue",
]


def download(url, path, digest, algorithm):
    if not path.exists() or hashlib.new(algorithm, path.read_bytes()).hexdigest() != digest:
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
        if hashlib.new(algorithm, data).hexdigest() != digest:
            raise ValueError(f"Checksum mismatch: {url}")
        path.write_bytes(data)
    return path


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--cache-dir", type=Path, default=root / "build/minecraft-26.3-validation")
    args = parser.parse_args()
    cache = args.cache_dir.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    java = str(Path(args.java_home) / "bin/java") if args.java_home else shutil.which("java")
    javac = str(Path(args.java_home) / "bin/javac") if args.java_home else shutil.which("javac")
    if not java or not javac:
        parser.error("JDK 25 is required; pass --java-home")
    junit = download(JUNIT_URL, cache / "junit-console.jar", JUNIT_SHA256, "sha256")
    classes = cache / "classes"
    classes.mkdir(exist_ok=True)
    sources = [str(root / f"common/src/main/java/ac/cult/cultac/{name}.java") for name in CLASSES]
    tests = [str(root / f"common/src/test/java/ac/cult/cultac/{name}Test.java") for name in CLASSES]
    subprocess.run([javac, "--release", "21", "-cp", str(junit), "-d", str(classes), *sources, *tests], check=True)
    for version, sha1 in SERVERS.items():
        server = download(f"https://piston-data.mojang.com/v1/objects/{sha1}/server.jar",
                          cache / f"server-{version}.jar", sha1, "sha1")
        runtime = cache / version
        runtime.mkdir(exist_ok=True)
        jars = []
        with zipfile.ZipFile(server) as archive:
            for name in archive.namelist():
                if name.startswith(("META-INF/libraries/", "META-INF/versions/")) and name.endswith(".jar"):
                    # Flatten to generated names so archive paths cannot escape the cache.
                    target = runtime / f"{len(jars)}.jar"
                    target.write_bytes(archive.read(name))
                    jars.append(str(target))
        if not jars:
            raise ValueError(f"No runtime jars in {server}")
        command = [java, "-cp", os.pathsep.join([str(classes), str(junit), *jars]),
                   "org.junit.platform.console.ConsoleLauncher", "execute", "--disable-banner", "--details=summary"]
        for name in CLASSES:
            command += ["--select-class", "ac.cult.cultac." + name.replace("/", ".") + "Test"]
        print(f"Testing Mojang {version} ({sha1})", flush=True)
        subprocess.run(command, check=True)


if __name__ == "__main__":
    main()
