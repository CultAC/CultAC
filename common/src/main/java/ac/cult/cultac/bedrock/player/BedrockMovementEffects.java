package ac.cult.cultac.bedrock.player;

import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;

/** Ordered server effects, sampled once for each accepted actor tick. */
public final class BedrockMovementEffects {
    private int glideBoostTicks;
    private BedrockAuthInputFrame sampledFrame;
    private boolean sampledBoost;

    public void setGlideBoost(int duration) { glideBoostTicks = duration; }

    public boolean glideBoost(BedrockAuthInputFrame frame, boolean actorTick) {
        if (sampledFrame != frame) {
            sampledFrame = frame;
            sampledBoost = glideBoostTicks != 0;
            if (actorTick && glideBoostTicks > 0) glideBoostTicks--;
        }
        return sampledBoost;
    }

    public void clear() {
        glideBoostTicks = 0;
        sampledFrame = null;
        sampledBoost = false;
    }
}
