package ac.cult.cultac.cloud.perf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.cloud.intake.v3.PerfMachineLoad;
import ac.cult.cultac.cloud.intake.v3.PerfThreadCpu;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcCpuTest {

    private static final long TICK = 10_000_000L;

    @TempDir
    Path root;

    @Test
    void readsUserAndSysPastACommWithSpacesAndParens() throws IOException {
        Files.createDirectories(root.resolve("self/task"));
        Files.writeString(root.resolve("self/stat"), stat("java (x) y", 150, 50));
        ProcCpu proc = new ProcCpu(root);
        ProcCpu.Snapshot start = proc.read();
        Files.writeString(root.resolve("self/stat"), stat("java (x) y", 250, 80));

        ProcCpu.Snapshot end = proc.read();

        assertEquals(100 * TICK, ProcCpu.userNanos(start, end));
        assertEquals(30 * TICK, ProcCpu.sysNanos(start, end));
    }

    @Test
    void anUnreadableStatLeavesUserAndSysAtZero() throws IOException {
        Files.createDirectories(root.resolve("self/task"));
        Files.writeString(root.resolve("self/stat"), stat("java", 150, 50));
        ProcCpu proc = new ProcCpu(root);
        ProcCpu.Snapshot start = proc.read();
        Files.writeString(root.resolve("self/stat"), "garbage");

        ProcCpu.Snapshot end = proc.read();

        assertEquals(0, ProcCpu.userNanos(start, end));
        assertEquals(0, ProcCpu.sysNanos(start, end));
    }

    @Test
    void sumsJvmThreadCpuByKindAndLeavesJavaThreadsOut() throws IOException {
        task(1, "GC Thread#0", 10, 5);
        task(2, "C2 CompilerThre", 100, 0);
        task(3, "VM Thread", 4, 4);
        task(4, "JFR Recorder T", 1, 1);
        task(5, "Server thread", 1_000, 100);
        task(7, "G1 Conc#0", 0, 0);
        Files.writeString(root.resolve("self/task/8"), "not a directory");
        Files.writeString(root.resolve("self/stat"), stat("java", 0, 0));
        ProcCpu proc = new ProcCpu(root);
        ProcCpu.Snapshot start = proc.read();
        Files.delete(root.resolve("self/task/7/stat"));
        Files.delete(root.resolve("self/task/7/comm"));
        Files.delete(root.resolve("self/task/7"));
        task(1, "GC Thread#0", 30, 5);
        task(2, "C2 CompilerThre", 400, 0);
        task(3, "VM Thread", 6, 4);
        task(4, "JFR Recorder T", 3, 1);
        task(5, "Server thread", 5_000, 100);
        task(6, "G1 Conc#0", 7, 3);
        Files.createDirectories(root.resolve("self/task/9"));
        Files.writeString(root.resolve("self/task/9/comm"), "GC Thread#1\n");
        Files.writeString(root.resolve("self/task/9/stat"), "9 (GC Thread#1) S 1");

        List<PerfThreadCpu> cpu = ProcCpu.threadCpu(start, proc.read());

        assertEquals(
                List.of(
                        threadCpu("gc", 30 * TICK, 2),
                        threadCpu("jit", 300 * TICK, 1),
                        threadCpu("vm", 2 * TICK, 1),
                        threadCpu("jfr", 2 * TICK, 1)),
                cpu);
    }

    @Test
    void classifiesByCommPrefix() {
        assertEquals("gc", ProcCpu.kind("ZGC Driver"));
        assertEquals("gc", ProcCpu.kind("Shenandoah"));
        assertEquals("jit", ProcCpu.kind("JVMCI-native Co"));
        assertEquals("vm", ProcCpu.kind("Notification Th"));
        assertEquals("vm", ProcCpu.kind("Monitor Deflati"));
        assertEquals(null, ProcCpu.kind("Netty Epoll Ser"));
        assertEquals(null, ProcCpu.kind("cult-perf"));
        assertEquals("jfr", ProcCpu.kind("JFR Periodic Ta"));
        assertEquals(null, ProcCpu.kind("JFR Event Strea"));
    }

    @Test
    void classifiesNamesShortenedInTheMiddle() {
        assertEquals("gc", ProcCpu.kind("ShenWorker#11"));
        assertEquals("gc", ProcCpu.kind("ShenControl"));
        assertEquals("gc", ProcCpu.kind("ShenRegulator"));
        assertEquals("gc", ProcCpu.kind("ShenUncommit"));
        assertEquals("gc", ProcCpu.kind("Safepoi..read#0"));
        assertEquals("jit", ProcCpu.kind("C2 Comp..hread0"));
        assertEquals("jit", ProcCpu.kind("C1 Comp..read12"));
        assertEquals("vm", ProcCpu.kind("VM Peri..Thread"));
        assertEquals("vm", ProcCpu.kind("Monitor..Thread"));
        assertEquals("vm", ProcCpu.kind("Notific..Thread"));
        assertEquals("jfr", ProcCpu.kind("JFR Rec..Thread"));
        assertEquals(null, ProcCpu.kind("JFR Eve..ream 1"));
        assertEquals(null, ProcCpu.kind("Shenanigans"));
    }

    @Test
    void sumsSchedulerCountersOfThreadsAliveAtTheEnd() throws IOException {
        sched(1, "1000 5000000 10", 100, 5);
        sched(3, "0 1000000 2", 1, 1);
        ProcCpu proc = new ProcCpu(root);
        ProcCpu.Snapshot start = proc.read();
        sched(1, "2000 9000000 15", 130, 9);
        Files.createDirectories(root.resolve("self/task/2"));
        delete(root.resolve("self/task/3"));
        sched(4, "0 2000000 3", 7, 2);

        ProcCpu.Snapshot end = proc.read();

        assertArrayEquals(new long[] {6_000_000, 8, 37, 6}, ProcCpu.schedDelta(start, end));
        PerfMachineLoad load =
                new MachineLoad(root, root.resolve("sys")).window(MachineLoad.NONE, MachineLoad.NONE, start, end);
        assertEquals(6, load.getRunQueueWaitMillis());
        assertEquals(8, load.getTimeslices());
        assertEquals(37, load.getVoluntarySwitches());
        assertEquals(6, load.getInvoluntarySwitches());
    }

    @Test
    void aKernelWithoutSchedstatReportsOnlySwitches() throws IOException {
        sched(1, null, 100, 5);
        ProcCpu proc = new ProcCpu(root);
        ProcCpu.Snapshot start = proc.read();
        sched(1, null, 110, 6);

        assertArrayEquals(new long[] {-1, -1, 10, 1}, ProcCpu.schedDelta(start, proc.read()));
    }

    @Test
    void anUnlistableTaskDirectoryLeavesEveryCounterUnknown() throws IOException {
        ProcCpu proc = new ProcCpu(root);
        ProcCpu.Snapshot start = proc.read();
        sched(1, "1 2 3", 4, 5);

        assertArrayEquals(new long[] {-1, -1, -1, -1}, ProcCpu.schedDelta(start, proc.read()));
        assertArrayEquals(new long[] {-1, -1, -1, -1}, ProcCpu.schedDelta(ProcCpu.NONE, ProcCpu.NONE));
    }

    @Test
    void parsesSchedstatAndStatusLeavingShortOrBadValuesUnknown() {
        assertEquals(new ProcCpu.Sched(20, 3, -1, -1), parse("10 20 3\n", null));
        assertEquals(new ProcCpu.Sched(-1, -1, 4, -1), parse("10 20\n", "voluntary_ctxt_switches:\t4\n"));
        assertEquals(
                new ProcCpu.Sched(-1, -1, -1, 9),
                parse("a b c", "voluntary_ctxt_switches:\tx\nnonvoluntary_ctxt_switches:\t9\n"));
        assertEquals(
                new ProcCpu.Sched(0, 7, 12, 0),
                parse("5 0 7", "voluntary_ctxt_switches:\t12\r\nnonvoluntary_ctxt_switches: 0"));
        assertEquals(
                new ProcCpu.Sched(-1, -1, -1, 2),
                parse(
                        "1 99999999999999999999 3",
                        "x\n\nnonvoluntary_ctxt_switches:\t2\nvoluntary_ctxt_switches:\t-4\n"));
        assertEquals(null, parse(null, "Name:\tjava\n"));
        assertEquals(null, parse("", ""));
    }

    @Test
    void aSchedstatOfZerosIsAKernelWithoutSchedulerStatistics() {
        assertEquals(
                new ProcCpu.Sched(-1, -1, 4, 1),
                parse("0 0 0\n", "voluntary_ctxt_switches:\t4\nnonvoluntary_ctxt_switches:\t1\n"));
        assertEquals(null, parse("0 0 0\n", null));
        assertEquals(new ProcCpu.Sched(0, 0, -1, -1), parse("1 0 0\n", null));
    }

    @Test
    void aStatusThatDoesNotFitIsLeftUnread() throws IOException {
        sched(1, "1000 5000000 10", 100, 5);
        ProcCpu proc = new ProcCpu(root);
        ProcCpu.Snapshot start = proc.read();
        sched(1, "2000 9000000 15", 130, 9);
        Path status = root.resolve("self/task/1/status");
        Files.writeString(status, "Pad:\t0\n".repeat(2_000) + Files.readString(status));

        assertArrayEquals(new long[] {4_000_000, 5, -1, -1}, ProcCpu.schedDelta(start, proc.read()));
    }

    private static ProcCpu.Sched parse(String schedstat, String status) {
        byte[] schedstatBytes = schedstat == null ? new byte[0] : schedstat.getBytes(StandardCharsets.US_ASCII);
        byte[] statusBytes = status == null ? new byte[0] : status.getBytes(StandardCharsets.US_ASCII);
        return ProcCpu.sched(
                schedstatBytes,
                schedstat == null ? -1 : schedstatBytes.length,
                statusBytes,
                status == null ? -1 : statusBytes.length);
    }

    private void sched(long tid, String schedstat, long voluntary, long involuntary) throws IOException {
        Path dir = Files.createDirectories(root.resolve("self/task/" + tid));
        Files.writeString(dir.resolve("comm"), "worker-" + tid + "\n");
        if (schedstat != null) Files.writeString(dir.resolve("schedstat"), schedstat + "\n");
        Files.writeString(
                dir.resolve("status"),
                "Name:\tworker-" + tid + "\nState:\tS (sleeping)\nThreads:\t40\n"
                        + "voluntary_ctxt_switches:\t" + voluntary + "\n"
                        + "nonvoluntary_ctxt_switches:\t" + involuntary + "\n");
    }

    private static void delete(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) Files.delete(file);
        }
        Files.delete(dir);
    }

    private void task(long tid, String comm, long utime, long stime) throws IOException {
        Path dir = Files.createDirectories(root.resolve("self/task/" + tid));
        Files.writeString(dir.resolve("comm"), comm + "\n");
        Files.writeString(dir.resolve("stat"), stat(comm, utime, stime).replaceFirst("^1234 ", tid + " "));
    }

    /** A {@code /proc/<pid>/stat} line: utime and stime are fields 14 and 15. */
    private static String stat(String comm, long utime, long stime) {
        return "1234 (" + comm + ") S 1 1234 1234 0 -1 4194560 100 0 0 0 " + utime + " " + stime
                + " 0 0 20 0 42 0 100 1000000 5000 18446744073709551615\n";
    }

    private static PerfThreadCpu threadCpu(String kind, long nanos, int threads) {
        return PerfThreadCpu.newBuilder()
                .setKind(kind)
                .setNanos(nanos)
                .setThreads(threads)
                .build();
    }
}
