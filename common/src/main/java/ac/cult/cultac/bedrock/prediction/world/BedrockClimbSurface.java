package ac.cult.cultac.bedrock.prediction.world;

/**
 * The climb surface a tick starts from. {@code climbableAtFeet} is the climbable block at the centre of the feet, which
 * caps a fall and holds a sneaking climber whatever scaffolding the box reaches.
 */
public record BedrockClimbSurface(
        Type type,
        boolean climbableAtFeet,
        boolean overScaffolding,
        boolean descendAllowed,
        boolean fluidSuppressesFallClamp) {
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
