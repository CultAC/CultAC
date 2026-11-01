"""Copy container evidence without following attacker-authored links on the host."""
import os
from pathlib import Path
import shutil
import stat
import sys


def collect(sandbox: Path, destination: Path, suite: str) -> None:
    roots = {
        'java': ['mcp-client-smoketest/.real-validation/runs'],
        'bedrock': ['artifacts', 'server/logs'],
    }[suite]
    workspace = sandbox / 'workspace'
    destination.mkdir(parents=True, exist_ok=True)
    for relative in roots:
        source = workspace / relative
        # os.walk does not follow child directory links; also reject linked roots
        # and ancestors before walking. The container has exited, so no writer runs.
        if any(p.is_symlink() for p in (source, *source.parents)):
            continue
        for directory, _, filenames in os.walk(source, followlinks=False):
            for filename in filenames:
                path = Path(directory) / filename
                if not stat.S_ISREG(path.lstat().st_mode):
                    continue
                # Keep .real-validation out of the uploaded tree: upload-artifact
                # excludes hidden directories by default.
                target = destination / source.name / path.relative_to(source)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)


if __name__ == '__main__':
    collect(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3])
