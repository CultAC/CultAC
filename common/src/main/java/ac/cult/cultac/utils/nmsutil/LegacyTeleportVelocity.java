package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.utils.data.TeleportData;
import net.minecraft.world.phys.Vec3;

/** Velocity after the ordered packets translating a modern teleport to an old client. */
public final class LegacyTeleportVelocity {
    private LegacyTeleportVelocity() {}

    public static Vec3 apply(ClientVersion client, ClientVersion server, TeleportData teleport, Vec3 previous) {
        // NetHandlerPlayClient#handlePlayerPosLook clears absolute axes. On a
        // modern server ViaRewind's PlayerPacketRewriter1_9 first resolves all
        // relative arguments and sends 1.8 flags=0, clearing every velocity axis.
        Vec3 reset = client.isOlderThan(ClientVersion.V_1_9) && server.isNewerThanOrEquals(ClientVersion.V_1_9)
                ? Vec3.ZERO : teleport.modifyVector(previous);
        if (server.isOlderThan(ClientVersion.V_1_21_2)) return reset;

        // ViaBackwards EntityPacketRewriter1_21_2#handleRelativeArguments sends
        // the teleport first, then an explosion (float addend) for all-relative
        // delta flags or entity motion (short/8000) for all-absolute delta flags.
        // Mixed delta flags have no follow-up packet in that translator.
        Vec3 delta = teleport.getDeltaMovement();
        if (teleport.isRotateDelta()) {
            // Match the translator's in-place rotation, including its use of
            // the already-updated X/Y components when calculating Z.
            double yaw = Math.toRadians(teleport.getSourceYaw() - teleport.getFinalYaw());
            double pitch = Math.toRadians(teleport.getSourcePitch() - teleport.getFinalPitch());
            double x = delta.x * Math.cos(yaw) + delta.z * Math.sin(yaw);
            double z = delta.z * Math.cos(yaw) - x * Math.sin(yaw);
            double y = delta.y * Math.cos(pitch) + z * Math.sin(pitch);
            z = z * Math.cos(pitch) - y * Math.sin(pitch);
            delta = new Vec3(x, y, z);
        }
        if (teleport.isRelativeDeltaX() && teleport.isRelativeDeltaY() && teleport.isRelativeDeltaZ()) {
            return reset.add((float) delta.x, (float) delta.y, (float) delta.z);
        }
        if (!teleport.isRelativeDeltaX() && !teleport.isRelativeDeltaY() && !teleport.isRelativeDeltaZ()) {
            return new Vec3(legacyMotion(delta.x), legacyMotion(delta.y), legacyMotion(delta.z));
        }
        return reset;
    }

    private static double legacyMotion(double value) {
        // ViaBackwards VelocityUtil rounds lossy modern LP-vector components
        // and clamps to the wire short range before sending entity motion.
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value * 8000.0D))) / 8000.0D;
    }
}
