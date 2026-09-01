package ac.grim.grimac.bedrock.prediction.state;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import ac.grim.grimac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.grim.grimac.bedrock.prediction.model.BedrockBoundingBoxMode;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import java.util.Objects;

public record BedrockMovementUpdate(
    Vec3d physicalFeetPosition,
    Vec3d velocity,
    BedrockInputFrame frame,
    BedrockCollisionFlags collisionFlags,
    BedrockBoundingBoxMode boundingBoxMode,
    PlayerDimensionsState playerDimensions,
    float fallDistance,
    long powderSnowTicks,
    Glide glide,
    boolean swimming,
    double swimAmount,
    Riptide riptide,
    ItemUse itemUse
) {
    public BedrockMovementUpdate {
        Objects.requireNonNull(physicalFeetPosition, "physicalFeetPosition");
        Objects.requireNonNull(velocity, "velocity");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(collisionFlags, "collisionFlags");
        Objects.requireNonNull(boundingBoxMode, "boundingBoxMode");
        Objects.requireNonNull(playerDimensions, "playerDimensions");
        Objects.requireNonNull(glide, "glide");
        Objects.requireNonNull(riptide, "riptide");
        Objects.requireNonNull(itemUse, "itemUse");
        if (!Float.isFinite(fallDistance) || fallDistance < 0.0F) {
            throw new IllegalArgumentException("fallDistance must be finite and non-negative");
        }
        if (powderSnowTicks < 0L) {
            throw new IllegalArgumentException("powderSnowTicks must be non-negative");
        }
        if (!Double.isFinite(swimAmount) || swimAmount < 0.0D || swimAmount > 1.0D) {
            throw new IllegalArgumentException("swimAmount must be finite and between 0 and 1");
        }
    }

    public record Glide(boolean active, boolean requested) {
    }

    public record Riptide(long chargeTicks, boolean spinActive, long spinTicks) {
        public Riptide {
            requireNonNegative(chargeTicks, "chargeTicks");
            requireNonNegative(spinTicks, "spinTicks");
            if (!spinActive && spinTicks != 0L) {
                throw new IllegalArgumentException("inactive riptide spin must have zero ticks");
            }
        }
    }

    public record ItemUse(boolean slowdownActive, long slowdownTicks) {
        public ItemUse {
            requireNonNegative(slowdownTicks, "slowdownTicks");
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
