package ac.cult.cultac.cloud.perf;

import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wall time and allocated bytes on the recorder's thread per window, over {@code CULTAC_MACHINE_LOAD_BENCH} reps, for
 * the {@link ProcCpu} and {@link MachineLoad} reads at both window ends and the window. On Linux it reads the real
 * {@code /proc} and {@code /sys}; elsewhere a copy of their layout with {@value #THREADS} threads and {@value #CPUS}
 * CPUs.
 */
@EnabledIfEnvironmentVariable(named = "CULTAC_MACHINE_LOAD_BENCH", matches = "\\d+")
class MachineLoadCostBenchTest {
    private static final int THREADS = 300;
    private static final int CPUS = 32;

    @TempDir
    Path temp;

    @Test
    void bench() throws IOException {
        Path proc = Path.of("/proc");
        Path cpuSys = Path.of("/sys/devices/system/cpu");
        boolean real = Files.isDirectory(proc.resolve("self/task"));
        if (!real) {
            proc = temp.resolve("proc");
            cpuSys = temp.resolve("sys");
            layout(proc, cpuSys);
        }
        int reps = Integer.parseInt(System.getenv("CULTAC_MACHINE_LOAD_BENCH"));
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        ProcCpu procCpu = new ProcCpu(proc);
        MachineLoad machine = new MachineLoad(proc, cpuSys);
        List<long[]> runs = new ArrayList<>();
        int tasks = 0;
        for (int rep = -5; rep < reps; rep++) {
            long bytes = threads.getCurrentThreadAllocatedBytes();
            long begin = System.nanoTime();
            ProcCpu.Snapshot procStart = procCpu.read();
            MachineLoad.Snapshot start = machine.read();
            ProcCpu.Snapshot procEnd = procCpu.read();
            machine.window(start, machine.read(), procStart, procEnd);
            long nanos = System.nanoTime() - begin;
            bytes = threads.getCurrentThreadAllocatedBytes() - bytes;
            tasks = procEnd.sched() == null ? 0 : procEnd.sched().size();
            if (rep >= 0) runs.add(new long[] {nanos, bytes});
        }
        long[] nanos = runs.stream().mapToLong(run -> run[0]).sorted().toArray();
        long[] bytes = runs.stream().mapToLong(run -> run[1]).sorted().toArray();
        System.out.println(String.format(
                Locale.ROOT,
                "%s: median %.1f us, min %.1f, max %.1f; allocated median %.1f KiB; threads %d, cpus %d, reps %d",
                real ? "real /proc" : "synthetic layout",
                nanos[nanos.length / 2] / 1e3,
                nanos[0] / 1e3,
                nanos[nanos.length - 1] / 1e3,
                bytes[bytes.length / 2] / 1024.0,
                tasks,
                Runtime.getRuntime().availableProcessors(),
                reps));
    }

    private static void layout(Path proc, Path cpuSys) throws IOException {
        Files.createDirectories(proc.resolve("self"));
        Files.writeString(proc.resolve("self/stat"), "1 (java) S 1 1 1 0 -1 0 0 0 0 0 150 50 0 0 20 0 1 0 1\n");
        String status = "Name:\tworker\n" + "Pad:\t0123456789012345678\n".repeat(55);
        for (int tid = 1; tid <= THREADS; tid++) {
            Path dir = Files.createDirectories(proc.resolve("self/task/" + tid));
            String comm = tid % 10 == 0 ? "GC Thread#" + tid : "worker-" + tid;
            Files.writeString(dir.resolve("comm"), comm + "\n");
            Files.writeString(
                    dir.resolve("stat"), tid + " (" + comm + ") S 1 1 1 0 -1 0 0 0 0 0 15 5 0 0 20 0 1 0 1\n");
            Files.writeString(dir.resolve("schedstat"), "123456789 2345678 9012\n");
            Files.writeString(
                    dir.resolve("status"),
                    status + "voluntary_ctxt_switches:\t" + tid + "\nnonvoluntary_ctxt_switches:\t" + tid + "\n");
        }
        StringBuilder stat = new StringBuilder("cpu  1000 10 500 90000 50 5 200 3 0 0\n");
        for (int cpu = 0; cpu < CPUS; cpu++) {
            stat.append("cpu").append(cpu).append(" 30 1 15 2800 2 1 6 0 0 0\n");
            Path freq = Files.createDirectories(cpuSys.resolve("cpu" + cpu + "/cpufreq"));
            Files.writeString(freq.resolve("scaling_cur_freq"), "3400000\n");
            Files.writeString(freq.resolve("cpuinfo_max_freq"), "3700000\n");
        }
        stat.append("intr ").append("0 ".repeat(400)).append("\nctxt 123456\nbtime 1\nprocesses 99\n");
        Files.createDirectories(proc.resolve("pressure"));
        Files.writeString(proc.resolve("stat"), stat);
        Files.writeString(
                proc.resolve("pressure/cpu"),
                "some avg10=1.00 avg60=0.50 avg300=0.10 total=123456\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=0\n");
        Files.createDirectories(cpuSys.resolve("smt"));
        Files.writeString(cpuSys.resolve("smt/active"), "1\n");
    }
}
