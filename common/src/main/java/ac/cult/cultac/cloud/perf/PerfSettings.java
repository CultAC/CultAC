package ac.cult.cultac.cloud.perf;

import ac.cult.cultac.cloud.intake.v3.PerfLimits;

/** One recording's settings: the service's {@code PerfLimits} and requested window, each clamped to this build's maximum. */
public record PerfSettings(
        int seconds,
        int executionPeriodMs,
        int nativePeriodMs,
        int allocationPerSecond,
        int profileBytes,
        boolean wholeJvm,
        int skipMsptMicros) {
    public static final int MAX_SECONDS = 300;
    public static final int MAX_PROFILE_BYTES = 1 << 20;
    public static final int MIN_PROFILE_BYTES = 64 * 1024;
    public static final int MIN_PERIOD_MS = 5;
    public static final int MAX_PERIOD_MS = 1000;
    public static final int MAX_ALLOCATION_PER_SECOND = 1000;

    static final PerfSettings DEFAULTS = new PerfSettings(60, 10, 20, 300, MAX_PROFILE_BYTES, false, 45_000);

    /** A window of {@code seconds} under {@code limits}: 0 takes the default, everything else is clamped. */
    public static PerfSettings of(PerfLimits limits, int seconds) {
        long maxSeconds = clamp(limits.getMaxSeconds(), MAX_SECONDS, 1, MAX_SECONDS);
        return new PerfSettings(
                (int) clamp(seconds, DEFAULTS.seconds, 1, maxSeconds),
                (int) clamp(limits.getExecutionPeriodMs(), DEFAULTS.executionPeriodMs, MIN_PERIOD_MS, MAX_PERIOD_MS),
                (int) clamp(limits.getNativePeriodMs(), DEFAULTS.nativePeriodMs, MIN_PERIOD_MS, MAX_PERIOD_MS),
                (int) clamp(
                        limits.getAllocationSamplesPerSecond(),
                        DEFAULTS.allocationPerSecond,
                        1,
                        MAX_ALLOCATION_PER_SECOND),
                (int) clamp(limits.getProfileBytes(), DEFAULTS.profileBytes, MIN_PROFILE_BYTES, MAX_PROFILE_BYTES),
                limits.getWholeJvm(),
                (int) clamp(limits.getSkipMsptMicros(), DEFAULTS.skipMsptMicros, 1, Integer.MAX_VALUE));
    }

    /** {@code value} read as the unsigned proto field it is. */
    private static long clamp(int value, long fallback, long min, long max) {
        long unsigned = Integer.toUnsignedLong(value);
        return Math.min(Math.max(unsigned == 0 ? fallback : unsigned, min), max);
    }
}
