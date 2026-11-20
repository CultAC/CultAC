package ac.cult.cultac.bedrock.prediction.simulation.frame;

public record BedrockMobJumpComponentState(boolean reduceNextSwimUpImpulse, int jumpCooldownTicks) {
    public static final double NORMAL_SWIM_UP_IMPULSE = 0.04F;
    public static final double REDUCED_SWIM_UP_IMPULSE = 0.028F;
    public static final BedrockMobJumpComponentState DEFAULT = new BedrockMobJumpComponentState(false);

    public BedrockMobJumpComponentState(boolean reduceNextSwimUpImpulse) {
        this(reduceNextSwimUpImpulse, 0);
    }

    BedrockMobJumpComponentState beginTick(boolean jumping) {
        return withJumpCooldownTicks(jumping ? Math.max(0, jumpCooldownTicks - 1) : 0);
    }

    BedrockMobJumpComponentState afterJumpRequest() {
        return withJumpCooldownTicks(10);
    }

    private BedrockMobJumpComponentState withJumpCooldownTicks(int delay) {
        return delay == jumpCooldownTicks ? this : new BedrockMobJumpComponentState(reduceNextSwimUpImpulse, delay);
    }

    public BedrockMobJumpComponentState afterSwimUpImpulseSelection() {
        return reduceNextSwimUpImpulse ? new BedrockMobJumpComponentState(false, jumpCooldownTicks) : this;
    }

    public double swimUpImpulse() {
        return reduceNextSwimUpImpulse ? REDUCED_SWIM_UP_IMPULSE : NORMAL_SWIM_UP_IMPULSE;
    }
}
