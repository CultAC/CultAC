package ac.cult.cultac.protocol.packet.clientbound;

public record ClientboundProjectilePower(int entityId, double accelerationPower) implements ClientboundPacket {}
