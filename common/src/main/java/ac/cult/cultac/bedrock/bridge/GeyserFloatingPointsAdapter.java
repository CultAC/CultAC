package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportOperation;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportProvenance;
import ac.cult.cultac.utils.anticheat.LogUtil;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.TeleportCache;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.PacketSendingEvent;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;

final class GeyserFloatingPointsAdapter {
    private final GeyserSession session;
    private final GfpSessionHooks hooks;
    private final BedrockOriginDispatch dispatch = new BedrockOriginDispatch();
    private boolean closed;
    private long operationSequence;
    private TeleportCache teleportCache;
    private BedrockTeleportOperation cacheOperation;

    static boolean present() {
        return ac.cult.cultac.utils.floodgate.GeyserUtil.isGeyserAvailable()
                && GeyserApi.api().extensionManager().extension("geyserfloatingpoints") != null;
    }

    GeyserFloatingPointsAdapter(GeyserSession session) throws ReflectiveOperationException {
        this(session, enabledReflection());
    }

    private static GfpReflection enabledReflection() throws ReflectiveOperationException {
        Extension extension = GeyserApi.api().extensionManager().extension("geyserfloatingpoints");
        if (extension == null || !extension.isEnabled()) throw new IllegalStateException("GFP is not enabled");
        return new GfpReflection(extension.getClass().getClassLoader());
    }

    GeyserFloatingPointsAdapter(GeyserSession session, GfpReflection reflection) throws ReflectiveOperationException {
        this.session = session;
        hooks = new GfpSessionHooks(this, session, reflection);
        synchronized (this) {
            try {
                hooks.attach();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                closed = true;
                try { hooks.detach(); }
                catch (ReflectiveOperationException | RuntimeException | LinkageError cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }

    synchronized void refresh(boolean requireJavaHook) throws ReflectiveOperationException {
        if (closed) throw new IllegalStateException("GFP adapter is closed");
        hooks.refresh(requireJavaHook);
    }

    void receive(GfpSessionHooks.JavaHook hook, Session downstream, Packet packet) {
        session.ensureInEventLoop(() -> {
            synchronized (this) {
                if (closed) return;
                TeleportCache beforeCache = session.getUnconfirmedTeleport();
                BedrockTeleportOperation operation = newOperation(BedrockTeleportProvenance.GEYSER);
                dispatch.begin();
                try {
                    var rewritten = hook.rewriter.receive(downstream, packet);
                    finishRewrite(hooks.offset(), operation);
                    if (!rewritten.cancelled()) {
                        dispatch.withOperation(operation,
                                () -> hook.delegates.forEach(listener -> listener.packetReceived(downstream, rewritten.packet())));
                    }
                    bindTeleportCache(beforeCache, operation);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    fail(failure);
                }
            }
        });
    }

    synchronized void send(GfpSessionHooks.JavaHook hook, PacketSendingEvent event) {
        if (closed) { event.setCancelled(true); return; }
        TeleportCache beforeCache = session.getUnconfirmedTeleport();
        dispatch.begin();
        try {
            Packet originalPacket = event.getPacket();
            Vector3i beforeOffset = hooks.offset();
            var rewritten = hook.rewriter.send(event);
            Vector3i afterOffset = hooks.offset();
            boolean rebase = rewritten.cancelled() && !afterOffset.equals(beforeOffset) && hasPosition(originalPacket);
            BedrockTeleportOperation operation = rebase ? newOperation(BedrockTeleportProvenance.GFP_REBASE) : null;
            finishRewrite(afterOffset, operation);
            bindTeleportCache(beforeCache, operation);
            if (!rewritten.cancelled()) hook.delegates.forEach(listener -> listener.packetSending(event));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            event.setCancelled(true);
            fail(failure);
        }
    }

    private static boolean hasPosition(Packet packet) {
        return packet instanceof ServerboundMovePlayerPosPacket
                || packet instanceof ServerboundMovePlayerPosRotPacket
                || packet instanceof ServerboundMoveVehiclePacket;
    }

    private void finishRewrite(Vector3i offset, BedrockTeleportOperation operation) {
        dispatch.finish(offset.getX(), offset.getZ(), operation);
    }

    private BedrockTeleportOperation newOperation(BedrockTeleportProvenance source) {
        return new BedrockTeleportOperation(++operationSequence, source, null);
    }

    private void bindTeleportCache(TeleportCache before, BedrockTeleportOperation operation) {
        TeleportCache after = session.getUnconfirmedTeleport();
        if (after != before && after != teleportCache) {
            teleportCache = after;
            cacheOperation = after == null ? null : operation;
        }
    }

    synchronized void withTeleportRetry(Runnable action) {
        TeleportCache current = session.getUnconfirmedTeleport();
        if (current != teleportCache) {
            teleportCache = null;
            cacheOperation = null;
        }
        dispatch.withOperation(current == null ? null : cacheOperation, action);
        if (session.getUnconfirmedTeleport() == null) {
            teleportCache = null;
            cacheOperation = null;
        }
    }

    synchronized void enqueue(BedrockPacket packet, Runnable write) {
        if (closed) write.run();
        else dispatch.enqueue(packet, write);
    }

    synchronized boolean isClosed() { return closed; }
    synchronized BedrockOriginDispatch.Emission takeEmission(BedrockPacket packet) { return dispatch.take(packet); }
    synchronized BedrockCoordinateFrame coordinateFrame() { return dispatch.frame(); }

    synchronized void close() {
        closed = true;
        clear();
        try { hooks.detach(); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { fail(failure); }
    }

    synchronized void fail(Throwable failure) {
        closed = true;
        clear();
        LogUtil.warn("GeyserFloatingPoints compatibility failure: " + failure);
        session.disconnect("CultAC: incompatible GeyserFloatingPoints hooks. Use oryxel1 2.0 Build 5 and reconnect.");
    }

    private void clear() {
        teleportCache = null;
        cacheOperation = null;
        dispatch.clear();
    }
}
