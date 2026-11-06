package ac.cult.cultac.protocol.packet.serverbound;

/** Missing position/rotation fields remain absent; consumers apply their own prior state. */
public record ServerboundMovePlayer(
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        boolean onGround,
        boolean horizontalCollision,
        boolean hasPosition,
        boolean hasRotation)
        implements ServerboundPacket {
    public double xOr(double fallback) {
        return hasPosition ? x : fallback;
    }

    public double yOr(double fallback) {
        return hasPosition ? y : fallback;
    }

    public double zOr(double fallback) {
        return hasPosition ? z : fallback;
    }

    public float yawOr(float fallback) {
        return hasRotation ? yaw : fallback;
    }

    public float pitchOr(float fallback) {
        return hasRotation ? pitch : fallback;
    }

    public boolean rotationOnly() {
        return hasRotation && !hasPosition;
    }

    public ServerboundMovePlayer withOnGround(boolean ground) {
        return new ServerboundMovePlayer(x, y, z, yaw, pitch, ground, horizontalCollision, hasPosition, hasRotation);
    }

    public ServerboundMovePlayer withPitch(float pitch) {
        return new ServerboundMovePlayer(x, y, z, yaw, pitch, onGround, horizontalCollision, hasPosition, hasRotation);
    }

    /** A position projection retains optional rotation and clears horizontal collision. */
    public ServerboundMovePlayer withPosition(double x, double y, double z, boolean ground) {
        return new ServerboundMovePlayer(x, y, z, yawOr(0), pitchOr(0), ground, false, true, hasRotation);
    }
}
