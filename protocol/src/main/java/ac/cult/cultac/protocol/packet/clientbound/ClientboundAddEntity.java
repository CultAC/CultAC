package ac.cult.cultac.protocol.packet.clientbound;

import ac.cult.cultac.protocol.value.Vec3d;
import java.util.Objects;
import java.util.UUID;

public record ClientboundAddEntity(
        int entityId,
        String entityType,
        Vec3d position,
        float yaw,
        float pitch,
        int data,
        UUID uuid,
        Vec3d velocity,
        float headYaw)
        implements ClientboundPacket {
    public ClientboundAddEntity {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(velocity, "velocity");
    }

    /** A spawn whose head yaw is its yaw, as a mob's is when it is created. */
    public ClientboundAddEntity(
            int entityId,
            String entityType,
            Vec3d position,
            float yaw,
            float pitch,
            int data,
            UUID uuid,
            Vec3d velocity) {
        this(entityId, entityType, position, yaw, pitch, data, uuid, velocity, yaw);
    }

    public ClientboundAddEntity(
            int entityId, String entityType, Vec3d position, float yaw, float pitch, int data, UUID uuid) {
        this(entityId, entityType, position, yaw, pitch, data, uuid, Vec3d.ZERO);
    }

    public ClientboundAddEntity(int entityId, String entityType, Vec3d position, float yaw, float pitch, int data) {
        this(entityId, entityType, position, yaw, pitch, data, new UUID(0, 0));
    }
}
