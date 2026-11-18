package ac.cult.cultac.cloud.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.PerfMachineLoad;
import ac.cult.cultac.cloud.intake.v3.PerfProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MachineLoadTest {

    @TempDir
    Path root;

    private Path proc() {
        return root.resolve("proc");
    }

    private Path cpuSys() {
        return root.resolve("sys");
    }

    @Test
    void reportsCpuTimeByKindAndTheBusiestCpuInMillis() throws IOException {
        stat("cpu  100 10 50 1000 5 1 20 3 0 0", "cpu0 60 5 30 400 3 1 15 2 0 0", "cpu1 40 5 20 600 2 0 5 1 0 0");
        MachineLoad machine = new MachineLoad(proc(), cpuSys());
        MachineLoad.Snapshot start = machine.read();
        stat("cpu  300 12 90 1500 9 3 60 5 0 0", "cpu0 100 6 40 800 5 2 20 3 0 0", "cpu1 200 6 50 700 4 1 40 2 0 0");

        PerfMachineLoad load = MachineLoad.window(start, machine.read());

        assertEquals(2_000, load.getUserMillis());
        assertEquals(20, load.getNiceMillis());
        assertEquals(400, load.getSystemMillis());
        assertEquals(5_000, load.getIdleMillis());
        assertEquals(40, load.getIowaitMillis());
        assertEquals(20, load.getIrqMillis());
        assertEquals(400, load.getSoftirqMillis());
        assertEquals(20, load.getStealMillis());
        assertEquals(2, load.getCpusOnline());
        assertEquals(350, load.getMaxCpuSoftirqMillis());
        assertEquals(2_280, load.getMaxCpuBusyMillis());
    }

    @Test
    void aShortStatLineLeavesTheMissingKindsOut() throws IOException {
        stat("cpu  100 10 50 1000 5", "cpu0 100 10 50 1000 5", "cpu1 1 2", "intr 5 0 0");
        MachineLoad machine = new MachineLoad(proc(), cpuSys());
        MachineLoad.Snapshot start = machine.read();
        stat("cpu  200 10 60 1100 5", "cpu0 200 10 60 1100 5", "cpu1 1 2", "intr 9 0 0");

        PerfMachineLoad load = MachineLoad.window(start, machine.read());

        assertEquals(1_000, load.getUserMillis());
        assertEquals(0, load.getIowaitMillis());
        assertTrue(load.hasIowaitMillis());
        assertFalse(load.hasIrqMillis());
        assertFalse(load.hasSoftirqMillis());
        assertFalse(load.hasStealMillis());
        assertEquals(1, load.getCpusOnline());
        assertFalse(load.hasMaxCpuSoftirqMillis());
        assertEquals(1_100, load.getMaxCpuBusyMillis());
    }

    @Test
    void aCounterThatGoesBackIsLeftOut() throws IOException {
        stat("cpu  100 10 50 1000 5 1 20 3", "cpu0 100 10 50 1000 5 1 20 3");
        MachineLoad machine = new MachineLoad(proc(), cpuSys());
        MachineLoad.Snapshot start = machine.read();
        stat("cpu  90 10 50 1100 5 1 20 3", "cpu0 90 10 50 1100 5 1 20 3");

        PerfMachineLoad load = MachineLoad.window(start, machine.read());

        assertFalse(load.hasUserMillis());
        assertEquals(1_000, load.getIdleMillis());
    }

    @Test
    void readsCpuPressureClockAndSmt() throws IOException {
        pressure(1_000);
        freq(0, "2000000");
        freq(1, "3000000");
        Files.writeString(cpuSys().resolve("cpu0/cpufreq/cpuinfo_max_freq"), "3600000\n");
        Files.createDirectories(cpuSys().resolve("smt"));
        Files.writeString(cpuSys().resolve("smt/active"), "1\n");
        Files.createDirectories(cpuSys().resolve("cpufreq"));
        MachineLoad machine = new MachineLoad(proc(), cpuSys(), () -> 0);
        MachineLoad.Snapshot start = machine.read();
        pressure(251_000);
        freq(0, "3000000");
        freq(1, "4000000");

        PerfMachineLoad load = machine.window(start, machine.read(), ProcCpu.NONE, ProcCpu.NONE);

        assertEquals(250_000, load.getCpuPressureSomeMicros());
        assertEquals(3_500_000, load.getCurFreqKhz());
        assertEquals(3_600_000, load.getMaxFreqKhz());
        assertTrue(load.getSmtActive());
        assertTrue(load.hasSmtActive());
        assertFalse(load.hasUserMillis());
        assertFalse(load.hasRunQueueWaitMillis());
    }

    @Test
    void anUnreadableCpuClockIsLeftOutOfTheMean() throws IOException {
        freq(0, "2400000");
        freq(1, "garbage");
        Files.createDirectories(cpuSys().resolve("cpu2"));
        MachineLoad machine = new MachineLoad(proc(), cpuSys(), () -> 0);

        PerfMachineLoad load = machine.window(MachineLoad.NONE, MachineLoad.NONE, ProcCpu.NONE, ProcCpu.NONE);

        assertEquals(2_400_000, load.getCurFreqKhz());
    }

    @Test
    void aSecondSlowClockReadLeavesTheClockOut() throws IOException {
        freq(0, "2400000");
        freq(1, "2600000");
        long[] now = {0};
        long[] calls = {0};
        MachineLoad slow = new MachineLoad(proc(), cpuSys(), () -> now[0] += TimeUnit.MILLISECONDS.toNanos(10));
        MachineLoad once = new MachineLoad(
                proc(), cpuSys(), () -> now[0] += TimeUnit.MILLISECONDS.toNanos(calls[0]++ < 2 ? 10 : 1));
        MachineLoad fast = new MachineLoad(proc(), cpuSys(), () -> now[0] += TimeUnit.MILLISECONDS.toNanos(1));

        assertEquals(
                2_500_000,
                once.window(MachineLoad.NONE, MachineLoad.NONE, ProcCpu.NONE, ProcCpu.NONE)
                        .getCurFreqKhz());
        assertFalse(slow.window(MachineLoad.NONE, MachineLoad.NONE, ProcCpu.NONE, ProcCpu.NONE)
                .hasCurFreqKhz());
        assertEquals(
                2_500_000,
                fast.window(MachineLoad.NONE, MachineLoad.NONE, ProcCpu.NONE, ProcCpu.NONE)
                        .getCurFreqKhz());
    }

    @Test
    void parsesOnlyTheSomePressureTotal() {
        assertEquals(
                42,
                MachineLoad.pressure("some avg10=0.10 avg60=0.05 avg300=0.01 total=42\n"
                        + "full avg10=0.00 avg60=0.00 avg300=0.00 total=7\n"));
        assertEquals(-1, MachineLoad.pressure("full avg10=0.00 avg60=0.00 avg300=0.00 total=7\n"));
        assertEquals(-1, MachineLoad.pressure("some avg10=0.10 total=x\n"));
        assertEquals(-1, MachineLoad.pressure(null));
    }

    @Test
    void aStatWithoutACpuLineHasNoTotals() {
        Map<Integer, long[]> cpus = new HashMap<>();

        assertNull(MachineLoad.stat("intr 1 2 3\nctxt 5\n", cpus));
        assertTrue(cpus.isEmpty());
    }

    @Test
    void everyFieldIsAbsentWithoutProcOrSys() throws IOException {
        MachineLoad machine = new MachineLoad(proc(), cpuSys());
        ProcCpu procCpu = new ProcCpu(proc());

        PerfMachineLoad load = machine.window(machine.read(), machine.read(), procCpu.read(), procCpu.read());

        assertEquals(PerfMachineLoad.getDefaultInstance(), load);
        assertEquals(PerfMachineLoad.getDefaultInstance(), MachineLoad.window(MachineLoad.NONE, MachineLoad.NONE));
        assertFalse(PerfRecorder.machineLoad(load).hasMachineLoad());
        assertEquals(PerfProfile.getDefaultInstance(), PerfRecorder.machineLoad(load));
    }

    @Test
    void aReadableSourceSetsTheProfileField() {
        PerfMachineLoad load = PerfMachineLoad.newBuilder().setSmtActive(false).build();

        assertTrue(PerfRecorder.machineLoad(load).hasMachineLoad());
    }

    private void stat(String... lines) throws IOException {
        Files.createDirectories(proc());
        Files.writeString(proc().resolve("stat"), String.join("\n", lines) + "\nctxt 123\nbtime 1\n");
    }

    private void pressure(long total) throws IOException {
        Files.createDirectories(proc().resolve("pressure"));
        Files.writeString(
                proc().resolve("pressure/cpu"),
                "some avg10=1.00 avg60=0.50 avg300=0.10 total=" + total + "\n"
                        + "full avg10=0.00 avg60=0.00 avg300=0.00 total=0\n");
    }

    private void freq(int cpu, String khz) throws IOException {
        Path dir = Files.createDirectories(cpuSys().resolve("cpu" + cpu + "/cpufreq"));
        Files.writeString(dir.resolve("scaling_cur_freq"), khz + "\n");
    }
}
