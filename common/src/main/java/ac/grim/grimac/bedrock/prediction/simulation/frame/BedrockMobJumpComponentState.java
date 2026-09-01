package ac.grim.grimac.bedrock.prediction.simulation.frame;

public record BedrockMobJumpComponentState(boolean reduceNextSwimUpImpulse) {
    public static final double NORMAL_SWIM_UP_IMPULSE = 0.04F;
    public static final double REDUCED_SWIM_UP_IMPULSE = 0.028F;
    public static final BedrockMobJumpComponentState DEFAULT = new BedrockMobJumpComponentState(false);

    public BedrockMobJumpComponentState afterSwimUpImpulseSelection() {
        return reduceNextSwimUpImpulse ? DEFAULT : this;
    }

    public double swimUpImpulse() {
        return reduceNextSwimUpImpulse ? REDUCED_SWIM_UP_IMPULSE : NORMAL_SWIM_UP_IMPULSE;
    }
}
