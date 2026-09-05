package ac.cult.cultac.bedrock.prediction.world;

import java.util.Set;

public record StandingSurfaceState(Set<Surface> surfaces, double blockFriction) {
    public StandingSurfaceState(Set<Surface> surfaces) {
        this(surfaces, BedrockBlockFriction.DEFAULT);
    }

    public StandingSurfaceState {
        surfaces = Set.copyOf(surfaces);
        if (!Double.isFinite(blockFriction) || blockFriction <= 0.0D) {
            throw new IllegalArgumentException("blockFriction must be finite and positive");
        }
    }

    public boolean hasSurface(Surface surface) {
        return surfaces.contains(surface);
    }
}
