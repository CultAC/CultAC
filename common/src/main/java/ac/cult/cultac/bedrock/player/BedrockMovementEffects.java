package ac.cult.cultac.bedrock.player;

import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;

/** Ordered server effects, sampled once for each accepted actor tick. */
public final class BedrockMovementEffects {
    private int glideBoostTicks;
    private boolean glideBoostActive;
    private long stopFinalTick = Long.MIN_VALUE;
    private BedrockAuthInputFrame sampledFrame;
    private boolean sampledBoost;

    public void setGlideBoost(int duration) {
        glideBoostTicks = duration < -1 ? 0 : duration;
        glideBoostActive = true;
        stopFinalTick = Long.MIN_VALUE;
    }

    public void setGlideBoost(int duration, long lastAuthTickBeforeAcknowledgement) {
        setGlideBoost(duration);
        if (duration == 0
                && lastAuthTickBeforeAcknowledgement >= 0
                && lastAuthTickBeforeAcknowledgement < Long.MAX_VALUE) {
            stopFinalTick = lastAuthTickBeforeAcknowledgement + 1;
        }
    }

    public boolean glideBoost(BedrockAuthInputFrame frame, boolean actorTick) {
        if (sampledFrame != frame) {
            sampledFrame = frame;
            sampledBoost =
                    glideBoostActive && (stopFinalTick == Long.MIN_VALUE || frame.getClientTick() <= stopFinalTick);
            if (actorTick) spend(frame);
        }
        return sampledBoost;
    }

    /** The sampled tick of {@code frame} turned out to move its actor: it spends a tick of the boost. */
    public void actorTickTaken(BedrockAuthInputFrame frame) {
        if (sampledFrame == frame) spend(frame);
    }

    private void spend(BedrockAuthInputFrame frame) {
        if (!glideBoostActive || glideBoostTicks == -1) return;
        if (stopFinalTick != Long.MIN_VALUE) {
            if (frame.getClientTick() >= stopFinalTick) glideBoostActive = false;
        } else {
            if (glideBoostTicks > 0) glideBoostTicks--;
            if (glideBoostTicks == 0) glideBoostActive = false;
        }
    }

    public void clear() {
        glideBoostTicks = 0;
        glideBoostActive = false;
        stopFinalTick = Long.MIN_VALUE;
        sampledFrame = null;
        sampledBoost = false;
    }
}
