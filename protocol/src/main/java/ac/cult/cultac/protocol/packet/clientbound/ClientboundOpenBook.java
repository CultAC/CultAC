package ac.cult.cultac.protocol.packet.clientbound;

import ac.cult.cultac.protocol.value.Hand;

public record ClientboundOpenBook(Hand hand) implements ClientboundPacket {}
