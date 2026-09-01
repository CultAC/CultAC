# grimParity

`grimParity` is the fail-closed inventory, static-dependency, and live-trace
comparison harness for the runtime-registered Grim checks.

Run the complete driver with:

```text
scripts/run-grim-parity.sh --baseline-ref 73a835d04f9bfbc76dc1d41c24ea73f3aec2280c --checks all --artifact-root PATH
```

The driver builds the detached baseline and current runtime jars, probes each
`CheckManager`, freezes the stable-key reconciliation, validates the versioned
four-path/three-network-profile catalog, and invokes the MCP-Reborn/Paper live
adapter for every trial. It exits nonzero for missing inventory, stale review
metadata, ambiguous identity, invalid scenario evidence, stimulus divergence,
static differences, or unequal semantic traces. `--checks` selectors are
available to the Gradle entry point for diagnosis, but the repository wrapper
rejects anything other than `all`.

The artifact root contains the frozen inventory, SHA-256 provenance (including
the names and contents of untracked current-worktree files), static
JSONL results, per-check reports, packet/semantic traces, validity evidence,
coverage matrix, and final counters. The live adapter is the
`grimParityLive` task in the pinned MCP/Paper smoketest workspace.
Choose an artifact root outside the repository or beneath a git-ignored path;
the driver rejects an unignored in-repository path so generated artifacts
cannot contaminate or recursively enter the current-worktree provenance.
