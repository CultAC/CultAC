package ac.grim.grimac.network.event;

import ac.grim.grimac.network.protocol.player.User;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;

public final class PacketReceiveEvent extends PacketEvent {
    private boolean acceptedTransactionResponse;

    public PacketReceiveEvent(User user, Packet<?> packet, ConnectionProtocol connectionState) {
        super(user, packet, connectionState);
    }

    public boolean isAcceptedTransactionResponse() {
        return acceptedTransactionResponse;
    }

    public void setAcceptedTransactionResponse(boolean acceptedTransactionResponse) {
        this.acceptedTransactionResponse = acceptedTransactionResponse;
    }
}
