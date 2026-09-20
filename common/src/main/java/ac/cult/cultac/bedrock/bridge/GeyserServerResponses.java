package ac.cult.cultac.bedrock.bridge;

import java.util.List;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.*;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;

/** Marks responses produced by server packet translation, never by a Bedrock input. */
final class GeyserServerResponses {
    static final String CHANNEL = "cultac:server_response/" + java.util.UUID.randomUUID();
    private final GeyserSession session;
    // These fields belong to Geyser's downstream I/O loop, where packetSending runs.
    private boolean translating;
    private boolean marked;
    private SessionAdapter installed;
    private List<SessionListener> original;

    GeyserServerResponses(GeyserSession session) { this.session = session; }

    void translate(Runnable translation) {
        var downstream = session.getDownstream().getSession();
        var loop = downstream.getChannel().eventLoop();
        // GeyserSession#sendDownstreamPacket queues each response on this same loop.
        loop.execute(() -> translating = true);
        try { translation.run(); }
        finally {
            loop.execute(() -> {
                if (marked) downstream.send(GeyserInputQueue.markerPacket(CHANNEL, new byte[]{0}));
                marked = false;
                translating = false;
            });
        }
    }

    void sending(PacketSendingEvent event) {
        if (!translating || marked || event.isCancelled()) return;
        Packet packet = event.getPacket();
        if (!(packet instanceof ServerboundMovePlayerPosPacket
                || packet instanceof ServerboundMovePlayerPosRotPacket
                || packet instanceof ServerboundMoveVehiclePacket)) return;
        marked = true;
        event.getSession().send(GeyserInputQueue.markerPacket(CHANNEL, new byte[]{1}));
    }

    /** Without GFP there is no adapter dispatch wrapper, so wrap Geyser's listeners directly. */
    void install() {
        if (session.getDownstream() == null) return;
        var downstream = session.getDownstream().getSession();
        if (installed != null) return;
        original = List.copyOf(downstream.getListeners());
        installed = new SessionAdapter() {
            @Override public void packetReceived(Session source, Packet packet) {
                session.ensureInEventLoop(() -> translate(() -> original.forEach(l -> l.packetReceived(source, packet))));
            }
            @Override public void packetSending(PacketSendingEvent event) {
                original.forEach(l -> l.packetSending(event));
                sending(event);
            }
            @Override public void packetSent(Session source, Packet packet) { original.forEach(l -> l.packetSent(source, packet)); }
            @Override public void connected(ConnectedEvent event) { original.forEach(l -> l.connected(event)); }
            @Override public void disconnected(DisconnectedEvent event) { original.forEach(l -> l.disconnected(event)); }
            @Override public void packetError(PacketErrorEvent event) { original.forEach(l -> l.packetError(event)); }
        };
        original.forEach(downstream::removeListener);
        downstream.addListener(installed);
    }

    void close() {
        if (installed == null || session.getDownstream() == null) return;
        var downstream = session.getDownstream().getSession();
        downstream.removeListener(installed);
        original.forEach(downstream::addListener);
        installed = null;
    }
}
