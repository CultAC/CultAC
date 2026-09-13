package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.protocol.BedrockTeleportProvenance;
import ac.cult.cultac.utils.anticheat.LogUtil;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.UpstreamSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.*;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;

/** Optional local-Geyser integration for oryxel1 GFP 2.0 Build 5 (9f242a3). */
final class GeyserFloatingPointsAdapter {
    private final GeyserSession session;
    private final GfpReflection reflection;
    private final Object user;
    private final Field upstreamField;
    private final Field postStartGamePackets;
    private final BedrockOriginDispatch dispatch = new BedrockOriginDispatch();
    private final LongSupplier authTick;
    private final LongConsumer suppressedProjection;
    private UpstreamHook upstreamHook;
    private JavaHook javaHook;
    private boolean closed;

    static boolean present() {
        return ac.cult.cultac.utils.floodgate.GeyserUtil.isGeyserAvailable()
                && GeyserApi.api().extensionManager().extension("geyserfloatingpoints") != null;
    }

    GeyserFloatingPointsAdapter(GeyserSession session, LongSupplier authTick, LongConsumer suppressedProjection)
            throws ReflectiveOperationException {
        this(session, enabledReflection(), authTick, suppressedProjection);
    }

    private static GfpReflection enabledReflection() throws ReflectiveOperationException {
        Extension extension = GeyserApi.api().extensionManager().extension("geyserfloatingpoints");
        if (extension == null || !extension.isEnabled()) throw new IllegalStateException("GFP is not enabled");
        return new GfpReflection(extension);
    }

    GeyserFloatingPointsAdapter(GeyserSession session, GfpReflection reflection,
            LongSupplier authTick, LongConsumer suppressedProjection) throws ReflectiveOperationException {
        this.session = session;
        this.reflection = reflection;
        this.authTick = authTick;
        this.suppressedProjection = suppressedProjection;
        user = reflection.user(session.getUpstream(), session);
        if (!Vector3i.ZERO.equals(reflection.offset(user))) {
            throw new IllegalStateException("GFP origin changed before CultAC attached");
        }
        upstreamField = GeyserSession.class.getDeclaredField("upstream");
        upstreamField.setAccessible(true);
        postStartGamePackets = UpstreamSession.class.getDeclaredField("postStartGamePackets");
        postStartGamePackets.setAccessible(true);
        refresh(false);
    }

    synchronized void refresh(boolean requireJavaHook) throws ReflectiveOperationException {
        if (closed) throw new IllegalStateException("GFP adapter is closed");
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
        List<SessionListener> current = new ArrayList<>(installed);
        for (int i = 0; i < current.size(); i++) {
            SessionListener listener = current.get(i);
            if (!reflection.isAdapter(listener)) continue;
            List<SessionListener> delegates = new ArrayList<>();
            boolean replacedWrapper = false;
            for (SessionListener delegate : reflection.delegates(listener, user)) {
                if (delegate instanceof JavaHook hook) {
                    delegates.addAll(hook.delegates);
                    replacedWrapper = true;
                }
                else delegates.add(delegate);
            }
            // A replacement GFP wrapper may have captured our previous hook. Teardown must
            // restore GFP with its real delegates, without retaining a closed CultAC hook.
            SessionListener original = replacedWrapper ? reflection.adapter(user, List.copyOf(delegates)) : listener;
            javaHook = new JavaHook(original, List.copyOf(delegates));
            current.set(i, javaHook);
            replaceListeners(downstream, current);
            return;
        }
        if (requireJavaHook) throw new IllegalStateException("Missing GFP Java dispatch wrapper");
    }

    synchronized BedrockOriginDispatch.Emission takeEmission(BedrockPacket packet) {
        return dispatch.take(packet);
    }

    synchronized void close() {
        closed = true;
        dispatch.clear();
        try {
            if (session.getUpstream() == upstreamHook) upstreamField.set(session, upstreamHook.delegate);
            if (javaHook != null && session.getDownstream() != null) {
                var downstream = session.getDownstream().getSession();
                List<SessionListener> listeners = new ArrayList<>(downstream.getListeners());
                int index = listeners.indexOf(javaHook);
                if (index >= 0) {
                    listeners.set(index, javaHook.original);
                    replaceListeners(downstream, listeners);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            fail(failure);
        }
    }

    private static void replaceListeners(Session session, List<SessionListener> replacements) {
        new ArrayList<>(session.getListeners()).forEach(session::removeListener);
        replacements.forEach(session::addListener);
    }

    private void fail(Throwable failure) {
        closed = true;
        LogUtil.warn("GeyserFloatingPoints compatibility failure: " + failure);
        session.disconnect("CultAC: incompatible GeyserFloatingPoints hooks. Use oryxel1 2.0 Build 5 and reconnect.");
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
            synchronized (GeyserFloatingPointsAdapter.this) {
                Runnable write = () -> {
                    if (immediate) delegate.sendPacketImmediately(packet);
                    else delegate.sendPacket(packet);
                    // GFP establishes its Java wrapper inside the StartGame send.
                    if (packet instanceof StartGamePacket && !closed && upstreamHook == this) {
                        try { refresh(true); } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { fail(failure); }
                    }
                };
                if (closed || upstreamHook != this) write.run();
                else dispatch.enqueue(packet, write);
            }
        }

        @Override public void disconnect(String reason) { delegate.disconnect(reason); }
        @Override public int getProtocolVersion() { return delegate.getProtocolVersion(); }
        @Override public boolean isInitialized() { return delegate.isInitialized(); }
        @Override public void setInitialized(boolean initialized) { delegate.setInitialized(initialized); }
        @Override public void queuePostStartGamePacket(BedrockPacket packet) { delegate.queuePostStartGamePacket(packet); }
        @SuppressWarnings("unchecked")
        @Override public void sendPostStartGamePackets() {
            synchronized (GeyserFloatingPointsAdapter.this) {
                if (isClosed()) return;
                try {
                    // UpstreamSession drains directly to BedrockServerSession; keep those writes inside
                    // the same origin enqueue boundary as ordinary packets, including queued packets.
                    Queue<BedrockPacket> packets = (Queue<BedrockPacket>) postStartGamePackets.get(delegate);
                    BedrockPacket packet;
                    while ((packet = packets.poll()) != null) sendPacket(packet);
                    postStartGamePackets.set(delegate, null);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { fail(failure); }
            }
        }
    }

    private final class JavaHook extends SessionAdapter {
        private final SessionListener original;
        private final List<SessionListener> delegates;

        private JavaHook(SessionListener original, List<SessionListener> delegates) {
            this.original = original;
            this.delegates = delegates;
        }

        @Override public void packetReceived(Session downstream, Packet packet) {
            // Geyser's translator dispatch also uses this loop. Running the complete GFP dispatch here
            // keeps rewriting and translation together instead of reading an offset from a later task.
            session.ensureInEventLoop(() -> {
                synchronized (GeyserFloatingPointsAdapter.this) {
                    if (closed) return;
                    dispatch.begin();
                    try {
                        GfpReflection.Rewrite result = reflection.rewrite(user, packet, false);
                        Vector3i offset = reflection.offset(user);
                        Integer id = packet instanceof ClientboundPlayerPositionPacket teleport ? teleport.getId() : null;
                        BedrockTeleportProvenance source = id == null
                                ? BedrockTeleportProvenance.GEYSER : BedrockTeleportProvenance.JAVA_TELEPORT;
                        dispatch.finish(offset.getX(), offset.getZ(), source, id);
                        // Rewriting has completed. Geyser may synchronously send a Java echo which
                        // triggers another GFP rebase; earlier emissions must retain this origin.
                        if (!result.cancelled()) dispatch.withContext(source, id,
                                () -> delegates.forEach(l -> l.packetReceived(downstream, result.packet())));
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        dispatch.clear();
                        fail(failure);
                    }
                }
            });
        }

        @Override public void packetSending(PacketSendingEvent event) {
            synchronized (GeyserFloatingPointsAdapter.this) {
                if (closed) { event.setCancelled(true); return; }
                dispatch.begin();
                try {
                    long tick = authTick.getAsLong();
                    Packet originalPacket = event.getPacket();
                    // Build 5 omits this propagation and otherwise leaks cancelled, unshifted projections.
                    GfpReflection.Rewrite result = reflection.sending(user, event, delegates);
                    Vector3i offset = reflection.offset(user);
                    boolean rebase = result.cancelled() && (originalPacket instanceof ServerboundMovePlayerPosPacket
                            || originalPacket instanceof ServerboundMovePlayerPosRotPacket
                            || originalPacket instanceof ServerboundMoveVehiclePacket);
                    dispatch.finish(offset.getX(), offset.getZ(), rebase
                            ? BedrockTeleportProvenance.GFP_REBASE : BedrockTeleportProvenance.GEYSER, null);
                    if (rebase) suppressedProjection.accept(tick);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    event.setCancelled(true);
                    dispatch.clear();
                    fail(failure);
                }
            }
        }

        @Override public void packetSent(Session session, Packet packet) { delegates.forEach(l -> l.packetSent(session, packet)); }
        @Override public void connected(ConnectedEvent event) { delegates.forEach(l -> l.connected(event)); }
        @Override public void disconnected(DisconnectedEvent event) { delegates.forEach(l -> l.disconnected(event)); }
        @Override public void packetError(PacketErrorEvent event) { delegates.forEach(l -> l.packetError(event)); }
    }
}
