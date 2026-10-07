package ac.cult.cultac.protocol.packet.clientbound;

import ac.cult.cultac.protocol.value.Vec3d;
import java.util.Objects;

public record ClientboundAddEntity(
        int entityId, String entityType, Vec3d position, float yaw, float pitch, int data, java.util.UUID uuid)
        implements ClientboundPacket {
    public ClientboundAddEntity {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(uuid, "uuid");
    }

    public ClientboundAddEntity(int entityId, String entityType, Vec3d position, float yaw, float pitch, int data) {
        this(entityId, entityType, position, yaw, pitch, data, new java.util.UUID(0, 0));
    }
}
