package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.logging.BedrockPacketLogger;
import ac.cult.cultac.bedrock.logging.BedrockPacketLogOutboundHandler;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockPositionTranslator;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputPluginMessage;
import ac.cult.cultac.bedrock.protocol.BedrockClientAction;
import ac.cult.cultac.bedrock.protocol.BedrockMoveFrame;
import ac.cult.cultac.bedrock.protocol.BedrockMoveVector;
import ac.cult.cultac.bedrock.protocol.BedrockProtocolVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.collisions.BedrockClientBlockShapeMappings;
import ac.cult.cultac.utils.floodgate.GeyserUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import java.nio.charset.StandardCharsets;
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
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovementPredictionSyncPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionInitializeEvent;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostReloadEvent;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;

public final class GeyserBedrockBridgeRuntime {
    private static final Map<GeyserConnection, PacketTapHandler> PACKET_TAPS = new ConcurrentHashMap<>();

    private static volatile EventRegistrar eventOwner;
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
        eventOwner = owner;

        GeyserApi.api().eventBus().subscribe(owner, GeyserPostReloadEvent.class, event -> {
            BedrockPacketLogger logger = packetLogger;
            if (logger != null) logger.stopAll("Geyser reload");
            GeyserUtil.forceForwardPlayerPing();
            refreshCollisionMappings();
        });
        GeyserApi.api().eventBus().subscribe(owner, SessionInitializeEvent.class, event -> {
            installPacketTap(event.connection());
            BedrockPacketLogger logger = packetLogger;
            if (logger != null && event.connection() instanceof GeyserSession session) {
                logger.onInitialize(session, session.bedrockUsername(), session.protocolVersion());
            }
        });
        GeyserApi.api().eventBus().subscribe(owner, SessionLoginEvent.class, event -> {
            if (event.connection() instanceof GeyserSession session) {
                session.executeInEventLoop(() -> installPacketTap(session));
            }
        });
        GeyserApi.api().eventBus().subscribe(owner, SessionJoinEvent.class, event -> {
            installPacketTap(event.connection());
            BedrockPacketLogger logger = packetLogger;
            if (logger != null) logger.identity(event.connection(), event.connection().javaUuid());
        });
        GeyserApi.api().eventBus().subscribe(owner, SessionDisconnectEvent.class, GeyserBedrockBridgeRuntime::onSessionDisconnect);

        LogUtil.info("Geyser Bedrock movement bridge enabled.");
    }

    public static synchronized void stop() {
        BedrockPacketLogger logger = packetLogger;
        packetLogger = null;
        if (logger != null) logger.close();
        EventRegistrar owner = eventOwner;
        if (owner != null && GeyserUtil.isGeyserAvailable()) {
            GeyserApi.api().eventBus().unregisterAll(owner);
            eventOwner = null;
        }

        for (PacketTapHandler tap : Set.copyOf(PACKET_TAPS.values())) {
            tap.detach();
        }
        PACKET_TAPS.clear();
        BedrockClientBlockShapeMappings.clear();
        started = false;
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
            installedTap.detach();
        }

        if (currentHandler instanceof PacketTapHandler tap) {
            PACKET_TAPS.put(connection, tap);
            tap.attachOutboundTap();
            return;
        }

        PacketTapHandler tap = new PacketTapHandler(session, bedrockSession, currentHandler);
        bedrockSession.setPacketHandler(tap);
        PACKET_TAPS.put(connection, tap);
        tap.attachOutboundTap();
    }

    private static final class PacketTapHandler implements BedrockPacketHandler {
        private final GeyserSession connection;
        private final BedrockSession bedrockSession;
        private final BedrockPacketHandler delegate;
        private final AtomicBoolean detached = new AtomicBoolean();
        private final String outboundHandlerName;
        private final String packetLogHandlerName;
        private Vec3 lastAuthInputPosition;
        private Vector3f latestTrustedMotion;
        private float lastWrittenDeltaY;

        private PacketTapHandler(GeyserSession connection, BedrockSession bedrockSession, BedrockPacketHandler delegate) {
            this.connection = connection;
            this.bedrockSession = bedrockSession;
            this.delegate = delegate;
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
            logPacket("C->S", packet);
            return delegate.handlePacket(packet);
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
            if (packet instanceof NetworkStackLatencyPacket) {
                return delegate.handlePacket(packet);
            }
            if (packet instanceof PlayerAuthInputPacket authInputPacket) {
                return scheduleAuthInputBeforeTranslation(
                        connection::ensureInEventLoop,
                        () -> processAuthInput(authInputPacket),
                        error -> LogUtil.warn("Unable to record Bedrock movement packet for CultAC: "
                                + error.getClass().getSimpleName()),
                        () -> delegate.handlePacket(packet));
            }

            try {
                if (packet instanceof MovePlayerPacket movePlayerPacket) {
                    submitMove(movePlayerPacket);
                } else if (packet instanceof InventoryTransactionPacket inventoryTransactionPacket) {
                    submitInventoryTransaction(inventoryTransactionPacket);
                } else if (packet instanceof PlayerActionPacket playerActionPacket) {
                    submitPlayerAction(playerActionPacket);
                }
            } catch (RuntimeException error) {
                LogUtil.warn("Unable to record Bedrock movement packet for CultAC: " + error.getClass().getSimpleName());
            }
            return delegate.handlePacket(packet);
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
                ChannelHandlerContext context,
                BedrockPacketWrapper source,
                Float width,
                Float height,
                Boolean gliding,
                Boolean crawling,
                Boolean swimming
        ) {
            UUID uuid = connection.javaUuid();
            if (!isCurrentConnection(uuid)) {
                return;
            }
            if ((width == null && height == null && gliding == null
                    && crawling == null && swimming == null)
                    || (width != null && (!Float.isFinite(width) || width <= 0.0F))
                    || (height != null && (!Float.isFinite(height) || height <= 0.0F))) {
                return;
            }

            writeLatencyBoundary(context, source, () -> {
                if (isCurrentConnection(uuid)) {
                    // Rely on FIFO to match this payload with the auth input.
                    connection.sendDownstreamGamePacket(createPayloadPacket(
                            BedrockAuthInputPluginMessage.encodeAcknowledgedMetadata(
                                    uuid, width, height, gliding, crawling, swimming)
                    )
                    );
                }
            });
        }

        private boolean beginOutboundVelocity(Vec3 observedMotion) {
            UUID uuid = connection.javaUuid();
            if (!isCurrentConnection(uuid)) {
                return false;
            }

            connection.sendDownstreamGamePacket(
                    createVelocityPayloadPacket(uuid, observedMotion, false));
            return true;
        }

        private Long registerOutboundGeyserTeleport(MovePlayerPacket packet) {
            UUID uuid = connection.javaUuid();
            Vec3 physicalFeetTarget = toJavaPosition(packet.getPosition());
            if (isCurrentConnection(uuid) && physicalFeetTarget != null
                    && isGeyserPositionTeleport(packet.getMode())) {
                return commitOutboundBedrockTeleport(uuid, physicalFeetTarget, packet.isOnGround());
            }
            return null;
        }

        private Long registerOutboundGeyserTeleport(MoveEntityAbsolutePacket packet) {
            if (!packet.isTeleported()) {
                return null;
            }
            UUID uuid = connection.javaUuid();

            Vec3 physicalFeetTarget = toJavaPosition(packet.getPosition());
            if (!isCurrentConnection(uuid) || physicalFeetTarget == null) {
                return null;
            }
            // Treat every client-visible position teleport through the same
            // packet-driven queue path. No Geyser teleport cache is consulted.
            return commitOutboundBedrockTeleport(uuid, physicalFeetTarget, packet.isOnGround());
        }

        private Long commitOutboundBedrockTeleport(UUID uuid, Vec3 physicalFeetPosition, boolean onGround) {
            CultPlayer player = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(uuid);
            if (player == null || !player.isBedrockMovement()) {
                return null;
            }
            return player.getSetbackTeleportUtil()
                    .addImmediateBedrockTransportTeleport(physicalFeetPosition, onGround);
        }

        private void completeOutboundVelocity(
                ChannelHandlerContext context,
                BedrockPacketWrapper source,
                Vec3 observedMotion
        ) {
            UUID uuid = connection.javaUuid();
            // The marker is queued after SetEntityMotion on the same reliable
            // Bedrock connection; Geyser's FIFO callback proves receipt.
            writeLatencyBoundary(context, source, () -> {
                if (isCurrentConnection(uuid)) {
                    connection.sendDownstreamGamePacket(
                            createVelocityPayloadPacket(uuid, observedMotion, true));
                }
            });
        }

        private void writeLatencyBoundary(
                ChannelHandlerContext context,
                BedrockPacketWrapper source,
                Runnable acknowledgement
        ) {

            connection.getLatencyPingCache().add(acknowledgement);
            NetworkStackLatencyPacket marker = new NetworkStackLatencyPacket();
            marker.setFromServer(true);
            marker.setTimestamp(1L);
            context.write(BedrockPacketWrapper.create(
                    0,
                    source.getSenderSubClientId(),
                    source.getTargetSubClientId(),
                    marker,
                    null), context.voidPromise());
        }

        private void processAuthInput(PlayerAuthInputPacket packet) {
            if (detached.get()) {
                return;
            }
            UUID uuid = connection.javaUuid();
            if (!isCurrentConnection(uuid)) {
                return;
            }
            try {
                submitAuthInput(packet);
            } catch (RuntimeException error) {
                LogUtil.warn("Unable to record Bedrock movement packet for CultAC: "
                        + error.getClass().getSimpleName());
            }

            Vector3f rewritten = rewriteDelta(packet.getDelta(), latestTrustedMotion);
            if (rewritten != null) {
                packet.setDelta(rewritten);
                lastWrittenDeltaY = rewritten.getY();
            }
        }

        private void acceptAuthInputCompletion(AuthInputCompletion completion) {
            connection.ensureInEventLoop(() -> applyAuthInputCompletion(completion));
        }

        private void applyAuthInputCompletion(AuthInputCompletion completion) {
            if (detached.get()) {
                return;
            }
            if (!isFinite(completion.trustedVelocity())) {
                return;
            }
            Vector3f trusted = toVector3f(completion.trustedVelocity());
            latestTrustedMotion = trusted;
            connection.getPlayerEntity().setMotion(trusted);

            if ((trusted.getY() < 0.0F) == (lastWrittenDeltaY < 0.0F)) {
                connection.getPlayerEntity().setLastTickEndVelocity(trusted);
            }
        }

        private boolean matchesJavaUser(User user) {
            var downstream = connection.getDownstream();
            return downstream != null && sameJavaConnection(
                    downstream.getSession().getChannel(), user.getChannel());
        }

        private void submitAuthInput(PlayerAuthInputPacket packet) {
            UUID uuid = connection.javaUuid();
            if (!isCurrentConnection(uuid)) {
                return;
            }

            Vec3 position = toJavaPosition(packet.getPosition());
            Vec3 delta = lastAuthInputPosition == null ? Vec3.ZERO : position.subtract(lastAuthInputPosition);
            lastAuthInputPosition = position;
            BedrockAuthInputFrame frame = createAuthInputFrame(
                    uuid,
                    connection.protocolVersion(),
                    packet,
                    position,
                    delta,
                    projectedOnGround(connection, packet));
            connection.sendDownstreamGamePacket(createAuthInputPayloadPacket(frame));
        }

        private void submitMove(MovePlayerPacket packet) {
            UUID uuid = connection.javaUuid();
            if (!isCurrentConnection(uuid)) {
                return;
            }

            connection.sendDownstreamGamePacket(createMoveFramePayloadPacket(
                    createMoveFrame(uuid, connection.protocolVersion(), packet)));
        }

        private void submitInventoryTransaction(InventoryTransactionPacket packet) {
            if (packet.getTransactionType() != InventoryTransactionType.ITEM_RELEASE) {
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
            }
        }

        private void submitClientAction(UUID uuid, BedrockClientAction action) {
            if (isCurrentConnection(uuid)) {
                connection.sendDownstreamGamePacket(createClientActionPayloadPacket(uuid, action));
            }
        }

        private boolean isCurrentConnection(UUID uuid) {
            return uuid != null && PACKET_TAPS.get(connection) == this;
        }
    }

    public static void completeAuthInput(
            User user,
            Vec3 trustedVelocity
    ) {
        if (user == null) {
            return;
        }
        PacketTapHandler tap = packetTapForUser(user);
        if (tap != null) {
            tap.acceptAuthInputCompletion(new AuthInputCompletion(trustedVelocity));
        }
    }

    private static PacketTapHandler packetTapForUser(User user) {
        Object associatedConnection = user.getBedrockBridgeConnection();
        if (associatedConnection instanceof GeyserConnection geyserConnection) {
            PacketTapHandler associatedTap = PACKET_TAPS.get(geyserConnection);
            if (associatedTap != null) {
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

    private record AuthInputCompletion(Vec3 trustedVelocity) {
    }

    static <T> T forwardAuthInputBeforeTranslation(
            Runnable forwardAuthInput,
            Consumer<RuntimeException> forwardingFailure,
            Supplier<T> translateMovement
    ) {
        try {
            forwardAuthInput.run();
        } catch (RuntimeException error) {
            forwardingFailure.accept(error);
        }
        return translateMovement.get();
    }

    static PacketSignal scheduleAuthInputBeforeTranslation(
            Consumer<Runnable> tickLoopScheduler,
            Runnable forwardAuthInput,
            Consumer<RuntimeException> forwardingFailure,
            Supplier<PacketSignal> translateMovement
    ) {
        tickLoopScheduler.accept(() -> forwardAuthInputBeforeTranslation(
                forwardAuthInput,
                forwardingFailure,
                translateMovement));
        return PacketSignal.HANDLED;
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
            if (!(message instanceof BedrockPacketWrapper wrapper)) {
                context.write(message, promise);
                return;
            }

            BedrockPacket packet = wrapper.getPacket();
            if (packet instanceof NetworkStackLatencyPacket latencyPacket && latencyPacket.isFromServer()) {
                context.write(message, promise);
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
                owner.registerOutboundGeyserTeleport(move);
                context.write(message, promise);
                return;
            }
            if (packet instanceof MoveEntityAbsolutePacket move
                    && move.getRuntimeEntityId() == selfRuntimeId
                    && move.isTeleported()) {
                owner.registerOutboundGeyserTeleport(move);
                context.write(message, promise);
                return;
            }

            context.write(message, promise);
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

            context.write(message, promise);
            if (width != null || height != null || gliding != null
                    || crawling != null || swimming != null) {
                owner.recordOutboundMetadata(context, (BedrockPacketWrapper) message,
                        width, height, gliding, crawling, swimming);
            }
        }

        private void writeSelfMotion(ChannelHandlerContext context, Object message, ChannelPromise promise,
                                     Vector3f motion) {
            Vec3 observedMotion = new Vec3(motion.getX(), motion.getY(), motion.getZ());
            boolean observed = owner.beginOutboundVelocity(observedMotion);

            context.write(message, promise);
            if (observed) {
                owner.completeOutboundVelocity(
                        context, (BedrockPacketWrapper) message, observedMotion);
            }
        }

    }

    private static BedrockAuthInputFrame createAuthInputFrame(
            UUID uuid,
            int protocolVersion,
            PlayerAuthInputPacket packet,
            Vec3 position,
            Vec3 delta,
            boolean projectedOnGround
    ) {
        Set<PlayerAuthInputData> inputData = packet.getInputData();
        BedrockMoveVector moveVector = resolveMoveVector(packet);


        return BedrockAuthInputFrame.builder(uuid)
                .protocolVersion(protocolVersion)
                .clientTick(packet.getTick())
                .inputMode(packet.getInputMode() == null ? -1 : packet.getInputMode().ordinal())
                .playMode(packet.getPlayMode() == null ? -1 : packet.getPlayMode().ordinal())
                .position(position)
                .packetPosition(toVec3(packet.getPosition()))
                .delta(delta)
                .reportedEndOfTickVelocity(toVec3(packet.getDelta()))
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
                packet.getRotation().getY()
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

    static Vector3f rewriteDelta(Vector3f reported, Vector3f lastKnownGood) {
        if (reported == null || lastKnownGood == null) {
            return reported;
        }
        if ((lastKnownGood.getY() < 0.0F) == (reported.getY() < 0.0F)) {
            return lastKnownGood;
        }
        return Vector3f.from(
                lastKnownGood.getX(),
                clampSignPreservingY(reported.getY()),
                lastKnownGood.getZ());
    }

    static float clampSignPreservingY(float reportedY) {
        return reportedY < 0.0F ? -0.01F : 0.01F;
    }

    static boolean sameJavaConnection(
            io.netty.channel.Channel geyserDownstreamChannel,
            Object userChannel
    ) {
        return geyserDownstreamChannel != null
                && userChannel instanceof io.netty.channel.Channel javaServerChannel
                && java.util.Objects.equals(
                        geyserDownstreamChannel.localAddress(),
                        javaServerChannel.remoteAddress());
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

    private static ServerboundCustomPayloadPacket createAuthInputPayloadPacket(BedrockAuthInputFrame frame) {
        return createPayloadPacket(BedrockAuthInputPluginMessage.encode(frame));
    }

    private static ServerboundCustomPayloadPacket createClientActionPayloadPacket(UUID uuid, BedrockClientAction action) {
        return createPayloadPacket(BedrockAuthInputPluginMessage.encode(uuid, action));
    }

    private static ServerboundCustomPayloadPacket createMoveFramePayloadPacket(BedrockMoveFrame frame) {
        return createPayloadPacket(BedrockAuthInputPluginMessage.encode(frame));
    }

    private static ServerboundCustomPayloadPacket createVelocityPayloadPacket(
            UUID uuid,
            Vec3 motion,
            boolean acknowledged
    ) {
        return createPayloadPacket(BedrockAuthInputPluginMessage.encodeVelocity(
                uuid, motion, acknowledged));
    }

    private static ServerboundCustomPayloadPacket createPayloadPacket(byte[] data) {
        byte[] channel = BedrockAuthInputPluginMessage.CHANNEL.getBytes(StandardCharsets.UTF_8);
        ByteBuf buffer = Unpooled.buffer(varIntSize(channel.length) + channel.length + data.length);
        try {
            writeVarInt(buffer, channel.length);
            buffer.writeBytes(channel);
            buffer.writeBytes(data);
            return new ServerboundCustomPayloadPacket(buffer);
        } finally {
            buffer.release();
        }
    }

    private static void writeVarInt(ByteBuf buffer, int value) {
        while ((value & -128) != 0) {
            buffer.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buffer.writeByte(value);
    }

    private static int varIntSize(int value) {
        int size = 1;
        while ((value & -128) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }
}
