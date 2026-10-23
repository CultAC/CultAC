package ac.cult.cultac.network;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.logging.PacketLogObserver;
import ac.cult.cultac.network.packet.FrameLease;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.utils.anticheat.LogUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.util.concurrent.EventExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/** Authoritative identity, player attachment and transport state for one channel. */
public final class CultConnection implements CodecState, FrameLease.Source {
    private static final int CONTEXT_SLOTS = 256;

    private final Channel channel;
    private final PlatformConnection platform;
    boolean loginNotified;
    private volatile boolean disconnected;
    /** Preparation reached a final outcome without an inspected user: excluded, refused or failed open. */
    private volatile boolean excluded;

    private final PacketDispatcher dispatcher;
    private final ProjectedPackets packets;
    private final Function<Channel, PacketOwner> ownerResolver;
    private volatile EventExecutor owner;
    // Kept untyped so Java connections do not require the optional Geyser classes.
    private volatile Object bedrockBridge;
    /** How long a client may be in play without a level before a dropped packet is worth a warning. */
    private static final long LEVEL_GRACE_NANOS = TimeUnit.SECONDS.toNanos(10);

    private volatile ConnectionPhase serverbound = ConnectionPhase.HANDSHAKE, clientbound = ConnectionPhase.HANDSHAKE;
    /** The client has no level (login or configuration) and has not been sent the play login that gives it one. */
    private volatile boolean levelPending;
    /** When {@link #levelPending} last became true. */
    private volatile long levelLostNanos = System.nanoTime();

    private volatile boolean levelWarned;

    /** A movement was forwarded since the last forwarded tick end, for a client that ends its ticks. Owner only. */
    private boolean midClientTick;

    private final List<Runnable> afterClientTick = new ArrayList<>();

    private volatile User user;
    private volatile CultPlayer player;
    // Owned by the outbound connection executor. The encoder opens a flush bundle with a
    // leading ping and closes it at the next actual Netty flush.
    private boolean flushBundleOpen;

    /** The encoder has written the opening delimiter of a flush bundle and not yet closed it. */
    public boolean flushBundleOpen() {
        return flushBundleOpen;
    }

    public void flushBundleOpen(boolean open) {
        flushBundleOpen = open;
    }

    /**
     * The CultAC ping whose pong proves the client handled every packet written before this queue position, from
     * inside an outbound write such as an {@link #executeAfterWrites} task; 0 when there is none or no write is being
     * prepared.
     */
    public int provingPing() {
        var source = flushPingSource;
        return source != null && isPreparingWrites() ? source.getAsInt() : 0;
    }

    public void flushPingSource(@org.jetbrains.annotations.Nullable java.util.function.IntSupplier source) {
        flushPingSource = source;
    }

    private boolean ownerResolved, prepared;
    private Consumer<CultConnection> initializer = ignored -> {};
    private BiConsumer<Object, ChannelPromise> reentrantWriter;
    private java.util.function.IntSupplier flushPingSource;
    private volatile CompletableFuture<Void> removed;
    private volatile PacketLogObserver packetObserver = PacketLogObserver.NONE;
    private io.netty.util.concurrent.GenericFutureListener<io.netty.util.concurrent.Future<? super Void>> closeListener;
    private volatile ConnectionContinuation continuation;
    /** Codec contexts bound to this connection, by base identity; a slot holds one immutable context or none. */
    private final ProtocolContext[] contexts = new ProtocolContext[CONTEXT_SLOTS];

    private final FrameLease.Lender frameLender = new FrameLease.Lender();

    public void packetObserver(PacketLogObserver observer) {
        packetObserver = Objects.requireNonNull(observer);
    }

    public boolean packetLoggingActive() {
        try {
            return packetObserver.active();
        } catch (Throwable failure) {
            CultNetworkManager.reportFailure("packet log status", failure);
            stopPacketLog("INCOMPLETE: observer failure");
            return false;
        }
    }

    public void observePacket(PacketDirection direction, ConnectionPhase phase, io.netty.buffer.ByteBuf frame) {
        try {
            packetObserver.packet(direction, phase, frame);
        } catch (Throwable failure) {
            CultNetworkManager.reportFailure("packet logging", failure);
            stopPacketLog("INCOMPLETE: observer failure");
        }
    }

    public void notifyPacketLogIdentity() {
        try {
            if (user != null) packetObserver.identity(user.getProfile());
        } catch (Throwable failure) {
            CultNetworkManager.reportFailure("packet log identity", failure);
            stopPacketLog("INCOMPLETE: identity failure");
        }
    }

    public void stopPacketLog(String reason) {
        try {
            packetObserver.stop(reason);
        } catch (Throwable failure) {
            CultNetworkManager.reportFailure("packet log stop", failure);
        }
    }

    public CultConnection(Channel channel, PacketDispatcher dispatcher, Function<Channel, PacketOwner> resolver) {
        this(null, channel, dispatcher, resolver);
    }

    public CultConnection(
            PlatformConnection platform,
            Channel channel,
            PacketDispatcher dispatcher,
            Function<Channel, PacketOwner> resolver) {
        this.platform = platform;
        this.channel = Objects.requireNonNull(channel);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        var version = platform == null ? null : platform.getObservedProtocol();
        var model = dispatcher.runtime();
        var observed = version == null || version == model.data().version()
                ? model
                : model.forData(ac.cult.cultac.protocol.data.ProtocolData.load(version));
        packets = new ProjectedPackets(observed, model, () -> platform.packetValues(this));
        ownerResolver = Objects.requireNonNull(resolver);
        owner = channel.eventLoop();
    }

    public Channel channel() {
        return channel;
    }

    public PlatformConnection platform() {
        return platform;
    }

    @Override
    public <T> T require(Class<T> type) {
        if (type == ac.cult.cultac.utils.latency.ClientWorldRegistries.class) {
            var attached = player;
            return attached == null ? null : type.cast(attached.getWorldRegistries());
        }
        if (type == ac.cult.cultac.protocol.data.RegistryNames.class && platform != null)
            return type.cast(platform.registryNames());
        return CodecState.EMPTY.require(type);
    }

    /** Lends the clientbound frames the encoder owns to the values decoded from them. Model context only. */
    public FrameLease.Lender frameLender() {
        return frameLender;
    }

    @Override
    public FrameLease lease(ByteBuf frame) {
        return frameLender.lease(frame);
    }

    @Override
    public ProtocolContext bind(ProtocolContext base) {
        int slot = System.identityHashCode(base) & (CONTEXT_SLOTS - 1);
        ProtocolContext bound = contexts[slot];
        if (bound == null || !bound.binds(base, this)) contexts[slot] = bound = base.withState(this);
        return bound;
    }

    /**
     * Only an attached player consumes model values; without one, values that need it are skipped, as are
     * chunks of a level whose dimension type is unknown.
     */
    @Override
    public boolean requiresValues() {
        var attached = player;
        return attached != null && !attached.compensatedWorld.isClientboundUnresolved();
    }

    public boolean disconnected() {
        return disconnected;
    }

    boolean excluded() {
        return excluded;
    }

    void exclude() {
        excluded = true;
    }

    synchronized boolean markDisconnected() {
        if (disconnected) return false;
        disconnected = true;
        return true;
    }

    public PacketDispatcher dispatcher() {
        return dispatcher;
    }

    public ProtocolRuntime runtime() {
        return packets.observed();
    }

    /** Packet IDs and schemas at this connection's actual observation point. */
    public ac.cult.cultac.protocol.ProtocolVersion getObservedProtocol() {
        return packets.observed().data().version();
    }

    /** Original client identity; unknown before authentication must not become a model-version guess. */
    @org.jetbrains.annotations.Nullable
    public ac.cult.cultac.protocol.ProtocolVersion getClientProtocol() {
        return platform == null ? null : platform.getClientProtocol();
    }

    public ProjectedPackets packets() {
        return packets;
    }

    public EventExecutor owner() {
        return owner;
    }
    /** The Geyser session whose Java projection this connection carries, decided once with the owner. */
    public Object bedrockBridge() {
        return bedrockBridge;
    }

    void bedrockBridge(Object bridge) {
        bedrockBridge = bridge;
    }

    public User user() {
        return user;
    }

    public CultPlayer player() {
        return player;
    }

    public ConnectionPhase phase(PacketDirection direction) {
        return direction == PacketDirection.SERVERBOUND ? serverbound : clientbound;
    }

    public void phase(PacketDirection direction, ConnectionPhase phase) {
        if (direction == PacketDirection.SERVERBOUND) serverbound = Objects.requireNonNull(phase);
        else {
            clientbound = Objects.requireNonNull(phase);
            if (phase == ConnectionPhase.PLAY) levelPending = false;
            else levelLost();
        }
    }

    /**
     * Whether the client is in play with a level: play-only packets are valid to it. Between a configuration and the
     * play login that follows it, the client is in play but has no player or level to apply them to.
     */
    public boolean clientHasLevel() {
        return clientbound == ConnectionPhase.PLAY && !levelPending;
    }
    /** Geyser's downstream channel becomes identifiable before its first handshake is read. */
    public void resolveOwner() {
        if (!ownerResolved
                && (serverbound == ConnectionPhase.HANDSHAKE
                        || serverbound == ConnectionPhase.CONFIGURATION
                        || serverbound == ConnectionPhase.PLAY)) {
            PacketOwner resolved = ownerResolver.apply(channel);
            ownerResolved = resolved != null || serverbound != ConnectionPhase.HANDSHAKE;
            if (resolved != null) {
                owner = resolved.executor();
                bedrockBridge = resolved.bedrockBridge();
            }
        }
    }

    public void initializer(Consumer<CultConnection> initializer) {
        this.initializer = Objects.requireNonNull(initializer);
    }

    public void prepare() {
        if (!prepared && (serverbound == ConnectionPhase.CONFIGURATION || serverbound == ConnectionPhase.PLAY)) {
            prepared = true;
            initializer.accept(this);
        }
    }

    public synchronized void bind(User user) {
        if (user.getCultConnection() != this) throw new IllegalArgumentException("User belongs to another session");
        if (this.user != null && this.user != user) throw new IllegalStateException("Session already has an identity");
        if (disconnected) throw new IllegalStateException("Session has disconnected");
        this.user = user;
        notifyPacketLogIdentity();
    }
    /** PlayerDataManager owns attachment mutations under this session's monitor. */
    public void player(CultPlayer player) {
        if (!Thread.holdsLock(this)) throw new IllegalStateException("Player attachment requires the session lock");
        if (player != null && (disconnected || player.user != user))
            throw new IllegalStateException("Invalid player attachment");
        this.player = player;
    }

    public void runInModel(Runnable task) {
        if (platform == null) task.run();
        else platform.runInModel(task);
    }

    public void execute(Runnable task) {
        var host = ac.cult.cultac.runtime.RuntimeEnvironment.host();
        if (host == null) {
            if (owner.inEventLoop()) runInModel(task);
            else owner.execute(() -> runInModel(task));
            return;
        }
        var accepted = host.resources().once(() -> runInModel(task));
        try {
            if (owner.inEventLoop()) accepted.run();
            else owner.execute(accepted);
        } catch (RuntimeException | Error failure) {
            try {
                ((AutoCloseable) accepted).close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public void executeLater(Runnable task) {
        Runnable accepted = ac.cult.cultac.runtime.RuntimeEnvironment.once(() -> runInModel(task));
        try {
            owner.execute(accepted);
        } catch (RuntimeException | Error failure) {
            if (accepted instanceof AutoCloseable cancellable) {
                try {
                    cancellable.close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }
    /** Allocate packet proofs only once earlier outbound work has reached its queue position. */
    public ChannelFuture executeAfterWrites(Runnable task) {
        return writeMessage(new WriteTask(Objects.requireNonNull(task)));
    }

    public boolean isPreparingWrites() {
        return owner.inEventLoop() && reentrantWriter != null;
    }

    public record WriteTask(Runnable action) {}

    public void reentrantWriter(BiConsumer<Object, ChannelPromise> writer) {
        reentrantWriter = writer;
    }

    public ChannelFuture write(CultWrite message) {
        return writeMessage(message);
    }

    public ChannelFuture write(List<CultWrite> writes, boolean bundle) {
        return writeMessage(new WriteGroup(List.copyOf(writes), bundle));
    }

    private ChannelFuture writeMessage(Object message) {
        var promise = channel.newPromise();
        if (owner.inEventLoop() && reentrantWriter != null) reentrantWriter.accept(message, promise);
        else
            onChannelLoop(() -> {
                if (removed != null) promise.tryFailure(new java.nio.channels.ClosedChannelException());
                else channel.writeAndFlush(message, promise);
            });
        return promise;
    }

    /** The flush {@link #executeAfterWrites} issues for a task that writes nothing, without writing the task. */
    public void flushAfterWrites() {
        if (owner.inEventLoop() && reentrantWriter != null) executeAfterWrites(() -> {});
        else
            onChannelLoop(() -> {
                if (removed == null) channel.flush();
            });
    }

    private void onChannelLoop(Runnable send) {
        if (channel.eventLoop().inEventLoop() && (owner == channel.eventLoop() || !owner.inEventLoop())) send.run();
        else channel.eventLoop().execute(send);
    }

    public void forwarded(PacketType<?> type, Object packet) {
        serverbound = ConnectionLifecycle.nextPhase(PacketDirection.SERVERBOUND, serverbound, type, packet);
        clientbound = ConnectionLifecycle.nextPhase(PacketDirection.CLIENTBOUND, clientbound, type, packet);
        if (clientbound != ConnectionPhase.PLAY) levelLost();
        // Registry data is decoded whether or not a player is attached, so each configuration restarts its
        // received registries here for every connection, not only for inspected ones.
        if (type == ClientboundPackets.START_CONFIGURATION && platform != null) {
            var values = platform.modelValues();
            if (values != null) values.beginConfiguration();
        }
        if (type == ServerboundPackets.CLIENT_TICK_END || serverbound != ConnectionPhase.PLAY) clientTickEnded();
        else if (type == ServerboundPackets.MOVE_PLAYER
                && packets.observed().supports(ServerboundPackets.CLIENT_TICK_END)) midClientTick = true;
    }

    /**
     * Whether the server has been forwarded this client's movement and not yet the tick end that follows it: a packet
     * forwarded now lands inside the client's tick. Never for a client without tick ends. On the channel loop.
     */
    public boolean midClientTick() {
        return midClientTick && owner == channel.eventLoop();
    }

    /** Runs {@code task} once, right after the next tick end is forwarded or the client leaves play. On the owner. */
    public void afterClientTick(Runnable task) {
        afterClientTick.add(Objects.requireNonNull(task));
    }

    /** Releases tick-bound tasks after a forwarded tick end, leaving play, or the synthetic tick-end timeout. */
    public void clientTickEnded() {
        midClientTick = false;
        if (afterClientTick.isEmpty()) return;
        var tasks = List.copyOf(afterClientTick);
        afterClientTick.clear();
        for (Runnable task : tasks) {
            try {
                task.run();
            } catch (Throwable failure) {
                CultNetworkManager.reportFailure("client tick end task", failure);
            }
        }
    }

    private void levelLost() {
        if (levelPending) return;
        levelLostNanos = System.nanoTime();
        levelPending = true;
    }

    /**
     * A play-only packet of CultAC's was dropped for want of a level. Warns once per connection when the client has
     * been in play for a while with no play login seen, as CultAC then sends it nothing until the next one.
     */
    public void droppedWithoutLevel(PacketType<?> type) {
        if (levelWarned || clientbound != ConnectionPhase.PLAY) return;
        if (System.nanoTime() - levelLostNanos < LEVEL_GRACE_NANOS) return;
        levelWarned = true;
        var attached = player;
        LogUtil.warn("CultAC saw no play login for "
                + (attached == null ? "a connection" : attached.getName())
                + " since it left its last level; its corrections, starting with " + type + ", are not sent");
    }

    /**
     * {@code type} took its place in the client's stream: packets placed after a play login or respawn reach a level, packets
     * placed after the client leaves play do not. On the writing event loop, before the packet is emitted.
     */
    public void placedClientbound(PacketType<?> type) {
        if (type == ClientboundPackets.LOGIN || type == ClientboundPackets.RESPAWN) levelPending = false;
        else if (type == ClientboundPackets.LOGIN_FINISHED || type == ClientboundPackets.START_CONFIGURATION)
            levelLost();
    }

    /** Run before forwarding the ack to the host, which may immediately connect the next backend. */
    public boolean handoffAtReconfiguration() {
        if (!owner.inEventLoop()) throw new IllegalStateException("Hand-off requires the packet owner");
        var host = ac.cult.cultac.runtime.RuntimeEnvironment.host();
        if (host == null || !host.platform().equals("velocity")) return true;
        var player = this.player;
        try {
            host.handoff(
                    channel,
                    player == null || player.platformPlayer == null ? null : player.platformPlayer.getNative());
            return true;
        } catch (Throwable failure) {
            // The releasing generation's config decides: the adopting one failed and may not have loaded.
            if (CultAPI.INSTANCE.getConfigManager().failsOpen()) {
                LogUtil.warn(
                        "Not inspecting " + (user == null ? channel.remoteAddress() : user.getName())
                                + " any more; the runtime hand-off failed",
                        failure);
                return true;
            }
            CultNetworkManager.reportFailure("runtime hand-off", failure);
            channel.close();
            return false;
        }
    }

    public java.util.concurrent.CompletionStage<Void> removeHandlers(Runnable remove) {
        if (!channel.eventLoop().inEventLoop() && !owner.inEventLoop())
            throw new IllegalStateException("Handler removal requires I/O or the packet owner");
        if (removed != null) return removed;
        removed = new CompletableFuture<>();
        try {
            remove.run();
            removed.complete(null);
        } catch (Throwable failure) {
            removed.completeExceptionally(failure);
        } finally {
            stopPacketLog("transport removal");
        }
        return removed;
    }

    void closeListener(
            io.netty.util.concurrent.GenericFutureListener<io.netty.util.concurrent.Future<? super Void>> listener) {
        closeListener = listener;
        channel.closeFuture().addListener(listener);
    }

    /** The session no longer owns the channel; its closure must not reach this engine. */
    void detachCloseListener() {
        var listener = closeListener;
        closeListener = null;
        if (listener != null) channel.closeFuture().removeListener(listener);
    }

    /** State another engine received on this connection; set before the session prepares. */
    public void continuation(ConnectionContinuation continuation) {
        this.continuation = continuation;
    }

    public ConnectionContinuation continuation() {
        return continuation;
    }

    public record WriteGroup(List<CultWrite> writes, boolean bundle) {}
}
