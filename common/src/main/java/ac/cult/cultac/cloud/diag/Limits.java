package ac.cult.cultac.cloud.diag;

/**
 * The service's diagnostics limits ({@code DiagLimits}), each clamped to this build's compiled maximum. A field the
 * service leaves at 0 takes its default.
 */
public record Limits(
        boolean enabled,
        int ringTicks,
        long playerRingBytes,
        long serverRingBytes,
        int maxPlayers,
        int partBytes,
        int uploadBytesPerSecond,
        int bundleBytes,
        int bundlesPerHour) {
    public static final int MAX_RING_TICKS = 1200;
    public static final long MAX_PLAYER_RING_BYTES = 4L * 1024 * 1024;
    public static final long MAX_SERVER_RING_BYTES = 16L * 1024 * 1024;
    public static final int MAX_PLAYERS = 8;
    public static final int MAX_PART_BYTES = 32 * 1024;
    public static final int MAX_UPLOAD_BYTES_PER_SECOND = 256 * 1024;
    public static final int MAX_BUNDLE_BYTES = 1024 * 1024;
    public static final int MAX_BUNDLES_PER_HOUR = 120;
    /** The smallest part: room for a tick's trace and a part's own fields. */
    public static final int MIN_PART_BYTES = 4 * 1024;

    public static final Limits OFF =
            new Limits(false, 400, 1L << 20, 4L << 20, 2, 32 * 1024, 32 * 1024, 512 * 1024, 30);

    /** {@code DiagLimits} as the service sent it: 0 takes the default, everything else is clamped. */
    public static Limits of(
            boolean enabled,
            long ringTicks,
            long playerRingBytes,
            long serverRingBytes,
            long maxPlayers,
            long partBytes,
            long uploadBytesPerSecond,
            long bundleBytes,
            long bundlesPerHour) {
        return new Limits(
                enabled,
                (int) clamp(ringTicks, OFF.ringTicks, 1, MAX_RING_TICKS),
                clamp(playerRingBytes, OFF.playerRingBytes, 64 * 1024, MAX_PLAYER_RING_BYTES),
                clamp(serverRingBytes, OFF.serverRingBytes, 64 * 1024, MAX_SERVER_RING_BYTES),
                (int) clamp(maxPlayers, OFF.maxPlayers, 1, MAX_PLAYERS),
                (int) clamp(partBytes, OFF.partBytes, MIN_PART_BYTES, MAX_PART_BYTES),
                (int) clamp(uploadBytesPerSecond, OFF.uploadBytesPerSecond, 1024, MAX_UPLOAD_BYTES_PER_SECOND),
                (int) clamp(bundleBytes, OFF.bundleBytes, MIN_PART_BYTES, MAX_BUNDLE_BYTES),
                (int) clamp(bundlesPerHour, OFF.bundlesPerHour, 1, MAX_BUNDLES_PER_HOUR));
    }

    private static long clamp(long value, long fallback, long min, long max) {
        if (value <= 0) return fallback;
        return Math.min(Math.max(value, min), max);
    }
}
