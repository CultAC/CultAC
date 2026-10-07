package ac.cult.cultac.network;

import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.*;
import io.netty.channel.*;
import io.netty.util.concurrent.EventExecutor;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/** Authoritative identity, player attachment and transport state for one channel. */
public final class CultConnection implements CodecState {
    private final Channel channel;
    private final PlatformConnection platform;
    boolean loginNotified;
    private volatile boolean disconnected;
    private final PacketDispatcher dispatcher;
    private final ProjectedPackets packets;
    private final Function<Channel, PacketOwner> ownerResolver;
    private volatile EventExecutor owner;
    // Kept untyped so Java connections do not require the optional Geyser classes.
    private volatile Object bedrockBridge;
    private volatile ConnectionPhase serverbound = ConnectionPhase.HANDSHAKE, clientbound = ConnectionPhase.HANDSHAKE;
    private volatile User user;
    private volatile CultPlayer player;
    private boolean ownerResolved, prepared;
    private Consumer<CultConnection> initializer = ignored -> {};
    private BiConsumer<Object, ChannelPromise> reentrantWriter;
    private volatile CompletableFuture<Void> removed;

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
        if (type == ac.cult.cultac.utils.latency.ClientWorldRegistries.class && player != null)
            return type.cast(player.getWorldRegistries());
        if (type == ac.cult.cultac.protocol.data.RegistryNames.class && platform != null)
            return type.cast(platform.registryNames());
        return CodecState.EMPTY.require(type);
    }

    public boolean disconnected() {
        return disconnected;
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
        else clientbound = Objects.requireNonNull(phase);
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
        if (owner.inEventLoop()) runInModel(task);
        else owner.execute(() -> runInModel(task));
    }

    public void executeLater(Runnable task) {
        owner.execute(() -> runInModel(task));
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
        else {
            Runnable send = () -> {
                if (removed != null) promise.tryFailure(new java.nio.channels.ClosedChannelException());
                else channel.writeAndFlush(message, promise);
            };
            if (channel.eventLoop().inEventLoop() && (owner == channel.eventLoop() || !owner.inEventLoop())) send.run();
            else channel.eventLoop().execute(send);
        }
        return promise;
    }

    public void forwarded(PacketType<?> type, Object packet) {
        serverbound = ConnectionLifecycle.nextPhase(PacketDirection.SERVERBOUND, serverbound, type, packet);
        clientbound = ConnectionLifecycle.nextPhase(PacketDirection.CLIENTBOUND, clientbound, type, packet);
    }

    public java.util.concurrent.CompletionStage<Void> removeHandlers(Runnable remove) {
        if (!channel.eventLoop().inEventLoop()) throw new IllegalStateException("Handler removal requires I/O");
        if (removed != null) return removed;
        removed = new CompletableFuture<>();
        try {
            remove.run();
            removed.complete(null);
        } catch (Throwable failure) {
            removed.completeExceptionally(failure);
        }
        return removed;
    }

    public record WriteGroup(List<CultWrite> writes, boolean bundle) {}
}
