package ac.cult.cultac.cloud.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.cloud.intake.v3.PerfThreadCpu;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
