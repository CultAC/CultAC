"""Copy trusted-branch test evidence with strict type and size limits."""
import os
from pathlib import Path
import shutil
import stat
import sys

MAX_FILES = 1000
MAX_FILE_BYTES = 64 * 1024 * 1024
MAX_TOTAL_BYTES = 256 * 1024 * 1024


def collect(sandbox: Path, destination: Path, suite: str) -> None:
    roots = {
        'java': ['mcp-client-smoketest/.real-validation/runs'],
        'bedrock': ['artifacts', 'server/logs'],
    }[suite]
    workspace = sandbox / 'workspace'
    destination.mkdir(parents=True, exist_ok=True)
    count = total = 0
    for relative in roots:
        source = workspace / relative
        if any(p.is_symlink() for p in (source, *source.parents)):
            continue
        for directory, subdirs, filenames in os.walk(source, followlinks=False):
            subdirs[:] = sorted(name for name in subdirs if not (Path(directory) / name).is_symlink())
            for filename in sorted(filenames):
                path = Path(directory) / filename
                metadata = path.lstat()
                if not stat.S_ISREG(metadata.st_mode):
                    continue
                if (count >= MAX_FILES or metadata.st_size > MAX_FILE_BYTES or
                        total + metadata.st_size > MAX_TOTAL_BYTES):
                    continue
                target = destination / source.name / path.relative_to(source)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
                total += metadata.st_size
                count += 1


if __name__ == '__main__':
    collect(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3])
