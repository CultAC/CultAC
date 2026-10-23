package ac.cult.cultac.cloud.perf;

import ac.cult.cultac.cloud.intake.v3.PerfThreadCpu;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** The process's user and sys CPU and its JVM threads' CPU by kind, read from a Linux {@code /proc}. Never throws. */
final class ProcCpu {
    /** Kinds in {@code PerfThreadCpu} order. */
    static final List<String> KINDS = List.of("gc", "jit", "vm", "jfr");

    /** Linux's USER_HZ: {@code /proc} counts CPU in hundredths of a second. */
    private static final long TICK_NANOS = TimeUnit.SECONDS.toNanos(1) / 100;

    private static final List<String> GC = List.of("GC Thread", "G1 ", "ZGC", "Z ", "Shenandoah", "GC ");
    private static final List<String> JIT =
            List.of("C1 CompilerThre", "C2 CompilerThre", "C1 Compiler", "C2 Compiler", "JVMCI");
    private static final List<String> JFR = List.of("JFR ");
    /** The recording stream's dispatch thread, whose CPU {@code recorder_cpu_nanos} already counts. */
    private static final String RECORDER_DISPATCH = "JFR Event Strea";

    private static final List<String> VM =
            List.of("VM Thread", "VM Periodic", "Monitor Deflati", "Service Thread", "Notification Th");

    /** {@code user} and {@code sys} in ticks, -1 when unread; {@code tasks}: the JVM threads by tid. */
    record Snapshot(long user, long sys, Map<Long, Task> tasks) {}

    record Task(String kind, long ticks) {}

    /** A snapshot of a system without {@code /proc}. */
    static final Snapshot NONE = new Snapshot(-1, -1, Map.of());

    private final Path root;

    ProcCpu(Path root) {
        this.root = root;
    }

    Snapshot read() {
        long user = -1;
        long sys = -1;
        try {
            long[] times = times(Files.readString(root.resolve("self/stat")));
            user = times[0];
            sys = times[1];
        } catch (IOException | RuntimeException ignored) {
            // user and sys stay unknown
        }
        return new Snapshot(user, sys, tasks());
    }

    private Map<Long, Task> tasks() {
        Map<Long, Task> tasks = new HashMap<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root.resolve("self/task"))) {
            for (Path dir : dirs) {
                try {
                    long tid = Long.parseLong(dir.getFileName().toString());
                    String kind = kind(comm(Files.readString(dir.resolve("comm"))));
                    if (kind == null) continue;
                    long[] times = times(Files.readString(dir.resolve("stat")));
                    tasks.put(tid, new Task(kind, times[0] + times[1]));
                } catch (IOException | RuntimeException ignored) {
                    // the task ended or its files do not parse
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // no task list
        }
        return tasks;
    }

    static long userNanos(Snapshot start, Snapshot end) {
        return delta(start.user, end.user);
    }

    static long sysNanos(Snapshot start, Snapshot end) {
        return delta(start.sys, end.sys);
    }

    /** {@code end - start} in nanos; 0 unless both reads succeeded. */
    private static long delta(long start, long end) {
        return start < 0 || end < 0 ? 0 : Math.max(0, end - start) * TICK_NANOS;
    }

    /** CPU by kind of the threads alive at {@code end}, each new since {@code start} from 0; kinds without one left out. */
    static List<PerfThreadCpu> threadCpu(Snapshot start, Snapshot end) {
        long[] ticks = new long[KINDS.size()];
        int[] threads = new int[KINDS.size()];
        for (Map.Entry<Long, Task> entry : end.tasks.entrySet()) {
            Task task = entry.getValue();
            Task before = start.tasks.get(entry.getKey());
            int kind = KINDS.indexOf(task.kind);
            ticks[kind] += Math.max(0, task.ticks - (before == null ? 0 : before.ticks));
            threads[kind]++;
        }
        List<PerfThreadCpu> cpu = new ArrayList<>();
        for (int kind = 0; kind < KINDS.size(); kind++) {
            if (threads[kind] == 0) continue;
            cpu.add(PerfThreadCpu.newBuilder()
                    .setKind(KINDS.get(kind))
                    .setNanos(ticks[kind] * TICK_NANOS)
                    .setThreads(threads[kind])
                    .build());
        }
        return cpu;
    }

    /** The kind of a JVM thread by its {@code comm}; null for a Java thread or the recorder's dispatch thread. */
    static String kind(String comm) {
        if (comm.startsWith(RECORDER_DISPATCH)) return null;
        if (startsWithAny(comm, GC)) return "gc";
        if (startsWithAny(comm, JIT)) return "jit";
        if (startsWithAny(comm, JFR)) return "jfr";
        if (startsWithAny(comm, VM)) return "vm";
        return null;
    }

    private static boolean startsWithAny(String comm, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (comm.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String comm(String file) {
        return file.endsWith("\n") ? file.substring(0, file.length() - 1) : file;
    }

    /** utime and stime of a {@code stat} line: fields 14 and 15, counted past the {@code )} that closes comm. */
    private static long[] times(String stat) {
        String[] fields = stat.substring(stat.lastIndexOf(')') + 1).trim().split("\\s+");
        return new long[] {Long.parseLong(fields[11]), Long.parseLong(fields[12])};
    }
}
