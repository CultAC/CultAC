package ac.cult.cultac.cloud.perf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.cloud.intake.v3.PerfAllocCalibration;
import ac.cult.cultac.cloud.intake.v3.PerfClassAlloc;
import ac.cult.cultac.cloud.intake.v3.PerfOutcome;
import ac.cult.cultac.cloud.intake.v3.PerfProfile;
import ac.cult.cultac.cloud.intake.v3.PerfStack;
import ac.cult.cultac.cloud.intake.v3.PerfThreadCpu;
import ac.cult.cultac.platform.api.PlatformServer;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import com.github.luben.zstd.Zstd;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.Test;

class PerfRecorderTest {

    private static final AgentInfo AGENT =
            new AgentInfo("paper", "test-server", "abc123", "1.0.0", "hash", "observe-only");
    private static final String BUSY = PerfRecorderTest.class.getName() + ".busyWork";
    private static final String CPU_TIME_BUSY = PerfRecorderTest.class.getName() + ".cpuTimeBusyWork";

    static volatile byte[] sink;

    @Test
    void recordsCpuAndAllocationOfABusyThread() throws Exception {
        FakePlatform platform = new FakePlatform(null, 5);
        PerfRecorder recorder = new PerfRecorder(platform, AGENT, Clock.systemUTC());
        Thread busy = Thread.ofPlatform().name("perf-test-busy").start(() -> busyWork(TimeUnit.SECONDS.toNanos(4)));
        PerfRecorder.Result result;
        try {
            result = recorder.record(new PerfSettings(3, 10, 20, 1000, 1 << 20, true, 45_000), () -> false);
        } finally {
            busy.join();
        }

        PerfRecorder.Result.Done done = assertInstanceOf(PerfRecorder.Result.Done.class, result, result::toString);
        assertTrue(done.zstdProfile().length <= 1 << 20);
        PerfProfile profile = PerfProfile.parseFrom(
                Zstd.decompress(done.zstdProfile(), (int) Zstd.getFrameContentSize(done.zstdProfile())));
        int busyFrame = profile.getFramesList().indexOf(BUSY);
        assertTrue(busyFrame >= 0, "frames must name " + BUSY);
        List<PerfStack> busyStacks = profile.getStacksList().stream()
                .filter(stack -> stack.getFramesList().contains(busyFrame))
                .toList();
        assertTrue(busyStacks.stream().mapToLong(PerfStack::getCpuSamples).sum() > 0);
        assertTrue(busyStacks.stream().mapToLong(PerfStack::getAllocBytes).sum() > 0);
        assertEquals("paper", profile.getPlatform());
        assertEquals("test-server", profile.getServerId());
        assertEquals("abc123", profile.getCommit());
        assertTrue(profile.getWholeJvm());
        assertTrue(profile.getWallNanos() >= TimeUnit.SECONDS.toNanos(3));
        assertTrue(profile.getPlayerSeconds() > 14 && profile.getPlayerSeconds() < 20, "" + profile.getPlayerSeconds());
        assertEquals(5, profile.getPlayersMax());
        assertEquals(0, profile.getTickMicrosP50P95MaxCount());
        assertTrue(profile.getCpuSamplesTotal() > 0);
        int perf = profile.getThreadGroupsList().indexOf("perf");
        String recorderFrames = PerfRecorder.class.getName();
        for (PerfStack stack : profile.getStacksList()) {
            List<String> frames = StackTrieTest.names(profile, stack);
            if (frames.stream()
                    .anyMatch(
                            frame -> frame.startsWith(recorderFrames + ".") || frame.startsWith(recorderFrames + "$")))
                assertEquals(perf, stack.getThreadGroup(), frames.toString());
        }
        assertTrue(
                profile.getAllocClassesList().stream()
                        .filter(PerfClassAlloc::getOwnStack)
                        .allMatch(type -> type.getClassName().equals("[B")),
                profile.getAllocClassesList().toString());
    }

    @Test
    void samplesCpuTimeAndJvmThreadsOnLinux() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"));
        PerfRecorder.cpuTimeFailed = false;
        boolean cpuTime = FlightRecorder.getFlightRecorder().getEventTypes().stream()
                .anyMatch(type -> type.getName().equals("jdk.CPUTimeSample"));
        PerfRecorder recorder = new PerfRecorder(new FakePlatform(null, 1), AGENT, Clock.systemUTC());
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        Thread busy = Thread.ofPlatform()
                .name("cult-cpu-time-busy")
                .start(() -> cpuTimeBusyWork(TimeUnit.SECONDS.toNanos(6)));
        PerfRecorder.Result result;
        long busyCpu;
        try {
            long busyCpuStart = threads.getThreadCpuTime(busy.threadId());
            result = recorder.record(new PerfSettings(3, 10, 20, 1000, 1 << 20, true, 45_000), () -> false);
            busyCpu = threads.getThreadCpuTime(busy.threadId()) - busyCpuStart;
        } finally {
            busy.join();
            PerfRecorder.cpuTimeFailed = false;
        }

        PerfRecorder.Result.Done done = assertInstanceOf(PerfRecorder.Result.Done.class, result, result::toString);
        PerfProfile profile = PerfProfile.parseFrom(
                Zstd.decompress(done.zstdProfile(), (int) Zstd.getFrameContentSize(done.zstdProfile())));
        if (cpuTime) assertEquals("cpu-time", profile.getCpuSampler());
        assertFalse(done.reason().contains("produced no samples"), done.reason());
        assertTrue(profile.getCpuSamplePeriodNanos() > 0);
        int busyFrame = profile.getFramesList().indexOf(CPU_TIME_BUSY);
        assertTrue(busyFrame >= 0, "frames must name " + CPU_TIME_BUSY);
        long busySamples = profile.getStacksList().stream()
                .filter(stack -> stack.getFramesList().contains(busyFrame))
                .mapToLong(PerfStack::getCpuSamples)
                .sum();
        double expected = (double) busyCpu / profile.getCpuSamplePeriodNanos();
        assertTrue(busySamples >= expected * 0.5, busySamples + " samples for " + expected + " periods of CPU");
        assertTrue(profile.getJvmThreadCpuList().stream()
                        .mapToLong(PerfThreadCpu::getNanos)
                        .sum()
                > 0);
        assertTrue(profile.getProcessUserNanos() > 0);
        PerfAllocCalibration own = profile.getAllocCalibrationList().stream()
                .filter(group -> group.getThreadGroup() == Attribution.OWN)
                .findFirst()
                .orElseThrow();
        assertTrue(own.getSampledBytes() > 0 && own.getExactBytes() > 0, own.toString());
        double factor = (double) own.getExactBytes() / own.getSampledBytes();
        assertTrue(factor >= 0.3 && factor <= 3, "factor " + factor);
    }

    @Test
    void recordsTheSamplerAndCalibrationEverywhere() throws Exception {
        PerfRecorder recorder = new PerfRecorder(new FakePlatform(null, 1), AGENT, Clock.systemUTC());
        Thread busy = Thread.ofPlatform().name("cult-test-busy").start(() -> busyWork(TimeUnit.SECONDS.toNanos(3)));
        PerfRecorder.Result result;
        try {
            result = recorder.record(new PerfSettings(2, 10, 20, 1000, 1 << 20, true, 45_000), () -> false);
        } finally {
            busy.join();
            PerfRecorder.cpuTimeFailed = false;
        }

        PerfRecorder.Result.Done done = assertInstanceOf(PerfRecorder.Result.Done.class, result, result::toString);
        PerfProfile profile = PerfProfile.parseFrom(
                Zstd.decompress(done.zstdProfile(), (int) Zstd.getFrameContentSize(done.zstdProfile())));
        assertTrue(List.of("cpu-time", "execution").contains(profile.getCpuSampler()), profile.getCpuSampler());
        assertTrue(
                profile.getAllocCalibrationList().stream()
                        .anyMatch(group -> group.getThreadGroup() == Attribution.OWN && group.getExactBytes() > 0),
                profile.getAllocCalibrationList().toString());
    }

    @Test
    void onlyVirtualThreadsAllocationSamplesTakeTheCarrierBound() throws Exception {
        Thread pool = Thread.ofPlatform()
                .name("ForkJoinPool-9-worker-1")
                .unstarted(() -> busyWork(TimeUnit.MILLISECONDS.toNanos(300)));
        Thread virtual = Thread.ofVirtual().unstarted(() -> busyWork(TimeUnit.MILLISECONDS.toNanos(300)));
        Path file = Files.createTempFile("perf-routing", ".jfr");
        List<RecordedEvent> events;
        try {
            try (Recording recording = new Recording()) {
                recording.enable("jdk.ObjectAllocationSample").with("throttle", "off");
                recording.start();
                pool.start();
                virtual.start();
                pool.join();
                virtual.join();
                recording.stop();
                recording.dump(file);
            }
            events = RecordingFile.readAllEvents(file);
        } finally {
            Files.deleteIfExists(file);
        }

        List<RecordedEvent> poolSamples = allocSamples(events, pool);
        List<RecordedEvent> virtualSamples = allocSamples(events, virtual);
        RecordedEvent poolEarliest = earliest(poolSamples);
        assertTrue(poolSamples.size() > 1 && poolEarliest.getLong("weight") > 0, poolSamples.toString());
        assertTrue(virtualSamples.size() > 1 && earliest(virtualSamples).getLong("weight") > 0);
        assertEquals(weight(poolSamples) - poolEarliest.getLong("weight"), kept(poolSamples, pool));
        assertEquals(weight(virtualSamples), kept(virtualSamples, virtual));
    }

    private static List<RecordedEvent> allocSamples(List<RecordedEvent> events, Thread thread) {
        return events.stream()
                .filter(event -> event.getEventType().getName().equals("jdk.ObjectAllocationSample"))
                .filter(event -> event.getThread() != null && event.getThread().getJavaThreadId() == thread.threadId())
                .toList();
    }

    /** The first delivered of the samples with the earliest start time. */
    private static RecordedEvent earliest(List<RecordedEvent> samples) {
        RecordedEvent earliest = samples.getFirst();
        for (RecordedEvent sample : samples) {
            if (sample.getStartTime().isBefore(earliest.getStartTime())) earliest = sample;
        }
        return earliest;
    }

    private static long weight(List<RecordedEvent> samples) {
        return samples.stream().mapToLong(sample -> sample.getLong("weight")).sum();
    }

    /** Sampled bytes a window keeps of {@code samples}, {@code thread} alive at its start and live threads unknown. */
    private static long kept(List<RecordedEvent> samples, Thread thread) {
        PerfRecorder.Window window = new PerfRecorder.Window(
                -1, Map.of(thread.threadId(), new PerfRecorder.ThreadBytes(thread.getName(), 0)), () -> null);
        for (RecordedEvent sample : samples) window.sample(sample, PerfRecorder.Window.ALLOC);
        window.calibrate(null);
        return window.trie.encode(true, 1 << 20).build().getAllocBytesTotal();
    }

    @Test
    void skipsWhenLagging() {
        long[] ticks = new long[100];
        Arrays.fill(ticks, TimeUnit.MILLISECONDS.toNanos(60));
        AtomicInteger streams = new AtomicInteger();
        PerfRecorder recorder = new PerfRecorder(new FakePlatform(ticks, 20), AGENT, Clock.systemUTC(), () -> {
            streams.incrementAndGet();
            return new RecordingStream();
        });

        PerfRecorder.Result result = recorder.record(settings(60), () -> false);

        PerfRecorder.Result.Skipped skipped = assertInstanceOf(PerfRecorder.Result.Skipped.class, result);
        assertEquals(PerfOutcome.PERF_OUTCOME_SKIPPED_LAG, skipped.outcome());
        assertEquals(0, streams.get());
    }

    @Test
    void lagGateComparesMeanNanosWithMicrosAndIgnoresZeros() {
        long[] filling = new long[100];
        Arrays.fill(filling, 0, 10, TimeUnit.MICROSECONDS.toNanos(45_100));
        assertEquals(TimeUnit.MICROSECONDS.toNanos(45_100), PerfRecorder.meanTickNanos(filling));
        assertTrue(PerfRecorder.lagging(filling, 45_000));
        Arrays.fill(filling, 0, 10, TimeUnit.MICROSECONDS.toNanos(44_900));
        assertFalse(PerfRecorder.lagging(filling, 45_000));

        assertEquals(-1, PerfRecorder.meanTickNanos(new long[100]));
        assertEquals(-1, PerfRecorder.meanTickNanos(new long[0]));
        assertEquals(-1, PerfRecorder.meanTickNanos(null));
        assertFalse(PerfRecorder.lagging(new long[100], 45_000));
        assertFalse(PerfRecorder.lagging(null, 45_000));
    }

    @Test
    void tickStatsIgnoreZerosAndStayEmptyWhenUnknown() {
        long[] ring = new long[100];
        for (int i = 0; i < 20; i++) ring[i * 5] = TimeUnit.MILLISECONDS.toNanos(20 - i);

        assertArrayEquals(new int[] {11_000, 19_000, 20_000}, PerfRecorder.tickMicros(List.of(ring)));
        assertArrayEquals(new int[0], PerfRecorder.tickMicros(List.of(new long[100], new long[0])));
        assertArrayEquals(new int[0], PerfRecorder.tickMicros(List.of()));
    }

    @Test
    void cancelStopsWithinASecond() {
        PerfRecorder recorder = new PerfRecorder(new FakePlatform(null, 1), AGENT, Clock.systemUTC());
        long start = System.nanoTime();

        PerfRecorder.Result result =
                recorder.record(settings(60), () -> System.nanoTime() - start > TimeUnit.MILLISECONDS.toNanos(500));

        long took = System.nanoTime() - start;
        PerfRecorder.Result.Skipped skipped = assertInstanceOf(PerfRecorder.Result.Skipped.class, result);
        assertEquals(PerfOutcome.PERF_OUTCOME_FAILED, skipped.outcome());
        assertEquals("cancelled", skipped.reason());
        assertTrue(took < TimeUnit.MILLISECONDS.toNanos(1500), "took " + TimeUnit.NANOSECONDS.toMillis(took) + " ms");
    }

    @Test
    void aCappedTrieNamesItsCapInTheReason() {
        StackTrie trie = new StackTrie();
        for (int i = 0; trie.nodes() < StackTrie.MAX_NODES; i++) {
            trie.add(StackTrieTest.stack("x.Root.run", "y.Leaf.m" + i), Attribution.OTHER, 1, 0, 0);
        }
        for (int i = 0; i < 3; i++) {
            trie.add(StackTrieTest.stack("x.Root.run", "z.New.m" + i), Attribution.OTHER, 1, 0, 0);
        }

        PerfRecorder.Result result =
                PerfRecorder.profile(trie, PerfProfile.getDefaultInstance(), settings(60), () -> false);

        PerfRecorder.Result.Done done = assertInstanceOf(PerfRecorder.Result.Done.class, result, result::toString);
        assertEquals("nodes=" + StackTrie.MAX_NODES + " capped=3", done.reason());
    }

    @Test
    void anUncappedTrieHasNoReason() {
        StackTrie trie = new StackTrie();
        trie.add(StackTrieTest.stack("x.Root.run", "y.Leaf.m"), Attribution.OTHER, 1, 0, 0);

        PerfRecorder.Result result =
                PerfRecorder.profile(trie, PerfProfile.getDefaultInstance(), settings(60), () -> false);

        assertEquals(
                "", assertInstanceOf(PerfRecorder.Result.Done.class, result).reason());
    }

    @Test
    void cancelDuringEncodeEndsWithoutAProfile() {
        StackTrie trie = new StackTrie();
        trie.add(StackTrieTest.stack("x.Root.run", "y.Leaf.m"), Attribution.OTHER, 1, 0, 0);
        for (int cancelAt = 1; cancelAt <= 3; cancelAt++) {
            AtomicInteger checks = new AtomicInteger();
            int at = cancelAt;

            PerfRecorder.Result result = PerfRecorder.profile(
                    trie, PerfProfile.getDefaultInstance(), settings(60), () -> checks.incrementAndGet() >= at);

            PerfRecorder.Result.Skipped skipped =
                    assertInstanceOf(PerfRecorder.Result.Skipped.class, result, "cancel at check " + at);
            assertEquals(PerfOutcome.PERF_OUTCOME_FAILED, skipped.outcome());
            assertEquals("cancelled", skipped.reason());
        }
    }

    @Test
    void jfrInternalErrorIsAResultNotAThrow() {
        PerfRecorder recorder = new PerfRecorder(new FakePlatform(null, 1), AGENT, Clock.systemUTC(), () -> {
            throw new InternalError("jfr broke");
        });

        PerfRecorder.Result result = recorder.record(settings(60), () -> false);

        PerfRecorder.Result.Skipped skipped = assertInstanceOf(PerfRecorder.Result.Skipped.class, result);
        assertEquals(PerfOutcome.PERF_OUTCOME_FAILED, skipped.outcome());
        assertTrue(skipped.reason().contains("jfr broke"), skipped.reason());
    }

    @Test
    void tickReadFailureIsAResultNotAThrow() {
        PerfRecorder recorder = new PerfRecorder(
                new FakePlatform(null, 1) {
                    @Override
                    public long[] recentTickNanos() {
                        throw new IllegalStateException("no ticks");
                    }
                },
                AGENT,
                Clock.systemUTC());

        PerfRecorder.Result result = recorder.record(settings(60), () -> false);

        PerfRecorder.Result.Skipped skipped = assertInstanceOf(PerfRecorder.Result.Skipped.class, result);
        assertEquals(PerfOutcome.PERF_OUTCOME_FAILED, skipped.outcome());
    }

    @Test
    void jfrFailureIsAResultNotAThrow() {
        PerfRecorder recorder = new PerfRecorder(new FakePlatform(null, 1), AGENT, Clock.systemUTC(), () -> {
            throw new IllegalStateException("flight recorder is disabled");
        });

        PerfRecorder.Result result = recorder.record(settings(60), () -> false);

        PerfRecorder.Result.Skipped skipped = assertInstanceOf(PerfRecorder.Result.Skipped.class, result);
        assertEquals(PerfOutcome.PERF_OUTCOME_FAILED, skipped.outcome());
        assertTrue(skipped.reason().contains("flight recorder is disabled"), skipped.reason());
    }

    @Test
    void ownAllocationLeavesOutTheRecordingThread() throws Exception {
        assumeTrue(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean threads
                && threads.isThreadAllocatedMemorySupported()
                && threads.isThreadAllocatedMemoryEnabled());
        CountDownLatch done = new CountDownLatch(1);
        Thread sibling = Thread.ofPlatform().daemon().name("cult-test-sibling").start(() -> {
            try {
                done.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        AtomicReference<Map<Long, Long>> bytes = new AtomicReference<>();
        Thread recording =
                Thread.ofPlatform().name("cult-perf").start(() -> bytes.set(PerfRecorder.ownAllocatedBytes()));
        recording.join(5_000);
        done.countDown();
        sibling.join(5_000);

        assertFalse(bytes.get().containsKey(recording.threadId()), bytes.get().toString());
        assertTrue(bytes.get().containsKey(sibling.threadId()), bytes.get().toString());
    }

    @Test
    void jvmFlagsKeepOnlySwitchesAndNumbers() {
        List<String> arguments = List.of(
                "-XX:+UseG1GC",
                "-XX:OnOutOfMemoryError=kill -9 %p",
                "-XX:MaxGCPauseMillis=200",
                "-XX:OnError=/bin/x",
                "-XX:G1HeapRegionSize=16M",
                "-XX:HeapDumpPath=/data/dumps",
                "-Xmx8G",
                "-XX:ErrorFile=/tmp/e.log",
                "-Xss1m",
                "-Dsecret=1",
                "-XX:-UseBiasedLocking");

        assertEquals(
                List.of(
                        "-XX:+UseG1GC",
                        "-XX:MaxGCPauseMillis=200",
                        "-XX:G1HeapRegionSize=16M",
                        "-Xmx8G",
                        "-Xss1m",
                        "-XX:-UseBiasedLocking"),
                PerfRecorder.jvmFlags(arguments));
    }

    private static PerfSettings settings(int seconds) {
        return new PerfSettings(seconds, 10, 20, 300, 1 << 20, true, 45_000);
    }

    static void cpuTimeBusyWork(long nanos) {
        busyWork(nanos);
    }

    static void busyWork(long nanos) {
        long end = System.nanoTime() + nanos;
        long spin = 0;
        while (System.nanoTime() < end) {
            for (int i = 0; i < 1_000; i++) spin += i * 31L ^ spin;
            byte[] bytes = new byte[1024];
            bytes[(int) (spin & 1023)] = (byte) spin;
            sink = bytes;
        }
    }

    private static class FakePlatform implements PlatformServer {
        private final long[] ticks;
        private final int players;

        FakePlatform(long[] ticks, int players) {
            this.ticks = ticks;
            this.players = players;
        }

        @Override
        public long[] recentTickNanos() {
            return ticks == null ? null : ticks.clone();
        }

        @Override
        public int onlinePlayers() {
            return players;
        }

        @Override
        public String getPlatformImplementationString() {
            return "fake";
        }

        @Override
        public void dispatchCommand(Sender sender, String command) {}

        @Override
        public Sender getConsoleSender() {
            return null;
        }

        @Override
        public void registerOutgoingPluginChannel(String name) {}

        @Override
        public void forwardAlert(String message) {}

        @Override
        public boolean isProxyForwardingEnabled() {
            return false;
        }

        @Override
        public void applyValidationBlock(PlatformPlayer player, int x, int y, int z, String state, Runnable applied) {}

        @Override
        public double getTPS() {
            return 20;
        }
    }
}
