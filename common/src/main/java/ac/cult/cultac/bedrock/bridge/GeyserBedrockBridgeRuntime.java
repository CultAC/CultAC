package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.logging.BedrockPacketLogger;
import ac.cult.cultac.bedrock.logging.BedrockPacketLogOutboundHandler;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockPositionTranslator;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionDebug;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.integration.BedrockMovementCorrections;
import ac.cult.cultac.bedrock.prediction.integration.BedrockReplayEvent;
import ac.cult.cultac.bedrock.prediction.integration.BedrockReplayContextEvent;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportOperation;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportProvenance;
import ac.cult.cultac.bedrock.protocol.BedrockClientAction;
import ac.cult.cultac.bedrock.protocol.BedrockMoveFrame;
import ac.cult.cultac.bedrock.protocol.BedrockMoveVector;
import ac.cult.cultac.bedrock.protocol.BedrockProtocolVersion;
import ac.cult.cultac.bedrock.protocol.BedrockMovementCorrection;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.collisions.BedrockClientBlockShapeMappings;
import ac.cult.cultac.utils.floodgate.GeyserUtil;
import ac.cult.cultac.utils.latency.GeyserQueue;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.world.phys.Vec3;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.data.AuthoritativeMovementMode;
import org.cloudburstmc.protocol.bedrock.data.PredictionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket;
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovementPredictionSyncPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.geysermc.geyser.entity.type.BoatEntity;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionInitializeEvent;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostReloadEvent;
import org.geysermc.geyser.session.GeyserSession;

public final class GeyserBedrockBridgeRuntime {
    private static final Map<GeyserConnection, GeyserFloatingPointsAdapter> GFP_ADAPTERS = new ConcurrentHashMap<>();
    private static final Map<GeyserConnection, PacketTapHandler> PACKET_TAPS = new ConcurrentHashMap<>();

    private static volatile GeyserEventSubscriptions eventSubscriptions;
    private static volatile boolean started;
    private static GeyserSprintAttributeTranslator sprintTranslator;
    private static GeyserPingTranslator pingTranslator;
    private static GeyserKeepAliveTranslator keepAliveTranslator;
    private static volatile BedrockPacketLogger packetLogger;

    private GeyserBedrockBridgeRuntime() {
    }

    public static synchronized void start() {
        if (started) {
            return;
        }

        if (!GeyserUtil.isGeyserAvailable()) {
            return;
        }
        started = true;
        try {
            initializeBridge();
        } catch (RuntimeException | LinkageError failure) {
            try {
                stop();
            } catch (RuntimeException | LinkageError cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static void initializeBridge() {
        packetLogger = new BedrockPacketLogger(
                CultAPI.INSTANCE.getGrimPlugin().getDataFolder().toPath().resolve("geyserpacketlogs"),
                "cultac_version=" + CultAPI.INSTANCE.getExternalAPI().getGrimVersion()
                        + "\ngeyser_version=" + org.bukkit.Bukkit.getPluginManager().getPlugin("Geyser-Spigot").getDescription().getVersion(),
                LogUtil::info);

        GeyserUtil.forceForwardPlayerPing();
        refreshCollisionMappings();
        if (ac.cult.cultac.network.protocol.util.viaversion.ViaVersionUtil.isAvailable()
                && !com.viaversion.viaversion.api.Via.getManager().isInitialized()) {
            com.viaversion.viaversion.api.Via.getManager().addPostEnableListener(() -> {
                if (started) {
                    refreshCollisionMappings();
                }
            });
        }

        EventRegistrar owner = EventRegistrar.of(CultAPI.INSTANCE.getPlugin());
        GeyserEventSubscriptions subscriptions = new GeyserEventSubscriptions(GeyserApi.api().eventBus(), owner);
        eventSubscriptions = subscriptions;

        subscriptions.subscribe(GeyserPostInitializeEvent.class, event -> installSprintTranslator());
        subscriptions.subscribe(GeyserPostReloadEvent.class, event -> {
            installSprintTranslator();
            BedrockPacketLogger logger = packetLogger;
            if (logger != null) logger.stopAll("Geyser reload");
            GeyserUtil.forceForwardPlayerPing();
            refreshCollisionMappings();
            for (GeyserConnection connection : PACKET_TAPS.keySet()) {
                if (connection instanceof GeyserSession session) session.executeInEventLoop(() -> installFloatingPoints(session, true));
            }
        });
        subscriptions.subscribe(SessionInitializeEvent.class, event -> {
            // The listener can accept a session before the post-initialize event is fired.
            // Packet registries are already populated before Geyser binds its listener.
            installSprintTranslator();
            installPacketTap(event.connection());
            BedrockPacketLogger logger = packetLogger;
            if (logger != null && event.connection() instanceof GeyserSession session) {
                logger.onInitialize(session, session.bedrockUsername(), session.protocolVersion());
            }
        });
        CultAPI.INSTANCE.getNetworkManager().setPacketOwnerResolver(GeyserBedrockBridgeRuntime::packetOwner);
        subscriptions.subscribeLast(SessionLoginEvent.class, event -> {
            if (event.connection() instanceof GeyserSession session) {
                // GFP installs its upstream wrapper at NORMAL priority. Geyser creates and
                // connects the downstream only after this event returns. Attach here, rather
                // than racing that listener with a task on another event loop.
                installPacketTap(session);
                installFloatingPoints(session, false);
            }
        });
        subscriptions.subscribe(SessionJoinEvent.class, event -> {
            installPacketTap(event.connection());
            if (event.connection() instanceof GeyserSession session) installFloatingPoints(session, true);
            BedrockPacketLogger logger = packetLogger;
            if (logger != null) logger.identity(event.connection(), event.connection().javaUuid());
        });
        subscriptions.subscribe(SessionDisconnectEvent.class, GeyserBedrockBridgeRuntime::onSessionDisconnect);

        // Spigot enables Geyser before its ServerLoadEvent populates packet translators.
        // Subscribe first so startup cannot be missed; install now only for an already-ready Geyser.
        if (GeyserSprintAttributeTranslator.available()) installSprintTranslator();
        LogUtil.info("Geyser Bedrock movement bridge enabled.");
    }

    private static synchronized void installSprintTranslator() {
        if (!started) return;
        if (pingTranslator == null || !pingTranslator.isInstalled()) pingTranslator = new GeyserPingTranslator();
        if (keepAliveTranslator == null || !keepAliveTranslator.isInstalled()) keepAliveTranslator = new GeyserKeepAliveTranslator();
        if (sprintTranslator != null && sprintTranslator.isInstalled()) return;
        sprintTranslator = new GeyserSprintAttributeTranslator((session, attribute) -> {
            var tap = PACKET_TAPS.get(session);
            if (tap == null) throw new IllegalStateException("Missing Cult movement tap");
            tap.initializeActor(tap.currentPlayer());
            tap.sprintAttributes.source(attribute, tap.sprintBoundary(), session.getPlayerEntity().geyserId(), tap::sendSprintAttribute);
        }, (session, entity, attribute) -> {
            var tap = PACKET_TAPS.get(session);
            if (tap == null) throw new IllegalStateException("Missing Cult movement tap");
            var packet = tap.vehicleAttributes.source(entity.geyserId(), attribute);
            ((org.geysermc.geyser.entity.vehicle.ClientVehicle) entity).getVehicleComponent()
                    .setMoveSpeed(packet.getAttributes().getFirst().getValue());
            session.sendUpstreamPacket(packet);
        });
    }

    public static synchronized void stop() {
        started = false;
        CultAPI.INSTANCE.getNetworkManager().setPacketOwnerResolver(channel -> null);
        if (sprintTranslator != null) { sprintTranslator.close(); sprintTranslator = null; }
        if (pingTranslator != null) { pingTranslator.close(); pingTranslator = null; }
        if (keepAliveTranslator != null) { keepAliveTranslator.close(); keepAliveTranslator = null; }
        BedrockPacketLogger logger = packetLogger;
        packetLogger = null;
        if (logger != null) logger.close();
        GeyserEventSubscriptions subscriptions = eventSubscriptions;
        eventSubscriptions = null;
        if (subscriptions != null) subscriptions.close();

        for (PacketTapHandler tap : Set.copyOf(PACKET_TAPS.values())) {
            tap.connection.disconnect("CultAC movement bridge stopped. Please reconnect.");
            tap.detach();
        }
        // Resolve optional Geyser classes only when there is an attached session.
        for (GeyserFloatingPointsAdapter adapter : GFP_ADAPTERS.values()) {
            adapter.close();
        }
        GFP_ADAPTERS.clear();
        PACKET_TAPS.clear();
        BedrockClientBlockShapeMappings.clear();
    }

    private static synchronized void refreshCollisionMappings() {
        try {
            BedrockClientBlockShapeMappings.initialize();
        } catch (RuntimeException | LinkageError failure) {
            LogUtil.warn("Bedrock collision initialization failed; "
                    + (BedrockClientBlockShapeMappings.isInitialized()
                    ? "keeping the last valid collision cache: "
                    : "using native collision fallback: ") + failure);
        }
    }

    private static void onSessionDisconnect(SessionDisconnectEvent event) {
        GeyserConnection connection = event.connection();
        BedrockPacketLogger logger = packetLogger;
        if (logger != null) logger.stop(connection, "disconnect");
        GeyserFloatingPointsAdapter adapter = GFP_ADAPTERS.remove(connection);
        if (adapter != null) adapter.close();
        PacketTapHandler tap = PACKET_TAPS.remove(connection);
        if (tap != null) {
            tap.detach();
        }
    }

    public static void configurePacketLog(String username, UUID uuid, boolean onJoin, Consumer<String> feedback) {
        BedrockPacketLogger logger = packetLogger;
        if (logger == null || !started) {
            feedback.accept("Geyser packet logger is unavailable.");
            return;
        }
        if (onJoin) {
            for (GeyserConnection connection : PACKET_TAPS.keySet()) {
                if (username.equalsIgnoreCase(connection.bedrockUsername())) {
                    feedback.accept("Player is already connected. Toggle their capture without --onjoin using their server player name.");
                    return;
                }
            }
            feedback.accept(logger.arm(username));
            return;
        }
        GeyserConnection connection = GeyserApi.api().connectionByUuid(uuid);
        if (!(connection instanceof GeyserSession session) || !PACKET_TAPS.containsKey(connection)) {
            feedback.accept("Player has no active local Geyser packet tap.");
            return;
        }
        logger.toggle(session, session.bedrockUsername(), session.protocolVersion(), uuid, feedback);
    }

    private static void installPacketTap(GeyserConnection connection) {
        if (!(connection instanceof GeyserSession session)) {
            return;
        }

        BedrockSession bedrockSession = session.getUpstream().getSession();

        BedrockPacketHandler currentHandler = bedrockSession.getPacketHandler();
        if (currentHandler == null) {
            return;
        }

        PacketTapHandler installedTap = PACKET_TAPS.get(connection);
        if (currentHandler == installedTap) {
            installedTap.attachOutboundTap();
            return;
        }
        if (installedTap != null) {
            if (session.getDownstream() != null) {
                session.disconnect("CultAC movement handler changed. Please reconnect.");
                installedTap.detach();
                return;
            }
            installedTap.detach();
        }

        if (currentHandler instanceof PacketTapHandler tap) {
            PACKET_TAPS.put(connection, tap);
            tap.attachOutboundTap();
            return;
        }

        GeyserQueue latencyQueue;
        try {
            latencyQueue = installLatencyQueue(session);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LogUtil.warn("Unable to install Geyser latency queue: " + failure);
            session.disconnect("CultAC could not initialize latency tracking. Please reconnect.");
            return;
        }
        PacketTapHandler tap = new PacketTapHandler(session, bedrockSession, currentHandler, latencyQueue);
        bedrockSession.setPacketHandler(tap);
        PACKET_TAPS.put(connection, tap);
        tap.attachOutboundTap();
    }

    private static boolean installFloatingPoints(GeyserSession session, boolean requireJavaHook) {
        if (!GeyserFloatingPointsAdapter.present()) {
            return true;
        }
        GeyserEventSubscriptions generation = eventSubscriptions;
        try {
            GeyserFloatingPointsAdapter adapter = GFP_ADAPTERS.computeIfAbsent(session,
                    ignored -> createFloatingPointsAdapter(session, generation));
            if (adapter == null) return false;
            // Teardown can run between construction and publication in the map.
            if (!started || eventSubscriptions != generation || session.isClosed()) {
                GFP_ADAPTERS.remove(session, adapter);
                adapter.close();
                return false;
            }
            adapter.refresh(requireJavaHook);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            LogUtil.warn("Unable to bind GeyserFloatingPoints: " + failure);
            session.disconnect("CultAC: GeyserFloatingPoints compatibility error. Use oryxel1 2.0 Build 5 and reconnect.");
            return false;
        }
    }

    private static GeyserFloatingPointsAdapter createFloatingPointsAdapter(
            GeyserSession session, GeyserEventSubscriptions generation) {
        if (!started || eventSubscriptions != generation || session.isClosed()) return null;
        try {
            return new GeyserFloatingPointsAdapter(session);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to install GFP session hooks", failure);
        }
    }

    static GeyserQueue installLatencyQueue(GeyserSession session) throws ReflectiveOperationException {
        var current = session.getLatencyPingCache();
        if (current instanceof GeyserQueue queue && queue.isTrackingWrites()) return queue;
        // Install during session initialization, before any callback can be in flight.
        if (current == null || !current.isEmpty()) throw new IllegalStateException("Latency queue is already in use");
        var field = GeyserSession.class.getDeclaredField("latencyPingCache");
        field.setAccessible(true);
        GeyserQueue queue = new GeyserQueue();
        field.set(session, queue);
        if (session.getLatencyPingCache() != queue) throw new IllegalStateException("Latency queue replacement failed");
        return queue;
    }

    static Runnable nativeLatencyCallback(GeyserSession session, int id) {
        return () -> session.ensureInEventLoop(() -> acceptTransaction(session, id));
    }

    private static CultPlayer playerForSession(GeyserSession session) {
        var tap = PACKET_TAPS.get(session);
        return tap == null ? null : tap.currentPlayer();
    }

    static boolean acceptTransaction(GeyserSession session, int id) {
        CultPlayer player = playerForSession(session);
        return player != null && ac.cult.cultac.events.packets.listeners.PacketPingListener.acceptBedrockResponse(player, id);
    }

    static void acceptKeepAlive(GeyserSession session, long id) {
        CultPlayer player = playerForSession(session);
        if (player != null) player.checkManager.getListener(ac.cult.cultac.utils.latency.KeepAliveProcessor.class).acceptResponse(id);
    }

    static void correctCollisions(PlayerAuthInputPacket packet,
                                  ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags collision) {
        // Bedrock delta is next-tick velocity; collision flags describe the simulated move just completed.
        PacketTapHandler.setInputFlag(packet, PlayerAuthInputData.HORIZONTAL_COLLISION, collision.horizontalCollision());
        PacketTapHandler.setInputFlag(packet, PlayerAuthInputData.VERTICAL_COLLISION, collision.verticalCollision());
    }

    static boolean hasFiniteMovement(PlayerAuthInputPacket packet) {
        return finite(packet.getPosition()) && finite(packet.getDelta()) && finite(packet.getRotation())
                && packet.getMotion() != null && Float.isFinite(packet.getMotion().getX())
                && Float.isFinite(packet.getMotion().getY());
    }

    private static boolean finite(Vector3f vector) {
        return vector != null && Float.isFinite(vector.getX()) && Float.isFinite(vector.getY())
                && Float.isFinite(vector.getZ());
    }

    static net.minecraft.core.BlockPos worldBlock(GeyserSession session, org.cloudburstmc.math.vector.Vector3i position) {
        var adapter = GFP_ADAPTERS.get(session);
        var frame = adapter == null ? BedrockCoordinateFrame.IDENTITY : adapter.coordinateFrame();
        var world = frame.toWorld(new Vec3(position.getX(), position.getY(), position.getZ()));
        return net.minecraft.core.BlockPos.containing(world);
    }

    private static final class PacketTapHandler implements BedrockPacketHandler {
        private final GeyserSession connection;
        private final BedrockSession bedrockSession;
        private final BedrockPacketHandler delegate;
        private final GeyserQueue latencyQueue;
        private final AtomicBoolean detached = new AtomicBoolean();
        private final String outboundHandlerName;
        private final String packetLogHandlerName;
        private final Map<BedrockPacket, BedrockTeleportOperation> playerSetbackSources = new IdentityHashMap<>();
        private long playerTeleportSequence;
        private final GeyserSprintAttributes sprintAttributes = new GeyserSprintAttributes();
        private final GeyserVehicleAttributes vehicleAttributes = new GeyserVehicleAttributes();
        private final GeyserEntityPositions entityPositions = new GeyserEntityPositions();
        private final GeyserEntityAttributes entityAttributes = new GeyserEntityAttributes();
        private long movementCorrectionSequence;
        private record CorrectionSource(BedrockMovementCorrection correction, Vec3 claimedPosition, int debugId) { }
        private final Map<CorrectPlayerMovePredictionPacket, CorrectionSource> movementCorrectionSources = new IdentityHashMap<>();
        private Long actorCreationRuntimeId;
        private final GeyserTeleportRecovery teleportRecovery = new GeyserTeleportRecovery();

        private PacketTapHandler(GeyserSession connection, BedrockSession bedrockSession, BedrockPacketHandler delegate, GeyserQueue latencyQueue) {
            this.connection = connection;
            this.bedrockSession = bedrockSession;
            this.delegate = delegate;
            this.latencyQueue = latencyQueue;
            this.outboundHandlerName = "cultac-outbound-packet-" + Integer.toHexString(System.identityHashCode(this));
            this.packetLogHandlerName = outboundHandlerName + "-log";

        }

        @Override
        public PacketSignal handlePacket(BedrockPacket packet) {
            return handleTapped(packet);
        }

        @Override
        public PacketSignal handle(PlayerAuthInputPacket packet) {
            return handleTapped(packet);
        }

        @Override
        public PacketSignal handle(MovePlayerPacket packet) {
            return handleTapped(packet);
        }

        @Override
        public PacketSignal handle(MovementPredictionSyncPacket packet) {
            return handleTapped(packet);
        }

        @Override
        public PacketSignal handle(NetworkStackLatencyPacket packet) {
            return handleTapped(packet);
        }

        @Override
        public PacketSignal handle(InventoryTransactionPacket packet) {
            return handleTapped(packet);
        }

        @Override
        public PacketSignal handle(PlayerActionPacket packet) {
            return handleTapped(packet);
        }

        @Override
        public void onDisconnect(CharSequence reason) {
            delegate.onDisconnect(reason);
        }

        private PacketSignal handleTapped(BedrockPacket packet) {
            logPacket("C->S", packet);
            io.netty.util.ReferenceCountUtil.retain(packet);
            connection.ensureInEventLoop(() -> {
                try {
                    if (detached.get()) return;
                    if (currentPlayer() == null) {
                        // Login traffic must establish the Java connection before ownership can bind.
                        delegate.handlePacket(packet);
                    } else translateInput(packet);
                } finally {
                    io.netty.util.ReferenceCountUtil.release(packet);
                }
            });
            return PacketSignal.HANDLED;
        }

        private void translateInput(BedrockPacket packet) {
            CultPlayer player = currentPlayer();
            if (player == null) return;
            GeyserInventoryActions.translate(connection, player, packet, () -> {
                if (packet instanceof InteractPacket interaction
                        && !GeyserVehicleInput.acceptsInteraction(connection, player, interaction)) return;
                int sequence = GeyserItemUse.sequence(connection);
                if (packet instanceof PlayerAuthInputPacket authInput) {
                    if (!processAuthInput(player, authInput)) {
                        // Inventory requests share the auth-input envelope but do not authorize movement.
                        if (authInput.getInputData().contains(PlayerAuthInputData.PERFORM_ITEM_STACK_REQUEST)
                                && authInput.getItemStackRequest() != null) {
                            connection.getPlayerInventoryHolder().translateRequests(java.util.List.of(authInput.getItemStackRequest()));
                        }
                        return;
                    }
                } else if (packet instanceof MovePlayerPacket move) {
                    submitMove(player, move);
                } else if (packet instanceof InventoryTransactionPacket transaction) {
                    if (!GeyserItemUse.allow(connection, player, transaction)) return;
                    submitInventoryTransaction(player, transaction);
                } else if (packet instanceof PlayerActionPacket action) {
                    submitPlayerAction(player, action);
                }
                var adapter = GFP_ADAPTERS.get(connection);
                if (adapter != null && packet instanceof PlayerAuthInputPacket) {
                    adapter.withTeleportRetry(() -> delegate.handlePacket(packet));
                } else delegate.handlePacket(packet);
                if (packet instanceof InventoryTransactionPacket transaction) {
                    GeyserItemUse.observe(connection, player, transaction, sequence);
                } else if (packet instanceof PlayerAuthInputPacket input) {
                    GeyserVehicleInput.observe(connection, player, input);
                    player.actionManager.endClientTick();
                    player.packetStateData.acceptedClientTick++;
                    player.packetStateData.lastClientTickEndTransaction = player.lastTransactionReceived.get();
                    player.packetStateData.carriedItemChangedThisClientTick = false;
                    player.serverOpenedInventoryThisTick = false;
                }
            });
        }

        private void logPacket(String direction, BedrockPacket packet) {
            BedrockPacketLogger logger = packetLogger;
            if (logger != null) logger.record(connection, direction, packet);
        }

        private void detach() {
            if (!detached.compareAndSet(false, true)) {
                return;
            }
            PACKET_TAPS.remove(connection, this);
            connection.ensureInEventLoop(() -> { entityPositions.clear(); entityAttributes.clear(); sprintAttributes.close(); vehicleAttributes.clear(); });
            if (connection.isClosed()) latencyQueue.clear();
            else latencyQueue.stopTrackingWrites();
            if (bedrockSession.getPacketHandler() == this) {
                bedrockSession.setPacketHandler(delegate);
            }
            var channel = bedrockSession.getPeer().getChannel();
            channel.eventLoop().execute(() -> {
                removeHandlerIfPresent(channel.pipeline(), outboundHandlerName);
                removeHandlerIfPresent(channel.pipeline(), packetLogHandlerName);
            });
        }

        private void attachOutboundTap() {
            var channel = bedrockSession.getPeer().getChannel();
            channel.eventLoop().execute(() -> {
                var pipeline = channel.pipeline();
                if (detached.get()) return;
                if (pipeline.get(outboundHandlerName) != null) {
                    return;
                }

                pipeline.addLast(connection.getTickEventLoop(), packetLogHandlerName,
                        new BedrockPacketLogOutboundHandler(packet -> logPacket("S->C", packet)));
                pipeline.addLast(connection.getTickEventLoop(), outboundHandlerName, new OutboundPacketTap(this));
            });

        }

        private void recordOutboundMetadata(
                ChannelHandlerContext context, BedrockPacketWrapper source,
                Float width, Float height, Boolean gliding, Boolean crawling, Boolean swimming,
                Boolean sneaking, Boolean spinning, Boolean sleeping, Boolean usingItem, Boolean sprinting,
                long tick, long generation
        ) {
            if (!isCurrentConnection()) {
                return;
            }
            if ((width == null && height == null && gliding == null
                    && crawling == null && swimming == null && sneaking == null
                    && spinning == null && sleeping == null && usingItem == null && sprinting == null)
                    || (width != null && (!Float.isFinite(width) || width <= 0.0F))
                    || (height != null && (!Float.isFinite(height) || height <= 0.0F))) {
                return;
            }

            writeLatencyBoundary(context, source, player -> {
                if (isCurrentConnection()) {
                    var metadata = player.bedrockState.movementCorrections.metadata(generation, tick,
                            new BedrockReplayEvent.Metadata(width, height, gliding, crawling, swimming, spinning, sprinting));
                    if (metadata == null) {
                        if (sleeping != null) player.checkManager.getSimulationProcessor()
                                .handleBedrockSleepingStateChange(sleeping);
                        return;
                    }
                    if (usingItem != null) player.bedrockState.movementCorrections.queueUpdate(generation, -1,
                            connection.getPlayerEntity().geyserId(), 0, false,
                            new BedrockReplayContextEvent(Map.of(), null, null, -1, null, usingItem));
                    // Apply at the acknowledged wire boundary before the next input.
                    player.checkManager.getSimulationProcessor().applyAcknowledgedBedrockMetadata(
                            metadata.width(), metadata.height(), metadata.gliding(), metadata.crawling(), metadata.swimming(),
                            sneaking, metadata.spinning(), sleeping, usingItem, metadata.sprinting());
                }
            });
        }

        private Long registerOutboundGeyserTeleport(MovePlayerPacket packet, BedrockOriginDispatch.Emission emission) {
            Vec3 physicalFeetTarget = toJavaPosition(packet.getPosition());
            if (physicalFeetTarget != null
                    && isGeyserPositionTeleport(packet.getMode())) {
                return commitOutboundBedrockTeleport(physicalFeetTarget, packet.isOnGround(), rawPosition(packet.getPosition()), emission);
            }
            return null;
        }

        private Long registerOutboundGeyserTeleport(MoveEntityAbsolutePacket packet, BedrockOriginDispatch.Emission emission) {
            if (!packet.isTeleported()) {
                return null;
            }
            Vec3 physicalFeetTarget = toJavaPosition(packet.getPosition());
            if (physicalFeetTarget == null) {
                return null;
            }
            // Treat every client-visible position teleport through the same
            // packet-driven queue path. No Geyser teleport cache is consulted.
            return commitOutboundBedrockTeleport(physicalFeetTarget, packet.isOnGround(), rawPosition(packet.getPosition()), emission);
        }

        private Long commitOutboundBedrockTeleport(Vec3 physicalFeetPosition, boolean onGround,
                                                   Vec3 localTarget, BedrockOriginDispatch.Emission emission) {
            CultPlayer player = currentPlayer();
            if (player == null) {
                return null;
            }
            if (emission == null) {
                if (GeyserFloatingPointsAdapter.present()) {
                    connection.disconnect("CultAC: GFP packet bypassed the required origin enqueue hook.");
                    return null;
                }
                return player.getSetbackTeleportUtil().addImmediateBedrockTransportTeleport(physicalFeetPosition, onGround);
            }
            CultPlayer.BedrockTransaction before = player.getLastClientboundBedrockTransaction();
            int proof = before == null ? player.lastTransactionReceived.get() : before.transaction();
            long revision = player.getSetbackTeleportUtil().addImmediateBedrockTransportTeleport(
                    emission.frame().toWorld(physicalFeetPosition), onGround, emission.frame(), localTarget,
                    emission.operation(), proof);
            BedrockPacketLogger logger = packetLogger;
            if (logger != null) logger.origin(connection, revision, emission.frame(), localTarget,
                    emission.provenance(), emission.operation() == null ? null : emission.operation().setbackTransaction(), proof);
            return revision;
        }

        private void confirmOrigin(CultPlayer player, long revision, BedrockOriginDispatch.Emission emission, Vec3 localTarget) {
            if (emission == null) return;
            player.getSetbackTeleportUtil().confirmBedrockOrigin(revision, emission.operation(), emission.frame(), localTarget);
            BedrockPacketLogger logger = packetLogger;
            if (logger != null) logger.originReceipt(connection, revision);
        }

        private void writeLatencyBoundary(ChannelHandlerContext context, BedrockPacketWrapper source,
                                          Consumer<CultPlayer> acknowledgement) {
            CultPlayer player = currentPlayer();
            if (player == null) return;
            CultPlayer.BedrockTransaction transaction = player.createBedrockTransactionAfterClientbound();
            // This newly allocated transaction cannot be acknowledged before its write.
            // Prepare its callback synchronously, before exposing the latency marker.
            player.addBedrockTransactionTask(transaction, () -> acknowledgement.accept(player));
            writeTransactionBoundary(context, source, player, transaction);
        }

        private void writeTransactionBoundary(ChannelHandlerContext context, BedrockPacketWrapper source,
                                              CultPlayer player, CultPlayer.BedrockTransaction transaction) {
            NetworkStackLatencyPacket marker = new NetworkStackLatencyPacket();
            marker.setFromServer(true);
            marker.setTimestamp(transaction.id());
            latencyQueue.insert(nativeLatencyCallback(connection, transaction.id()), () -> {
                player.markBedrockTransactionClientbound(transaction.id());
                entityPositions.boundary(player, transaction);
                entityAttributes.boundary(player, transaction);
                context.write(BedrockPacketWrapper.create(0, source.getSenderSubClientId(),
                        source.getTargetSubClientId(), marker, null), context.voidPromise());
            });
        }

        private void initializeActor(CultPlayer player) {
            if (actorCreationRuntimeId == null || player == null) return;
            player.checkManager.getSimulationProcessor().handleBedrockActorCreation(actorCreationRuntimeId);
            actorCreationRuntimeId = null;
        }

        private GeyserSprintAttributes.Boundary sprintBoundary() {
            CultPlayer player = currentPlayer();
            if (player == null) return new GeyserSprintAttributes.Boundary(-1, 0, false);
            var commit = player.checkManager.getSimulationProcessor().getCurrentPredictionCommit();
            var state = commit == null ? null
                    : ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState.previousState(commit.carry());
            // The simulation frame's clientTick is a processed-input sequence. Only the
            // paired PlayerAuthInput timestamp addresses the client's rewind history.
            return new GeyserSprintAttributes.Boundary(player.bedrockState.movementCorrections.generation(),
                    state == null ? 0 : player.bedrockState.processedClientTick(),
                    state != null && !state.isVehicle() && state.sprinting());
        }

        private void sendSprintAttribute(org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket packet) {
            // Geyser resends this cache for effects, respawns, and dimension changes.
            connection.getPlayerEntity().getAttributes().put(
                    org.geysermc.geyser.entity.attribute.GeyserAttributeType.MOVEMENT_SPEED, packet.getAttributes().getFirst());
            connection.sendUpstreamPacket(packet);
        }

        private boolean processAuthInput(CultPlayer player, PlayerAuthInputPacket packet) {
            if (!hasFiniteMovement(packet)) return false;
            if (!installFloatingPoints(connection, true)) return false;
            initializeActor(player);
            var beforeSprint = sprintBoundary();
            BedrockAuthInputFrame frame = captureAuthInput(player, packet);
            long previousTick = player.bedrockState.lastMovementTick();
            var state = ac.cult.cultac.bedrock.prediction.integration.BedrockFrameProcessor.process(player, frame,
                    ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger.BEDROCK_THREAD,
                    () -> GeyserVehicleInput.translateRejectedMovement(connection, player, packet),
                    resolved -> {
                        if (packet.getInputData().contains(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS)) {
                            player.bedrockState.blockBreakActions.apply(player,
                                    resolved.getCoordinateFrame(), packet.getPlayerActions());
                        }
                    });
            // Recovery must run even when the pending teleport rejects movement before Geyser's translator.
            var recoveryAdapter = GFP_ADAPTERS.get(connection);
            teleportRecovery.input(player.bedrockState.lastMovementTick(),
                    player.getSetbackTeleportUtil().isPendingBedrockSetback(teleportRecovery.operation()),
                    recoveryAdapter == null ? BedrockCoordinateFrame.IDENTITY : recoveryAdapter.coordinateFrame());
            if (state == null && player.bedrockState.lastMovementTick() > previousTick
                    && player.getSetbackTeleportUtil().mustAcknowledgeBedrockTransportTeleport()
                    && !teleportRecovery.active()) {
                var retryAdapter = GFP_ADAPTERS.get(connection);
                if (retryAdapter == null) GeyserTeleportRecovery.retryGeyserTeleport(connection);
                else retryAdapter.withTeleportRetry(() -> GeyserTeleportRecovery.retryGeyserTeleport(connection));
            }
            if (state == null) return false;
            if (state.isVehicle() && !GeyserVehicleInput.acceptsMovement(connection, player)) return false;
            if (player.compensatedEntities.getSelf().isDead) return false;
            var boundary = sprintBoundary();
            if (!state.isVehicle() && (boundary.generation() != beforeSprint.generation()
                    || boundary.sprinting() != beforeSprint.sprinting())) {
                sprintAttributes.confirm(boundary, connection.getPlayerEntity().geyserId(), this::sendSprintAttribute);
            }
            var adapter = GFP_ADAPTERS.get(connection);
            var coordinates = adapter == null ? BedrockCoordinateFrame.IDENTITY : adapter.coordinateFrame();
            var vehicle = state.isVehicle() ? connection.getPlayerEntity().getVehicle() : null;
            if (state.isVehicle() && (vehicle == null || frame.getPredictedVehicleJavaId() == null
                    || vehicle.getEntityId() != frame.getPredictedVehicleJavaId())) return false;
            Vec3 position = ac.cult.cultac.bedrock.prediction.integration.BedrockVectorAdapter.toJava(state.physicalFeetPosition());
            packet.setPosition(toVector3f(coordinates.toLocal(position)).up(vehicle == null ? ownerPlayerOffset() : vehicle.getOffset()));
            packet.setDelta(toVector3f(ac.cult.cultac.bedrock.prediction.integration.BedrockVectorAdapter.toJava(state.velocity())));
            correctCollisions(packet, state.collisionFlags());
            if (state.isBoat()) packet.setVehicleRotation(Vector2f.from(state.inputFrame().pitch(), state.inputFrame().yaw()));
            return true;
        }

        private static void setInputFlag(PlayerAuthInputPacket packet, PlayerAuthInputData flag, boolean enabled) {
            if (enabled) packet.getInputData().add(flag);
            else packet.getInputData().remove(flag);
        }

        private boolean matchesJavaUser(User user) {
            var downstream = connection.getDownstream();
            return downstream != null && sameJavaConnection(
                    downstream.getSession().getChannel(), user.getChannel());
        }

        private BedrockAuthInputFrame captureAuthInput(CultPlayer player, PlayerAuthInputPacket packet) {
            long vehicleId = predictedVehicleId(packet);
            var vehicle = vehicleId == -1L ? null : connection.getEntityCache().getEntityByGeyserId(vehicleId);
            if (isPredictedHorse(vehicle) || vehicle instanceof BoatEntity) {
                player.bedrockState.movementCorrections.beginFrame(player, packet.getTick(), vehicle.getEntityId(), vehicleId);
            }
            Vec3 position = vehicleId == -1L ? toJavaPosition(packet.getPosition()) : rawPosition(
                    vehicle instanceof BoatEntity ? packet.getPosition().down(vehicle.getOffset()) : packet.getPosition());
            // HANDLE_TELEPORT is a correction-only tick. This diagnostic displacement must not
            // include the change of local origin; authorization still requires the transport echo.
            var previous = player.bedrockState.getLastOfferedFrame();
            Vec3 delta = previous == null || !java.util.Objects.equals(previous.getPredictedVehicleId(), vehicleId)
                    || packet.getInputData().contains(PlayerAuthInputData.HANDLE_TELEPORT)
                    ? Vec3.ZERO : position.subtract(previous.getCoordinateFrame().toLocal(previous.getPosition()));
            return createAuthInputFrame(
                    player.playerUUID,
                    connection.protocolVersion(),
                    packet,
                    position,
                    delta,
                    vehicle == null ? null : vehicle.getEntityId());
        }

        private void submitMove(CultPlayer player, MovePlayerPacket packet) {
            if (!installFloatingPoints(connection, true)) return;
            player.bedrockState.setLastMoveFrame(player.getSetbackTeleportUtil().resolveBedrockCoordinates(
                    createMoveFrame(player.playerUUID, connection.protocolVersion(), packet)));
        }

        private void submitInventoryTransaction(CultPlayer player, InventoryTransactionPacket packet) {
            if (packet.getTransactionType() != InventoryTransactionType.ITEM_RELEASE || packet.getActionType() != 0) {
                return;
            }
            player.bedrockState.recordClientAction(BedrockClientAction.ITEM_RELEASE);
        }

        private void submitPlayerAction(CultPlayer player, PlayerActionPacket packet) {
            PlayerActionType action = packet.getAction();
            if (action == PlayerActionType.START_SPIN_ATTACK) {
                player.bedrockState.recordClientAction(BedrockClientAction.START_SPIN_ATTACK);
            } else if (action == PlayerActionType.STOP_SPIN_ATTACK) {
                player.bedrockState.recordClientAction(BedrockClientAction.STOP_SPIN_ATTACK);
            } else if (action == PlayerActionType.START_GLIDE) {
                player.bedrockState.recordClientAction(BedrockClientAction.START_GLIDING);
            } else if (action == PlayerActionType.STOP_GLIDE) {
                player.bedrockState.recordClientAction(BedrockClientAction.STOP_GLIDING);
            } else if (action == PlayerActionType.START_SNEAK) {
                player.bedrockState.recordClientAction(BedrockClientAction.START_SNEAKING);
            } else if (action == PlayerActionType.STOP_SNEAK) {
                player.bedrockState.recordClientAction(BedrockClientAction.STOP_SNEAKING);
            } else if (action == PlayerActionType.START_SWIMMING) {
                player.bedrockState.recordClientAction(BedrockClientAction.START_SWIMMING);
            } else if (action == PlayerActionType.STOP_SWIMMING) {
                player.bedrockState.recordClientAction(BedrockClientAction.STOP_SWIMMING);
            } else if (action == PlayerActionType.START_CRAWLING) {
                player.bedrockState.recordClientAction(BedrockClientAction.START_CRAWLING);
            } else if (action == PlayerActionType.STOP_CRAWLING) {
                player.bedrockState.recordClientAction(BedrockClientAction.STOP_CRAWLING);
            }
        }

        private boolean isCurrentConnection() {
            return currentPlayer() != null;
        }

        private CultPlayer currentPlayer() {
            UUID uuid = connection.javaUuid();
            if (uuid == null || detached.get() || PACKET_TAPS.get(connection) != this) return null;
            CultPlayer player = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(uuid);
            // A reconnect can reuse the UUID while an old session still has queued writes.
            return player != null && player.isBedrockMovement() && matchesJavaUser(player.user) ? player : null;
        }
    }

    private static io.netty.util.concurrent.EventExecutor packetOwner(io.netty.channel.Channel channel) {
        for (PacketTapHandler tap : PACKET_TAPS.values()) {
            var downstream = tap.connection.getDownstream();
            if (downstream != null && sameJavaConnection(downstream.getSession().getChannel(), channel)) {
                return tap.connection.getTickEventLoop();
            }
        }
        return null;
    }

    private static float ownerPlayerOffset() {
        return (float) BedrockPositionTranslator.PLAYER_PACKET_Y_OFFSET;
    }

    public static boolean sendPlayerTeleport(User user, Vec3 position, float yaw, float pitch,
                                              boolean onGround, int setbackTransaction) {
        PacketTapHandler tap = packetTapForUser(user);
        if (tap == null) return false;
        tap.connection.ensureInEventLoop(() -> {
            if (!tap.matchesJavaUser(user)) return;
            sendPlayerTeleport(tap, position, yaw, pitch, onGround,
                    new BedrockTeleportOperation(++tap.playerTeleportSequence,
                            BedrockTeleportProvenance.CULT_SETBACK, setbackTransaction), null);
        });
        return true;
    }

    private static void sendPlayerTeleport(PacketTapHandler tap, Vec3 position, float yaw, float pitch,
                                          boolean onGround, BedrockTeleportOperation operation, SetEntityMotionPacket motion) {
        CultPlayer player = tap.currentPlayer();
        if (player == null || operation.setbackTransaction() == null
                || !player.getSetbackTeleportUtil().isCurrentBedrockSetback(operation.setbackTransaction())) return;
        var adapter = GFP_ADAPTERS.get(tap.connection);
        var coordinates = adapter == null ? BedrockCoordinateFrame.IDENTITY : adapter.coordinateFrame();
        tap.connection.setUnconfirmedTeleport(null);
        tap.connection.getPlayerEntity().setPosition(toVector3f(coordinates.toLocal(position)));
        MovePlayerPacket packet = new MovePlayerPacket();
        packet.setRuntimeEntityId(tap.connection.getPlayerEntity().geyserId());
        packet.setPosition(toVector3f(coordinates.toLocal(position)).up(ownerPlayerOffset()));
        packet.setRotation(Vector3f.from(pitch, yaw, yaw));
        packet.setMode(MovePlayerPacket.Mode.TELEPORT);
        packet.setTeleportationCause(MovePlayerPacket.TeleportationCause.UNKNOWN);
        packet.setOnGround(onGround);
        tap.playerSetbackSources.put(packet, operation);
        tap.connection.sendUpstreamPacket(packet);
        if (motion != null) tap.connection.sendUpstreamPacket(motion);
    }

    public static boolean sendMovementCorrection(User user, BedrockMovementCorrection correction, Vec3 claimedPosition, int debugId) {
        PacketTapHandler tap = packetTapForUser(user);
        if (tap == null) return false;
        if (!tap.connection.getTickEventLoop().inEventLoop()) throw new IllegalStateException("Correction outside movement loop");
        {
            CultPlayer player = tap.currentPlayer();
            var vehicle = tap.connection.getPlayerEntity().getVehicle();
            if (player == null || !tap.matchesJavaUser(user)
                    || player.getSetbackTeleportUtil().hasPendingBedrockTransportTeleport()
                    || player.bedrockState.movementCorrections.generation() != correction.controlGeneration()
                    || correction.vehicle() && (!(isPredictedHorse(vehicle) || vehicle instanceof BoatEntity)
                        || vehicle.getEntityId() != correction.vehicleId() || vehicle.geyserId() != correction.runtimeId())
                    || !correction.vehicle() && vehicle != null) return false;
            var adapter = GFP_ADAPTERS.get(tap.connection);
            var coordinates = adapter == null ? BedrockCoordinateFrame.IDENTITY : adapter.coordinateFrame();
            var packet = new CorrectPlayerMovePredictionPacket();
            packet.setPredictionType(correction.vehicle() ? PredictionType.VEHICLE : PredictionType.PLAYER);
            packet.setTick(correction.tick());
            packet.setPosition(toVector3f(coordinates.toLocal(correction.position())).up(
                    correction.vehicle() ? vehicle.getOffset() : ownerPlayerOffset()));
            packet.setDelta(toVector3f(correction.velocity()));
            packet.setOnGround(correction.onGround());
            // Modern codecs serialize this field for both prediction types.
            packet.setVehicleRotation(Vector2f.ZERO);
            if (correction.vehicle()) {
                packet.setVehicleRotation(Vector2f.from(correction.pitch(), correction.yaw()));
                packet.setVehicleAngularVelocity(correction.angularVelocity());
            }
            tap.movementCorrectionSources.put(packet, new PacketTapHandler.CorrectionSource(new BedrockMovementCorrection(correction.sequence(), correction.controlGeneration(),
                    correction.vehicleId(), correction.runtimeId(), correction.tick(), correction.position(), correction.velocity(),
                    correction.yaw(), correction.pitch(), correction.onGround(), coordinates, correction.teleportTransaction(),
                    correction.angularVelocity(), correction.vehicle()), claimedPosition, debugId));
            tap.connection.sendUpstreamPacket(packet);
        }
        return true;
    }

    private static PacketTapHandler packetTapForUser(User user) {
        Object associatedConnection = user.getBedrockBridgeConnection();
        if (associatedConnection instanceof GeyserConnection geyserConnection) {
            PacketTapHandler associatedTap = PACKET_TAPS.get(geyserConnection);
            if (associatedTap != null && associatedTap.matchesJavaUser(user)) {
                return associatedTap;
            }
        }

        for (PacketTapHandler tap : PACKET_TAPS.values()) {
            if (tap.matchesJavaUser(user)) {
                user.setBedrockBridgeConnection(tap.connection);
                return tap;
            }
        }
        return null;
    }

    static void removeHandlerIfPresent(ChannelPipeline pipeline, String handlerName) {
        try {
            if (pipeline.get(handlerName) != null) {
                pipeline.remove(handlerName);
            }
        } catch (NoSuchElementException ignored) {
            // Channel teardown can remove handlers on their assigned executor
            // after the lookup but before the named removal reaches it.
        }
    }

    static boolean isGeyserPositionTeleport(MovePlayerPacket.Mode mode) {
        return mode == MovePlayerPacket.Mode.TELEPORT
                || mode == MovePlayerPacket.Mode.RESPAWN;
    }

    static void convertSelfCorrectionToTeleport(MovePlayerPacket packet) {
        if (packet.getMode() != MovePlayerPacket.Mode.NORMAL) {
            return;
        }

        packet.setMode(MovePlayerPacket.Mode.TELEPORT);
        packet.setTeleportationCause(MovePlayerPacket.TeleportationCause.BEHAVIOR);
    }

    private static final class OutboundPacketTap extends ChannelDuplexHandler {
        private final PacketTapHandler owner;

        private OutboundPacketTap(PacketTapHandler owner) {
            this.owner = owner;
        }

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            if (owner.detached.get() || !(message instanceof BedrockPacketWrapper wrapper)) {
                context.write(message, promise);
                return;
            }

            BedrockPacket packet = wrapper.getPacket();
            owner.initializeActor(owner.currentPlayer());
            boolean normalizedVehicleEffect = false;
            if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket attributes) {
                owner.vehicleAttributes.written(attributes);
            } else if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket removed) {
                owner.vehicleAttributes.remove(removed.getUniqueEntityId());
            } else if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket) {
                owner.vehicleAttributes.clear();
            } else if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket effect
                    && effect.getTick() == 0) {
                var attributes = owner.vehicleAttributes.beforeEffect(effect);
                if (attributes != null) {
                    var entity = owner.connection.getEntityCache().getEntityByGeyserId(effect.getRuntimeEntityId());
                    if (entity instanceof org.geysermc.geyser.entity.vehicle.ClientVehicle vehicle) {
                        vehicle.getVehicleComponent().setMoveSpeed(attributes.getAttributes().getFirst().getValue());
                    }
                    write(context, BedrockPacketWrapper.create(0, wrapper.getSenderSubClientId(),
                            wrapper.getTargetSubClientId(), attributes, null), context.newPromise());
                    normalizedVehicleEffect = true;
                }
            }
            if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket attributes
                    && attributes.getRuntimeEntityId() == owner.connection.getPlayerEntity().geyserId()) {
                // Keep unrelated attributes at their original boundary when movement receives a tick.
                var others = attributes.getAttributes().stream().filter(a -> !a.getName().equals("minecraft:movement")).toList();
                if (!others.isEmpty() && others.size() != attributes.getAttributes().size()) {
                    var separate = new org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket();
                    separate.setRuntimeEntityId(attributes.getRuntimeEntityId());
                    separate.setTick(attributes.getTick());
                    separate.setAttributes(others);
                    write(context, BedrockPacketWrapper.create(0, wrapper.getSenderSubClientId(),
                            wrapper.getTargetSubClientId(), separate, null), context.newPromise());
                    attributes.setAttributes(attributes.getAttributes().stream()
                            .filter(a -> a.getName().equals("minecraft:movement")).toList());
                }
                if (!owner.sprintAttributes.rewrite(attributes, owner.sprintBoundary())) {
                    io.netty.util.ReferenceCountUtil.release(message);
                    promise.trySuccess();
                    return;
                }
                for (var attribute : attributes.getAttributes()) {
                    if (attribute.getName().equals("minecraft:movement")) owner.connection.getPlayerEntity().getAttributes().put(
                            org.geysermc.geyser.entity.attribute.GeyserAttributeType.MOVEMENT_SPEED, attribute);
                }
            }
            if (packet instanceof SetEntityDataPacket metadata
                    && metadata.getRuntimeEntityId() == owner.connection.getPlayerEntity().geyserId()
                    && metadata.getMetadata().get(EntityDataTypes.FLAGS) != null) {
                var other = new SetEntityDataPacket();
                other.setRuntimeEntityId(metadata.getRuntimeEntityId());
                other.setTick(metadata.getTick());
                other.getMetadata().putAll(metadata.getMetadata());
                other.getMetadata().remove(EntityDataTypes.FLAGS);
                other.getMetadata().remove(EntityDataTypes.FLAGS_2);
                if (!other.getMetadata().isEmpty()) {
                    write(context, BedrockPacketWrapper.create(0, wrapper.getSenderSubClientId(),
                            wrapper.getTargetSubClientId(), other, null), context.newPromise());
                    for (var key : other.getMetadata().keySet()) metadata.getMetadata().remove(key);
                }
                var boundary = owner.sprintBoundary();
                metadata.getMetadata().putFlags(metadata.getMetadata().getFlags().clone());
                metadata.getMetadata().setFlag(EntityFlag.SPRINTING, boundary.sprinting());
                metadata.setTick(boundary.tick());
            }
            if (packet instanceof StartGamePacket startGame) {
                // Newer protocols omit the movement mode but still transmit the history size.
                startGame.setAuthoritativeMovementMode(AuthoritativeMovementMode.SERVER_WITH_REWIND);
                startGame.setRewindHistorySize(BedrockMovementCorrections.CLIENT_HISTORY_SIZE);
                owner.actorCreationRuntimeId = startGame.getRuntimeEntityId();
                owner.initializeActor(owner.currentPlayer());
            }
            GeyserFloatingPointsAdapter adapter = GFP_ADAPTERS.get(owner.connection);
            BedrockOriginDispatch.Emission capturedOrigin = adapter == null
                    ? new BedrockOriginDispatch.Emission(BedrockCoordinateFrame.IDENTITY, null) : adapter.takeEmission(packet);
            BedrockTeleportOperation ownTeleport = owner.playerSetbackSources.remove(packet);
            BedrockOriginDispatch.Emission emission = ownTeleport == null ? capturedOrigin
                    : new BedrockOriginDispatch.Emission(capturedOrigin == null ? BedrockCoordinateFrame.IDENTITY : capturedOrigin.frame(), ownTeleport);
            CultPlayer observedPlayer = owner.currentPlayer();
            if (ownTeleport != null && (observedPlayer == null || ownTeleport.setbackTransaction() == null
                    || !observedPlayer.getSetbackTeleportUtil().isCurrentBedrockSetback(ownTeleport.setbackTransaction()))) {
                io.netty.util.ReferenceCountUtil.release(message);
                promise.trySuccess();
                return;
            }
            if (adapter != null && emission == null && GeyserEntityPositions.hasPosition(packet)) {
                io.netty.util.ReferenceCountUtil.release(message);
                promise.tryFailure(new IllegalStateException("Missing entity coordinate origin"));
                owner.connection.disconnect("CultAC: entity update bypassed the coordinate hook. Please reconnect.");
                return;
            }
            if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.SetEntityLinkPacket link
                    && observedPlayer != null
                    && link.getEntityLink().getTo() == owner.connection.getPlayerEntity().geyserId()
                    && link.getEntityLink().getType() != org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData.Type.REMOVE) {
                Runnable received = observedPlayer.getSetbackTeleportUtil().captureBedrockVehicleMount();
                context.write(message, promise);
                owner.writeLatencyBoundary(context, wrapper,
                        player -> received.run());
                return;
            }
            if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket) owner.entityPositions.clear();
            if (packet instanceof StartGamePacket || packet instanceof org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket) {
                owner.teleportRecovery.clear();
            }
            if (observedPlayer != null) owner.entityPositions.capture(owner.connection, observedPlayer, packet,
                    emission == null ? BedrockCoordinateFrame.IDENTITY : emission.frame());
            // The preceding attribute packet already includes this effect's speed change.
            var replayUpdate = normalizedVehicleEffect ? null : GeyserReplayUpdate.capture(packet);
            if (observedPlayer != null && replayUpdate != null) {
                owner.entityAttributes.capture(owner.connection, observedPlayer, replayUpdate);
            }
            if (packet instanceof CorrectPlayerMovePredictionPacket correction) {
                writeMovementCorrection(context, message, promise, wrapper, correction, emission);
                return;
            }
            if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.MovementEffectPacket effect
                    && effect.getEntityRuntimeId() == owner.connection.getPlayerEntity().geyserId()
                    && effect.getEffectType() == org.cloudburstmc.protocol.bedrock.data.MovementEffectType.GLIDE_BOOST) {
                int duration = effect.getDuration();
                long tick = owner.connection.getClientTicks();
                effect.setTick(tick);
                var replay = captureReplayUpdate(effect.getEntityRuntimeId(), tick, true,
                        new BedrockReplayEvent.Boost(duration));
                context.write(message, promise);
                owner.writeLatencyBoundary(context, wrapper,
                        player -> {
                            if (replay != null) replay.accept(player);
                            player.bedrockState.movementEffects.setGlideBoost(duration);
                        });
                return;
            }
            if (packet instanceof NetworkStackLatencyPacket latencyPacket && latencyPacket.isFromServer()) {
                CultPlayer player = owner.currentPlayer();
                owner.latencyQueue.write(() -> {
                    if (player != null) {
                        player.markBedrockTransactionClientbound(latencyPacket.getTimestamp());
                        var boundary = player.getLastClientboundBedrockTransaction();
                        if (boundary != null && boundary.id() == latencyPacket.getTimestamp()) {
                            owner.entityPositions.boundary(player, boundary);
                            owner.entityAttributes.boundary(player, boundary);
                        }
                    }
                    context.write(message, promise);
                });
                return;
            }

            long metadataId = packet instanceof AddEntityPacket spawn ? spawn.getRuntimeEntityId()
                    : packet instanceof SetEntityDataPacket update ? update.getRuntimeEntityId() : -1;
            var entity = metadataId < 0 || owner.connection.getEntityCache() == null ? null
                    : owner.connection.getEntityCache().getEntityByGeyserId(metadataId);
            if (entity instanceof BoatEntity) {
                var data = packet instanceof AddEntityPacket spawn ? spawn.getMetadata()
                        : ((SetEntityDataPacket) packet).getMetadata();
                var captured = GeyserBoatMetadata.capture(data);
                if (captured.isEmpty() && !(packet instanceof AddEntityPacket)) {
                    context.write(message, promise);
                    return;
                }
                long metadataTick = packet instanceof SetEntityDataPacket update ? update.getTick() : 0;
                var historicalMetadata = captureReplayUpdate(metadataId, metadataTick, metadataTick != 0, captured.replayable());
                var immediateMetadata = captureReplayUpdate(metadataId, 0, false, captured.immediate());
                int javaId = entity.getEntityId();
                float spawnOffset = entity.getOffset();
                var spawnPosition = packet instanceof AddEntityPacket spawn ? spawn.getPosition() : null;
                var spawnMotion = packet instanceof AddEntityPacket spawn ? spawn.getMotion() : null;
                float spawnYaw = packet instanceof AddEntityPacket spawn ? spawn.getRotation().getY() : 0;
                var coordinates = emission == null ? BedrockCoordinateFrame.IDENTITY : emission.frame();
                var tracked = observedPlayer == null ? null : observedPlayer.compensatedEntities.getTrackedEntity(javaId);
                int spawnTransaction = tracked == null ? 0 : tracked.getLastTransactionHung();
                context.write(message, promise);
                owner.writeLatencyBoundary(context, wrapper, player -> player.latencyUtils.addRealTimeTask(spawnTransaction, () -> {
                    var boat = player.compensatedEntities.getEntity(javaId);
                    if (boat != null && boat.isBoat()) {
                        if (spawnPosition == null && boat.bedrockBoat != null
                                && boat.bedrockBoat.runtimeId() != metadataId) return;
                        boat.bedrockBoat = captured.apply(spawnPosition == null ? boat.bedrockBoat : null, metadataId);
                        if (spawnPosition == null) {
                            if (historicalMetadata != null) historicalMetadata.accept(player);
                            if (immediateMetadata != null) immediateMetadata.accept(player);
                        }
                        if (spawnPosition != null) {
                            var feet = coordinates.toWorld(rawPosition(spawnPosition.down(spawnOffset)));
                            ac.cult.cultac.bedrock.prediction.integration.BedrockVehiclePredictionState.initializeBoat(
                                    boat, new ac.cult.cultac.bedrock.prediction.geometry.Vec3d(feet.x, feet.y, feet.z),
                                    spawnMotion == null ? ac.cult.cultac.bedrock.prediction.geometry.Vec3d.ZERO
                                            : new ac.cult.cultac.bedrock.prediction.geometry.Vec3d(
                                                spawnMotion.getX(), spawnMotion.getY(), spawnMotion.getZ()),
                                    spawnYaw, coordinates);
                        }
                    }
                }));
                return;
            }

            if (packet instanceof SetEntityDataPacket metadata && isPredictedHorse(entity)) {
                var data = metadata.getMetadata();
                Boolean standing = data.get(EntityDataTypes.FLAGS) == null ? null : data.getFlag(EntityFlag.STANDING);
                var replay = captureReplayUpdate(metadataId, metadata.getTick(), metadata.getTick() != 0,
                        new BedrockReplayEvent.HorseMetadata(standing, data.get(EntityDataTypes.WIDTH), data.get(EntityDataTypes.HEIGHT)));
                context.write(message, promise);
                if (replay != null) owner.writeLatencyBoundary(context, wrapper, replay);
                return;
            }
            long selfRuntimeId = owner.connection.getPlayerEntity().geyserId();
            if (replayUpdate != null) {
                var replay = replayUpdate.event() instanceof ac.cult.cultac.bedrock.prediction.integration.BedrockReplayAttributeEvent
                        ? null : captureReplayUpdate(replayUpdate.actorId(), replayUpdate.tick(), replayUpdate.tick() != 0, replayUpdate.event());
                context.write(message, promise);
                // Attribute callbacks are batched after entity creation at this same boundary.
                owner.writeLatencyBoundary(context, wrapper, player -> { if (replay != null) replay.accept(player); });
                return;
            }
            if (packet instanceof SetEntityMotionPacket motion && motion.getMotion() != null && motion.getTick() != 0) {
                var velocity = motion.getMotion();
                var replay = captureReplayUpdate(motion.getRuntimeEntityId(), motion.getTick(), true,
                        new BedrockReplayEvent.Motion(new Vec3d(velocity.getX(), velocity.getY(), velocity.getZ())));
                context.write(message, promise);
                if (replay != null) owner.writeLatencyBoundary(context, wrapper, replay);
                return;
            }
            if (packet instanceof SetEntityDataPacket metadata
                    && metadata.getRuntimeEntityId() == selfRuntimeId) {
                writeSelfMetadata(context, message, promise, metadata);
                return;
            }
            if (packet instanceof SetEntityMotionPacket motion
                    && motion.getRuntimeEntityId() == selfRuntimeId
                    && motion.getMotion() != null) {
                owner.teleportRecovery.motion(motion);
                writeSelfMotion(context, message, promise, motion.getMotion());
                return;
            }
            if (packet instanceof MovePlayerPacket move
                    && move.getRuntimeEntityId() == selfRuntimeId) {
                if (move.getTick() != 0) {
                    var coordinates = emission == null ? BedrockCoordinateFrame.IDENTITY : emission.frame();
                    Vec3 feet = coordinates.toWorld(rawPosition(move.getPosition().down(ownerPlayerOffset())));
                    var replay = captureReplayUpdate(selfRuntimeId, move.getTick(), true,
                            new BedrockReplayEvent.Reposition(new Vec3d(feet.x, feet.y, feet.z),
                                    move.getRotation().getY(), move.getRotation().getX(), move.isOnGround(), coordinates));
                    context.write(message, promise);
                    if (replay != null) owner.writeLatencyBoundary(context, wrapper, replay);
                    return;
                }
                convertSelfCorrectionToTeleport(move);
                Vec3 localTarget = rawPosition(move.getPosition());
                Long revision = owner.registerOutboundGeyserTeleport(move, emission);
                if (revision != null) beginTeleportRecovery(context, wrapper, revision, emission);
                context.write(message, promise);
                if (revision != null && emission != null) {
                    owner.writeLatencyBoundary(context, wrapper,
                            player -> owner.confirmOrigin(player, revision, emission, localTarget));
                }
                return;
            }
            if (packet instanceof MoveEntityAbsolutePacket move
                    && move.getRuntimeEntityId() == selfRuntimeId
                    && move.isTeleported()) {
                Vec3 localTarget = rawPosition(move.getPosition());
                Long revision = owner.registerOutboundGeyserTeleport(move, emission);
                if (revision != null) beginTeleportRecovery(context, wrapper, revision, emission);
                context.write(message, promise);
                if (revision != null && emission != null) {
                    owner.writeLatencyBoundary(context, wrapper,
                            player -> owner.confirmOrigin(player, revision, emission, localTarget));
                }
                return;
            }

            context.write(message, promise);
        }

        private void writeMovementCorrection(ChannelHandlerContext context, Object message, ChannelPromise promise,
                BedrockPacketWrapper wrapper, CorrectPlayerMovePredictionPacket packet, BedrockOriginDispatch.Emission emission) {
            CultPlayer player = owner.currentPlayer();
            var captured = owner.movementCorrectionSources.remove(packet);
            var source = captured == null ? null : captured.correction();
            var vehicle = owner.connection.getPlayerEntity().getVehicle();
            boolean isVehicle = packet.getPredictionType() == PredictionType.VEHICLE;
            if (source != null && (player == null
                    || player.getSetbackTeleportUtil().hasPendingBedrockTransportTeleport()
                    || source.controlGeneration() != player.bedrockState.movementCorrections.generation()
                    || isVehicle && (vehicle == null || vehicle.geyserId() != source.runtimeId())
                    || !isVehicle && vehicle != null)) {
                io.netty.util.ReferenceCountUtil.release(message);
                promise.trySuccess();
                return;
            }
            if (player == null || source == null) {
                Consumer<CultPlayer> replay = null;
                if (player != null && (!isVehicle || vehicle != null)) {
                    var coordinates = emission == null ? BedrockCoordinateFrame.IDENTITY : emission.frame();
                    long actorId = isVehicle ? vehicle.geyserId() : owner.connection.getPlayerEntity().geyserId();
                    Vec3 feet = coordinates.toWorld(rawPosition(packet.getPosition().down(isVehicle ? vehicle.getOffset() : ownerPlayerOffset())));
                    var update = new BedrockMovementCorrection(0, player.bedrockState.movementCorrections.generation(),
                            isVehicle ? vehicle.getEntityId() : -1, actorId, packet.getTick(), feet,
                            rawPosition(packet.getDelta()), 0, 0, packet.isOnGround(), coordinates, -1,
                            packet.getVehicleAngularVelocity(), isVehicle);
                    replay = captureReplayUpdate(actorId, packet.getTick(), true, new BedrockReplayEvent.Transform(update));
                }
                context.write(message, promise);
                if (replay != null) owner.writeLatencyBoundary(context, wrapper, replay);
                return;
            }
            BedrockCoordinateFrame coordinates = emission == null ? source.coordinates() : emission.frame();
            var correction = new BedrockMovementCorrection(++owner.movementCorrectionSequence,
                    source.controlGeneration(), source.vehicleId(), isVehicle ? vehicle.geyserId() : owner.connection.getPlayerEntity().geyserId(), packet.getTick(),
                    coordinates.toWorld(rawPosition(packet.getPosition().down(isVehicle ? vehicle.getOffset() : ownerPlayerOffset()))),
                    rawPosition(packet.getDelta()), source.yaw(), source.pitch(), packet.isOnGround(), coordinates,
                    source.teleportTransaction(), packet.getVehicleAngularVelocity(), isVehicle);
            player.bedrockState.movementCorrections.observe(player, correction);
            ChannelPromise writePromise = promise.unvoid();
            writePromise.addListener(future -> {
                if (future.isSuccess()) BedrockPredictionDebug.reportCorrectionSent(player, correction, captured.claimedPosition(), captured.debugId());
            });
            context.write(message, writePromise);
            owner.writeLatencyBoundary(context, wrapper,
                    acknowledged -> acknowledged.bedrockState.movementCorrections.acknowledge(correction.sequence()));
        }

        private void writeSelfMetadata(ChannelHandlerContext context, Object message, ChannelPromise promise,
                                       SetEntityDataPacket metadata) {
            Float width = metadata.getMetadata().get(EntityDataTypes.WIDTH);
            Float height = metadata.getMetadata().get(EntityDataTypes.HEIGHT);
            Boolean gliding = metadata.getMetadata().get(EntityDataTypes.FLAGS) == null
                    ? null
                    : metadata.getMetadata().getFlag(EntityFlag.GLIDING);
            Boolean crawling = metadata.getMetadata().get(EntityDataTypes.FLAGS) == null
                    ? null
                    : metadata.getMetadata().getFlag(EntityFlag.CRAWLING);
            Boolean swimming = metadata.getMetadata().get(EntityDataTypes.FLAGS) == null
                    ? null
                    : metadata.getMetadata().getFlag(EntityFlag.SWIMMING);
            Boolean sprinting = metadata.getMetadata().get(EntityDataTypes.FLAGS) == null
                    ? null : metadata.getMetadata().getFlag(EntityFlag.SPRINTING);
            Boolean sneaking = metadata.getMetadata().get(EntityDataTypes.FLAGS) == null
                    ? null : metadata.getMetadata().getFlag(EntityFlag.SNEAKING);
            Boolean spinning = metadata.getMetadata().get(EntityDataTypes.FLAGS) == null
                    ? null : metadata.getMetadata().getFlag(EntityFlag.DAMAGE_NEARBY_MOBS);
            Boolean sleeping = GeyserPlayerSleepMetadata.sleeping(metadata.getMetadata());
            Boolean usingItem = metadata.getMetadata().get(EntityDataTypes.FLAGS) == null
                    ? null : metadata.getMetadata().getFlag(EntityFlag.USING_ITEM);

            long metadataTick = metadata.getTick();
            var observed = owner.currentPlayer();
            long generation = observed == null ? -1 : observed.bedrockState.movementCorrections.generation();
            var seat = metadata.getMetadata().get(EntityDataTypes.SEAT_OFFSET);
            var vehicle = owner.connection.getPlayerEntity().getVehicle();
            context.write(message, promise);
            if (seat != null && vehicle instanceof BoatEntity) {
                int javaId = vehicle.getEntityId();
                long runtimeId = vehicle.geyserId();
                var value = new GeyserBoatMetadata(null, null, null, null, null, null, null, null,
                        new ac.cult.cultac.bedrock.prediction.geometry.Vec3d(seat.getX(), seat.getY(), seat.getZ()));
                var replay = captureReplayUpdate(runtimeId, metadataTick, metadataTick != 0, value.replayable());
                owner.writeLatencyBoundary(context, (BedrockPacketWrapper) message, player -> {
                    if (replay != null) replay.accept(player);
                    var boat = player.compensatedEntities.getEntity(javaId);
                    if (boat != null && boat.bedrockBoat != null && boat.bedrockBoat.runtimeId() == runtimeId) {
                        boat.bedrockBoat = value.apply(boat.bedrockBoat, runtimeId);
                    }
                });
            }
            if (width != null || height != null || gliding != null
                    || crawling != null || swimming != null || sneaking != null
                    || spinning != null || sleeping != null || usingItem != null || sprinting != null) {
                owner.recordOutboundMetadata(context, (BedrockPacketWrapper) message,
                        width, height, gliding, crawling, swimming, sneaking, spinning, sleeping, usingItem, sprinting,
                        metadataTick, generation);
            }
        }

        private Consumer<CultPlayer> captureReplayUpdate(long actorId, long tick, boolean historical, BedrockReplayEvent event) {
            CultPlayer player = owner.currentPlayer();
            if (player == null) return null;
            var vehicle = owner.connection.getPlayerEntity().getVehicle();
            boolean self = actorId == owner.connection.getPlayerEntity().geyserId();
            if (!self && (vehicle == null || actorId != vehicle.geyserId())) return null;
            int vehicleId = self ? -1 : vehicle.getEntityId();
            long generation = player.bedrockState.movementCorrections.generation();
            return acknowledged -> acknowledged.bedrockState.movementCorrections.queueUpdate(
                    generation, vehicleId, actorId, tick, historical, event);
        }

        private void writeSelfMotion(ChannelHandlerContext context, Object message, ChannelPromise promise,
                                     Vector3f motion) {
            Vec3 observedMotion = new Vec3(motion.getX(), motion.getY(), motion.getZ());
            CultPlayer player = owner.currentPlayer();
            if (player == null) {
                context.write(message, promise);
                return;
            }
            CultPlayer.BedrockTransaction before = player.getLastClientboundBedrockTransaction();
            CultPlayer.BedrockTransaction receipt = player.createBedrockTransactionAfterClientbound();
            player.checkManager.getKnockbackHandler().handleObservedEntityVelocity(observedMotion,
                    player.entityID, player.getSetbackTeleportUtil().getBedrockTeleportRevision(),
                    before, receipt);
            context.write(message, promise);
            owner.writeTransactionBoundary(context, (BedrockPacketWrapper) message, player, receipt);
        }

        private void beginTeleportRecovery(ChannelHandlerContext context, BedrockPacketWrapper source, long revision,
                                           BedrockOriginDispatch.Emission emission) {
            CultPlayer player = owner.currentPlayer();
            if (player == null) return;
            int sender = source.getSenderSubClientId();
            int target = source.getTargetSubClientId();
            Consumer<BedrockPacket> writer = packet -> {
                var wrapper = BedrockPacketWrapper.create(0, sender, target, packet, null);
                var promise = context.newPromise();
                promise.addListener(future -> {
                    if (!future.isSuccess()) owner.connection.disconnect("CultAC: teleport recovery write failed");
                });
                context.write(wrapper, promise);
            };
            if (!player.getSetbackTeleportUtil().isBedrockSetbackTransport(revision)) {
                if (!player.getSetbackTeleportUtil().isPendingSetback()) owner.teleportRecovery.clear();
                if (player.bedrockState.lastMovementTick() > 0) {
                    writer.accept(GeyserTeleportRecovery.reset(source.getPacket(), player.bedrockState.lastMovementTick()));
                }
                return;
            }
            var required = player.getSetbackTeleportUtil().getRequiredSetBack();
            owner.teleportRecovery.begin(source.getPacket(), emission.operation(), emission.frame(),
                    player.bedrockState.lastMovementTick(), writer,
                    motion -> sendPlayerTeleport(owner, required.getTeleportData().getLocation(),
                            required.getXRot(), required.getYRot(), required.isExpectedOnGround(), emission.operation(), motion));
        }

    }

    private static BedrockAuthInputFrame createAuthInputFrame(
            UUID uuid,
            int protocolVersion,
            PlayerAuthInputPacket packet,
            Vec3 position,
            Vec3 delta,
            Integer predictedVehicleJavaId
    ) {
        Set<PlayerAuthInputData> inputData = packet.getInputData();
        BedrockMoveVector moveVector = resolveMoveVector(packet);


        return BedrockAuthInputFrame.builder(uuid)
                .protocolVersion(protocolVersion)
                .clientTick(packet.getTick())
                .inputMode(packet.getInputMode() == null ? -1 : packet.getInputMode().ordinal())
                .playMode(packet.getPlayMode() == null ? -1 : packet.getPlayMode().ordinal())
                .interactionModel(packet.getInputInteractionModel() == null ? -1 : packet.getInputInteractionModel().ordinal())
                .position(position)
                .packetPosition(rawPosition(packet.getPosition()))
                .delta(delta)
                .reportedEndOfTickVelocity(toVec3(packet.getDelta()))
                .predictedVehicleId(predictedVehicleId(packet))
                .predictedVehicleJavaId(predictedVehicleJavaId)
                .vehicleRotation(packet.getVehicleRotation() == null ? null
                        : new BedrockAuthInputFrame.VehicleRotation(packet.getVehicleRotation().getY(), packet.getVehicleRotation().getX()))
                .rotation(packet.getRotation().getY(), packet.getRotation().getX(), packet.getRotation().getY())
                .moveVector(moveVector.x(), moveVector.z())
                .rawInputFlags(rawInputFlags(inputData))
                .rawInputFlagsHigh(rawInputFlagsHigh(inputData))
                .jumping(hasAnyInput(inputData, PlayerAuthInputData.JUMP_CURRENT_RAW, PlayerAuthInputData.JUMP_DOWN, PlayerAuthInputData.JUMPING, PlayerAuthInputData.START_JUMPING, PlayerAuthInputData.AUTO_JUMPING_IN_WATER))
                .jumpStarted(inputData.contains(PlayerAuthInputData.START_JUMPING))
                .jumpPressedRaw(inputData.contains(PlayerAuthInputData.JUMP_PRESSED_RAW))
                .jumpCurrentRaw(inputData.contains(PlayerAuthInputData.JUMP_CURRENT_RAW))
                .wantUp(inputData.contains(PlayerAuthInputData.WANT_UP))
                .sneaking(hasAnyInput(inputData, PlayerAuthInputData.SNEAK_CURRENT_RAW, PlayerAuthInputData.SNEAK_DOWN, PlayerAuthInputData.SNEAKING, PlayerAuthInputData.START_SNEAKING, PlayerAuthInputData.DESCEND, PlayerAuthInputData.SNEAK_TOGGLE_DOWN))
                .startSneaking(hasAnyInput(inputData, PlayerAuthInputData.START_SNEAKING, PlayerAuthInputData.SNEAK_PRESSED_RAW, PlayerAuthInputData.SNEAKING, PlayerAuthInputData.SNEAK_DOWN, PlayerAuthInputData.SNEAK_CURRENT_RAW))
                .stopSneaking(inputData.contains(PlayerAuthInputData.STOP_SNEAKING))
                .sprinting(inputData.contains(PlayerAuthInputData.SPRINTING))
                .startSwimming(inputData.contains(PlayerAuthInputData.START_SWIMMING))
                .stopSwimming(inputData.contains(PlayerAuthInputData.STOP_SWIMMING))
                .startCrawling(inputData.contains(PlayerAuthInputData.START_CRAWLING))
                .stopCrawling(inputData.contains(PlayerAuthInputData.STOP_CRAWLING))
                .startGliding(inputData.contains(PlayerAuthInputData.START_GLIDING))
                .stopGliding(inputData.contains(PlayerAuthInputData.STOP_GLIDING))
                .usingItem(hasAnyInput(inputData, PlayerAuthInputData.PERFORM_ITEM_INTERACTION, PlayerAuthInputData.PERFORM_ITEM_STACK_REQUEST, PlayerAuthInputData.START_USING_ITEM))
                .blockAction(inputData.contains(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS))
                .authorityMode("client-auth-input")
                .build();
    }

    static boolean isPredictedHorse(org.geysermc.geyser.entity.type.Entity entity) {
        // donkey and mule use ChestedHorseEntity, as do excluded llamas.
        return entity instanceof org.geysermc.geyser.entity.type.living.animal.horse.HorseEntity
                || entity instanceof org.geysermc.geyser.entity.type.living.animal.horse.SkeletonHorseEntity
                || entity instanceof org.geysermc.geyser.entity.type.living.animal.horse.ZombieHorseEntity
                || entity instanceof org.geysermc.geyser.entity.type.living.animal.horse.ChestedHorseEntity
                    && !(entity instanceof org.geysermc.geyser.entity.type.living.animal.horse.LlamaEntity);
    }

    static long predictedVehicleId(PlayerAuthInputPacket packet) {
        // The codec leaves this field at zero when the optional vehicle section is absent.
        return packet.getInputData().contains(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE)
                ? packet.getPredictedVehicle() : -1L;
    }

    static BedrockMoveVector resolveMoveVector(PlayerAuthInputPacket packet) {
        Vector2f vector = packet.getMotion();
        if (vector != null && (Math.abs(vector.getX()) > 1.0E-6F || Math.abs(vector.getY()) > 1.0E-6F)) {
            return new BedrockMoveVector(vector.getX(), vector.getY());
        }
        return BedrockMoveVector.ZERO;
    }

    private static BedrockMoveFrame createMoveFrame(UUID uuid, int protocolVersion, MovePlayerPacket packet) {
        return new BedrockMoveFrame(
                uuid,
                new BedrockProtocolVersion(protocolVersion),
                packet.getTick(),
                toJavaPosition(packet.getPosition()),
                packet.getRotation().getY(),
                packet.getRotation().getX(),
                packet.getRotation().getY(),
                rawPosition(packet.getPosition()), BedrockCoordinateFrame.IDENTITY, true
        );
    }

    static Vec3 toJavaPosition(Vector3f vector) {
        if (vector == null) {
            return null;
        }

        Vec3d physicalFeet = BedrockPositionTranslator.packetPositionToPhysicalFeet(new Vec3d(
                Double.parseDouble(Float.toString(vector.getX())),
                Double.parseDouble(Float.toString(vector.getY())),
                Double.parseDouble(Float.toString(vector.getZ()))
        ));
        return new Vec3(physicalFeet.x(), physicalFeet.y(), physicalFeet.z());
    }

    private static Vec3 rawPosition(Vector3f vector) {
        return vector == null ? null : new Vec3(vector.getX(), vector.getY(), vector.getZ());
    }

    private static Vec3 toVec3(Vector3f vector) {
        if (vector == null) {
            return null;
        }

        return new Vec3(
                Double.parseDouble(Float.toString(vector.getX())),
                Double.parseDouble(Float.toString(vector.getY())),
                Double.parseDouble(Float.toString(vector.getZ()))
        );
    }

    private static Vector3f toVector3f(Vec3 vector) {
        if (vector == null) {
            return null;
        }
        return Vector3f.from((float) vector.x, (float) vector.y, (float) vector.z);
    }

    static boolean sameJavaConnection(
            io.netty.channel.Channel geyserDownstreamChannel,
            Object userChannel
    ) {
        return geyserDownstreamChannel != null
                && userChannel instanceof io.netty.channel.Channel javaServerChannel
                && geyserDownstreamChannel.localAddress() != null
                && javaServerChannel.unsafe() != null
                && java.util.Objects.equals(
                        geyserDownstreamChannel.localAddress(),
                        // Geyser's ChannelWrapper.remoteAddress() is the spoofed Bedrock IP.
                        // Unsafe delegates to its underlying LocalChannel and identifies the peer.
                        javaServerChannel.unsafe().remoteAddress());
    }

    private static boolean hasAnyInput(Set<PlayerAuthInputData> inputData, PlayerAuthInputData... inputs) {
        for (PlayerAuthInputData input : inputs) {
            if (inputData.contains(input)) {
                return true;
            }
        }
        return false;
    }

    private static long rawInputFlags(Set<PlayerAuthInputData> inputData) {
        long flags = 0L;
        for (PlayerAuthInputData input : inputData) {
            if (input.ordinal() < Long.SIZE) {
                flags |= 1L << input.ordinal();
            }
        }
        return flags;
    }

    private static long rawInputFlagsHigh(Set<PlayerAuthInputData> inputData) {
        long flags = 0L;
        for (PlayerAuthInputData input : inputData) {
            int ordinal = input.ordinal();
            if (ordinal >= Long.SIZE && ordinal < Long.SIZE * 2) {
                flags |= 1L << (ordinal - Long.SIZE);
            }
        }
        return flags;
    }

}
