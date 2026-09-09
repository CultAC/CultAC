package ac.cult.cultac.bedrock.prediction.state;

/**
 * Client scan timer, predicted dolphin movement effect, and swim multiplier.
 * The scan runs after swim actions; the effect expires after movement.
 */
public record BedrockDolphinBoost(int scanTicks, int effectTicks, boolean boosted) {
    private static final int SCAN_INTERVAL = 60;
    private static final int EFFECT_DURATION = 60;
    // New players start with a full scan interval.
    public static final BedrockDolphinBoost INITIAL = new BedrockDolphinBoost(SCAN_INTERVAL, 0, false);

    public BedrockDolphinBoost {
        if (scanTicks < 1 || scanTicks > SCAN_INTERVAL) {
            throw new IllegalArgumentException("scanTicks must be between 1 and 60");
        }
        if (effectTicks < 0 || effectTicks > EFFECT_DURATION) {
            throw new IllegalArgumentException("effectTicks must be between 0 and 60");
        }
    }

    public BedrockDolphinBoost tick(boolean actorSwimming, boolean dolphinNearby) {
        // Count the current tick's swim state after start/stop actions.
        // Stopping pauses the scan and disables the multiplier, but the effect still ages.
        int nextScan = scanTicks;
        int remaining = effectTicks;
        if (actorSwimming) {
            nextScan--;
            if (nextScan == 0) {
                nextScan = SCAN_INTERVAL;
                if (dolphinNearby) {
                    remaining = EFFECT_DURATION;
                }
            }
        }
        return new BedrockDolphinBoost(nextScan, remaining, actorSwimming && remaining > 0);
    }

    public BedrockDolphinBoost endTick() {
        // Age the effect after travel has consumed this tick's multiplier.
        return effectTicks == 0 ? this : new BedrockDolphinBoost(scanTicks, effectTicks - 1, boosted);
    }

    public double multiplier() {
        return boosted ? 2.0D : 1.0D;
    }
}
