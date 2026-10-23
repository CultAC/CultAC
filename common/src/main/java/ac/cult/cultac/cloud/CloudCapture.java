package ac.cult.cultac.cloud;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.cloud.Cloud;
import ac.cult.cultac.checks.impl.combat.EntityReach;
import ac.cult.cultac.checks.impl.combat.PlayerReach;
import ac.cult.cultac.checks.impl.combat.ReachCheck;
import ac.cult.cultac.cloud.capture.ActionRef;
import ac.cult.cultac.cloud.capture.CaptureStream;
import ac.cult.cultac.cloud.capture.ChunkIds;
import ac.cult.cultac.cloud.capture.EngineVerdicts;
import ac.cult.cultac.cloud.capture.PlayerText;
import ac.cult.cultac.cloud.capture.SectionBytes;
import ac.cult.cultac.cloud.capture.Sections;
import ac.cult.cultac.cloud.capture.TickFlags;
import ac.cult.cultac.cloud.diag.Diagnostics;
import ac.cult.cultac.cloud.diag.PredictionDiagnostics;
import ac.cult.cultac.cloud.intake.v3.Outcome;
import ac.cult.cultac.cloud.transport.CloudConnection;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.network.codec.ClientPacketCodecs;
import ac.cult.cultac.platform.api.ServerFacts;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.player.PlayerObserver;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.data.IdTable;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.latency.CompensatedInventory;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.Nullable;

/**
 * A player's upload to the intake service: the capture taps on the player's channel, one segment per backend
 * connection, and each frame paired with the player's verdict on its tick.
 *
 * <p>A frame is one client tick: the packets the client sent, up to and including its {@code client_tick_end}, the
 * packets it received in between, and a {@code VERDICT} with the tick's movement conditions, the prediction engine's
 * state, the crosshair's sight of the last attacked target, each attack's lag-compensated geometry and the checks that
 * flagged. The taps sit where the bytes are the client's own: inbound ahead of the first decoder, outbound past the last
 * encoder that writes the client's protocol. Both run on the channel's event loop, which cuts the frame when the inbound
 * tap sees the {@code client_tick_end}; the tick's verdict follows from the tick-end listeners, numbered by the player's
 * count of tick ends, and {@link CaptureStream} pairs the two.
 *
 * <p>A segment starts at each clientbound play {@code login} and ends at the next {@code start_configuration} or the
 * disconnect. The {@link SegmentBinder} names the backend a segment belongs to, and the segment uploads on that
 * backend's socket; frames wait until it is bound and the service has answered its JOIN. Java clients are streamed;
 * Bedrock players are only announced, with a JOIN saying why and a LEAVE.
 */
public final class CloudCapture implements PlayerObserver {
    static final String INBOUND = "cult-cloud-in";
    static final String OUTBOUND = "cult-cloud-out";
    /** The codecs that can come first in a pipeline: ViaVersion's, CultAC's, then the host's on Paper and Velocity. */
    private static final Set<String> DECODERS = Set.of("via-decoder", "cult-decoder", "decoder", "minecraft-decoder");

    private static final Set<String> ENCODERS = Set.of("via-encoder", "cult-encoder", "encoder", "minecraft-encoder");
    /** The first protocol with {@code client_tick_end}. */
    static final int FIRST_PROTOCOL = 768;
    /** Capture frames retained until they are sent or dropped, across every player. */
    static final long MAX_HELD_BYTES = 64L * 1024 * 1024;

    static final Budget HELD = new Budget(MAX_HELD_BYTES);
    /** How long a segment waits for its backend's name before it is discarded. */
    static final long BIND_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    /** Ticks after an attack the upload is told whether the crosshair is on its target. */
    private static final int SIGHT_TICKS = 100;

    private static final double PICK_RANGE = 6;
    private static final int MAX_EYES = 8;
    /** The last protocol whose packet ids and player text codecs a capture knows. */
    static final int LAST_PROTOCOL = 777;
    /** Segments discarded because no backend was named for them in time. */
    static final AtomicLong unbound = new AtomicLong();
    /** Diagnostics cuts dropped because their player's loop already had {@link #MAX_WAITING_CUTS} to take. */
    static final AtomicLong cutsDropped = new AtomicLong();
    /** Diagnostics cuts a player's loop may have waiting; each holds at most one reply entry. */
    static final int MAX_WAITING_CUTS = 16;

    private static final Map<Integer, StreamIds> STREAM_IDS = new ConcurrentHashMap<>();
    private static final List<String> REGISTRIES = List.of("minecraft:damage_type", "minecraft:enchantment");
    private static final LogThrottle THROTTLE = new LogThrottle();
    private static final List<Class<? extends ReachCheck>> REACH_CHECKS = List.of(PlayerReach.class, EntityReach.class);

    private static volatile SegmentBinder binder = SegmentBinder.NONE;
    private static volatile Uplinks uplinks = Uplinks.NONE;

    private final CultPlayer player;
    private final TickFlags tickFlags = new TickFlags();
    /** Only touched on the event loop. */
    private final ActionOutcomes actions;
    /** Built at install; published to the packet thread by the {@link #channel} write that follows. */
    private CaptureStream stream;

    private EngineVerdicts engine;
    /** Whether the stream holds or sends frames, as last seen on the event loop. */
    private volatile boolean capturing;
    /** Runs of this tick-end handler; behind the received count when a tick end's handlers were skipped. */
    private final AtomicLong handledTickEnds = new AtomicLong();
    /** Received minus handled tick ends when the segment started, so both count frames from the same start. */
    private volatile long handledOffset;
    /** The session facts as last sent; null until a login or respawn is sent. */
    private volatile CaptureStream.Session session;
    /** The client has been sent its login, or was already playing when the capture started. */
    private volatile boolean loggedIn;
    /** A Bedrock player: announced, never streamed. */
    private volatile boolean unstreamed;
    /** Set on the event loop once the capture is installed; null while it is not. */
    private volatile Channel channel;
    /** On {@link #channel}'s close future while the capture is installed; touched only on the event loop. */
    private ChannelFutureListener closed;

    // Touched only on the player's packet thread.
    private int sightTarget = -1;
    private ReachCheck sightCheck;
    private int sinceAttack;
    /** Where the client stood at the end of its previous tick, which its pick this tick starts from. */
    private Vec3 sightOrigin;

    // Touched only on the channel's event loop.
    private int tickEnd = -1;
    private int pingPacket = -1;
    private Segment segment;

    public CloudCapture(CultPlayer player) {
        this.player = player;
        actions =
                new ActionOutcomes(this::outcome, slot -> player.getInventory().serverViewSlot(slot));
    }

    /** The sockets segments upload on, by backend name. */
    interface Uplinks {
        /** Uploads nothing. */
        Uplinks NONE = new Uplinks() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public @Nullable Uplink open(String serverName) {
                return null;
            }

            @Override
            public void release(Uplink uplink) {}
        };

        boolean enabled();

        /** The socket for backend {@code serverName}, held until {@link #release}; null when it has none. */
        @Nullable
        Uplink open(String serverName);

        void release(Uplink uplink);
    }

    /** One server id's socket, as a segment uses it. */
    interface Uplink {
        String serverId();

        /** Whether this and {@code other} are the same socket. */
        boolean same(Uplink other);

        void register(UUID player, CloudConnection.Stream stream);

        void unregister(UUID player, CloudConnection.Stream stream);

        boolean send(long generation, byte[] record, long resolvedExtra);

        CloudConnection.Terms terms(long generation);

        /** Whether the socket of {@code generation} said {@code textStripped} at connect; true once it is gone. */
        default boolean textStripped(long generation) {
            return true;
        }

        int maxFrameBytes();

        /** The socket's queue is backed up. */
        default boolean backlogged() {
            return false;
        }

        Diagnostics diagnostics();
    }

    public static void segmentBinder(SegmentBinder segmentBinder) {
        binder = Objects.requireNonNull(segmentBinder);
    }

    static void uplinks(Uplinks sockets) {
        uplinks = Objects.requireNonNull(sockets);
    }

    /** Loads every streamed protocol's packet ids and player text codecs ahead of the first capture. */
    static void warm() {
        for (int protocol = FIRST_PROTOCOL; protocol <= LAST_PROTOCOL; protocol++) {
            StreamIds.of(protocol);
            PlayerText.of(protocol);
        }
    }

    /** {@code player}'s capture; null while they have none. Any thread. */
    static @Nullable CloudCapture of(CultPlayer player) {
        return player.observer instanceof CloudCapture capture ? capture : null;
    }

    /**
     * Makes {@code player}'s capture when it is {@linkplain #wanted wanted} and they have none, then installs it, or
     * takes it out when it is not. Any thread.
     */
    static void reconfigure(CultPlayer player) {
        if (!(player.user.getChannel() instanceof Channel found)) return;
        try {
            found.eventLoop().execute(() -> {
                CloudCapture capture = of(player);
                if (capture == null) {
                    if (!wanted(player)) return;
                    capture = new CloudCapture(player);
                    player.observer = capture;
                }
                try {
                    capture.reconfigureOnLoop(found);
                } catch (Throwable failure) {
                    capture.streamFailed("reconfigure", failure);
                }
            });
        } catch (RuntimeException shutDown) {
        }
    }

    @Override
    public void flagged(Check check, double violations) {
        // The service's own findings, which it already has.
        if (channel == null || check instanceof Cloud) return;
        try {
            String name = check.getCheckName();
            if (name == null || name.isEmpty()) name = check.getClass().getSimpleName();
            tickFlags.add(name, violations);
        } catch (Throwable failure) {
            failed("flag", failure);
        }
    }

    @Override
    public void received(ServerboundPacket packet, boolean cancelled) {
        boolean swap = packet instanceof ServerboundPlayerAction action
                && action.action() == PlayerAction.SWAP_ITEM_WITH_OFFHAND;
        if (!swap && !(packet instanceof InventoryClick || packet instanceof ServerboundSetCarriedItem)) return;
        Channel tapped = channel;
        if (tapped == null || !tapped.eventLoop().inEventLoop()) return;
        try {
            if (!swap && !cancelled) actions.handsMoved();
            if (unstreamed || !swap && !binder.marks(player)) return;
            // The action is the packet the inbound tap saw last, which holds only on the loop that decoded it.
            ActionRef action = stream.action();
            if (action == null) return;
            CompensatedInventory inventory = player.getInventory();
            boolean immobile = player.isDead() || player.isInBed;
            long now = System.nanoTime();
            if (swap) actions.swap(action, inventory.inventory.selected, cancelled, inventory.serverWindowId(), now);
            else if (packet instanceof InventoryClick click)
                actions.click(action, click, cancelled, inventory.serverWindowId(), immobile, now);
            else actions.held(action, ((ServerboundSetCarriedItem) packet).slot(), cancelled, immobile, now);
        } catch (Throwable failure) {
            streamFailed("action", failure);
        }
    }

    @Override
    public void sent(Object packet) {
        Channel tapped = channel;
        if (tapped == null || !tapped.eventLoop().inEventLoop()) return;
        try {
            actions.sent(packet, System.nanoTime());
        } catch (Throwable failure) {
            streamFailed("answer", failure);
        }
    }

    @Override
    public void totemPopped() {
        Channel tapped = channel;
        if (tapped == null || unstreamed || !tapped.eventLoop().inEventLoop()) return;
        try {
            actions.popped(player.getInventory().inventory.selected, System.nanoTime());
        } catch (Throwable failure) {
            streamFailed("pop", failure);
        }
    }

    /** {@code player}'s backend answered {@code marker}. On the player's event loop. */
    public static void markerAnswered(CultPlayer player, int marker) {
        CloudCapture capture = of(player);
        Channel tapped = capture == null ? null : capture.channel;
        if (tapped == null || !tapped.eventLoop().inEventLoop()) return;
        try {
            capture.actions.answered(marker);
        } catch (Throwable failure) {
            capture.streamFailed("marker", failure);
        }
    }

    /** An outcome for the stream's next frames. On the event loop. */
    private void outcome(Outcome outcome, ActionRef action, long nanos) {
        try {
            if (stream != null) stream.outcome(outcome, action, nanos);
        } catch (Throwable failure) {
            streamFailed("outcome", failure);
        }
    }

    /**
     * {@code player}'s server or player facts change after their JOIN: the merged settings go out as a META record in
     * the open frame. Any thread.
     */
    public static void factsChanged(CultPlayer player) {
        CloudCapture capture = of(player);
        Channel tapped = capture == null ? null : capture.channel;
        if (tapped == null || capture.unstreamed) return;
        try {
            Map<String, String> settings = capture.facts().settings();
            capture.onLoop(tapped, () -> {
                if (capture.channel != tapped) return;
                try {
                    capture.stream.settings(settings, System.nanoTime());
                } catch (Throwable failure) {
                    capture.streamFailed("facts", failure);
                }
            });
        } catch (Throwable failure) {
            capture.failed("facts", failure);
        }
    }

    /** A failure in the upload's work on the player's checks, logged and kept from them. Any thread. */
    private void failed(String where, Throwable failure) {
        THROTTLE.warn("cloud-" + where, "Cloud capture of " + player.getName() + " failed at " + where, failure);
    }

    /** As {@link #failed}, for the stream's own work: the segment uploads nothing more. On the event loop. */
    private void streamFailed(String where, Throwable failure) {
        failed(where, failure);
        Segment current = segment;
        if (current != null) current.refused = true;
        try {
            if (stream != null) stream.reset();
        } catch (Throwable ignored) {
        }
        capturing = false;
    }

    /** Whether the tick's attack geometry and target sight go out; read on the player's packet thread. */
    boolean streaming() {
        return channel != null && !unstreamed && capturing;
    }

    /** Mirrors the stream's state for the packet thread; on the event loop after the stream changes. */
    private void mirror() {
        capturing = stream != null && stream.capturing();
    }

    /**
     * Installs the capture on the player's channel when it is {@linkplain #wanted wanted}, and takes it out when it is
     * not; segments whose socket the settings moved end there and go on on the new one. Any thread.
     */
    void reconfigure() {
        if (!(player.user.getChannel() instanceof Channel found)) return;
        try {
            found.eventLoop().execute(guarded("reconfigure", () -> reconfigureOnLoop(found)));
        } catch (RuntimeException shutDown) {
        }
    }

    /**
     * Plugin disable: the taps leave the pipeline and the segment ends. Any thread; does not block. Completes once the
     * capture is out of the channel, or at once when its loop is gone.
     */
    CompletableFuture<Void> detach() {
        Channel tapped = channel;
        if (tapped == null) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> detached = new CompletableFuture<>();
        try {
            tapped.eventLoop().execute(() -> {
                try {
                    detachOnLoop(tapped);
                } finally {
                    detached.complete(null);
                }
            });
        } catch (RuntimeException shutDown) {
            detached.complete(null);
        }
        return detached;
    }

    /**
     * {@code player}'s connection moves to a newer runtime: the capture leaves the channel before this returns when
     * called on its loop, so the new runtime's capture finds the pipeline free. On the connection's packet owner.
     */
    static void release(CultPlayer player) {
        if (player == null) return;
        CloudCapture capture = of(player);
        Channel tapped = capture == null ? null : capture.channel;
        if (tapped == null) return;
        if (tapped.eventLoop().inEventLoop()) capture.detachOnLoop(tapped);
        else capture.detach();
    }

    /**
     * A capture belongs on {@code player}'s channel while the upload is on and this runtime still tracks them. A
     * hand-off stops tracking them on the channel's loop, so work queued before it finds them gone and stays out of
     * the pipeline the newer runtime's capture takes.
     */
    private static boolean wanted(CultPlayer player) {
        return uplinks.enabled() && player.user.getCultPlayer() == player;
    }

    private void reconfigureOnLoop(Channel found) {
        if (!wanted(player)) {
            if (channel != null) detachOnLoop(channel);
            leave();
            return;
        }
        if (channel == null) {
            install(found);
            return;
        }
        Segment current = segment;
        if (current == null) {
            resume(channel);
            return;
        }
        if (current.uplink == null) return;
        Uplink fresh = uplinks.open(current.serverName);
        if (fresh != null && fresh.same(current.uplink)) {
            uplinks.release(fresh);
            return;
        }
        stream.leave(System.nanoTime());
        leaveUplink(current);
        current.generation = -1;
        current.refused = false;
        if (fresh == null) {
            segment = null;
            PredictionDiagnostics.INSTANCE.ended(player);
            return;
        }
        current.uplink = fresh;
        current.registration = registration(current);
        fresh.register(player.getUniqueId(), current.registration);
    }

    private void install(Channel found) {
        if (channel != null || !found.isActive()) return;
        int protocol = player.getClientVersion().getProtocolVersion();
        if (player.isBedrockMovement()) {
            unstreamed = true;
            stream = new CaptureStream(player.getUniqueId());
            attach(found);
            return;
        }
        StreamIds ids = StreamIds.of(protocol);
        if (ids.tickEnd() < 0) {
            LogUtil.warn("Cloud upload for " + player.getName() + " has no packet ids for protocol " + protocol);
            return;
        }
        ChannelPipeline pipeline = found.pipeline();
        String decoder = first(pipeline, DECODERS);
        String encoder = first(pipeline, ENCODERS);
        if (decoder == null || encoder == null || pipeline.get(INBOUND) != null) {
            LogUtil.warn("Cloud upload for " + player.getName() + " found no codec to attach to");
            return;
        }
        CaptureStream built = new CaptureStream(player.getUniqueId());
        try {
            built.pingPacket(ids.ping());
            built.pongPacket(ids.pong());
            built.chunks(ids.chunks());
            built.text(PlayerText.of(protocol));
            built.terrain(new SimulationTerrain());
            stream = built;
            engine = new EngineVerdicts(player);
            tickEnd = ids.tickEnd();
            pingPacket = ids.ping();
            pipeline.addBefore(decoder, INBOUND, new InboundTap());
            pipeline.addBefore(encoder, OUTBOUND, new OutboundTap());
        } catch (RuntimeException failure) {
            removeTaps(pipeline);
            LogUtil.warn("Cloud upload for " + player.getName() + " could not attach: " + failure);
            return;
        }
        player.recordProvenance(true);
        player.compensatedWorld.terrainListener(new TerrainTap());
        attach(found);
    }

    private void attach(Channel found) {
        channel = found;
        player.observer = this;
        CloudService.installed(this);
        closed = ignored -> onLoop(found, this::disconnected);
        found.closeFuture().addListener(closed);
        resume(found);
    }

    /** A capture in play with no segment starts one here; the packet thread must be the loop to own the stream. */
    private void resume(Channel found) {
        if (player.user.getEncoderState() == ConnectionPhase.PLAY
                && (unstreamed || player.user.getPacketExecutor() == found.eventLoop())) {
            loggedIn = true;
            startSegment(true);
        }
    }

    private void detachOnLoop(Channel tapped) {
        if (channel != tapped) return;
        endSegmentGuarded();
        removeTaps(tapped.pipeline());
        released();
        unstreamed = false;
    }

    private void disconnected() {
        Channel tapped = channel;
        if (tapped == null) return;
        try {
            binder.disconnected(player);
        } catch (Throwable failure) {
            failed("disconnect", failure);
        }
        endSegmentGuarded();
        released();
    }

    /** As {@link #endSegment}, with what it throws logged and the stream reset, so the capture still comes out. */
    private void endSegmentGuarded() {
        try {
            endSegment();
        } catch (Throwable failure) {
            streamFailed("leave", failure);
        }
    }

    /** The capture is out of the player's channel: nothing more is recorded for it. */
    private void released() {
        player.compensatedWorld.terrainListener(null);
        if (!unstreamed) player.recordProvenance(false);
        CloudService.uninstalled(this);
        channel.closeFuture().removeListener(closed);
        closed = null;
        channel = null;
        capturing = false;
        if (stream != null) stream.reset();
        stream = null;
        engine = null;
        leave();
    }

    /** The engine stops calling this capture. */
    private void leave() {
        if (player.observer == this) player.observer = null;
    }

    private static void removeTaps(ChannelPipeline pipeline) {
        for (String name : List.of(INBOUND, OUTBOUND)) {
            try {
                if (pipeline.get(name) != null) pipeline.remove(name);
            } catch (RuntimeException gone) {
            }
        }
    }

    private static String first(ChannelPipeline pipeline, Set<String> names) {
        for (String name : pipeline.names()) {
            if (names.contains(name)) return name;
        }
        return null;
    }

    /**
     * A new segment, unbound: the stream holds its frames from here, and the binder names its backend.
     * {@code playing}: the client is already in play, so the world's bounds are known at once, not only at the login.
     */
    private void startSegment(boolean playing) {
        Channel tapped = channel;
        if (tapped == null) return;
        if (segment != null) endSegment();
        actions.forget();
        Segment started = new Segment();
        segment = started;
        started.resumed = playing;
        long received = player.clientTickEnds.get();
        handledOffset = received - handledTickEnds.get();
        if (!unstreamed) {
            stream.start(received);
            started.initialSession = session();
            started.initiallyLoggedIn = loggedIn;
            if (playing) bounds(started);
            stream.prepare(sink(null, -1));
            mirror();
        }
        EventLoop loop = tapped.eventLoop();
        started.timeout =
                loop.schedule(guarded("bind", () -> unbound(started)), BIND_TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
        // Queued even from the loop, so the JOIN reads the world after CultAC has handled the login.
        binder.started(
                player,
                name -> {
                    try {
                        loop.execute(guarded("bind", () -> bind(started, name)));
                    } catch (RuntimeException shutDown) {
                    }
                },
                playing);
    }

    /** The world's bounds as the client has them, the segment's JOIN's when it is still to be sent. */
    private void bounds(Segment started) {
        CompensatedWorld.ClientboundDimensionData dimension = player.compensatedWorld.getLastClientboundDimension();
        started.initialMinY = dimension.minHeight();
        started.initialHeight = dimension.maxHeight() - dimension.minHeight();
        started.bounded = true;
    }

    private void unbound(Segment started) {
        if (segment != started || started.uplink != null) return;
        unbound.incrementAndGet();
        segment = null;
        stream.reset();
        mirror();
        binder.abandoned(player);
    }

    private void bind(Segment started, String serverName) {
        if (segment != started || started.uplink != null || started.serverName != null) return;
        started.serverName = serverName;
        Uplink uplink = serverName == null || serverName.isBlank() ? null : uplinks.open(serverName);
        if (uplink == null) {
            unbound(started);
            return;
        }
        if (started.timeout != null) started.timeout.cancel(false);
        started.uplink = uplink;
        started.registration = registration(started);
        uplink.register(player.getUniqueId(), started.registration);
    }

    /** The segment ends: what the client has not ended goes out, then LEAVE. */
    private void endSegment() {
        Segment ended = segment;
        if (ended == null) return;
        segment = null;
        actions.forget();
        if (ended.timeout != null) ended.timeout.cancel(false);
        if (ended.uplink == null) {
            stream.reset();
            mirror();
            return;
        }
        try {
            stream.leave(System.nanoTime());
        } catch (Throwable failure) {
            failed("leave", failure);
            stream.reset();
        }
        leaveUplink(ended);
        PredictionDiagnostics.INSTANCE.ended(player);
    }

    /** The segment's socket is let go after its LEAVE. */
    private void leaveUplink(Segment owner) {
        mirror();
        owner.uplink.diagnostics().left(player.getUniqueId());
        owner.uplink.unregister(player.getUniqueId(), owner.registration);
        uplinks.release(owner.uplink);
        owner.uplink = null;
    }

    private CloudConnection.Stream registration(Segment owner) {
        return new CloudConnection.Stream() {
            @Override
            public void connected(long generation) {
                run("join", () -> CloudCapture.this.connected(owner, generation));
            }

            @Override
            public void disconnected(long generation) {
                run("disconnect", () -> {
                    if (segment != owner || generation != owner.generation) return;
                    stream.reset();
                    mirror();
                });
            }

            /**
             * The replies the loop has yet to take, in order: the newest capture policy, at most a watch stop and start
             * between two cuts ({@link #supersede}), at most {@link #MAX_WAITING_CUTS} cuts and a refusal, after which
             * nothing matters. One task at a time carries them there. Guarded by itself.
             */
            private final ArrayDeque<Waiting> waiting = new ArrayDeque<>();

            private int waitingCuts;

            @Override
            public void reply(long generation, IntakeWire.Reply reply) {
                Channel tapped = channel;
                if (tapped == null) return;
                synchronized (waiting) {
                    boolean idle = waiting.isEmpty();
                    if (!idle && waiting.peekLast().reply() instanceof IntakeWire.Refused) return;
                    if (reply instanceof IntakeWire.DiagCutReply) {
                        if (waitingCuts == MAX_WAITING_CUTS) {
                            cutsDropped.incrementAndGet();
                            return;
                        }
                        waitingCuts++;
                    } else if (reply instanceof IntakeWire.DiagWatchReply watch) {
                        supersede(waiting, watch);
                    } else if (!(reply instanceof IntakeWire.Refused)) {
                        waiting.removeIf(queued -> queued.reply().getClass() == reply.getClass());
                    }
                    waiting.addLast(new Waiting(generation, reply));
                    if (!idle) return;
                }
                onLoop(tapped, () -> {
                    List<Waiting> taken;
                    synchronized (waiting) {
                        taken = new ArrayList<>(waiting);
                        waiting.clear();
                        waitingCuts = 0;
                    }
                    if (channel != tapped) return;
                    for (Waiting next : taken) {
                        guarded("reply", () -> CloudCapture.this.reply(owner, next.generation(), next.reply()))
                                .run();
                    }
                });
            }

            private void run(String where, Runnable task) {
                Channel tapped = channel;
                if (tapped != null) {
                    onLoop(tapped, guarded(where, () -> {
                        if (channel == tapped) task.run();
                    }));
                }
            }
        };
    }

    private void connected(Segment owner, long generation) {
        Uplink uplink = owner.uplink;
        if (segment != owner || uplink == null || owner.refused || generation <= owner.generation) return;
        owner.generation = generation;
        long now = System.nanoTime();
        long base = stream.baseNanos(now);
        long epochMillis = System.currentTimeMillis() - TimeUnit.NANOSECONDS.toMillis(now - base);
        boolean initial = stream.preparing();
        if (!initial || !owner.bounded) bounds(owner);
        int minY = owner.initialMinY;
        int height = owner.initialHeight;
        if (!IntakeWire.validDimension(minY, height)) {
            minY = -64;
            height = 384;
        }
        CaptureStream.Session sent = initial ? owner.initialSession : session();
        boolean known = initial ? owner.initiallyLoggedIn : loggedIn;
        boolean resumed = !initial || owner.resumed;
        int protocol = player.getClientVersion().getProtocolVersion();
        IntakeWire.Facts facts = joinFacts(sent, known, resumed, epochMillis);
        byte[] join = unstreamed
                ? IntakeWire.unstreamed(
                        player.getUniqueId(), protocol, player.getBrand(), minY, height, facts, player.getName())
                : IntakeWire.join(
                        player.getUniqueId(),
                        protocol,
                        player.getBrand(),
                        minY,
                        height,
                        facts,
                        player.getName(),
                        ClientPacketCodecs.MODEL_VERSION.protocol());
        stream.join(sink(uplink, generation), join, base, sent);
        if (!unstreamed) mirror();
    }

    private void reply(Segment owner, long generation, IntakeWire.Reply reply) {
        Uplink uplink = owner.uplink;
        if (segment != owner || uplink == null || generation != owner.generation || !stream.joined()) return;
        switch (reply) {
            case IntakeWire.DiagWatchReply watch -> {
                Diagnostics sink = uplink.diagnostics();
                if (unstreamed) {
                    if (watch.leaseSeconds() > 0) sink.refuse(player.getUniqueId(), "bedrock");
                    return;
                }
                PredictionDiagnostics.INSTANCE.watch(player, sink, generation, watch.leaseSeconds(), this::frameSeq);
            }
            case IntakeWire.DiagCutReply cut ->
                PredictionDiagnostics.INSTANCE.cut(player, uplink.diagnostics(), cut.cut());
            case IntakeWire.DiagLimitsReply ignored -> {}
            case IntakeWire.PerfLimitsReply ignored -> {}
            case IntakeWire.PerfStartReply ignored -> {}
            case IntakeWire.TeamStateReply ignored -> {}
            case IntakeWire.TeamSweepReply ignored -> {}
            case IntakeWire.Event ignored -> {}
            case IntakeWire.Capture capture -> {
                if (!unstreamed) stream.capture(capture.serverbound(), capture.clientbound(), capture.dimension());
            }
            case IntakeWire.Refused refusal -> {
                if (unstreamed) return;
                THROTTLE.warn(
                        "cloud-refused",
                        "Cloud will not take " + player.getName() + ": " + LogThrottle.clip(refusal.reason()),
                        null);
                owner.refused = true;
                stream.reset();
                mirror();
                uplink.unregister(player.getUniqueId(), owner.registration);
            }
        }
    }

    /**
     * The seq of the frame a prediction made at this call belongs to: the open frame, or while a tick end's handlers
     * run, the frame that tick end cut; -1 once the capture is out. On the packet thread.
     */
    private long frameSeq() {
        CaptureStream current = stream;
        if (current == null) return -1;
        long seq = player.clientTickEnds.get() - current.origin();
        return player.checkManager.isDispatchingTickEnd() ? seq - 1 : seq;
    }

    /** The socket of {@code generation} for records, and the process's budget for held frames. */
    private CaptureStream.Sink sink(@Nullable Uplink uplink, long generation) {
        return new CaptureStream.Sink() {
            @Override
            public boolean send(byte[] record) {
                return uplink != null && uplink.send(generation, record, 0);
            }

            @Override
            public boolean send(byte[] record, long resolvedExtra) {
                return uplink != null && uplink.send(generation, record, resolvedExtra);
            }

            @Override
            public boolean hold(long bytes) {
                return HELD.take(bytes);
            }

            @Override
            public void release(long bytes) {
                HELD.give(bytes);
            }

            @Override
            public long holdRoom() {
                return HELD.remaining();
            }

            @Override
            public int maxFrameBytes() {
                return uplink == null ? 0 : uplink.maxFrameBytes();
            }

            @Override
            public CloudConnection.Terms terms() {
                return uplink == null ? CloudConnection.Terms.NONE : uplink.terms(generation);
            }

            @Override
            public boolean textStripped() {
                return uplink == null || uplink.textStripped(generation);
            }

            @Override
            public boolean backlogged() {
                return uplink != null && uplink.backlogged();
            }
        };
    }

    /**
     * The JOIN's facts. Before the login there is no entity, game mode or dimension; the login's WORLD has them.
     * {@code resumed}: the segment starts mid-session, at an install or a reconnect, not at a login.
     */
    private IntakeWire.Facts joinFacts(CaptureStream.Session sent, boolean known, boolean resumed, long epochMillis) {
        ServerFacts facts = facts();
        return new IntakeWire.Facts(
                known ? sent.entityId() : null,
                known ? sent.gameMode() : null,
                known ? sent.dimension() : null,
                epochMillis,
                facts.tickMicros(),
                resumed,
                facts.server(),
                facts.settings(),
                registries());
    }

    /** The server's facts, the player's over them, then {@code send-check-flags}. */
    ServerFacts facts() {
        PlatformPlayer platform = player.platformPlayer;
        return merged(
                CultAPI.INSTANCE.getPlatformServer().cloudFacts(),
                platform == null ? ServerFacts.NONE : platform.cloudFacts());
    }

    static ServerFacts merged(@Nullable ServerFacts server, @Nullable ServerFacts player) {
        return (server == null ? ServerFacts.NONE : server).merge(player).with("send-check-flags", "true");
    }

    /**
     * The damage types and enchantments in the order the client was sent them, each left out when unknown. Names
     * observed on another protocol than the client's passed a translator that may renumber them, so none are sent.
     */
    private Map<String, List<String>> registries() {
        Map<String, List<String>> registries = new LinkedHashMap<>();
        ClientComponentRegistries state = player.registryState;
        if (state == null) return registries;
        ProtocolVersion observed = state.receivedRegistryVersion();
        if (observed == null || observed.protocol() != player.getClientVersion().getProtocolVersion())
            return registries;
        for (String registry : REGISTRIES) {
            List<WireValueDecoder.RegistryEntry> received =
                    state.receivedRegistries().get(registry);
            if (received == null) continue;
            List<String> entries = new ArrayList<>(received.size());
            for (WireValueDecoder.RegistryEntry entry : received) entries.add(entry.name());
            registries.put(registry, entries);
        }
        return registries;
    }

    @Override
    public void loginSent(ClientboundLogin packet) {
        try {
            session = new CaptureStream.Session(
                    packet.playerId(),
                    packet.spawnInfo().gameMode().ordinal(),
                    packet.spawnInfo().dimension(),
                    true);
            loggedIn = true;
            Channel tapped = channel;
            if (tapped == null) return;
            // The login must be in the segment's first frame, so a streamed segment starts before it reaches the tap.
            Runnable start = guarded("login", () -> startSegment(false));
            if (tapped.eventLoop().inEventLoop()) start.run();
            else if (unstreamed) onLoop(tapped, start);
        } catch (Throwable failure) {
            failed("login", failure);
        }
    }

    @Override
    public void configurationStarted() {
        try {
            Channel tapped = channel;
            if (tapped == null) return;
            onLoop(tapped, guarded("configuration", () -> {
                if (segment == null) return;
                binder.configuring(player);
                endSegment();
            }));
        } catch (Throwable failure) {
            failed("configuration", failure);
        }
    }

    @Override
    public void respawnSent(ClientboundRespawn packet) {
        try {
            session = new CaptureStream.Session(
                    session().entityId(),
                    packet.spawnInfo().gameMode().ordinal(),
                    packet.spawnInfo().dimension(),
                    true);
        } catch (Throwable failure) {
            failed("respawn", failure);
        }
    }

    @Override
    public void gameModeSent(GameMode mode) {
        try {
            CaptureStream.Session previous = session();
            session = new CaptureStream.Session(previous.entityId(), mode.ordinal(), previous.dimension(), false);
        } catch (Throwable failure) {
            failed("game event", failure);
        }
    }

    /** {@code task}, on the event loop, with what it throws logged, the segment reset and kept from the loop. */
    private Runnable guarded(String where, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable failure) {
                streamFailed(where, failure);
            }
        };
    }

    /** The session facts as last sent; before a login or respawn has been seen, as CultAC knows them. */
    private CaptureStream.Session session() {
        CaptureStream.Session sent = session;
        if (sent != null) return sent;
        GameMode mode = player.gamemode;
        sent = new CaptureStream.Session(
                player.entityID,
                mode == null ? null : mode.ordinal(),
                player.compensatedWorld.getLastClientboundDimension().dimension(),
                false);
        session = sent;
        return sent;
    }

    @Override
    public void tickEnded() {
        long index = player.clientTickEnds.get();
        long handled = handledTickEnds.incrementAndGet() + handledOffset;
        List<IntakeWire.Flag> flags = tickFlags.drain();
        boolean streaming = streaming();
        List<IntakeWire.Geometry> measured = List.of();
        IntakeWire.Target seen = null;
        if (streaming) {
            try {
                measured = geometries();
                seen = sight();
            } catch (Throwable failure) {
                failed("reach geometry", failure);
            }
        }
        sightOrigin = streaming ? new Vec3(player.x, player.y, player.z) : null;
        List<IntakeWire.Geometry> attacks = measured;
        IntakeWire.Target sight = seen;
        try {
            Channel tapped = channel;
            if (tapped == null || unstreamed || !capturing) return;
            IntakeWire.Engine state = engine.read();
            long bits = conditions() | engine.conditions();
            EventLoop loop = tapped.eventLoop();
            if (loop.inEventLoop()) {
                verdict(index, handled, bits, flags, sight, attacks, state);
            } else {
                loop.execute(() -> verdict(index, handled, bits, flags, sight, attacks, state));
            }
        } catch (Throwable failure) {
            failed("tick end", failure);
        }
    }

    /** The lag-compensated geometry of each attack the reach checks measured this tick, in packet order. */
    private List<IntakeWire.Geometry> geometries() {
        List<IntakeWire.Geometry> geometries = List.of();
        int last = -1;
        for (Class<? extends ReachCheck> type : REACH_CHECKS) {
            ReachCheck check = player.checkManager.getCheck(type);
            for (ReachCheck.MeasuredAttack attack : check.measuredAttacks()) {
                if (geometries.isEmpty()) geometries = new ArrayList<>();
                geometries.add(geometry(attack));
                if (attack.index() > last) {
                    last = attack.index();
                    sightTarget = attack.entityId();
                    sightCheck = check;
                    sinceAttack = 0;
                }
            }
        }
        return geometries;
    }

    /**
     * An attack's geometry: the target's reach box (min then max), the candidate eye positions (x, y, z each), and the
     * reach, null when the look misses the box.
     */
    private IntakeWire.Geometry geometry(ReachCheck.MeasuredAttack attack) {
        SimpleCollisionBox box = attack.box();
        List<Double> heights = player.getPossibleEyeHeights();
        int count = Math.min(attack.origins().size() * heights.size(), MAX_EYES);
        double[] eyes = new double[count * 3];
        int at = 0;
        for (Vec3 from : attack.origins()) {
            for (double height : heights) {
                if (from == null || at == eyes.length) continue;
                eyes[at++] = from.x;
                eyes[at++] = from.y + height;
                eyes[at++] = from.z;
            }
        }
        EntityTypeUtil.EntityKey type = EntityTypeUtil.getKey(attack.type());
        return new IntakeWire.Geometry(
                attack.index(),
                type.namespace() + ":" + type.path(),
                new double[] {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ},
                at == eyes.length ? eyes : Arrays.copyOf(eyes, at),
                attack.reach() == Double.POSITIVE_INFINITY ? null : attack.reach());
    }

    /**
     * For a while after an attack, the crosshair's reach this tick to the last attacked target's reach box, from the
     * eye of the pose the client began its tick in, where it stood then, along this tick's rotation; null when the
     * look misses it or no target is followed.
     */
    private IntakeWire.Target sight() {
        if (sightTarget < 0) return null;
        PacketEntity entity = player.compensatedEntities.entityMap.get(sightTarget);
        if (entity == null || entity.isDead || ++sinceAttack > SIGHT_TICKS) {
            sightTarget = -1;
            sightCheck = null;
            return null;
        }
        if (sightOrigin == null || player.compensatedEntities.isUntrackedRider(entity)) return null;
        List<Vec3> origins = player.compensatedEntities.getSelf().getRiding() == null
                ? List.of(sightOrigin)
                : sightCheck.getAttackOrigins();
        SimpleCollisionBox box = sightCheck.getReachBox(entity);
        Vector3dm look = ReachUtils.getLook(player, player.xRot, player.yRot);
        double reach = Double.MAX_VALUE;
        double height = player.getEyeHeight();
        for (Vec3 from : origins) {
            Vector3dm eye = new Vector3dm(from.x, from.y + height, from.z);
            if (ReachUtils.isVecInside(box, eye)) {
                reach = 0;
                break;
            }
            Vector3dm end = eye.clone()
                    .add(new Vector3dm(look.getX() * PICK_RANGE, look.getY() * PICK_RANGE, look.getZ() * PICK_RANGE));
            Vector3dm intercept = ReachUtils.calculateIntercept(box, eye, end).getFirst();
            if (intercept != null) reach = Math.min(reach, eye.distance(intercept));
        }
        return new IntakeWire.Target(sightTarget, reach == Double.MAX_VALUE ? null : reach);
    }

    private void verdict(
            long index,
            long handled,
            long bits,
            List<IntakeWire.Flag> flags,
            IntakeWire.Target sight,
            List<IntakeWire.Geometry> attacks,
            IntakeWire.Engine state) {
        try {
            if (segment == null) return;
            stream.verdict(index, handled, bits, flags, sight, attacks, state);
            if (flags.isEmpty()) return;
            List<String> checks = new ArrayList<>(flags.size());
            for (IntakeWire.Flag flag : flags) checks.add(flag.check());
            PredictionDiagnostics.INSTANCE.tickEnd(player, index - stream.origin() - 1, checks);
        } catch (Throwable failure) {
            streamFailed("verdict", failure);
        }
    }

    private long conditions() {
        long bits = 0;
        if (player.packetStateData.teleportedThisClientTick) bits |= 1L << IntakeWire.TELEPORTED;
        if (player.compensatedEntities.getSelf().inVehicle()) bits |= 1L << IntakeWire.IN_VEHICLE;
        if (player.isFlying) bits |= 1L << IntakeWire.FLYING;
        if (player.gamemode == GameMode.SPECTATOR) bits |= 1L << IntakeWire.SPECTATOR;
        if (player.isGliding) bits |= 1L << IntakeWire.GLIDING;
        if (player.riptideSpinAttackTicks > 0 || player.isRiptidePose) bits |= 1L << IntakeWire.RIPTIDE;
        return bits;
    }

    /** Runs {@code task} at once when on {@code tapped}'s loop, else queues it there. */
    private void onLoop(Channel tapped, Runnable task) {
        EventLoop loop = tapped.eventLoop();
        if (loop.inEventLoop()) {
            task.run();
            return;
        }
        try {
            loop.execute(task);
        } catch (RuntimeException shutDown) {
        }
    }

    static String summary() {
        return "Cloud segments: " + unbound.get() + " discarded unbound, " + HELD.remaining() / 1048576 + " of "
                + MAX_HELD_BYTES / 1048576 + " MiB free for held frames; outcomes " + CaptureStream.outcomesSent.get()
                + " sent, " + CaptureStream.outcomesDropped.get() + " dropped; diagnostics cuts " + cutsDropped.get()
                + " dropped";
    }

    /** Inbound: the packet as the client sent it. Nothing here may disturb the connection. */
    private final class InboundTap extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
            if (stream != null && message instanceof ByteBuf buf && buf.isReadable()) {
                int id = packetId(buf);
                try {
                    if (stream.capturing()
                            && player.user.getConnectionState() == ConnectionPhase.PLAY
                            && buf.readableBytes() <= CaptureStream.MAX_SERVERBOUND_BYTES
                            && stream.wants(IntakeWire.SERVERBOUND, id)) {
                        stream.tap(IntakeWire.SERVERBOUND, id, buf, System.nanoTime(), 0, 0, null, 0);
                    } else {
                        stream.skipped();
                    }
                } catch (Throwable failure) {
                    tapFailed(failure);
                }
                // Login and configuration ids stop short of client_tick_end's, so its id alone marks the tick.
                if (id == tickEnd) {
                    try {
                        long now = System.nanoTime();
                        stream.cut(now);
                        actions.expire(now);
                        mirror();
                    } catch (Throwable failure) {
                        tapFailed(failure);
                    }
                    ctx.fireChannelRead(message);
                    // The marker reaches the server after the tick end, so it never sits inside the client's tick.
                    try {
                        if (actions.unmarked()) binder.mark(player, actions.marker());
                    } catch (Throwable failure) {
                        tapFailed(failure);
                    }
                    return;
                }
            }
            ctx.fireChannelRead(message);
        }
    }

    /** Outbound: the packet as the client will receive it. */
    private final class OutboundTap extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
            if (message instanceof ByteBuf buf
                    && buf.isReadable()
                    && player.user.getEncoderState() == ConnectionPhase.PLAY) {
                try {
                    tapClientbound(buf);
                } catch (Throwable failure) {
                    tapFailed(failure);
                }
            }
            ctx.write(message, promise);
        }
    }

    /** A tap threw: the segment uploads nothing more, and the tick ends still count. On the event loop. */
    private void tapFailed(Throwable failure) {
        try {
            streamFailed("tap", failure);
        } catch (Throwable ignored) {
        }
    }

    private void tapClientbound(ByteBuf buf) {
        Segment current = segment;
        if (current == null) return;
        // The client's world is set by the time a login reaches here.
        if (!current.bounded) bounds(current);
        int id = packetId(buf);
        long now = System.nanoTime();
        CaptureStream.Session sent = session();
        CompensatedWorld.ClientboundDimensionData dimension = player.compensatedWorld.getLastClientboundDimension();
        int minY = dimension.minHeight();
        int height = dimension.maxHeight() - dimension.minHeight();
        stream.observe(sent, minY, height, now);
        if (!stream.capturing()) return;
        int pingKind = id == pingPacket && id >= 0 ? pingKind(buf, id) : 0;
        if (!stream.wants(IntakeWire.CLIENTBOUND, id)) return;
        stream.tap(IntakeWire.CLIENTBOUND, id, buf, now, minY, height, sent, pingKind);
    }

    /**
     * The frames written before this call were handed to the event loop first, so the mark queues behind them there
     * and lands at the tick end's place in the stream, ahead of an end-of-tick ping written after it.
     */
    @Override
    public void serverTickEnded(int serverTick) {
        Channel tapped = channel;
        if (tapped == null) return;
        onLoop(tapped, () -> {
            CaptureStream current = stream;
            if (segment == null || current == null || !current.capturing()) return;
            try {
                current.endOfTick(serverTick);
            } catch (Throwable failure) {
                tapFailed(failure);
            }
        });
    }

    /** A tapped ping's {@code PingKind}. */
    private int pingKind(ByteBuf buf, int id) {
        int at = buf.readerIndex() + varIntBytes(id);
        if (buf.writerIndex() - at < 4) return IntakeWire.PING_FOREIGN;
        long provenance = player.pingProvenance(buf.getInt(at));
        int kind = (int) (provenance & 0xFF);
        return kind == 0 ? IntakeWire.PING_FOREIGN : kind;
    }

    private static int varIntBytes(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }

    /** The packet id leading a packet's bytes; -1 when they do not start with a whole varint. */
    private static int packetId(ByteBuf buf) {
        int value = 0;
        int start = buf.readerIndex();
        for (int i = 0; i < 5 && i < buf.readableBytes(); i++) {
            int b = buf.getByte(start + i) & 0xFF;
            value |= (b & 0x7F) << 7 * i;
            if ((b & 0x80) == 0) return value;
        }
        return -1;
    }

    /** The simulation world, read on the event loop, which owns it: protocol 5's terrain. */
    private final class SimulationTerrain implements Sections.Terrain {
        @Override
        public long position() {
            return Sections.pack(
                    (int) Math.floor(player.x) >> 4, (int) Math.floor(player.y) >> 4, (int) Math.floor(player.z) >> 4);
        }

        @Override
        public int minSection() {
            return player.compensatedWorld.getLastClientboundDimension().minHeight() >> 4;
        }

        @Override
        public int maxSection() {
            return (player.compensatedWorld.getLastClientboundDimension().maxHeight() >> 4) - 1;
        }

        /** Only loaded columns of the dimension the client was last sent; none while its dimension type is unknown. */
        @Override
        public byte[] blocks(int x, int y, int z) {
            CompensatedWorld world = player.compensatedWorld;
            if (world.isClientboundUnresolved() || world.isLevelUnresolved()) return null;
            CompensatedWorld.CachedChunk column = world.getChunk(x, z);
            if (column == null) return null;
            CompensatedWorld.ClientboundDimensionData dimension = world.getLastClientboundDimension();
            if (!dimension.dimension().equals(world.dimensionForChunk(x, z))) return null;
            int index = y - (dimension.minHeight() >> 4);
            if (index < 0 || index >= column.sectionCount()) return null;
            return SectionBytes.of(column.getSection(index));
        }
    }

    /**
     * What the simulation world applies, passed on to protocol 5's terrain. It runs inside the world's own updates, so
     * nothing it throws reaches them.
     */
    private final class TerrainTap implements CompensatedWorld.TerrainListener {
        @Override
        public void columnLoaded(int chunkX, int chunkZ) {
            if (!onEventLoop()) return;
            try {
                stream.columnLoaded(chunkX, chunkZ, System.nanoTime());
            } catch (Throwable failure) {
                streamFailed("terrain", failure);
            }
        }

        @Override
        public void columnUnloaded(int chunkX, int chunkZ) {
            if (!onEventLoop()) return;
            try {
                stream.columnUnloaded(chunkX, chunkZ, System.nanoTime());
            } catch (Throwable failure) {
                streamFailed("terrain", failure);
            }
        }

        @Override
        public void blockChanged(int x, int y, int z, int state) {
            if (!onEventLoop()) return;
            try {
                stream.blockChanged(x, y, z, state, System.nanoTime());
            } catch (Throwable failure) {
                streamFailed("terrain", failure);
            }
        }

        // The stream reads the simulation world on the event loop, so only a world it owns is heard.
        private boolean onEventLoop() {
            Channel tapped = channel;
            return tapped != null && tapped.eventLoop().inEventLoop();
        }
    }

    record Waiting(long generation, IntakeWire.Reply reply) {}

    /**
     * Drops the waiting watches {@code watch} makes redundant: those since the last waiting cut, but for a stop when
     * {@code watch} starts one. Between two cuts at most a stop and a start then wait.
     */
    static void supersede(ArrayDeque<Waiting> waiting, IntakeWire.DiagWatchReply watch) {
        for (Iterator<Waiting> newest = waiting.descendingIterator(); newest.hasNext(); ) {
            IntakeWire.Reply queued = newest.next().reply();
            if (queued instanceof IntakeWire.DiagCutReply) return;
            if (queued instanceof IntakeWire.DiagWatchReply earlier) {
                if (watch.leaseSeconds() > 0 && earlier.leaseSeconds() <= 0) return;
                newest.remove();
            }
        }
    }

    /** One backend connection's part of the capture. Touched only on the event loop. */
    private static final class Segment {
        @Nullable
        String serverName;

        @Nullable
        Uplink uplink;

        CloudConnection.Stream registration;
        long generation = -1;
        /** The service refused the segment, or its stream failed: it uploads nothing more. */
        boolean refused;
        /** It started mid-play, at an install, not at a login. */
        boolean resumed;

        @Nullable
        Future<?> timeout;

        CaptureStream.Session initialSession;
        boolean initiallyLoggedIn;
        boolean bounded;
        int initialMinY;
        int initialHeight;
    }

    /** The packet ids a capture taps by in one client protocol, -1 where CultAC has no data for one. */
    record StreamIds(int tickEnd, int ping, int pong, ChunkIds chunks) {
        /** Read once per protocol: the protocol data is parsed whole on every load. */
        static StreamIds of(int protocol) {
            return STREAM_IDS.computeIfAbsent(protocol, StreamIds::load);
        }

        private static StreamIds load(int protocol) {
            IdTable clientbound;
            IdTable serverbound;
            try {
                ProtocolData data = ProtocolData.load(ProtocolVersion.of(protocol));
                clientbound = data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND);
                serverbound = data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND);
            } catch (RuntimeException unsupported) {
                return new StreamIds(-1, -1, -1, new ChunkIds(-1, -1, -1, -1));
            }
            return new StreamIds(
                    serverbound.id("minecraft:client_tick_end"),
                    clientbound.id("minecraft:ping"),
                    serverbound.id("minecraft:pong"),
                    new ChunkIds(
                            clientbound.id("minecraft:level_chunk_with_light"),
                            clientbound.id("minecraft:block_update"),
                            clientbound.id("minecraft:section_blocks_update"),
                            clientbound.id("minecraft:forget_level_chunk")));
        }
    }

    /** Bytes shared by everything that takes from it, up to a limit. */
    static final class Budget {
        private final long limit;
        private final AtomicLong used = new AtomicLong();

        Budget(long limit) {
            this.limit = limit;
        }

        boolean take(long bytes) {
            if (used.addAndGet(bytes) <= limit) return true;
            used.addAndGet(-bytes);
            return false;
        }

        void give(long bytes) {
            used.addAndGet(-bytes);
        }

        long remaining() {
            return limit - used.get();
        }
    }
}
