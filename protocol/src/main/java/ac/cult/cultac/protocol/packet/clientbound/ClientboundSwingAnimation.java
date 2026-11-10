package ac.cult.cultac.protocol.packet.clientbound;

/** {@code hand} and {@code animationType} are the wire ids of InteractionHand and SwingAnimationType. */
public record ClientboundSwingAnimation(int entityId, int hand, int animationType) implements ClientboundPacket {}
