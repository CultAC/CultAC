package ac.cult.cultac.network;

import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;

/** Vanilla's moveRelative input for a client's keys, for building client moves the engine has not seen. */
final class ClientMoveInput {
    private ClientMoveInput() {}

    /** KeyboardInput#tick, LocalPlayer#modifyInput scaled by {@code movingSlowlyFactor}, and Entity#getInputVector. */
    static Vec3 of(JavaTickFixture f, ServerboundPlayerInput keys, float movingSlowlyFactor, float speed, float yaw) {
        Vec3 modified = VelocityTransformer.getExactControllerModifiedInputStatic(
                new Vec3(impulse(keys.left(), keys.right()), 0.0D, impulse(keys.forward(), keys.backward())),
                movingSlowlyFactor);
        double lengthSqr = modified.lengthSqr();
        if (lengthSqr < 1.0E-7D) return Vec3.ZERO;
        Vec3 movement = (lengthSqr > 1.0D ? modified.normalize() : modified).scale(speed);
        float yawRadians = yaw * ((float) Math.PI / 180F);
        float sin = f.player.trigHandler.sin(yawRadians);
        float cos = f.player.trigHandler.cos(yawRadians);
        return new Vec3(movement.x * cos - movement.z * sin, movement.y, movement.z * cos + movement.x * sin);
    }

    private static float impulse(boolean positive, boolean negative) {
        return positive == negative ? 0.0F : positive ? 1.0F : -1.0F;
    }
}
