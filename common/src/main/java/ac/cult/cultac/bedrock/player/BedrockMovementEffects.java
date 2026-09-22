package ac.cult.cultac.bedrock.player;

import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;

/** Ordered server effects, sampled once for each accepted actor tick. */
public final class BedrockMovementEffects {
    private int glideBoostTicks;
    private boolean glideBoostActive;
    private BedrockAuthInputFrame sampledFrame;
    private boolean sampledBoost;

    public void setGlideBoost(int duration) {
        glideBoostTicks = duration < -1 ? 0 : duration;
        glideBoostActive = true;
    }

    public boolean glideBoost(BedrockAuthInputFrame frame, boolean actorTick) {
        if (sampledFrame != frame) {
            sampledFrame = frame;
            sampledBoost = glideBoostActive;
            if (actorTick && glideBoostActive && glideBoostTicks != -1) {
                if (glideBoostTicks > 0) glideBoostTicks--;
                if (glideBoostTicks == 0) glideBoostActive = false;
            }
        }
        return sampledBoost;
    }

    public void clear() {
        glideBoostTicks = 0;
        glideBoostActive = false;
        sampledFrame = null;
        sampledBoost = false;
    }
}
