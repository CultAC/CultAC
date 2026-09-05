package ac.cult.cultac.bedrock.prediction.world;

public record BedrockClimbSurface(
    Type type,
    boolean descendAllowed,
    boolean fluidSuppressesFallClamp
) {
    public BedrockClimbSurface {
        java.util.Objects.requireNonNull(type, "type");
    }

    public boolean inScaffolding() {
        return type == Type.SCAFFOLDING;
    }

    public boolean climbing() {
        return type != Type.NONE;
    }

    public enum Type {
        NONE,
        CLIMBABLE,
        SCAFFOLDING
    }
}
