package ac.cult.cultac.network.event;

import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import org.jetbrains.annotations.ApiStatus;

/**
 * A serverbound packet passing through CultAC's handlers. An event is valid only while its handler runs: a handler
 * that needs any of its values later copies them, and never keeps the event.
 */
public final class PacketReceiveEvent<R extends ServerboundPacket> extends PacketEvent<R> {
    private boolean synthetic;
    private boolean acceptedTransactionResponse;
    private boolean teleportPositionResponse;
    private boolean authoredTeleportResponse;

    public PacketReceiveEvent(User user, ConnectionPhase phase, PacketType<R> type, R packet) {
        this(user, phase, type, packet, false);
    }

    public PacketReceiveEvent(User user, ConnectionPhase phase, PacketType<R> type, R packet, boolean synthetic) {
        super(user, phase, type, packet);
        this.synthetic = synthetic;
        if (type.direction() != PacketDirection.SERVERBOUND)
            throw new IllegalArgumentException("Receive event requires serverbound packet");
    }

    /**
     * Gives a released event the state {@code new PacketReceiveEvent<>(user, phase, type, packet)} has. Only the
     * decoder that dispatched and released it may call this; handlers must not call it.
     */
    @ApiStatus.Internal
    @SuppressWarnings("unchecked")
    public <T extends ServerboundPacket> PacketReceiveEvent<T> restart(
            User user, ConnectionPhase phase, PacketType<T> type, T packet) {
        var event = (PacketReceiveEvent<T>) (PacketReceiveEvent<?>) this;
        event.restartEvent(user, phase, type, packet);
        event.synthetic = false;
        if (type.direction() != PacketDirection.SERVERBOUND)
            throw new IllegalArgumentException("Receive event requires serverbound packet");
        return event;
    }

    /** Ends an event whose dispatch returned. Only the decoder that dispatched it may call this; handlers must not. */
    @ApiStatus.Internal
    public void release() {
        releaseEvent();
        acceptedTransactionResponse = false;
        teleportPositionResponse = false;
        authoredTeleportResponse = false;
    }

    /** An internal recovery event, with no packet received from the client. */
    public boolean isSynthetic() {
        return synthetic;
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

    /** A normally processed reply to a teleport CultAC emitted instead of the backend. */
    public boolean isAuthoredTeleportResponse() {
        return authoredTeleportResponse;
    }

    public void setAuthoredTeleportResponse(boolean response) {
        authoredTeleportResponse = response;
    }
}
