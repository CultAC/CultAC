# CI smoketests

`smoketests.yml` runs on pushes and pull requests targeting `main`, `experimental`,
and `dev`. Manual runs are limited to those same branches. The Java job runs only
chunk deduplication and combat reach, retaining the existing thresholds and bot
settings. The Bedrock job runs the existing capture regression runner, including
its repeated baseline rounds and mutations. Full Java movement scenarios and
unrelated harness unit suites are not invoked.

## Trust boundary

The private harness and replay repositories require `SMOKETEST_REPO_TOKEN` with
read access. `CULTAC_REPLAY_READ_TOKEN` remains a fallback when it has access to
the required fixture repository. The workflow uses `pull_request_target` to make
those trusted checkouts available for fork PRs. The workflow and control scripts
always come from `github.workflow_sha`, not the PR. Checkout credentials are not
persisted, and the job's `GITHUB_TOKEN` has only `contents: read`.

The Actions host prepares only trusted harness code and prerequisites. It restores
an exact trusted cache namespace without legacy fallback keys, then explicitly
saves prerequisites **before** checking out the code under test. Only pushes and
manual runs on the three allowed branches can save caches. No Gradle or cache
action registers an automatic post-test save.

PR checkout deliberately opts into `allow-unsafe-pr-checkout` **as data only**.
The PR merge ref is tested; a missing/conflicted merge ref fails checkout instead
of silently testing the base. Neither the PR's build scripts nor plugin jar are
executed on the Actions host.

Builds and tests run in an unprivileged Docker container with a read-only root
filesystem, dropped capabilities, no-new-privileges, and bounded CPU, memory, and
process count. Its only writable bind mounts are disposable copies of the input
workspaces and Gradle prerequisites. Those copies preserve absolute paths needed
by MCP, but do not share writable files with host caches. The runner's home,
credentials, workflow command files, and Docker socket are not mounted or
forwarded. Workflow command processing is suspended around container stdout.

After the container exits, the host copies only regular evidence files, skipping
symlinks, linked ancestor directories, and special files. This prevents artifact
paths authored by PR code from reading host files during upload. Private source
checkouts and prerequisite directories are not uploaded as artifacts.

Changes to this boundary must preserve both the cache-save ordering and the
container mount/environment allowlists. Local orchestration checks:

```sh
python3 -m unittest discover -s .github/scripts/tests -v
bash -n .github/scripts/prepare-smoketests.sh \
  .github/scripts/run-smoketest-container.sh \
  .github/scripts/run-selected-smoketests.sh
```

The workflow policy check uses PyYAML. These local checks exercise command
selection, failure propagation, disposable cache copies, credential forwarding,
and hostile artifact links. They do not substitute for a live GitHub-hosted
Minecraft run. Because `pull_request_target` uses the installed trusted workflow,
new workflow changes take effect after they land on the trusted branch.
