package ac.cult.cultac.bedrock.protocol;

public record BedrockMoveVector(float x, float z) {
    public static final BedrockMoveVector ZERO = new BedrockMoveVector(0.0F, 0.0F);

    public double lengthSquared() {
        return (double) x * x + (double) z * z;
    }
}
