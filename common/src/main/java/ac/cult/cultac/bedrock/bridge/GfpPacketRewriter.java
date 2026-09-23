package ac.cult.cultac.bedrock.bridge;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.PacketSendingEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.event.session.SessionListener;
import org.geysermc.mcprotocollib.network.packet.Packet;

/** Runs GFP's packet wrapper under the adapter lock. */
final class GfpPacketRewriter {
    private final SessionListener wrapper;
    private Result result;

    GfpPacketRewriter(WrapperFactory factory) throws ReflectiveOperationException {
        wrapper = factory.wrap(new SessionAdapter() {
            @Override public void packetReceived(Session session, Packet packet) {
                result = new Result(packet, false);
            }

            @Override public void packetSending(PacketSendingEvent event) {
                result = new Result(event.getPacket(), false);
            }
        });
    }

    Result receive(Session session, Packet packet) {
        return rewrite(() -> wrapper.packetReceived(session, packet));
    }

    Result send(PacketSendingEvent event) {
        Result rewritten = rewrite(() -> wrapper.packetSending(event));
        if (rewritten.cancelled()) event.setCancelled(true);
        return rewritten;
    }

    private Result rewrite(Runnable action) {
        Result previous = result;
        result = Result.CANCELLED;
        try {
            action.run();
            return result;
        } finally {
            result = previous;
        }
    }

    record Result(Packet packet, boolean cancelled) {
        private static final Result CANCELLED = new Result(null, true);
    }

    @FunctionalInterface
    interface WrapperFactory {
        SessionListener wrap(SessionListener observer) throws ReflectiveOperationException;
    }
}
