package ac.grim.grimac.bedrock.prediction.model;

public record PlayerDimensionsState(
    double width,
    double height
) {
    public static final double DEFAULT_WIDTH = 0.6D;
    public static final double DEFAULT_HEIGHT = 1.8D;
    public static final PlayerDimensionsState DEFAULT = new PlayerDimensionsState(DEFAULT_WIDTH, DEFAULT_HEIGHT);

    public PlayerDimensionsState {
        if (!Double.isFinite(width) || width <= 0.0D) {
            throw new IllegalArgumentException("player width must be finite and positive");
        }
        if (!Double.isFinite(height) || height <= 0.0D) {
            throw new IllegalArgumentException("player height must be finite and positive");
        }
    }

    public double radius() {
        return width * 0.5D;
    }
}
