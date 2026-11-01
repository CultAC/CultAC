package ac.cult.cultac.cloud.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.cloud.intake.v3.PerfLimits;
import org.junit.jupiter.api.Test;

class PerfSettingsTest {

    @Test
    void zeroTakesTheDefaults() {
        assertEquals(
                new PerfSettings(60, 10, 20, 300, 1 << 20, false, 45_000),
                PerfSettings.of(PerfLimits.getDefaultInstance(), 0));
    }

    @Test
    void clampsToCompiledMaxima() {
        PerfLimits limits = PerfLimits.newBuilder()
                .setMaxSeconds(10_000)
                .setExecutionPeriodMs(1)
                .setNativePeriodMs(60_000)
                .setAllocationSamplesPerSecond(5_000)
                .setProfileBytes(8 << 20)
                .setWholeJvm(true)
                .setSkipMsptMicros(30_000)
                .build();

        assertEquals(new PerfSettings(300, 5, 1000, 1000, 1 << 20, true, 30_000), PerfSettings.of(limits, 600));
    }

    @Test
    void windowIsClampedByTheServiceMaxAndReadUnsigned() {
        PerfLimits limits =
                PerfLimits.newBuilder().setMaxSeconds(30).setProfileBytes(1).build();

        assertEquals(30, PerfSettings.of(limits, 100).seconds());
        assertEquals(30, PerfSettings.of(limits, -1).seconds());
        assertEquals(PerfSettings.MIN_PROFILE_BYTES, PerfSettings.of(limits, 10).profileBytes());
        assertEquals(
                PerfSettings.MAX_SECONDS,
                PerfSettings.of(PerfLimits.getDefaultInstance(), -1).seconds());
    }
}
