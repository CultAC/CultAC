package ac.cult.cultac.bedrock.protocol;

import java.util.Objects;
import net.minecraft.world.phys.Vec3;

/** An actor correction observed at the Bedrock write boundary. Positions are in world coordinates. */
public record BedrockMovementCorrection(
        long sequence, long controlGeneration, int vehicleId, long runtimeId, long tick,
        Vec3 position, Vec3 velocity, float yaw, float pitch, boolean onGround,
        BedrockCoordinateFrame coordinates, int teleportTransaction, Float angularVelocity, boolean vehicle
) {
    public BedrockMovementCorrection(long sequence, long controlGeneration, int vehicleId, long runtimeId, long tick,
            Vec3 position, Vec3 velocity, float yaw, float pitch, boolean onGround,
            BedrockCoordinateFrame coordinates, int teleportTransaction, Float angularVelocity) {
        this(sequence, controlGeneration, vehicleId, runtimeId, tick, position, velocity, yaw, pitch, onGround,
                coordinates, teleportTransaction, angularVelocity, true);
    }

    public BedrockMovementCorrection(long sequence, long controlGeneration, int vehicleId, long runtimeId, long tick,
            Vec3 position, Vec3 velocity, float yaw, float pitch, boolean onGround,
            BedrockCoordinateFrame coordinates, int teleportTransaction) {
        this(sequence, controlGeneration, vehicleId, runtimeId, tick, position, velocity, yaw, pitch, onGround,
                coordinates, teleportTransaction, null);
    }

    public BedrockMovementCorrection {
        Objects.requireNonNull(coordinates, "coordinates");
        requireFinite(position);
        requireFinite(velocity);
        if (sequence < 0 || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || angularVelocity != null && !Float.isFinite(angularVelocity)) {
            throw new IllegalArgumentException("Invalid vehicle correction");
        }
    }

    private static void requireFinite(Vec3 vector) {
        if (vector == null || !Double.isFinite(vector.x) || !Double.isFinite(vector.y) || !Double.isFinite(vector.z)) {
            throw new IllegalArgumentException("Invalid correction vector");
        }
    }
}
