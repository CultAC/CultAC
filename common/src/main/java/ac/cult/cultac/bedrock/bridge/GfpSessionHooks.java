package ac.cult.cultac.bedrock.bridge;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.UpstreamSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.*;
import org.geysermc.mcprotocollib.network.packet.Packet;

/** Attaches/restores the Java listener and all Bedrock emission paths. Owned by the adapter monitor. */
final class GfpSessionHooks {
    private final GeyserFloatingPointsAdapter owner;
    private final GeyserSession session;
    private final GfpReflection reflection;
    private final Object user;
    private final Field upstreamField;
    private final Field postStartGamePackets;
    private final Field immediatePacketsField;
    private final List<BedrockPacket> immediatePackets;
    private final GeyserImmediatePacketQueue immediateHook;
    private UpstreamHook upstreamHook;
    private JavaHook javaHook;

    GfpSessionHooks(GeyserFloatingPointsAdapter owner, GeyserSession session, GfpReflection reflection)
            throws ReflectiveOperationException {
        this.owner = owner;
        this.session = session;
        this.reflection = reflection;
        user = reflection.user(session.getUpstream(), session);
        if (!Vector3i.ZERO.equals(offset())) throw new IllegalStateException("GFP origin changed before CultAC attached");
        upstreamField = field(GeyserSession.class, "upstream");
        postStartGamePackets = field(UpstreamSession.class, "postStartGamePackets");
        immediatePacketsField = field(GeyserSession.class, "queuedImmediatelyPackets");
        immediatePackets = session.getQueuedImmediatelyPackets();
        if (!immediatePackets.isEmpty()) throw new IllegalStateException("GFP tick-end packets queued before CultAC attached");
        immediateHook = new GeyserImmediatePacketQueue(immediatePackets, owner::enqueue);
    }

    void attach() throws ReflectiveOperationException {
        immediatePacketsField.set(session, immediateHook);
        refresh(false);
    }

    Vector3i offset() throws ReflectiveOperationException { return reflection.offset(user); }

    void refresh(boolean requireJavaHook) throws ReflectiveOperationException {
        if (session.getUpstream() != upstreamHook) {
            UpstreamSession current = session.getUpstream();
            if (reflection.user(current, session) != user) throw new IllegalStateException("GFP user was replaced");
            upstreamHook = new UpstreamHook(current);
            upstreamField.set(session, upstreamHook);
        }
        if (session.getDownstream() == null || session.getDownstream().getSession() == null) {
            if (requireJavaHook) throw new IllegalStateException("Missing Geyser downstream");
            return;
        }
        var downstream = session.getDownstream().getSession();
        List<SessionListener> installed = downstream.getListeners();
        if (javaHook != null && installed.contains(javaHook)) return;
        List<SessionListener> replacements = new ArrayList<>(installed);
        for (int i = 0; i < replacements.size(); i++) {
            SessionListener listener = replacements.get(i);
            if (!reflection.isAdapter(listener)) continue;
            List<SessionListener> delegates = new ArrayList<>();
            boolean replacedWrapper = false;
            for (SessionListener delegate : reflection.delegates(listener, user)) {
                if (delegate instanceof JavaHook hook) {
                    delegates.addAll(hook.delegates);
                    replacedWrapper = true;
                } else delegates.add(delegate);
            }
            // GFP can wrap our installed hook again on StartGame. Restore only real delegates.
            SessionListener original = replacedWrapper ? reflection.adapter(user, List.copyOf(delegates)) : listener;
            javaHook = new JavaHook(original, List.copyOf(delegates));
            replacements.set(i, javaHook);
            replaceListeners(downstream, replacements);
            return;
        }
        if (requireJavaHook) throw new IllegalStateException("Missing GFP Java dispatch wrapper");
    }

    void detach() throws ReflectiveOperationException {
        if (session.getQueuedImmediatelyPackets() == immediateHook) immediatePacketsField.set(session, immediatePackets);
        if (session.getUpstream() == upstreamHook) upstreamField.set(session, upstreamHook.delegate);
        if (javaHook != null && session.getDownstream() != null) {
            var downstream = session.getDownstream().getSession();
            if (downstream == null) return;
            List<SessionListener> listeners = new ArrayList<>(downstream.getListeners());
            int index = listeners.indexOf(javaHook);
            if (index >= 0) {
                listeners.set(index, javaHook.original);
                replaceListeners(downstream, listeners);
            }
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void replaceListeners(Session session, List<SessionListener> replacements) {
        new ArrayList<>(session.getListeners()).forEach(session::removeListener);
        replacements.forEach(session::addListener);
    }

    private final class UpstreamHook extends UpstreamSession {
        private final UpstreamSession delegate;

        private UpstreamHook(UpstreamSession delegate) {
            super(delegate.getSession());
            this.delegate = delegate;
        }

        @Override public void sendPacket(BedrockPacket packet) { enqueue(packet, false); }
        @Override public void sendPacketImmediately(BedrockPacket packet) { enqueue(packet, true); }

        private void enqueue(BedrockPacket packet, boolean immediate) {
            synchronized (owner) {
                Runnable write = () -> {
                    if (immediate) delegate.sendPacketImmediately(packet);
                    else delegate.sendPacket(packet);
                    // GFP installs its Java wrapper during this call to its upstream wrapper.
                    if (packet instanceof StartGamePacket && !owner.isClosed() && upstreamHook == this) {
                        try { owner.refresh(true); }
                        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { owner.fail(failure); }
                    }
                };
                if (upstreamHook != this) write.run();
                else owner.enqueue(packet, write);
            }
        }

        @Override public void disconnect(String reason) { delegate.disconnect(reason); }
        @Override public int getProtocolVersion() { return delegate.getProtocolVersion(); }
        @Override public boolean isInitialized() { return delegate.isInitialized(); }
        @Override public void setInitialized(boolean initialized) { delegate.setInitialized(initialized); }
        @Override public void queuePostStartGamePacket(BedrockPacket packet) { delegate.queuePostStartGamePacket(packet); }

        @SuppressWarnings("unchecked")
        @Override public void sendPostStartGamePackets() {
            synchronized (owner) {
                if (isClosed()) return;
                try {
                    // Geyser normally sends this queue directly to BedrockServerSession, bypassing us.
                    Queue<BedrockPacket> packets = (Queue<BedrockPacket>) postStartGamePackets.get(delegate);
                    BedrockPacket packet;
                    while ((packet = packets.poll()) != null) sendPacket(packet);
                    postStartGamePackets.set(delegate, null);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { owner.fail(failure); }
            }
        }
    }

    final class JavaHook extends SessionAdapter {
        private final SessionListener original;
        final List<SessionListener> delegates;
        final GfpPacketRewriter rewriter;

        private JavaHook(SessionListener original, List<SessionListener> delegates) throws ReflectiveOperationException {
            this.original = original;
            this.delegates = delegates;
            rewriter = new GfpPacketRewriter(observer -> reflection.adapter(user, List.of(observer)));
        }

        @Override public void packetReceived(Session downstream, Packet packet) { owner.receive(this, downstream, packet); }
        @Override public void packetSending(PacketSendingEvent event) { owner.send(this, event); }
        @Override public void packetSent(Session session, Packet packet) { original.packetSent(session, packet); }
        @Override public void connected(ConnectedEvent event) { original.connected(event); }
        @Override public void disconnected(DisconnectedEvent event) { original.disconnected(event); }
        @Override public void packetError(PacketErrorEvent event) { original.packetError(event); }
    }
}
