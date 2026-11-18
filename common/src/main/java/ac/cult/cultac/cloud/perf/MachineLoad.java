package ac.cult.cultac.cloud.perf;

import ac.cult.cultac.cloud.intake.v3.PerfMachineLoad;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The machine's CPU time by kind, its busiest CPU, CPU pressure and clock, read from a Linux {@code /proc} and
 * {@code /sys/devices/system/cpu}. Never throws; an unreadable source leaves its fields absent.
 */
final class MachineLoad {
    /** {@code /proc/stat} columns kept, in order: user, nice, system, idle, iowait, irq, softirq, steal. */
    static final int KINDS = 8;

    private static final int IDLE = 3;
    private static final int IOWAIT = 4;
    private static final int SOFTIRQ = 6;

    /** Some x86 kernels before 5.18 sleep 10 ms in every {@code scaling_cur_freq} read. */
    private static final long SLOW_READ_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

    /**
     * {@code total}: the {@code cpu} line's columns in ticks, -1 for each the kernel lacks, null when unread;
     * {@code cpus}: the same per {@code cpuN} line by N, null when unread; {@code pressureMicros}: the {@code some}
     * total, -1 when unread.
     */
    record Snapshot(long[] total, Map<Integer, long[]> cpus, long pressureMicros) {}

    /** A snapshot of a system without {@code /proc}. */
    static final Snapshot NONE = new Snapshot(null, null, -1);

    private final Path proc;
    private final Path cpuSys;
    private final LongSupplier nanoTime;

    /** {@code proc}: {@code /proc}; {@code cpuSys}: {@code /sys/devices/system/cpu}. */
    MachineLoad(Path proc, Path cpuSys) {
        this(proc, cpuSys, System::nanoTime);
    }

    MachineLoad(Path proc, Path cpuSys, LongSupplier nanoTime) {
        this.proc = proc;
        this.cpuSys = cpuSys;
        this.nanoTime = nanoTime;
    }

    Snapshot read() {
        long[] total = null;
        Map<Integer, long[]> cpus = null;
        String stat = read(proc.resolve("stat"));
        if (stat != null) {
            cpus = new HashMap<>();
            total = stat(stat, cpus);
        }
        return new Snapshot(total, cpus, pressure(read(proc.resolve("pressure/cpu"))));
    }

    /** The {@code cpu} line's columns, each {@code cpuN} line's put in {@code cpus}; null without a {@code cpu} line. */
    static long[] stat(String text, Map<Integer, long[]> cpus) {
        long[] total = null;
        for (String line : text.split("\n")) {
            if (!line.startsWith("cpu")) continue;
            int space = line.indexOf(' ');
            if (space < 0) continue;
            long[] columns = columns(line.substring(space));
            if (columns == null) continue;
            String name = line.substring(0, space);
            if (name.equals("cpu")) {
                total = columns;
            } else {
                try {
                    cpus.put(Integer.parseInt(name.substring(3)), columns);
                } catch (NumberFormatException ignored) {
                    // not a cpuN line
                }
            }
        }
        return total;
    }

    /** Up to {@link #KINDS} tick columns, -1 past the line's end; null under four or when one does not parse. */
    private static long[] columns(String rest) {
        String[] fields = rest.trim().split("\\s+");
        if (fields.length < 4) return null;
        long[] columns = new long[KINDS];
        for (int i = 0; i < KINDS; i++) {
            if (i >= fields.length) {
                columns[i] = -1;
                continue;
            }
            try {
                columns[i] = Long.parseLong(fields[i]);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return columns;
    }

    /** The {@code some} line's {@code total=} microseconds; -1 when absent. */
    static long pressure(String text) {
        if (text == null) return -1;
        for (String line : text.split("\n")) {
            if (!line.startsWith("some ")) continue;
            for (String field : line.split("\\s+")) {
                if (!field.startsWith("total=")) continue;
                try {
                    return Long.parseLong(field.substring("total=".length()));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** The mean {@code scaling_cur_freq} over the CPUs; -1 when none is readable or a second read is slow. */
    private double curFreqKhz() {
        long sum = 0;
        int count = 0;
        boolean slow = false;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(cpuSys, "cpu[0-9]*")) {
            for (Path dir : dirs) {
                long begin = nanoTime.getAsLong();
                long khz = number(read(dir.resolve("cpufreq/scaling_cur_freq")));
                if (nanoTime.getAsLong() - begin > SLOW_READ_NANOS) {
                    if (slow) return -1;
                    slow = true;
                }
                if (khz < 0) continue;
                sum += khz;
                count++;
            }
        } catch (IOException | RuntimeException ignored) {
            return -1;
        }
        return count == 0 ? -1 : (double) sum / count;
    }

    /** The window from {@code start} to {@code end}, with the process's scheduler counters and the clock now. */
    PerfMachineLoad window(Snapshot start, Snapshot end, ProcCpu.Snapshot procStart, ProcCpu.Snapshot procEnd) {
        PerfMachineLoad.Builder load = window(start, end).toBuilder();
        double khz = curFreqKhz();
        if (khz >= 0) load.setCurFreqKhz((int) Math.round(khz));
        long maxKhz = number(read(cpuSys.resolve("cpu0/cpufreq/cpuinfo_max_freq")));
        if (maxKhz >= 0) load.setMaxFreqKhz((int) maxKhz);
        long smt = number(read(cpuSys.resolve("smt/active")));
        if (smt == 0 || smt == 1) load.setSmtActive(smt == 1);
        long[] sched = ProcCpu.schedDelta(procStart, procEnd);
        if (sched[0] >= 0) load.setRunQueueWaitMillis(TimeUnit.NANOSECONDS.toMillis(sched[0]));
        if (sched[1] >= 0) load.setTimeslices(sched[1]);
        if (sched[2] >= 0) load.setVoluntarySwitches(sched[2]);
        if (sched[3] >= 0) load.setInvoluntarySwitches(sched[3]);
        return load.build();
    }

    /** The {@code /proc/stat} and pressure fields from two snapshots. */
    static PerfMachineLoad window(Snapshot start, Snapshot end) {
        PerfMachineLoad.Builder load = PerfMachineLoad.newBuilder();
        if (start.total != null && end.total != null) {
            for (int kind = 0; kind < KINDS; kind++) {
                long ticks = delta(start.total[kind], end.total[kind]);
                if (ticks >= 0) kind(load, kind, millis(ticks));
            }
        }
        if (end.cpus != null && !end.cpus.isEmpty()) load.setCpusOnline(end.cpus.size());
        if (start.cpus != null && end.cpus != null) busiest(load, start.cpus, end.cpus);
        long pressure = delta(start.pressureMicros, end.pressureMicros);
        if (pressure >= 0) load.setCpuPressureSomeMicros(pressure);
        return load.build();
    }

    private static void busiest(PerfMachineLoad.Builder load, Map<Integer, long[]> start, Map<Integer, long[]> end) {
        long softirq = -1;
        long busy = -1;
        for (Map.Entry<Integer, long[]> entry : end.entrySet()) {
            long[] before = start.get(entry.getKey());
            if (before == null) continue;
            long[] after = entry.getValue();
            softirq = Math.max(softirq, delta(before[SOFTIRQ], after[SOFTIRQ]));
            busy = Math.max(busy, delta(busy(before), busy(after)));
        }
        if (softirq >= 0) load.setMaxCpuSoftirqMillis(millis(softirq));
        if (busy >= 0) load.setMaxCpuBusyMillis(millis(busy));
    }

    /** Ticks in every column but idle and iowait, columns the kernel lacks as 0. */
    private static long busy(long[] columns) {
        long busy = 0;
        for (int kind = 0; kind < KINDS; kind++) {
            if (kind != IDLE && kind != IOWAIT && columns[kind] > 0) busy += columns[kind];
        }
        return busy;
    }

    private static void kind(PerfMachineLoad.Builder load, int kind, long millis) {
        switch (kind) {
            case 0 -> load.setUserMillis(millis);
            case 1 -> load.setNiceMillis(millis);
            case 2 -> load.setSystemMillis(millis);
            case 3 -> load.setIdleMillis(millis);
            case 4 -> load.setIowaitMillis(millis);
            case 5 -> load.setIrqMillis(millis);
            case 6 -> load.setSoftirqMillis(millis);
            default -> load.setStealMillis(millis);
        }
    }

    /** {@code end - start}; -1 unless both are known and the counter did not go back. */
    private static long delta(long start, long end) {
        return start < 0 || end < start ? -1 : end - start;
    }

    private static long millis(long ticks) {
        return TimeUnit.NANOSECONDS.toMillis(ticks * ProcCpu.TICK_NANOS);
    }

    /** A file holding one non-negative number; -1 when unreadable or not one. */
    private static long number(String text) {
        if (text == null) return -1;
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }
}
