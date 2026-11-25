package ac.cult.cultac.protocol;

import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundIntention;
import ac.cult.cultac.protocol.value.ConnectionIntent;
import java.util.List;

/** Wire terminal boundaries; each direction remains independent until its own terminal. */
public final class ConnectionLifecycle {
    private static final List<PacketType<?>> TYPES = List.of(
            ServerboundPackets.INTENTION,
            ClientboundPackets.LOGIN_FINISHED,
            ServerboundPackets.LOGIN_ACKNOWLEDGED,
            ClientboundPackets.START_CONFIGURATION,
            ServerboundPackets.CONFIGURATION_ACKNOWLEDGED,
            ClientboundPackets.FINISH_CONFIGURATION,
            ServerboundPackets.FINISH_CONFIGURATION);

    private ConnectionLifecycle() {}

    public static List<PacketType<?>> types() {
        return TYPES;
    }

    public static boolean handles(PacketType<?> type) {
        return type != null && TYPES.contains(type);
    }

    /** Matches the client's independent decoder/encoder switches around each terminal. */
    public static ConnectionPhase nextPhase(
            PacketDirection direction, ConnectionPhase current, PacketType<?> type, Object packet) {
        if (type == ServerboundPackets.INTENTION) {
            var intention = (ServerboundIntention) packet;
            return intention.intention() == ConnectionIntent.STATUS ? ConnectionPhase.STATUS : ConnectionPhase.LOGIN;
        }
        if (direction == PacketDirection.CLIENTBOUND) {
            if (type == ClientboundPackets.LOGIN_FINISHED || type == ClientboundPackets.START_CONFIGURATION)
                return ConnectionPhase.CONFIGURATION;
            if (type == ClientboundPackets.FINISH_CONFIGURATION) return ConnectionPhase.PLAY;
        } else {
            if (type == ServerboundPackets.LOGIN_ACKNOWLEDGED || type == ServerboundPackets.CONFIGURATION_ACKNOWLEDGED)
                return ConnectionPhase.CONFIGURATION;
            if (type == ServerboundPackets.FINISH_CONFIGURATION) return ConnectionPhase.PLAY;
        }
        return current;
    }
}
