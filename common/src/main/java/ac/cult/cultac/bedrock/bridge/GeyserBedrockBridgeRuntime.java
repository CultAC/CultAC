package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.logging.BedrockPacketLogger;
import ac.cult.cultac.bedrock.logging.BedrockPacketLogOutboundHandler;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockPositionTranslator;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.integration.BedrockMovementCorrections;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
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
import java.util.function.Supplier;
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
import org.geysermc.geyser.entity.type.living.animal.horse.HorseEntity;
import org.geysermc.geyser.entity.type.BoatEntity;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionInitializeEvent;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostReloadEvent;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;

public final class GeyserBedrockBridgeRuntime {
    private static final Map<GeyserConnection, GeyserFloatingPointsAdapter> GFP_ADAPTERS = new ConcurrentHashMap<>();
    private static final Map<GeyserConnection, PacketTapHandler> PACKET_TAPS = new ConcurrentHashMap<>();

    private static volatile GeyserEventSubscriptions eventSubscriptions;
    private static volatile boolean started;
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

        subscriptions.subscribe(GeyserPostReloadEvent.class, event -> {
            BedrockPacketLogger logger = packetLogger;
            if (logger != null) logger.stopAll("Geyser reload");
            GeyserUtil.forceForwardPlayerPing();
            refreshCollisionMappings();
            for (GeyserConnection connection : PACKET_TAPS.keySet()) {
                if (connection instanceof GeyserSession session) session.executeInEventLoop(() -> installFloatingPoints(session, true));
            }
        });
        subscriptions.subscribe(SessionInitializeEvent.class, event -> {
            installPacketTap(event.connection());
            BedrockPacketLogger logger = packetLogger;
            if (logger != null && event.connection() instanceof GeyserSession session) {
                logger.onInitialize(session, session.bedrockUsername(), session.protocolVersion());
            }
        });
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
            BedrockPacketLogger logger = packetLogger;
            if (logger != null) logger.identity(event.connection(), event.connection().javaUuid());
        });
        subscriptions.subscribe(SessionDisconnectEvent.class, GeyserBedrockBridgeRuntime::onSessionDisconnect);

        LogUtil.info("Geyser Bedrock movement bridge enabled.");
    }

    public static synchronized void stop() {
        started = false;
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
            if (installedTap.inputs.ready()) {
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
        if (!GeyserFloatingPointsAdapter.present()) return true;
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
        return () -> session.ensureInEventLoop(() -> session.sendDownstreamPacket(new ServerboundPongPacket(id)));
    }

    private static final class PacketTapHandler implements BedrockPacketHandler {
        private final GeyserSession connection;
        private final BedrockSession bedrockSession;
        private final BedrockPacketHandler delegate;
        private final GeyserQueue latencyQueue;
        private final AtomicBoolean detached = new AtomicBoolean();
        private final String outboundHandlerName;
        private final String packetLogHandlerName;
        private final GeyserInputQueue inputs;
        private final GeyserEntityPositions entityPositions = new GeyserEntityPositions();
        private long movementCorrectionSequence;
        private final Map<CorrectPlayerMovePredictionPacket, BedrockMovementCorrection> movementCorrectionSources = new IdentityHashMap<>();
        private Long actorCreationRuntimeId;

        private PacketTapHandler(GeyserSession connection, BedrockSession bedrockSession, BedrockPacketHandler delegate, GeyserQueue latencyQueue) {
            this.connection = connection;
            this.bedrockSession = bedrockSession;
            this.delegate = delegate;
            this.latencyQueue = latencyQueue;
            this.outboundHandlerName = "cultac-outbound-packet-" + Integer.toHexString(System.identityHashCode(this));
            this.packetLogHandlerName = outboundHandlerName + "-log";
            this.inputs = new GeyserInputQueue(connection, () -> {
                CultPlayer player = currentPlayer();
                return player == null ? null : player.user;
            }, this::translateInput);
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
                    } else inputs.offer(packet);
                } finally {
                    io.netty.util.ReferenceCountUtil.release(packet);
                }
            });
            return PacketSignal.HANDLED;
        }

        private void translateInput(BedrockPacket packet) {
            CultPlayer player = currentPlayer();
            if (player == null || detached.get()) return;
            if (packet instanceof PlayerAuthInputPacket authInput) {
                if (!processAuthInput(authInput)) return;
            } else if (packet instanceof MovePlayerPacket move) {
                submitMove(move);
            } else if (packet instanceof InventoryTransactionPacket transaction) {
                submitInventoryTransaction(transaction);
            } else if (packet instanceof PlayerActionPacket action) {
                submitPlayerAction(action);
            }
            delegate.handlePacket(packet);
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
            connection.ensureInEventLoop(() -> { inputs.close(); entityPositions.clear(); });
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
                Boolean sneaking, Boolean spinning, Boolean sleeping, Boolean usingItem, Boolean sprinting
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
                    // Apply at the acknowledged wire boundary before the next input.
                    player.checkManager.getSimulationProcessor().applyAcknowledgedBedrockMetadata(
                            width, height, gliding, crawling, swimming, sneaking, spinning, sleeping, usingItem, sprinting);
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
                    emission.provenance(), emission.javaTeleportId(), proof);
            BedrockPacketLogger logger = packetLogger;
            if (logger != null) logger.origin(connection, revision, emission.frame(), localTarget,
                    emission.provenance(), emission.javaTeleportId(), proof);
            return revision;
        }

        private void confirmOrigin(CultPlayer player, long revision) {
            player.getSetbackTeleportUtil().confirmBedrockOrigin(revision);
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
                context.write(BedrockPacketWrapper.create(0, source.getSenderSubClientId(),
                        source.getTargetSubClientId(), marker, null), context.voidPromise());
            });
        }

        private boolean processAuthInput(PlayerAuthInputPacket packet) {
            CultPlayer player = currentPlayer();
            if (player == null || !installFloatingPoints(connection, true)) return false;
            if (actorCreationRuntimeId != null) {
                player.checkManager.getSimulationProcessor().handleBedrockActorCreation(actorCreationRuntimeId);
                actorCreationRuntimeId = null;
            }
            BedrockAuthInputFrame frame = captureAuthInput(packet);
            var state = ac.cult.cultac.bedrock.prediction.integration.BedrockFrameProcessor.process(player, frame,
                    ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger.BEDROCK_THREAD);
            player.packetStateData.clearBedrockTranslatedMovementPermit();
            if (state == null) return false;
            var adapter = GFP_ADAPTERS.get(connection);
            var coordinates = adapter == null ? BedrockCoordinateFrame.IDENTITY : adapter.coordinateFrame();
            var vehicle = state.isVehicle() ? connection.getPlayerEntity().getVehicle() : null;
            if (state.isVehicle() && (vehicle == null || frame.getPredictedVehicleJavaId() == null
                    || vehicle.getEntityId() != frame.getPredictedVehicleJavaId())) return false;
            Vec3 position = ac.cult.cultac.bedrock.prediction.integration.BedrockVectorAdapter.toJava(state.physicalFeetPosition());
            packet.setPosition(toVector3f(coordinates.toLocal(position)).up(vehicle == null ? ownerPlayerOffset() : vehicle.getOffset()));
            packet.setDelta(toVector3f(ac.cult.cultac.bedrock.prediction.integration.BedrockVectorAdapter.toJava(state.velocity())));
            setInputFlag(packet, PlayerAuthInputData.HORIZONTAL_COLLISION, state.collisionFlags().horizontalCollision());
            setInputFlag(packet, PlayerAuthInputData.VERTICAL_COLLISION, state.collisionFlags().verticalCollision());
            if (state.isBoat()) packet.setVehicleRotation(Vector2f.from(state.inputFrame().pitch(), state.inputFrame().yaw()));
            if (vehicle == null) {
                player.packetStateData.grantBedrockTranslatedMovementPermit(frame.getClientTick(),
                        projectedOnGround(connection, packet), state.movementGrounded());
            } else {
                // Compare against precisely the float-to-double conversion Geyser will forward.
                Vector3f projected = packet.getPosition().down(vehicle.getOffset());
                player.packetStateData.grantBedrockVehicleMovementPermit(frame.getClientTick(), vehicle.getEntityId(),
                        coordinates.toWorld(rawPosition(projected)), state.movementGrounded());
            }
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

        private BedrockAuthInputFrame captureAuthInput(PlayerAuthInputPacket packet) {
            UUID uuid = connection.javaUuid();

            long vehicleId = predictedVehicleId(packet);
            var vehicle = vehicleId == -1L ? null : connection.getEntityCache().getEntityByGeyserId(vehicleId);
            if (vehicle instanceof HorseEntity || vehicle instanceof BoatEntity) {
                currentPlayer().bedrockState.movementCorrections.beginFrame(currentPlayer(), packet.getTick(), vehicle.getEntityId(), vehicleId);
            }
            Vec3 position = vehicleId == -1L ? toJavaPosition(packet.getPosition()) : rawPosition(
                    vehicle instanceof BoatEntity ? packet.getPosition().down(vehicle.getOffset()) : packet.getPosition());
            // HANDLE_TELEPORT is a correction-only tick. This diagnostic displacement must not
            // include the change of local origin; authorization still requires the transport echo.
            var previous = currentPlayer().bedrockState.getLastOfferedFrame();
            Vec3 delta = previous == null || !java.util.Objects.equals(previous.getPredictedVehicleId(), vehicleId)
                    || packet.getInputData().contains(PlayerAuthInputData.HANDLE_TELEPORT)
                    ? Vec3.ZERO : position.subtract(previous.getCoordinateFrame().toLocal(previous.getPosition()));
            BedrockAuthInputFrame frame = createAuthInputFrame(
                    uuid,
                    connection.protocolVersion(),
                    packet,
                    position,
                    delta,
                    projectedOnGround(connection, packet),
                    vehicle == null ? null : vehicle.getEntityId());
            return frame;
        }

        private void submitMove(MovePlayerPacket packet) {
            if (detached.get() || !installFloatingPoints(connection, true)) return;
            UUID uuid = connection.javaUuid();
            if (!isCurrentConnection()) {
                return;
            }

            CultPlayer player = currentPlayer();
            player.bedrockState.setLastMoveFrame(player.getSetbackTeleportUtil().resolveBedrockCoordinates(
                    createMoveFrame(uuid, connection.protocolVersion(), packet)));
        }

        private void submitInventoryTransaction(InventoryTransactionPacket packet) {
            if (packet.getTransactionType() != InventoryTransactionType.ITEM_RELEASE || packet.getActionType() != 0) {
                return;
            }
            UUID uuid = connection.javaUuid();
            if (uuid == null) {
                return;
            }

            submitClientAction(uuid, BedrockClientAction.ITEM_RELEASE);
        }

        private void submitPlayerAction(PlayerActionPacket packet) {
            UUID uuid = connection.javaUuid();
            if (uuid == null) {
                return;
            }

            PlayerActionType action = packet.getAction();
            if (action == PlayerActionType.START_SPIN_ATTACK) {
                submitClientAction(uuid, BedrockClientAction.START_SPIN_ATTACK);
            } else if (action == PlayerActionType.STOP_SPIN_ATTACK) {
                submitClientAction(uuid, BedrockClientAction.STOP_SPIN_ATTACK);
            } else if (action == PlayerActionType.START_GLIDE) {
                submitClientAction(uuid, BedrockClientAction.START_GLIDING);
            } else if (action == PlayerActionType.STOP_GLIDE) {
                submitClientAction(uuid, BedrockClientAction.STOP_GLIDING);
            } else if (action == PlayerActionType.START_SNEAK) {
                submitClientAction(uuid, BedrockClientAction.START_SNEAKING);
            } else if (action == PlayerActionType.STOP_SNEAK) {
                submitClientAction(uuid, BedrockClientAction.STOP_SNEAKING);
            } else if (action == PlayerActionType.START_SWIMMING) {
                submitClientAction(uuid, BedrockClientAction.START_SWIMMING);
            } else if (action == PlayerActionType.STOP_SWIMMING) {
                submitClientAction(uuid, BedrockClientAction.STOP_SWIMMING);
            } else if (action == PlayerActionType.START_CRAWLING) {
                submitClientAction(uuid, BedrockClientAction.START_CRAWLING);
            } else if (action == PlayerActionType.STOP_CRAWLING) {
                submitClientAction(uuid, BedrockClientAction.STOP_CRAWLING);
            }
        }

        private void submitClientAction(UUID uuid, BedrockClientAction action) {
            if (isCurrentConnection()) {
                currentPlayer().bedrockState.recordClientAction(action);
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

    public static boolean finishTranslation(User user, String channel, Supplier<byte[]> payload) {
        if (!GeyserInputQueue.CHANNEL.equals(channel)) return false;
        PacketTapHandler tap = packetTapForUser(user);
        if (tap != null) tap.inputs.complete(user, payload.get());
        return true;
    }

    private static float ownerPlayerOffset() {
        return (float) BedrockPositionTranslator.PLAYER_PACKET_Y_OFFSET;
    }

    public static boolean sendMovementCorrection(User user, BedrockMovementCorrection correction) {
        PacketTapHandler tap = packetTapForUser(user);
        if (tap == null) return false;
        if (!tap.connection.getTickEventLoop().inEventLoop()) throw new IllegalStateException("Correction outside movement loop");
        {
            CultPlayer player = tap.currentPlayer();
            var vehicle = tap.connection.getPlayerEntity().getVehicle();
            if (player == null || !tap.matchesJavaUser(user)
                    || player.bedrockState.movementCorrections.generation() != correction.controlGeneration()
                    || correction.vehicle() && (!(vehicle instanceof HorseEntity || vehicle instanceof BoatEntity)
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
            tap.movementCorrectionSources.put(packet, new BedrockMovementCorrection(correction.sequence(), correction.controlGeneration(),
                    correction.vehicleId(), correction.runtimeId(), correction.tick(), correction.position(), correction.velocity(),
                    correction.yaw(), correction.pitch(), correction.onGround(), coordinates, correction.teleportTransaction(),
                    correction.angularVelocity(), correction.vehicle()));
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

            if (owner.currentPlayer() != null && !owner.inputs.ready()) {
                owner.inputs.deferWrite(() -> {
                    try { write(context, message, promise); }
                    catch (Exception failure) {
                        io.netty.util.ReferenceCountUtil.release(message);
                        promise.tryFailure(failure);
                        owner.connection.disconnect("CultAC: Bedrock initialization failed");
                    }
                }, () -> {
                    io.netty.util.ReferenceCountUtil.release(message);
                    promise.tryFailure(new java.nio.channels.ClosedChannelException());
                });
                return;
            }
            BedrockPacket packet = wrapper.getPacket();
            if (packet instanceof StartGamePacket startGame) {
                // Newer protocols omit the movement mode but still transmit the history size.
                startGame.setAuthoritativeMovementMode(AuthoritativeMovementMode.SERVER_WITH_REWIND);
                startGame.setRewindHistorySize(BedrockMovementCorrections.CLIENT_HISTORY_SIZE);
                owner.actorCreationRuntimeId = startGame.getRuntimeEntityId();
            }
            GeyserFloatingPointsAdapter adapter = GFP_ADAPTERS.get(owner.connection);
            BedrockOriginDispatch.Emission emission = adapter == null ? null : adapter.takeEmission(packet);
            CultPlayer observedPlayer = owner.currentPlayer();
            if (adapter != null && emission == null && GeyserEntityPositions.hasPosition(packet)) {
                io.netty.util.ReferenceCountUtil.release(message);
                promise.tryFailure(new IllegalStateException("Missing entity coordinate origin"));
                owner.connection.disconnect("CultAC: entity update bypassed the coordinate hook. Please reconnect.");
                return;
            }
            if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket) owner.entityPositions.clear();
            if (observedPlayer != null) owner.entityPositions.capture(owner.connection, observedPlayer, packet,
                    emission == null ? BedrockCoordinateFrame.IDENTITY : emission.frame());
            if (packet instanceof CorrectPlayerMovePredictionPacket correction) {
                writeMovementCorrection(context, message, promise, wrapper, correction, emission);
                return;
            }
            if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.MovementEffectPacket effect
                    && effect.getEntityRuntimeId() == owner.connection.getPlayerEntity().geyserId()
                    && effect.getEffectType() == org.cloudburstmc.protocol.bedrock.data.MovementEffectType.GLIDE_BOOST) {
                int duration = effect.getDuration();
                context.write(message, promise);
                owner.writeLatencyBoundary(context, wrapper,
                        player -> player.bedrockState.movementEffects.setGlideBoost(duration));
                return;
            }
            if (packet instanceof NetworkStackLatencyPacket latencyPacket && latencyPacket.isFromServer()) {
                CultPlayer player = owner.currentPlayer();
                owner.latencyQueue.write(() -> {
                    if (player != null) {
                        player.markBedrockTransactionClientbound(latencyPacket.getTimestamp());
                        var boundary = player.getLastClientboundBedrockTransaction();
                        if (boundary != null && boundary.id() == latencyPacket.getTimestamp())
                            owner.entityPositions.boundary(player, boundary);
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

            long selfRuntimeId = owner.connection.getPlayerEntity().geyserId();
            if (packet instanceof SetEntityDataPacket metadata
                    && metadata.getRuntimeEntityId() == selfRuntimeId) {
                writeSelfMetadata(context, message, promise, metadata);
                return;
            }
            if (packet instanceof SetEntityMotionPacket motion
                    && motion.getRuntimeEntityId() == selfRuntimeId
                    && motion.getMotion() != null) {
                writeSelfMotion(context, message, promise, motion.getMotion());
                return;
            }
            if (packet instanceof MovePlayerPacket move
                    && move.getRuntimeEntityId() == selfRuntimeId) {
                convertSelfCorrectionToTeleport(move);
                Long revision = owner.registerOutboundGeyserTeleport(move, emission);
                context.write(message, promise);
                if (revision != null && emission != null) {
                    owner.writeLatencyBoundary(context, wrapper,
                            player -> owner.confirmOrigin(player, revision));
                }
                return;
            }
            if (packet instanceof MoveEntityAbsolutePacket move
                    && move.getRuntimeEntityId() == selfRuntimeId
                    && move.isTeleported()) {
                Long revision = owner.registerOutboundGeyserTeleport(move, emission);
                context.write(message, promise);
                if (revision != null && emission != null) {
                    owner.writeLatencyBoundary(context, wrapper,
                            player -> owner.confirmOrigin(player, revision));
                }
                return;
            }

            context.write(message, promise);
        }

        private void writeMovementCorrection(ChannelHandlerContext context, Object message, ChannelPromise promise,
                BedrockPacketWrapper wrapper, CorrectPlayerMovePredictionPacket packet, BedrockOriginDispatch.Emission emission) {
            CultPlayer player = owner.currentPlayer();
            var source = owner.movementCorrectionSources.remove(packet);
            var vehicle = owner.connection.getPlayerEntity().getVehicle();
            boolean isVehicle = packet.getPredictionType() == PredictionType.VEHICLE;
            if (source != null && (player == null
                    || source.controlGeneration() != player.bedrockState.movementCorrections.generation()
                    || isVehicle && (vehicle == null || vehicle.geyserId() != source.runtimeId())
                    || !isVehicle && vehicle != null)) {
                io.netty.util.ReferenceCountUtil.release(message);
                promise.trySuccess();
                return;
            }
            if (player == null || source == null) {
                context.write(message, promise);
                return;
            }
            BedrockCoordinateFrame coordinates = emission == null ? source.coordinates() : emission.frame();
            var correction = new BedrockMovementCorrection(++owner.movementCorrectionSequence,
                    source.controlGeneration(), source.vehicleId(), isVehicle ? vehicle.geyserId() : owner.connection.getPlayerEntity().geyserId(), packet.getTick(),
                    coordinates.toWorld(rawPosition(packet.getPosition().down(isVehicle ? vehicle.getOffset() : ownerPlayerOffset()))),
                    rawPosition(packet.getDelta()), source.yaw(), source.pitch(), packet.isOnGround(), coordinates,
                    source.teleportTransaction(), packet.getVehicleAngularVelocity(), isVehicle);
            player.bedrockState.movementCorrections.observe(player, correction);
            context.write(message, promise);
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
            Boolean sleeping = metadata.getMetadata().get(EntityDataTypes.FLAGS) == null
                    ? null : metadata.getMetadata().getFlag(EntityFlag.SLEEPING);
            Boolean usingItem = metadata.getMetadata().get(EntityDataTypes.FLAGS) == null
                    ? null : metadata.getMetadata().getFlag(EntityFlag.USING_ITEM);

            var seat = metadata.getMetadata().get(EntityDataTypes.SEAT_OFFSET);
            var vehicle = owner.connection.getPlayerEntity().getVehicle();
            context.write(message, promise);
            if (seat != null && vehicle instanceof BoatEntity) {
                int javaId = vehicle.getEntityId();
                long runtimeId = vehicle.geyserId();
                var value = new GeyserBoatMetadata(null, null, null, null, null, null, null, null,
                        new ac.cult.cultac.bedrock.prediction.geometry.Vec3d(seat.getX(), seat.getY(), seat.getZ()));
                owner.writeLatencyBoundary(context, (BedrockPacketWrapper) message, player -> {
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
                        width, height, gliding, crawling, swimming, sneaking, spinning, sleeping, usingItem, sprinting);
            }
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

    }

    private static BedrockAuthInputFrame createAuthInputFrame(
            UUID uuid,
            int protocolVersion,
            PlayerAuthInputPacket packet,
            Vec3 position,
            Vec3 delta,
            boolean projectedOnGround,
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
                .projectedOnGround(projectedOnGround)
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

    static long predictedVehicleId(PlayerAuthInputPacket packet) {
        // The codec leaves this field at zero when the optional vehicle section is absent.
        return packet.getInputData().contains(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE)
                ? packet.getPredictedVehicle() : -1L;
    }

    private static boolean projectedOnGround(GeyserSession session, PlayerAuthInputPacket packet) {
        var entity = session.getPlayerEntity();
        Set<PlayerAuthInputData> inputData = packet.getInputData();
        boolean wasOnGround = entity.isOnGround();
        boolean startJumping = inputData.contains(PlayerAuthInputData.START_JUMPING);
        return projectedOnGround(
                inputData.contains(PlayerAuthInputData.VERTICAL_COLLISION),
                entity.getVehicle() != null,
                session.isNoClip(),
                wasOnGround,
                startJumping,
                entity.isOnClimbableBlock(),
                inputData.contains(PlayerAuthInputData.JUMPING),
                entity.getLastTickEndVelocity().getY(),
                wasOnGround && startJumping ? entity.getJumpVelocity() : 0.0F);
    }

    static boolean projectedOnGround(
            boolean verticalCollision,
            boolean hasVehicle,
            boolean noClip,
            boolean wasOnGround,
            boolean startJumping,
            boolean onClimbableBlock,
            boolean jumping,
            float lastTickEndVelocityY,
            float jumpVelocity
    ) {

        float adjustedVelocityY = lastTickEndVelocityY;
        if (wasOnGround && startJumping) {
            adjustedVelocityY = Math.max(adjustedVelocityY, jumpVelocity);
        }
        if (onClimbableBlock && jumping) {
            adjustedVelocityY = 0.2F;
        }
        return !hasVehicle && !noClip && verticalCollision && adjustedVelocityY < 0.0F;
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

    private static boolean isFinite(Vec3 vector) {
        return vector != null
                && Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
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
