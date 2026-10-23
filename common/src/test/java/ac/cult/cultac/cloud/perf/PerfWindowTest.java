package ac.cult.cultac.cloud.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.PerfAllocCalibration;
import ac.cult.cultac.cloud.intake.v3.PerfClassAlloc;
import ac.cult.cultac.cloud.intake.v3.PerfOutcome;
import ac.cult.cultac.cloud.intake.v3.PerfProfile;
import ac.cult.cultac.cloud.intake.v3.PerfStack;
import com.sun.management.ThreadMXBean;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PerfWindowTest {

    private static final int OWN_GROUP = Attribution.OWN;
    private static final StackTrie.StackTraceView OWN = StackTrieTest.stack("ac.cult.cultac.A.run", "x.B.alloc");
    private static final StackTrie.StackTraceView FOREIGN = StackTrieTest.stack("x.C.run", "x.D.alloc");

    @AfterEach
    void resetFallback() {
        PerfRecorder.cpuTimeFailed = false;
    }

    @Test
    void dropsEachThreadsFirstAllocationSample() {
        PerfRecorder.Window window = sampledWindow();

        PerfRecorder.Window.Calibration calibration = window.calibrate(null);

        PerfProfile profile = window.trie.encode(true, 1 << 20).build();
        assertEquals(1_000, profile.getAllocBytesTotal());
        assertEquals(Map.of("x.B.alloc", 400L, "x.D.alloc", 600L), allocByLeaf(profile));
        assertEquals(Map.of("[B", 400L, "Foo", 600L), classBytes(window));
        assertFalse(calibration.calibrated());
        assertEquals(List.of(calibration(OWN_GROUP, 1_000, 0)), calibration.groups());
    }

    @Test
    void dropsEachThreadsEarliestAllocationSampleInAnyDeliveryOrder() {
        PerfRecorder.Window window = reorderedWindow();

        PerfRecorder.Window.Calibration calibration = window.calibrate(null);

        PerfProfile profile = window.trie.encode(true, 1 << 20).build();
        assertEquals(1_000, profile.getAllocBytesTotal());
        assertEquals(Map.of("x.B.alloc", 400L, "x.D.alloc", 600L), allocByLeaf(profile));
        assertEquals(Map.of("[B", 400L, "Foo", 600L), classBytes(window));
        assertEquals(List.of(calibration(OWN_GROUP, 1_000, 0)), calibration.groups());
    }

    @Test
    void ofTwoSamplesAtOneTimeTheFirstDeliveredIsDropped() {
        PerfRecorder.Window window = new PerfRecorder.Window(-1);
        window.alloc(OWN, OWN_GROUP, 1, false, 5, "[B", 1_000_000);
        window.alloc(FOREIGN, OWN_GROUP, 1, false, 5, "Foo", 70);

        window.calibrate(null);

        assertEquals(
                Map.of("x.D.alloc", 70L),
                allocByLeaf(window.trie.encode(true, 1 << 20).build()));
    }

    @Test
    void scalesStacksAndClassesToTheExactBytesOfTheirGroup() {
        PerfRecorder.Window window = sampledWindow();
        long[] exact = new long[Attribution.GROUPS.size()];
        exact[OWN_GROUP] = 4_000;
        exact[Attribution.OTHER] = 500;

        PerfRecorder.Window.Calibration calibration = window.calibrate(exact);

        PerfProfile profile = window.trie.encode(true, 1 << 20).build();
        assertEquals(Map.of("x.B.alloc", 1_600L, "x.D.alloc", 2_400L), allocByLeaf(profile));
        assertEquals(4_500, profile.getAllocBytesTotal());
        assertEquals(Map.of("[B", 1_600L, "Foo", 2_400L), classBytes(window));
        assertTrue(calibration.calibrated());
        assertEquals(
                List.of(calibration(OWN_GROUP, 1_000, 4_000), calibration(Attribution.OTHER, 0, 500)),
                calibration.groups());
    }

    @Test
    void partnerModeNamesPartnerAllocationClassesOther() {
        PerfRecorder.Window window = new PerfRecorder.Window(-1);
        window.alloc(OWN, OWN_GROUP, -1, false, 1, "[B", 100);
        window.alloc(OWN, OWN_GROUP, -1, false, 2, "[Ljava.lang.String;", 50);
        window.alloc(OWN, OWN_GROUP, -1, false, 3, "partner.Secret", 30);
        window.alloc(OWN, OWN_GROUP, -1, false, 4, "[Lpartner.Secret;", 20);
        window.alloc(OWN, OWN_GROUP, -1, false, 5, null, 5);
        window.alloc(FOREIGN, OWN_GROUP, -1, false, 6, "partner.Elsewhere", 1_000);

        Map<String, Long> partner = window.topClasses(false).stream()
                .collect(Collectors.toMap(PerfClassAlloc::getClassName, PerfClassAlloc::getBytes));

        assertEquals(Map.of("[B", 100L, "[Ljava.lang.String;", 50L, "(other)", 55L), partner);
        assertEquals(
                Map.of(
                        "[B", 100L,
                        "[Ljava.lang.String;", 50L,
                        "partner.Secret", 30L,
                        "[Lpartner.Secret;", 20L,
                        "(other)", 5L,
                        "partner.Elsewhere", 1_000L),
                classBytes(window));
    }

    @Test
    void aGroupWithoutExactBytesIsLeftUncalibrated() {
        PerfRecorder.Window window = sampledWindow();
        window.alloc(FOREIGN, Attribution.NETTY, 7, false, 10, "Foo", 9_999);
        window.alloc(FOREIGN, Attribution.NETTY, 7, false, 11, "Foo", 50);
        long[] exact = new long[Attribution.GROUPS.size()];
        exact[OWN_GROUP] = 2_000;

        PerfRecorder.Window.Calibration calibration = window.calibrate(exact);

        assertFalse(calibration.calibrated());
        assertEquals(2_050, window.trie.encode(true, 1 << 20).build().getAllocBytesTotal());
    }

    @Test
    void samplesWithoutAThreadAreKept() {
        PerfRecorder.Window window = new PerfRecorder.Window(-1);
        window.alloc(FOREIGN, Attribution.OTHER, -1, false, 2, "Foo", 70);
        window.alloc(FOREIGN, Attribution.OTHER, -1, false, 1, "Foo", 30);

        window.calibrate(null);

        assertEquals(100, window.trie.encode(true, 1 << 20).build().getAllocBytesTotal());
    }

    @Test
    void aHeldSampleKeepsItsFramesWhenTheStreamReusesTheView() {
        StackTrie.StackTraceView[] current = {OWN};
        StackTrie.StackTraceView reused = new StackTrie.StackTraceView() {
            @Override
            public int size() {
                return current[0].size();
            }

            @Override
            public String type(int fromLeaf) {
                return current[0].type(fromLeaf);
            }

            @Override
            public String method(int fromLeaf) {
                return current[0].method(fromLeaf);
            }
        };
        PerfRecorder.Window window = new PerfRecorder.Window(-1);

        window.alloc(reused, OWN_GROUP, 1, false, 5, "[B", 400);
        current[0] = FOREIGN;
        window.alloc(reused, OWN_GROUP, 1, false, 1, "Foo", 1_000_000);
        window.calibrate(null);

        assertEquals(
                Map.of("x.B.alloc", 400L),
                allocByLeaf(window.trie.encode(true, 1 << 20).build()));
    }

    @Test
    void aThreadStartedAfterTheStartSnapshotKeepsItsEarliestSample() {
        PerfRecorder.Window window =
                new PerfRecorder.Window(-1, Map.of(1L, new PerfRecorder.ThreadBytes("cult-a", 0)), Map::of);

        window.alloc(OWN, OWN_GROUP, 1, false, 1, "[B", 1_000_000);
        window.alloc(OWN, OWN_GROUP, 1, false, 2, "[B", 100);
        window.alloc(FOREIGN, OWN_GROUP, 2, false, 2, "Foo", 600);
        window.alloc(FOREIGN, OWN_GROUP, 2, false, 1, "Foo", 300);
        window.calibrate(null);

        assertEquals(
                Map.of("x.B.alloc", 100L, "x.D.alloc", 900L),
                allocByLeaf(window.trie.encode(true, 1 << 20).build()));
    }

    @Test
    void aCarrierWeighedSampleHeavierThanAnyCarriersBytesSinceTheStartIsDropped() {
        long carrierGrowth = 100L << 20;
        Map<Long, PerfRecorder.ThreadBytes> start = Map.of(
                10L, new PerfRecorder.ThreadBytes("ForkJoinPool-1-worker-1", 5_000_000_000L),
                11L, new PerfRecorder.ThreadBytes("Netty Server IO #0", 0));
        Map<Long, PerfRecorder.ThreadBytes> now = Map.of(
                10L, new PerfRecorder.ThreadBytes("ForkJoinPool-1-worker-1", 5_000_000_000L + carrierGrowth),
                11L, new PerfRecorder.ThreadBytes("Netty Server IO #0", 50_000_000_000L));
        AtomicInteger reads = new AtomicInteger();
        PerfRecorder.Window window = new PerfRecorder.Window(-1, start, () -> {
            reads.incrementAndGet();
            return now;
        });
        long bound = carrierGrowth + PerfRecorder.Window.ALLOC_SLACK;

        window.alloc(OWN, Attribution.OTHER, 100, true, 2, "[B", 2_000);
        window.alloc(OWN, Attribution.OTHER, 100, true, 3, "[B", bound);
        window.alloc(FOREIGN, Attribution.OTHER, 101, true, 1, "Foo", bound + 1);
        window.alloc(FOREIGN, Attribution.OTHER, 102, true, 4, "Foo", 300);
        window.calibrate(null);

        assertEquals(Map.of("[B", 2_000 + bound, "Foo", 300L), classBytes(window));
        assertEquals(2, reads.get());
    }

    @Test
    void aCarrierStartedInTheWindowCountsFromZero() {
        Map<Long, PerfRecorder.ThreadBytes> start =
                Map.of(10L, new PerfRecorder.ThreadBytes("ForkJoinPool-1-worker-1", 1_000_000_000L));
        Map<Long, PerfRecorder.ThreadBytes> now = Map.of(
                10L, new PerfRecorder.ThreadBytes("ForkJoinPool-1-worker-1", 1_001_000_000L),
                12L, new PerfRecorder.ThreadBytes("ForkJoinPool-1-worker-2", 500L << 20));
        PerfRecorder.Window window = new PerfRecorder.Window(-1, start, () -> now);

        window.alloc(OWN, Attribution.OTHER, 100, true, 1, "[B", 400L << 20);
        window.alloc(FOREIGN, Attribution.OTHER, 100, true, 2, "Foo", 600L << 20);
        window.calibrate(null);

        assertEquals(
                Map.of("x.B.alloc", 400L << 20),
                allocByLeaf(window.trie.encode(true, 1 << 20).build()));
    }

    @Test
    void aCarrierWeighedSampleIsKeptWhenLiveThreadsAreUnknown() {
        PerfRecorder.Window window = new PerfRecorder.Window(-1, Map.of(), () -> null);

        window.alloc(OWN, Attribution.OTHER, 100, true, 1, "[B", 1_000_000_000_000L);
        window.calibrate(null);

        assertEquals(
                1_000_000_000_000L, window.trie.encode(true, 1 << 20).build().getAllocBytesTotal());
    }

    @Test
    void withoutAStartSnapshotCarrierWeighedSamplesAreHeldLikeTheOthers() {
        PerfRecorder.Window window = new PerfRecorder.Window(-1);

        window.alloc(OWN, Attribution.OTHER, 100, true, 2, "[B", 70);
        window.alloc(FOREIGN, Attribution.OTHER, 100, true, 1, "Foo", 1_000_000_000_000L);
        window.calibrate(null);

        assertEquals(
                Map.of("x.B.alloc", 70L),
                allocByLeaf(window.trie.encode(true, 1 << 20).build()));
    }

    @Test
    void carriersAreTheDefaultFactoryPoolWorkers() {
        assertTrue(PerfRecorder.carrier("ForkJoinPool-1-worker-1"));
        assertFalse(PerfRecorder.carrier("ForkJoinPool.commonPool-worker-1"));
        assertFalse(PerfRecorder.carrier("Netty Server IO #0"));
        assertFalse(PerfRecorder.carrier(null));
    }

    @Test
    void exactBytesAreTheGrowthOfThreadsAliveAtTheEnd() {
        Map<Long, PerfRecorder.ThreadBytes> start = Map.of(
                1L, new PerfRecorder.ThreadBytes("cult-a", 100),
                2L, new PerfRecorder.ThreadBytes("cult-gone", 900),
                3L, new PerfRecorder.ThreadBytes("Server thread", 1_000));
        Map<Long, PerfRecorder.ThreadBytes> end = Map.of(
                1L, new PerfRecorder.ThreadBytes("cult-a", 350),
                3L, new PerfRecorder.ThreadBytes("Server thread", 1_400),
                4L, new PerfRecorder.ThreadBytes("cult-new", 60),
                5L, new PerfRecorder.ThreadBytes("JFR dispatch", 80));

        long[] exact = PerfRecorder.exactAllocated(start, end, 9, 5);

        assertEquals(310, exact[OWN_GROUP]);
        assertEquals(400, exact[Attribution.TICK]);
        assertEquals(80, exact[Attribution.PERF]);
        assertEquals(0, exact[Attribution.OTHER]);
    }

    @Test
    void cpuTimeSamplingNeedsLinuxAndTheEvent() {
        List<String> events = List.of("jdk.ExecutionSample", "jdk.CPUTimeSample");

        assertEquals(PerfRecorder.CPU_TIME, PerfRecorder.cpuSampler("Linux", events));
        assertEquals(PerfRecorder.EXECUTION, PerfRecorder.cpuSampler("Windows 11", events));
        assertEquals(PerfRecorder.EXECUTION, PerfRecorder.cpuSampler("Linux", List.of("jdk.ExecutionSample")));
    }

    @Test
    void aCpuTimeWindowWithoutSamplesNamesIt() {
        assertEquals("cpu-time sampler produced no samples", PerfRecorder.samplerReason(PerfRecorder.CPU_TIME, 0, 0));
        assertFalse(PerfRecorder.cpuTimeFailed);
    }

    @Test
    void afterAFailedCpuTimeWindowExecutionSamples() {
        PerfRecorder.cpuTimeFailed = true;

        assertEquals(
                PerfRecorder.EXECUTION,
                PerfRecorder.cpuSampler("Linux", List.of("jdk.ExecutionSample", "jdk.CPUTimeSample")));
    }

    @Test
    void theSamplerReasonJoinsTheNodeCapReason() {
        StackTrie trie = new StackTrie();
        for (int i = 0; trie.nodes() < StackTrie.MAX_NODES; i++) {
            trie.add(StackTrieTest.stack("x.Root.run", "y.Leaf.m" + i), Attribution.OTHER, 1, 0, 0);
        }
        trie.add(StackTrieTest.stack("x.Root.run", "z.New.m"), Attribution.OTHER, 1, 0, 0);
        PerfSettings settings = new PerfSettings(60, 10, 20, 300, 1 << 20, true, 45_000);
        String reason = "cpu-time lost 9 of 10";

        PerfRecorder.Result capped = PerfRecorder.withReason(
                PerfRecorder.profile(trie, PerfProfile.getDefaultInstance(), settings, () -> false), reason);
        PerfRecorder.Result plain = PerfRecorder.withReason(new PerfRecorder.Result.Done(new byte[0]), reason);
        PerfRecorder.Result skipped = PerfRecorder.withReason(
                new PerfRecorder.Result.Skipped(PerfOutcome.PERF_OUTCOME_FAILED, "cancelled"), reason);

        assertEquals(
                "nodes=" + StackTrie.MAX_NODES + " capped=1; " + reason,
                assertInstanceOf(PerfRecorder.Result.Done.class, capped).reason());
        assertEquals(
                reason, assertInstanceOf(PerfRecorder.Result.Done.class, plain).reason());
        assertEquals(
                "cancelled",
                assertInstanceOf(PerfRecorder.Result.Skipped.class, skipped).reason());
    }

    @Test
    void aFailingAllocationSnapshotIsUnknown() {
        ThreadMXBean failing = (ThreadMXBean) Proxy.newProxyInstance(
                ThreadMXBean.class.getClassLoader(), new Class<?>[] {ThreadMXBean.class}, (proxy, method, args) -> {
                    if (method.getReturnType() == boolean.class) return true;
                    throw new UnsupportedOperationException(method.getName());
                });

        assertNull(PerfRecorder.threadAllocatedBytes(failing));
    }

    @Test
    void lostCpuTimeSamplesOverATenthAreNamed() {
        assertEquals("", PerfRecorder.samplerReason(PerfRecorder.CPU_TIME, 1_000, 100));
        assertEquals("cpu-time lost 101 of 1000", PerfRecorder.samplerReason(PerfRecorder.CPU_TIME, 1_000, 101));
        assertEquals("", PerfRecorder.samplerReason(PerfRecorder.EXECUTION, 0, 0));
        assertFalse(PerfRecorder.cpuTimeFailed);
        assertEquals(
                "cpu-time sampler produced no samples; cpu-time lost 5 of 0",
                PerfRecorder.samplerReason(PerfRecorder.CPU_TIME, 0, 5));
    }

    /** Two anticheat threads: thread 1's first sample (1e12) and thread 2's (5e11) are dropped, 1000 bytes stay. */
    private static PerfRecorder.Window sampledWindow() {
        PerfRecorder.Window window = new PerfRecorder.Window(-1);
        window.alloc(OWN, OWN_GROUP, 1, false, 1, "[B", 1_000_000_000_000L);
        window.alloc(FOREIGN, OWN_GROUP, 2, false, 2, "Foo", 500_000_000_000L);
        window.alloc(OWN, OWN_GROUP, 1, false, 3, "[B", 100);
        window.alloc(OWN, OWN_GROUP, 1, false, 4, "[B", 300);
        window.alloc(FOREIGN, OWN_GROUP, 2, false, 5, "Foo", 600);
        return window;
    }

    /** {@link #sampledWindow}'s samples delivered as JFR may deliver them, each thread's earliest not first. */
    private static PerfRecorder.Window reorderedWindow() {
        PerfRecorder.Window window = new PerfRecorder.Window(-1);
        window.alloc(OWN, OWN_GROUP, 1, false, 3, "[B", 100);
        window.alloc(FOREIGN, OWN_GROUP, 2, false, 5, "Foo", 600);
        window.alloc(OWN, OWN_GROUP, 1, false, 4, "[B", 300);
        window.alloc(OWN, OWN_GROUP, 1, false, 1, "[B", 1_000_000_000_000L);
        window.alloc(FOREIGN, OWN_GROUP, 2, false, 2, "Foo", 500_000_000_000L);
        return window;
    }

    private static PerfAllocCalibration calibration(int group, long sampled, long exact) {
        return PerfAllocCalibration.newBuilder()
                .setThreadGroup(group)
                .setSampledBytes(sampled)
                .setExactBytes(exact)
                .build();
    }

    private static Map<String, Long> allocByLeaf(PerfProfile profile) {
        return profile.getStacksList().stream()
                .filter(stack -> stack.getAllocBytes() > 0)
                .collect(Collectors.toMap(
                        stack -> StackTrieTest.names(profile, stack).getLast(), PerfStack::getAllocBytes, Long::sum));
    }

    private static Map<String, Long> classBytes(PerfRecorder.Window window) {
        return window.topClasses(true).stream()
                .collect(Collectors.toMap(PerfClassAlloc::getClassName, PerfClassAlloc::getBytes, Long::sum));
    }
}
