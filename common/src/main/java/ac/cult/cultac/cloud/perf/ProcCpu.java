package ac.cult.cultac.cloud.perf;

import ac.cult.cultac.cloud.intake.v3.PerfThreadCpu;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The process's user and sys CPU, its JVM threads' CPU by kind and its threads' scheduler counters, read from a Linux
 * {@code /proc}. Never throws.
 */
final class ProcCpu {
    /** Kinds in {@code PerfThreadCpu} order. */
    static final List<String> KINDS = List.of("gc", "jit", "vm", "jfr");

    /** Linux's USER_HZ: {@code /proc} counts CPU in hundredths of a second. */
    static final long TICK_NANOS = TimeUnit.SECONDS.toNanos(1) / 100;

    /** Linux HotSpot cuts a name of 16 or more characters to its first 15, or its first 7, {@code ..} and last 6. */
    private static final List<String> GC = List.of(
            "GC Thread",
            "G1 ",
            "ZGC",
            "Z ",
            "Shenandoah",
            "GC ",
            "ShenWorker",
            "ShenControl",
            "ShenRegulator",
            "ShenUncommit",
            "Safepoi..");

    private static final List<String> JIT = List.of(
            "C1 CompilerThre", "C2 CompilerThre", "C1 Compiler", "C2 Compiler", "JVMCI", "C1 Comp..", "C2 Comp..");
    private static final List<String> JFR = List.of("JFR ");
    /** The recording stream's dispatch thread, whose CPU {@code recorder_cpu_nanos} already counts. */
    private static final List<String> RECORDER_DISPATCH = List.of("JFR Event Strea", "JFR Eve..");

    private static final List<String> VM = List.of(
            "VM Thread",
            "VM Periodic",
            "Monitor Deflati",
            "Service Thread",
            "Notification Th",
            "VM Peri..Thread",
            "Monitor..Thread",
            "Notific..Thread");

    /**
     * {@code user} and {@code sys} in ticks, -1 when unread; {@code tasks}: the JVM threads by tid; {@code sched}: every
     * thread's scheduler counters by tid, null when the task list is unreadable.
     */
    record Snapshot(long user, long sys, Map<Long, Task> tasks, Map<Long, Sched> sched) {}

    record Task(String kind, long ticks) {}

    /** A thread's run-queue wait in nanos, timeslices and context switches; -1 for each unread. */
    record Sched(long waitNanos, long slices, long voluntary, long involuntary) {
        static final int COUNTERS = 4;

        long counter(int index) {
            return switch (index) {
                case 0 -> waitNanos;
                case 1 -> slices;
                case 2 -> voluntary;
                default -> involuntary;
            };
        }
    }

    /** A snapshot of a system without {@code /proc}. */
    static final Snapshot NONE = new Snapshot(-1, -1, Map.of(), null);

    private static final byte[] VOLUNTARY = "voluntary_ctxt_switches:".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] INVOLUNTARY = "nonvoluntary_ctxt_switches:".getBytes(StandardCharsets.US_ASCII);
    private static final int SCHEDSTAT_BYTES = 128;
    private static final int STATUS_BYTES = 8192;
    /** The digits a long always holds. */
    private static final int MAX_DIGITS = 18;

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
        Map<Long, Task> tasks = new HashMap<>();
        Map<Long, Sched> sched = new HashMap<>();
        boolean listed = tasks(tasks, sched);
        return new Snapshot(user, sys, tasks, listed ? sched : null);
    }

    /** Fills {@code tasks} and {@code sched} in one walk of {@code self/task}; false when it cannot be listed. */
    private boolean tasks(Map<Long, Task> tasks, Map<Long, Sched> sched) {
        byte[] schedstat = new byte[SCHEDSTAT_BYTES];
        byte[] status = new byte[STATUS_BYTES];
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root.resolve("self/task"))) {
            for (Path dir : dirs) {
                try {
                    long tid = Long.parseLong(dir.getFileName().toString());
                    int schedstatLength = read(dir.resolve("schedstat"), schedstat);
                    Sched counters = sched(schedstat, schedstatLength, status, read(dir.resolve("status"), status));
                    if (counters != null) sched.put(tid, counters);
                    String kind = kind(comm(Files.readString(dir.resolve("comm"))));
                    if (kind == null) continue;
                    long[] times = times(Files.readString(dir.resolve("stat")));
                    tasks.put(tid, new Task(kind, times[0] + times[1]));
                } catch (IOException | RuntimeException ignored) {
                    // the task ended or its files do not parse
                }
            }
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    /** Reads {@code file} into {@code into}; the bytes read, -1 when it is unreadable or does not fit. */
    private static int read(Path file, byte[] into) {
        try (InputStream in = Files.newInputStream(file)) {
            int length = 0;
            while (length < into.length) {
                int read = in.read(into, length, into.length - length);
                if (read < 0) return length;
                length += read;
            }
            return -1;
        } catch (IOException | RuntimeException ignored) {
            return -1;
        }
    }

    /**
     * A thread's counters from the bytes of its {@code schedstat} and {@code status}, a length of -1 for an unread
     * file; null when neither parses.
     */
    static Sched sched(byte[] schedstat, int schedstatLength, byte[] status, int statusLength) {
        // schedstat: nanos on a CPU, nanos runnable but waiting, timeslices
        long ran = field(schedstat, schedstatLength, 0);
        long wait = field(schedstat, schedstatLength, 1);
        long slices = field(schedstat, schedstatLength, 2);
        // a kernel that keeps no scheduler statistics reports 0 0 0
        if (wait < 0 || slices < 0 || (ran == 0 && wait == 0 && slices == 0)) {
            wait = -1;
            slices = -1;
        }
        long voluntary = -1;
        long involuntary = -1;
        for (int line = 0; line < statusLength; ) {
            int end = line;
            while (end < statusLength && status[end] != '\n') end++;
            if (startsWith(status, line, end, VOLUNTARY)) {
                voluntary = number(status, line + VOLUNTARY.length, end);
            } else if (startsWith(status, line, end, INVOLUNTARY)) {
                involuntary = number(status, line + INVOLUNTARY.length, end);
            }
            line = end + 1;
        }
        if (wait < 0 && slices < 0 && voluntary < 0 && involuntary < 0) return null;
        return new Sched(wait, slices, voluntary, involuntary);
    }

    /** The blank-separated field {@code index} of {@code text} as a number; -1 when absent or not one. */
    private static long field(byte[] text, int length, int index) {
        int at = 0;
        for (int field = 0; ; field++) {
            while (at < length && blank(text[at])) at++;
            int from = at;
            while (at < length && !blank(text[at])) at++;
            if (from == at) return -1;
            if (field == index) return number(text, from, at);
        }
    }

    /** The number between {@code from} and {@code to}, blanks around it skipped; -1 when not decimal digits. */
    private static long number(byte[] text, int from, int to) {
        while (from < to && blank(text[from])) from++;
        while (to > from && blank(text[to - 1])) to--;
        if (from == to || to - from > MAX_DIGITS) return -1;
        long value = 0;
        for (int at = from; at < to; at++) {
            int digit = text[at] - '0';
            if (digit < 0 || digit > 9) return -1;
            value = value * 10 + digit;
        }
        return value;
    }

    private static boolean blank(byte character) {
        return character == ' ' || character == '\t' || character == '\n' || character == '\r';
    }

    private static boolean startsWith(byte[] text, int from, int to, byte[] prefix) {
        if (to - from < prefix.length) return false;
        for (int at = 0; at < prefix.length; at++) {
            if (text[from + at] != prefix[at]) return false;
        }
        return true;
    }

    /**
     * Each {@link Sched} counter summed the way {@link #threadCpu} sums CPU: over the threads alive at {@code end}, each
     * new since {@code start} from 0, so a thread that ends within the window is not counted. -1 for a counter no thread
     * reports at both ends, and for every counter when either task list is unreadable.
     */
    static long[] schedDelta(Snapshot start, Snapshot end) {
        long[] sums = new long[Sched.COUNTERS];
        Arrays.fill(sums, -1);
        if (start.sched == null || end.sched == null) return sums;
        for (int counter = 0; counter < Sched.COUNTERS; counter++) {
            if (!reports(start.sched, counter)) continue;
            long sum = 0;
            boolean any = false;
            for (Map.Entry<Long, Sched> entry : end.sched.entrySet()) {
                long now = entry.getValue().counter(counter);
                if (now < 0) continue;
                Sched before = start.sched.get(entry.getKey());
                if (before == null) {
                    sum += now;
                } else if (before.counter(counter) >= 0) {
                    sum += Math.max(0, now - before.counter(counter));
                } else {
                    continue;
                }
                any = true;
            }
            if (any) sums[counter] = sum;
        }
        return sums;
    }

    private static boolean reports(Map<Long, Sched> sched, int counter) {
        for (Sched thread : sched.values()) {
            if (thread.counter(counter) >= 0) return true;
        }
        return false;
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
        if (startsWithAny(comm, RECORDER_DISPATCH)) return null;
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
