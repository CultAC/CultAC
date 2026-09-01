package ac.grim.grimac.bedrock.prediction.world;

public record FluidCurrentState(
    double pushPerTick,
    double disableMinFeetY,
    double resumeMinFeetY,
    double stopBeforeFeetY,
    double directionX,
    double directionY,
    double directionZ
) {
    public FluidCurrentState(
        double pushPerTick,
        double disableMinFeetY,
        double resumeMinFeetY,
        double stopBeforeFeetY
    ) {
        this(pushPerTick, disableMinFeetY, resumeMinFeetY, stopBeforeFeetY, 0.0D, 0.0D, 0.0D);
    }

    public static final FluidCurrentState NONE = new FluidCurrentState(
        0.0D,
        Double.POSITIVE_INFINITY,
        Double.POSITIVE_INFINITY,
        Double.POSITIVE_INFINITY,
        0.0D,
        0.0D,
        0.0D
    );

    public FluidCurrentState {
        if (pushPerTick < 0.0D) {
            throw new IllegalArgumentException("fluid current push must be non-negative");
        }
        if (!Double.isFinite(directionX) || !Double.isFinite(directionY) || !Double.isFinite(directionZ)) {
            throw new IllegalArgumentException("fluid current direction must be finite");
        }
        if (disableMinFeetY > resumeMinFeetY && disableMinFeetY != Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("fluid current resume height must not precede disable height");
        }
    }

    public boolean appliesAtFeetY(double feetY) {
        if (pushPerTick == 0.0D) {
            return false;
        }
        if (feetY < disableMinFeetY) {
            return true;
        }
        return feetY >= resumeMinFeetY && feetY < stopBeforeFeetY;
    }

    public boolean hasDirectionVector() {
        return directionX != 0.0D || directionY != 0.0D || directionZ != 0.0D;
    }
}
