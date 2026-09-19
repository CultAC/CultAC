package ac.cult.cultac.bedrock.prediction.state;

/** Rider-owned charge; a release supplies a request to the controlled mount. */
public record BedrockRidingJumpState(int ticks, float scale, boolean held) {
    public static final BedrockRidingJumpState INITIAL = new BedrockRidingJumpState(0, 0.0F, false);

    public Step tick(boolean jumping, boolean canPowerJump) {
        int nextTicks = ticks;
        float nextScale = scale;
        if (nextTicks < 0 && ++nextTicks == 0) nextScale = 0.0F;
        int request = -1;
        if (!canPowerJump) {
            nextScale = 0.0F;
        } else if (held && !jumping) {
            nextTicks = -10;
            request = (int) Math.floor(nextScale * 100.0F);
        } else if (!held && jumping) {
            nextTicks = 0;
            nextScale = 0.0F;
        } else if (jumping) {
            nextTicks++;
            nextScale = nextTicks < 10 ? nextTicks * 0.1F : 0.8F + (2.0F / (nextTicks - 9)) * 0.1F;
        }
        return new Step(new BedrockRidingJumpState(nextTicks, nextScale, jumping), request);
    }

    public record Step(BedrockRidingJumpState state, int release) { }
}
