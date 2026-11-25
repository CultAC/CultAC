package ac.cult.cultac.protocol.packet.clientbound;

/** A living entity's head yaw, which the client lerps to over three of the entity's ticks. */
public record ClientboundRotateHead(int entityId, float headYaw) implements ClientboundPacket {}
