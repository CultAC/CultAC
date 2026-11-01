# CI smoketests: security model

Protected-branch pushes and manual runs on main, experimental, and dev execute only the
private-fixture reach, chunk-deduplication, and Bedrock replay smoketests.
Both internal and fork pull requests run build-only verification without private
fixtures or secrets. A PR build pass is not a private smoketest pass.

## Trust boundary

- PR events use pull_request, never pull_request_target. A trusted-base Docker
  image and wrapper compile the PR merge revision inside an isolated container.
  No private repositories, repository secrets, runner home, shared Gradle cache,
  workflow command files, Docker socket, or artifacts are mounted or uploaded.
  Dependencies are warmed from trusted base Gradle scripts, then copied into the
  disposable build home. The untrusted PR builder runs --offline and --network=none.
  PRs adding new dependencies fail closed until the trusted base resolves them.
- Private fixtures are available only to the separate trusted-branch job.
  Secret-backed checkouts run before sandbox execution; trusted branch code can
  safely prepare its Gradle dependencies on the host.
- The trusted branch job downloads and verifies pinned Bedrock runtime jars
  before starting the Docker container. Its generated offline lock references
  local, SHA-256-verified paths; Gradle always uses --offline in the container.
- The actual reach, dedup, and Bedrock test container uses --network=none,
  retaining loopback for local Minecraft traffic while blocking external egress.
- Only trusted-branch jobs explicitly save cache entries under the separate
  trusted-smoketest-v2 namespace. No PR job restores or saves them.
- Test containers use dropped capabilities, no-new-privileges, resource limits,
  a read-only root and independent workspace/cache copies.
- Artifacts come only from trusted-branch runs. Collector rejects symlinks and
  special files, filters to status/qualification summaries and limits file count
  and byte size. Full private capture files, replay traces and server logs are not
  uploaded. Summary files can still contain sensitive failure messages, so
  trusted-branch code must not place secrets in test diagnostics.

Arbitrary PRs cannot run the confidential fixture suites safely under the
old same-container architecture. For full PR smoke coverage, use sanitized
public fixtures or isolate the plugin/server in a different security domain
from the private test harness and its evidence controller.

## Local validation

```sh
python3 -m unittest discover -s .github/scripts/tests -v
bash -n .github/scripts/prepare-smoketests.sh \
  .github/scripts/run-untrusted-pr-build.sh \
  .github/scripts/run-smoketest-container.sh \
  .github/scripts/run-selected-smoketests.sh
```

Live Docker/Minecraft execution and offline dependency completeness require a
real GitHub Actions run. Missing dependencies must be prepared on a trusted
host; do not turn network access back on in the smoketest container.
