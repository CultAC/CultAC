# Entity movement replication: issue #203

## Scope and baseline

This change targets sustained entity movement replication on `dev` commit
`85968bdd517d41f58f13eb8628551211ea96e9dd`.

[Issue #203](https://github.com/CultAC/CultAC/issues/203) reports a roughly
200-player Paper 1.21.11 KitPvP server on Java 26. Its 30-second JFR summary assigns
16.6% of Netty samples, inclusively, to `PacketEntityReplication.handleMoveEntity`,
and names `AbstractScheduledEventExecutor.schedule` among the self-time frames.
The recording itself has not been attached. The
[existing reply](https://github.com/CultAC/CultAC/issues/203#issuecomment-6533986366)
requests Spark and clarification about the deployed build.

Those samples identify a path worth investigating; they do not establish which
allocations or calls dominate the deployed build. The measurements below are a
controlled comparison of the pinned source, not a replay of the reporter's JFR
or a measurement of whole-server CPU savings. Inclusive sample shares overlap
and must not be added together.

## Changes

### Reuse the unbundled movement task for its two proof phases

Previously, an unbundled movement allocated a `TransactionBoundMovement` and two
capturing method-reference callbacks, `bound::apply` and `bound::settle`. The
holder also copied the movement's immutable entity ID, kind and delta sequence.

The holder now implements `Runnable` and is registered for both halves of the
existing `addRealTimeTaskWithNextTransaction` operation. Its first invocation
advances the phase before applying; its second settles only if application
completed. This removes the two callback objects and the redundant fields.
Bundled movements keep their existing object layout and single proof task.

The latency queue implementation and proof selection are unchanged. In
particular, settlement keeps the original insertion slot relative to metadata,
removals and later movements; it is not re-enqueued at the tail during apply.
A catch-up acknowledgement still runs apply before settle. A missing entity or
an exception during apply cannot cause the continuation to retry the move.
Path-proof accounting still refers to the entity that received the path, while
ordinary settlement retains the existing entity-ID lookup behavior.

### Read only the transform components that are needed

A position-and-rotation update overwrites all five transform components. It now
skips the compensated-entity lookup and fallback-source calculation whose values
would all have been discarded. Position-only and rotation-only updates retain
the existing source selection, including live entries, interpolation targets,
client positions, tracker fallback and entity-ID aliases.

Teleport processing reads its source position and both angles once, using the
existing scratch transform. It copies them into the position vector and primitive
angle locals before later work can reuse the scratch storage. It also passes the
already retrieved `TrackerData` into `handleMoveEntity` instead of looking it up
again. Coordinate calculations, codec-base updates, relative flags, vehicle
response proofs and Bedrock dispatch retain their existing behavior.

### Cancel obsolete FairReach deadlines

Each eligible FairReach movement update registers a transaction callback and a separate
Netty deadline. Previously, a transaction-winning move left its deadline queued;
the deadline later entered `runSafely` and the model before discovering that the
move had already been applied. In the normal runtime this also enters
`ScopedResources.once` and its resource accounting.

`PendingMove` now uses one atomic state for an uninstalled deadline, its scheduled
future, or completed application. The transaction winner cancels the future. A
proof already handled synchronously avoids scheduling, and an already-dequeued
deadline checks whether it still needs to enter the model. If either proof or
deadline runs before `schedule()` returns, the failed future installation cancels
the returned future. Application remains guarded against repetition, including after exceptions.

Normal pending movements still allocate and schedule their original deadline.
The gain is cancellation of obsolete queued work and shorter retention, not
removal of every per-movement `schedule()` call. Deadline-winning moves retain
their original deadline and executor ordering. `targetPlayers` also uses its
primitive-map type directly, eliminating boxed `Map<Integer, ...>` call sites;
the extent to which the JIT already removed boxing is not assumed.

Bulk deadline batching was deliberately excluded. When an executor is late, a
callback that drains every overdue movement can apply a later move ahead of a
reader or tick callback scheduled between the two deadlines. The new delayed
executor regression test explicitly checks that ordering.

## Correctness validation

The optimized source was built normally with Gradle. Compilation used the
repository's Java 25 toolchain and Java 21 release target; the matched broad test
runs used Temurin Java 26.0.2.1+1.

| Revision | Tests | Passed | Failed | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Pinned baseline, with behavior-preservation tests | 380 | 370 | 10 | 0 |
| Optimized | 385 | 375 | 10 | 0 |

The [validation summary](entity-movement-203-validation.json) records both runs.
The ten failure keys and assertion messages match exactly between revisions.
They are the existing parameterized
`EntityCodecBaseTest.theRiddenVehiclesMovesAdvanceTheBase` paddle-stroke cases:
the initial drive assertion reports `1.1999999284756264E-5` against a `< 1e-5`
limit, before the codec-base assertion. No test assertion was weakened and no
unrelated simulation change was made to hide these baseline failures.

All 20 new tests pass on the optimized source:

- Eight movement-proof tests cover multiple relative moves, packet-order
  decoding, split/catch-up acknowledgements, in-place continuations, immediate
  proofs, failed/skipped application, reused entity IDs, 26.3 path proof ownership,
  bundled ordering and Bedrock authority-only handling.
- Four transform tests cover full and partial updates, chained relative
  teleports, tick-boundary invalidation, active interpolation and pending spawns.
- Eight FairReach tests cover both winners, synchronous proofs, dequeued
  deadlines, both schedule-return races, rejected scheduling and an intervening
  executor reader. Three check existing behavior on both revisions; five assert
  the newly removed redundant work.

The broader selection also covers reach interpolation and interaction range,
local vehicles and passenger receipts, remount/dismount behavior, Bedrock vehicle
transport and transaction acceptance, and transaction queue ordering. Available
26.3 client `ClientPacketListener` source was inspected for packet-order codec
updates, bundle dispatch and vehicle teleport semantics.

This validation did not include a live Paper/Geyser combat session or the
reporter's original recording.

## Benchmark method

`SustainedEntityReplicationBenchTest` models one observer tracking 200 moving
player entities on protocol 1.21.11. Position-only, position-and-rotation and
rotation-only packets run separately, with bundled and unbundled proofs. Each
wave represents 50 ms. Primary cases acknowledge after 100 ms; one additional
bundled position-and-rotation case acknowledges after 400 ms so FairReach's
unchanged 300 ms deadline wins.

The harness supplies an existing shared bundle proof, or the pre-wave transaction
for unbundled movement, and drains both proof phases in order. It uses real
`LatencyUtils`, entity interpolation, FairReach targets, Netty scheduled tasks and
`ScopedResources` admission through a concrete host. Netty's clock is frozen and
advanced explicitly; deadlines are drained throughout the run. Pending work
carries across samples and is finally drained outside measurement. Fixture
creation, state checksums and CSV formatting are also outside measurement.

The measured scope includes input packet/event allocation, packet handling,
proof callbacks, remote-entity interpolation and deadline processing. It excludes
encoder serialization, sockets, ping generation, inbound pong decoding, actual
attacks and the local player's full movement prediction. Both revisions use the
identical benchmark source. Transaction counts, final tracked positions,
interpolation state and resource-release checks guard equivalent work.

Current-thread CPU time and allocated bytes come from `ThreadMXBean`. CPU time
excludes time descheduled on the shared host; it also excludes other threads'
compilation and GC work, and cannot remove all cache or machine contention. Each
benchmark JVM was configured with `-XX:ActiveProcessorCount=2`. Results use multiple samples in separate,
alternating Java 26 JVMs; they are not a multi-threaded server throughput result.

## Measurements

Three fresh JVMs per revision ran in the order baseline 1, optimized 1, baseline
2, optimized 2, baseline 3, optimized 3. Each scenario had 2,000 warm-up waves and
five measured samples of 2,000 waves per fork. This is **6,000,000 measured entity
updates per scenario per revision**, or 42,000,000 per revision across seven
scenarios. All 105 measured samples per revision completed, and corresponding
state checksums match.

The following values are the median of the three fork medians. The first six
rows use 100 ms acknowledgement delay.

| Case | CPU ns/move, baseline → optimized | CPU reduction | Bytes/move, baseline → optimized | Allocation reduction |
| --- | ---: | ---: | ---: | ---: |
| Bundled, position | 466.4 → 394.7 | 15.4% | 543.1 → 487.1 | 10.3% |
| Bundled, position + rotation | 485.1 → 371.8 | 23.4% | 543.1 → 487.1 | 10.3% |
| Bundled, rotation | 480.5 → 378.6 | 21.2% | 503.0 → 447.1 | 11.1% |
| Unbundled, position | 464.0 → 352.8 | 24.0% | 615.0 → 511.0 | 16.9% |
| Unbundled, position + rotation | 458.7 → 351.5 | 23.4% | 615.0 → 511.0 | 16.9% |
| Unbundled, rotation | 474.0 → 346.2 | 27.0% | 575.0 → 471.0 | 18.1% |
| Bundled, position + rotation, 400 ms ACK | 509.0 → 498.7 | No clear change | 543.0 → 543.0 | 0.0% |

The six 100 ms cases save approximately **56 bytes per bundled move** and
**104 bytes per unbundled move**. The 400 ms case has no allocation reduction,
and its CPU ranges overlap substantially; the 2.0% difference between its medians
is not reported as a demonstrated CPU gain.

These are combined-patch measurements. They do not separately attribute a CPU
percentage to the transform fast path, task representation, primitive-map
call sites or deadline cancellation. The source establishes which allocations
and operations were removed; a production gain at the reporter's packet rate
requires the actual recording and deployed build.

Fork-median variability on the shared host was:

| Case | Baseline fork-median range, ns/move | Optimized fork-median range, ns/move |
| --- | ---: | ---: |
| Bundled, position | 450.7–565.2 | 347.1–402.2 |
| Bundled, position + rotation | 458.2–510.4 | 366.1–417.5 |
| Bundled, rotation | 458.9–697.7 | 354.7–403.0 |
| Unbundled, position | 463.4–596.9 | 340.9–364.1 |
| Unbundled, position + rotation | 452.8–503.2 | 342.0–433.9 |
| Unbundled, rotation | 460.0–486.6 | 333.6–354.2 |
| Bundled, position + rotation, 400 ms ACK | 500.2–569.3 | 496.4–565.4 |

The ranges are observed fork ranges, not confidence intervals. All six primary
cases have lower optimized fork medians than the baseline fork medians in this
run. The high-latency control does not establish a clear CPU difference.

- [All 210 raw samples](entity-movement-203-samples.csv)
- [Summary, source hashes, JVM flags and fork order](entity-movement-203-measurements.json)
- [Matched correctness-test counts and baseline failures](entity-movement-203-validation.json)

## Reproduction

Use the pinned baseline and the optimized revision in separate checkouts. Copy
the exact same `SustainedEntityReplicationBenchTest.java` and
`scripts/entity-replication-bench.init.gradle` into the baseline checkout. Do not
copy the optimized production classes. The recorded benchmark source SHA-256 is
`8b3399abb6e3949cb2d116d8560daa8f22cce798136631cd7dea11b66076d518`.

In each checkout, compile the tests and export their normal runtime classpath:

```sh
./gradlew :common:testClasses :common:exportBenchmarkClasspath \
  -I scripts/entity-replication-bench.init.gradle --no-daemon --max-workers=2
```

Run the standalone benchmark with Java 26 and a fresh output file for each fork:

```sh
REPLICATION_JAVA=/path/to/jdk26/bin/java
"$REPLICATION_JAVA" -Xms512m -Xmx512m -XX:+UseG1GC \
  -XX:ActiveProcessorCount=2 -DCultPlatformOverride=BUKKIT \
  -cp "$(cat benchmark-runtime-classpath.txt)" \
  ac.cult.cultac.network.SustainedEntityReplicationBenchTest \
  /path/to/results/baseline-1.csv
```

Alternate checkouts for three forks each, changing the output filename. Compare
CPU and bytes per movement, take each fork's five-sample median, then take the
median across forks. Compare checksums and retain the individual fork ranges.
The optional `cult.replication.bench.warmupWaves`,
`cult.replication.bench.measuredWaves` and `cult.replication.bench.reps` system
properties allow a short functional smoke run; those short runs are not the
reported performance measurements.

For an opt-in JUnit run, set `CULT_SUSTAINED_REPLICATION_BENCH` to an output path
and select `*.SustainedEntityReplicationBenchTest` with `:common:test`. The init
script's `-PbenchmarkJava26=true` option selects a Java 26 test launcher while
keeping the repository's normal compilation toolchains. Put `--tests` options
immediately after `:common:test`, before any other task name.
