package ac.cult.cultac.network.event;

import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;

public final class PacketReceiveEvent<R extends ServerboundPacket> extends PacketEvent<R> {
    private boolean acceptedTransactionResponse;
    private boolean teleportPositionResponse;

    public PacketReceiveEvent(User user, ConnectionPhase phase, PacketType<R> type, R packet) {
        super(user, phase, type, packet);
        if (type.direction() != PacketDirection.SERVERBOUND)
            throw new IllegalArgumentException("Receive event requires serverbound packet");
    }

    public boolean isAcceptedTransactionResponse() {
        return acceptedTransactionResponse;
    }

    public void setAcceptedTransactionResponse(boolean accepted) {
        acceptedTransactionResponse = accepted;
    }

    /** Accepted legacy PosRot response, separate from LocalPlayer's ordinary movement. */
    public boolean isTeleportPositionResponse() {
        return teleportPositionResponse;
    }

    public void setTeleportPositionResponse(boolean response) {
        teleportPositionResponse = response;
    }
}
