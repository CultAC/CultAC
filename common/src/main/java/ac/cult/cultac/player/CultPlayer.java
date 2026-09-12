package ac.cult.cultac.player;

import ac.cult.cultac.CultAPI;
import ac.grim.grimac.api.event.events.GrimTransactionSendEvent;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.PacketWorld;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.handler.ResyncHandler;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.aim.processor.AimProcessor;
import ac.cult.cultac.checks.impl.combat.FairReach;
import ac.cult.cultac.checks.impl.movement.GhostBlockMitigator;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderProcessor;
import ac.cult.cultac.checks.impl.ping.TransactionOrder;
import ac.cult.cultac.checks.impl.prediction.checks.ServerStateNoSlow;
import ac.cult.cultac.checks.impl.prediction.runner.ExplosionHandler;
import ac.cult.cultac.checks.impl.prediction.runner.KnockbackHandler;
import ac.cult.cultac.events.packets.PacketEntityReplication;
import ac.cult.cultac.manager.LastInstanceManager;
import ac.cult.cultac.manager.PunishmentManager;
import ac.cult.cultac.manager.player.ActionManager;
import ac.cult.cultac.manager.player.CheckManager;
import ac.cult.cultac.manager.player.MovementData;
import ac.cult.cultac.manager.player.PluginChannelManager;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.manager.player.features.FeatureManagerImpl;
import ac.cult.cultac.manager.player.handlers.NoOpResyncHandler;
import ac.cult.cultac.network.netty.channel.ChannelHelper;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.protocol.util.FoliaCompatUtil;
import ac.cult.cultac.network.protocol.util.viaversion.ViaVersionUtil;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.utils.anticheat.HumanFormatter;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.cult.cultac.utils.anticheat.NettyScheduler;
import ac.cult.cultac.utils.change.PlayerBlockHistory;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.BoatData;
import ac.cult.cultac.utils.data.PacketStateData;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.data.VehicleData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.CompensatedCameraEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.latency.CompensatedFireworks;
import ac.cult.cultac.utils.latency.CompensatedInventory;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.latency.KeepAliveProcessor;
import ac.cult.cultac.utils.latency.LatencyUtils;
import ac.cult.cultac.utils.lists.EvictingQueue;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.TrigHandler;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketTracker;
import lombok.Getter;
import lombok.Setter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.SharedConstants;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

// Everything in this class should be sync'd to the anticheat thread.
// Put variables sync'd to the Netty thread in PacketStateData
// Variables that need lag compensation should have their own class
// Soon there will be a generic class for lag compensation
public class CultPlayer implements GrimUser {
    private static final class TransactionChannels {
        private static final GrimTransactionSendEvent.Channel SEND =
                CultAPI.INSTANCE.getEventBus().get(GrimTransactionSendEvent.class);
    }
    private static final Method SERVER_PLAYER_LEVEL_METHOD = resolveServerPlayerLevelMethod();
    private static final @NotNull ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    public record TrackedTransaction(int transaction, int id, ClientboundPingPacket packet) {
    }

    @lombok.Getter
    @lombok.RequiredArgsConstructor
    @lombok.experimental.Accessors(fluent = true)
    private static class SentTransaction {
        private final int transaction;
        private final int id;
        private final long sentAt;
    }

    // Only Bedrock entries carry native receipt state and callbacks.
    public static final class BedrockTransaction extends SentTransaction {
        private boolean acknowledged;
        private java.util.List<Runnable> acknowledgements;

        private BedrockTransaction(int transaction, int id) {
            super(transaction, id, System.nanoTime());
        }
    }

    public final @NotNull User user;
    public final @NotNull UUID playerUUID;
    public final long timeJoined;
    public @NotNull MovementPlatform movementPlatform = MovementPlatform.JAVA;
    public @Nullable BedrockPlayerState bedrockState;
    public long lastJoinedWorld;

    public int entityID;
    public @Nullable Player bukkitPlayer;
    public @MonotonicNonNull PlatformPlayer platformPlayer;
    // Start transaction handling stuff
    // Determining player ping
    // The difference between keepalive and transactions is that keepalive is async while transactions are sync
    private Queue<SentTransaction> transactionsSent = new ArrayDeque<>();
    private final @NotNull Map<@NotNull ClientboundPingPacket, @NotNull TrackedTransaction> transactionsPendingSend = new IdentityHashMap<>(4);
    public final @NotNull AtomicInteger lastTransactionSent = new AtomicInteger(0);
    public final @NotNull AtomicInteger lastTransactionReceived = new AtomicInteger(0);
    // End transaction handling stuff
    // Manager like classes
    public @NotNull CheckManager checkManager;
    public @NotNull ActionManager actionManager;
    public final @NotNull PunishmentManager punishmentManager;
    @Getter private final @NotNull FeatureManagerImpl featureManager = new FeatureManagerImpl(this);
    // The resync handler defaults to a no-op until the packet-sending implementation is
    // available on this platform's networking stack.
    @Getter @Setter private @NotNull ResyncHandler resyncHandler = NoOpResyncHandler.INSTANCE;
    // End manager like classes
    public int riptideSpinAttackTicks;
    private @MonotonicNonNull PacketTracker packetTracker;
    public final @NotNull PacketOrderProcessor packetOrderProcessor = new PacketOrderProcessor(this);
    public long rawTransactionPing = 0;
    public long lastTransSent = 0;
    @Getter private long playerClockAtLeast = System.nanoTime();
    public int powderSnowFrozenTicks = 0;
    public double x;
    public double y;
    public double z;
    public double lastX;
    public double lastY;
    public double lastZ;
    // Mojang uses xRot for pitch and yRot for yaw
    public float xRot;
    public float yRot;
    // The client does not tell us last tick's rotation, so we must assume the worst case
    public float lastTickXRot;
    public float lastTickYRot;
    public boolean onGround;
    public boolean lastOnGround;
    public boolean isSneaking;
    public boolean isSprinting;
    public boolean lastSprinting;
    // The client updates sprinting attribute at end of each tick
    // Don't false if the server update's the player's sprinting status
    public boolean lastSprintingForSpeed;
    public boolean isFlying;
    public boolean canFly;
    public boolean canInstabuild;
    public boolean isSwimming;
    public boolean isGliding;
    public boolean isRiptidePose = false;
    public @NotNull SimpleCollisionBox boundingBox;
    public @NotNull Pose pose = Pose.STANDING;
    public boolean isInBed = false;
    public boolean hasInventoryOpen = false;
    public long lastOpenedInventory;
    public int food = 20;
    public float flySpeed;
    public boolean settingMetaData = false;
    public @NotNull BoatData boatData = new BoatData();
    // You cannot initialize everything here for some reason
    public final @NotNull LastInstanceManager lastInstanceManager;
    public final @NotNull CompensatedFireworks compensatedFireworks;
    public @NotNull CompensatedWorld compensatedWorld;
    public @NotNull CompensatedEntities compensatedEntities;
    // Constructed after compensatedEntities; CompensatedCameraEntity seeds itself from the self entity.
    public final @NotNull CompensatedCameraEntity cameraEntity;
    public @NotNull LatencyUtils latencyUtils;
    public final @NotNull KeepAliveProcessor keepAliveProcessor;
    public final @NotNull NettyScheduler nettyScheduler;
    public @NotNull TrigHandler trigHandler;
    public @NotNull PacketStateData packetStateData;
    public ClientComponentRegistries registryState;
    public final @NotNull KnockbackHandler knockbackHandler;
    public final @NotNull ExplosionHandler explosionHandler;
    public final @NotNull PluginChannelManager pluginChannelManager;
    public final @NotNull PacketEntityReplication packetEntityReplication;
    private final @NotNull GhostBlockMitigator ghostBlockMitigator;
    private final @NotNull ServerStateNoSlow serverStateNoSlow;

    public GhostBlockMitigator getGhostBlockMitigator() {
        return ghostBlockMitigator;
    }

    public ServerStateNoSlow getServerStateNoSlow() {
        return serverStateNoSlow;
    }

    public long lastRotated;
    public final @NotNull VehicleData vehicleData = new VehicleData();
    public final @NotNull PlayerBlockHistory blockHistory = new PlayerBlockHistory();
    public boolean wasEyeInWater;
    public boolean serverOpenedInventoryThisTick;
    // Whether this tick's movement intersected a nether portal block (see MultiActionsD)
    public boolean intersectedWithNetherPortal;
    @Getter private final @NotNull MovementData movementData;
    public int minPlayerAttackSlow = 0;
    public int maxPlayerAttackSlow = 0;
    public @MonotonicNonNull GameMode gamemode;
    public @MonotonicNonNull ResourceKey<Level> dimension;
    public @Nullable String world;
    public @Nullable Vec3 bedPosition;
    public long lastBlockPlaceUseItem = 0;
    public int totalFlyingPacketsSent;
    public final @NotNull AtomicInteger cancelledPackets = new AtomicInteger(0);
    public boolean hasBrand = false;
    public @NotNull String brand = "Unknown";

    public boolean isDead() {
        return compensatedEntities.getSelf().isDead;
    }

    public boolean isBedrockMovement() {
        return movementPlatform == MovementPlatform.BEDROCK;
    }

    public boolean shouldEnforceMovementSetbacks() {
        return movementPlatform == MovementPlatform.JAVA || (bedrockState != null && bedrockState.shouldEnforceSetbacks());
    }

    public void onPacketCancel() {
        if (spamThreshold != -1 && cancelledPackets.incrementAndGet() > spamThreshold) {
            LogUtil.info("Disconnecting " + getName() + " for spamming invalid packets, packets cancelled within a second " + cancelledPackets);
            disconnect(MessageUtil.miniMessage(MessageUtil.replacePlaceholders(this, CultAPI.INSTANCE.getConfigManager().getDisconnectClosed())));
            cancelledPackets.set(0);

            if (debugPacketCancel) {
                try {
                    throw new Exception();
                } catch (Exception e) {
                    LogUtil.error("Stacktrace for onPacketCancel (debug-packet-cancel=true)", e);
                }
            }
        }
    }

    // Support for test servers that want to be able to disable cult
    @Getter
    @Setter
    private boolean disabled = false;

    @Getter @Setter private boolean experimentalChecks;
    @Getter @Setter private boolean exemptElytra;
    @Getter @Setter private boolean forceStuckSpeed = true;
    @Getter @Setter private boolean forceSlowMovement = true;

    private static final Permission DISABLED_PERMISSION = new Permission("cult.disabled", PermissionDefault.FALSE);

    public void updateDisabled() {
        if (bukkitPlayer == null) return;
        boolean current = bukkitPlayer.hasPermission(DISABLED_PERMISSION);
        if (current != disabled) {
            disabled = current;
        }
    }

    public CultPlayer(@NotNull User user) {
        this(user, MovementPlatform.JAVA, null);
    }

    public CultPlayer(@NotNull User user, @NotNull MovementPlatform movementPlatform, @Nullable BedrockPlayerState bedrockState) {
        this.user = Objects.requireNonNull(user, "user");
        this.playerUUID = Objects.requireNonNull(user.getUUID(), "uuid");
        this.movementPlatform = movementPlatform;
        this.bedrockState = bedrockState;
        this.bukkitPlayer = user.getPlayer();
        this.entityID = bukkitPlayer == null ? 0 : bukkitPlayer.getEntityId();
        this.timeJoined = System.currentTimeMillis();
        onReload();

        this.nettyScheduler = new NettyScheduler(this);
        this.packetEntityReplication = new PacketEntityReplication(this);
        this.ghostBlockMitigator = new GhostBlockMitigator(this);
        this.serverStateNoSlow = new ServerStateNoSlow(this);
        this.movementData = new MovementData(this);
        this.knockbackHandler = new KnockbackHandler(this);
        this.explosionHandler = new ExplosionHandler(this);
        this.actionManager = new ActionManager(this);
        this.pluginChannelManager = new PluginChannelManager(this);
        this.keepAliveProcessor = new KeepAliveProcessor(this);

        this.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(this, x, y, z);

        this.compensatedFireworks = new CompensatedFireworks(this); // Must be before checkmanager

        this.lastInstanceManager = new LastInstanceManager(this);
        this.checkManager = new CheckManager(this);
        this.punishmentManager = new PunishmentManager(this);

        this.compensatedWorld = new CompensatedWorld(this);
        this.compensatedEntities = new CompensatedEntities(this);
        this.cameraEntity = new CompensatedCameraEntity(this);
        this.latencyUtils = new LatencyUtils(this);
        this.trigHandler = new TrigHandler(this);

        this.packetStateData = new PacketStateData();
        updateServerPlayerBinding(user.getPlayer(), user.getHandle());
        // reload last: pulls config, per-check permissions, and the punishment manager
        reload();
    }

    public void updateServerPlayerBinding(@Nullable Player player, @Nullable ServerPlayer serverPlayer) {
        this.bukkitPlayer = player;
        if (player != null) {
            this.entityID = player.getEntityId();
        }
        if (serverPlayer != null) {
            ServerLevel level = serverPlayerLevel(serverPlayer);
            net.minecraft.network.protocol.game.CommonPlayerSpawnInfo spawnInfo = serverPlayer.createCommonSpawnInfo(level);
            this.gamemode = switch (spawnInfo.gameType()) {
                case CREATIVE -> GameMode.CREATIVE;
                case ADVENTURE -> GameMode.ADVENTURE;
                case SPECTATOR -> GameMode.SPECTATOR;
                default -> GameMode.SURVIVAL;
            };
            this.dimension = spawnInfo.dimension();
            this.world = NmsIdentifierUtil.resourceKey(spawnInfo.dimension());
            this.compensatedWorld.setLastClientboundDimension(spawnInfo);
            this.compensatedWorld.setDimension(spawnInfo);
            this.lastJoinedWorld = System.currentTimeMillis();
        }
    }

    private static Method resolveServerPlayerLevelMethod() {
        for (String candidate : new String[]{"level", "serverLevel"}) {
            try {
                return ServerPlayer.class.getMethod(candidate);
            } catch (NoSuchMethodException ignored) {
                // Mojang renamed the covariant ServerPlayer world accessor after 1.21.5.
            }
        }
        throw new IllegalStateException("Unable to resolve ServerPlayer level accessor");
    }

    private static ServerLevel serverPlayerLevel(ServerPlayer player) {
        try {
            return (ServerLevel) SERVER_PLAYER_LEVEL_METHOD.invoke(player);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to access ServerPlayer level", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("ServerPlayer level lookup failed", exception.getCause());
        }
    }

    public void onRemove() {
        ChannelHelper.runInEventLoop(user.getChannel(), () -> {
            nettyScheduler.removeScheduler();
            compensatedWorld.clearChunks();
            FairReach fairReach = checkManager.getListener(FairReach.class);
            if (fairReach != null) {
                fairReach.onPlayerQuit();
            }
        });
    }

    private long lastCheckedFlying = 0;

    // Players can get 0 ping by repeatedly sending invalid transaction packets, but that will only hurt them
    // The design is allowing players to miss transaction packets, which shouldn't be possible
    // But if some error made a client miss a packet, then it won't hurt them too bad.
    // Also it forces players to take knockback
    public synchronized boolean addTransactionResponse(int id) {
        // Count how many unacknowledged transactions this response jumps past
        int skipped = 0;
        boolean known = false;
        for (SentTransaction pending : transactionsSent) {
            if (pending.id() == id) {
                known = true;
                break;
            }
            skipped++;
        }
        if (!known) {
            return false;
        }

        // A skipped transaction is only suspicious with a valid keep alive baseline;
        // switching servers can legitimately drop transaction responses.
        if (!isBedrockMovement()
                && skipped > 0
                && keepAliveProcessor.lastKeepAlivePing > 0) {
            checkManager.getCheck(TransactionOrder.class).skipped(skipped);
        }

        SentTransaction acknowledged = acknowledgeThrough(id);

        // ViaVersion rate-limits inbound packets; our own transactions don't count
        if (packetTracker != null) {
            packetTracker.setIntervalPackets(packetTracker.getIntervalPackets() - 1);
        }

        pruneStalePistonStateWhenIdle();

        // A transaction response marks a new tick boundary for latency-compensated tasks
        latencyUtils.handleNettySyncTransaction(lastTransactionReceived.get());
        return acknowledged != null;
    }

    private SentTransaction acknowledgeThrough(int id) {
        SentTransaction current;
        do {
            current = transactionsSent.poll();
            if (current == null) {
                return null;
            }
            lastTransactionReceived.set(current.transaction());
            rawTransactionPing = System.nanoTime() - current.sentAt();
            transPings.add(rawTransactionPing);
            playerClockAtLeast = current.sentAt();
            if (current instanceof BedrockTransaction bedrock) {
                // Drain ordinary compensation before callbacks on this exact wire boundary.
                latencyUtils.handleNettySyncTransaction(current.transaction());
                bedrock.acknowledged = true;
                var tasks = bedrock.acknowledgements;
                bedrock.acknowledgements = null;
                if (tasks != null) tasks.forEach(latencyUtils::runQueuedTask);
            }
        } while (current.id() != id);
        return current;
    }

    private void pruneStalePistonStateWhenIdle() {
        long now = System.currentTimeMillis();
        // When flying packets stop for 2s, the cleanup they normally trigger never runs
        if (now - lastCheckedFlying <= 2000 || now - actionManager.lastFlyingPacketTime <= 2000) {
            return;
        }
        lastCheckedFlying = now;
        compensatedWorld.removeInvalidPistonLikeStuff(lastTransactionReceived.get());
    }

    @Getter private final @NotNull EvictingQueue<@NotNull Long> transPings = new EvictingQueue<>(60);

    public float getMaxUpStep() {
        PacketEntity riding = compensatedEntities.getSelf().getRiding();
        if (riding == null) {
            Double stepHeight = compensatedEntities.getSelf().stepHeightAttribute;
            return stepHeight == null ? 0.6f : stepHeight.floatValue();
        }

        if (EntityTypeUtil.isBoat(riding.type)) {
            return 0f;
        }

        if (riding.stepHeightAttribute != null) {
            return riding.stepHeightAttribute.floatValue();
        }

        // Pigs, horses, striders, and other vehicles all have 1 stepping height
        return 1.0f;
    }

    public void sendTransaction() {
        sendTransaction(false);
    }

    public void sendTransaction(boolean async) {
        if (async) {
            // Re-enter on the connection's event loop so the id is allocated in send order
            ChannelHelper.runInEventLoop(user.getChannel(), () -> sendTransaction(false));
            return;
        }

        if (!canSendTransactionNow()) {
            return;
        }

        lastTransSent = System.currentTimeMillis();
        TrackedTransaction transaction = createTrackedTransaction();
        user.writePacket(transaction.packet());
    }

    private boolean canSendTransactionNow() {
        // don't send transactions outside PLAY phase
        // Sending in non-play corrupts the pipeline, don't waste bandwidth when anticheat disabled
        if (user.getEncoderState() != ConnectionProtocol.PLAY || user.getHandle() == null) {
            return false;
        }
        // Send a packet once every 15 seconds to avoid any memory leaks
        return !disabled || (System.nanoTime() - getPlayerClockAtLeast()) <= 15e9;
    }

    public int sendTransactionAndGetId() {
        // Only use this on the player's outbound thread. Async transactions are still sent through
        // sendTransaction(true), where the ID is deliberately allocated inside the event loop task.
        if (!canSendTransactionNow()) {
            return -1;
        }

        lastTransSent = System.currentTimeMillis();
        TrackedTransaction transaction = createTrackedTransaction();
        user.writePacket(transaction.packet());
        return transaction.transaction();
    }

    @Nullable
    public TrackedTransaction createTrackedTransactionPacketForBundle() {
        return createTrackedTransactionPacketForDeferredSend();
    }

    @Nullable
    public TrackedTransaction createTrackedTransactionPacketForDeferredSend() {
        // Use this when the caller will write the ping later in the same outbound packet group.
        // The caller must mark the packet sent after the group is flushed, because the generated
        // ping may not pass through Cult's normal outbound ping listener.
        if (!canSendTransactionNow()) {
            return null;
        }

        lastTransSent = System.currentTimeMillis();
        return createTrackedTransaction();
    }

    private synchronized TrackedTransaction createTrackedTransaction() {
        int transactionID = nextTransactionId();
        ClientboundPingPacket packet = new ClientboundPingPacket(transactionID);
        TrackedTransaction transaction = new TrackedTransaction(lastTransactionSent.incrementAndGet(), transactionID, packet);
        transactionsPendingSend.put(packet, transaction);
        return transaction;
    }

    public void markTrackedTransactionPacketSent(@Nullable TrackedTransaction transaction) {
        if (transaction == null) {
            return;
        }

        // Replacement bundles created inside a packet-send handler are flattened
        // after handler dispatch, so the generated ping sub-packet may not pass
        // through the outbound ping listener. Mirror the send-side tracking here.
        if (markTransactionPacketSent(transaction.packet())) {
            packetStateData.lastServerTransWasValid = true;
        }
    }

    public boolean markTransactionPacketSent(ClientboundPingPacket packet) {
        return markTransactionPacketSent(packet, System.currentTimeMillis());
    }

    public synchronized boolean markTransactionPacketSent(ClientboundPingPacket packet, long timestamp) {
        TrackedTransaction transaction = transactionsPendingSend.remove(packet);
        if (transaction == null) {
            return false;
        }

        if (isBedrockMovement()) {
            bedrockTransactions().add(new BedrockTransaction(transaction.transaction(), transaction.id()));
        } else {
            transactionsSent.add(new SentTransaction(transaction.transaction(), transaction.id(), System.nanoTime()));
        }
        TransactionChannels.SEND.fire(this, transaction.id(), timestamp);
        return true;
    }

    private int nextTransactionId() {
        boolean legacyAcknowledgement = !isBedrockMovement() && getClientVersion().isOlderThan(ClientVersion.V_1_17);
        int id;
        boolean used;
        do {
            // ViaBackwards only forwards pre-1.17 pings as inventory acknowledgements
            // when the id fits a signed short. Otherwise it replies itself, before the
            // client processes the preceding packets, invalidating our transaction proof.
            id = legacyAcknowledgement
                    ? ThreadLocalRandom.current().nextInt(Short.MIN_VALUE, Short.MAX_VALUE + 1)
                    : ThreadLocalRandom.current().nextInt();
            used = false;
            for (SentTransaction sent : transactionsSent) used |= sent.id() == id;
            for (TrackedTransaction pending : transactionsPendingSend.values()) used |= pending.id() == id;
        } while (used);
        return id;
    }

    private java.util.LinkedList<SentTransaction> bedrockTransactions() {
        if (!(transactionsSent instanceof java.util.LinkedList)) {
            transactionsSent = new java.util.LinkedList<>(transactionsSent);
        }
        return (java.util.LinkedList<SentTransaction>) transactionsSent;
    }

    public synchronized BedrockTransaction getLastClientboundBedrockTransaction() {
        return bedrockState.lastClientboundTransaction;
    }

    /** Insert at the actual wire position, before Java pings allocated but not yet written. */
    public synchronized BedrockTransaction createBedrockTransactionAfterClientbound() {
        if (!isBedrockMovement()) throw new IllegalStateException("Native boundary requires Bedrock transport");
        BedrockTransaction previous = bedrockState.lastClientboundTransaction;
        BedrockTransaction transaction = new BedrockTransaction(
                previous == null ? lastTransactionReceived.get() : previous.transaction(), nextTransactionId());
        var pending = bedrockTransactions();
        if (previous == null || previous.acknowledged) {
            pending.addFirst(transaction);
        } else {
            int index = pending.indexOf(previous);
            if (index < 0) throw new IllegalStateException("Last written transaction is missing");
            pending.add(index + 1, transaction);
        }
        return transaction;
    }

    public synchronized void addBedrockTransactionTask(BedrockTransaction transaction, Runnable task) {
        if (!isBedrockMovement()) throw new IllegalStateException("Native boundary requires Bedrock transport");
        if (transaction == null || transaction.acknowledged) {
            latencyUtils.runQueuedTask(task);
        } else {
            if (transaction.acknowledgements == null) transaction.acknowledgements = new java.util.ArrayList<>(2);
            transaction.acknowledgements.add(task);
        }
    }

    /** Observe actual Bedrock writes, not Java allocation order. */
    public synchronized void markBedrockTransactionClientbound(long id) {
        for (SentTransaction entry : transactionsSent) {
            if (entry.id() == id && entry instanceof BedrockTransaction sent) {
                bedrockState.lastClientboundTransaction = sent;
                return;
            }
        }
    }

    public float getScale() {
        return compensatedEntities == null ? 1.0f : compensatedEntities.getSelf().scale;
    }

    public double getEyeHeight() {
        return pose.eyeHeight * getScale();
    }

    public float getBukkitHeight() {
        return pose.height * getScale();
    }

    public void refreshPlayerPose() {
        pose = determineCurrentPoseLikeVanilla();

        if (isInBed) {
            if (bedPosition == null) {
                bedPosition = new Vec3(x, y, z);
            }
        } else {
            bedPosition = null;
        }

        boundingBox = GetBoundingBox.getCollisionBoxForPlayer(this, x, y, z);
    }

    private Pose determineCurrentPoseLikeVanilla() {
        Pose desiredPose = getDesiredPoseLikeVanilla();
        if (compensatedWorld == null || compensatedEntities == null) {
            return desiredPose;
        }

        // The vanilla local player only evaluates pose fallback when it can fit the
        // swimming box, then falls back from the desired pose to crouching and finally
        // to swimming based on exact collision tests at the current position.
        if (!canPlayerFitWithinBlocksAndEntitiesWhen(Pose.SWIMMING)) {
            return desiredPose;
        }

        if (gamemode == GameMode.SPECTATOR
                || compensatedEntities.getSelf().inVehicle()
                || canPlayerFitWithinBlocksAndEntitiesWhen(desiredPose)) {
            return desiredPose;
        }

        if (canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING)) {
            return Pose.CROUCHING;
        }

        return Pose.SWIMMING;
    }

    private Pose getDesiredPoseLikeVanilla() {
        if (isInBed) {
            return Pose.SLEEPING;
        }
        if (isSwimming) {
            return Pose.SWIMMING;
        }
        if (isGliding) {
            return Pose.FALL_FLYING;
        }
        if (isRiptidePose) {
            return Pose.SPIN_ATTACK;
        }
        return isSneaking && !isFlying ? Pose.CROUCHING : Pose.STANDING;
    }

    private boolean canPlayerFitWithinBlocksAndEntitiesWhen(Pose newPose) {
        SimpleCollisionBox box = GetBoundingBox.getBoundingBoxFromPosAndSize(
                x,
                y,
                z,
                newPose.width * getScale(),
                newPose.height * getScale()
        ).expand(-1.0E-7D);
        return Collisions.isEmpty(this, box, y) && hasNoEntityCollision(box);
    }

    private boolean hasNoEntityCollision(SimpleCollisionBox playerBox) {
        PacketEntity riding = compensatedEntities.getSelf().getRiding();
        for (PacketEntity entity : compensatedEntities.entityMap.values()) {
            if (!canCollideWithForPoseCheck(riding, entity)) {
                continue;
            }

            if (entity.getPossibleCollisionBoxes().isIntersected(playerBox)) {
                return false;
            }
        }
        return true;
    }

    private boolean canCollideWithForPoseCheck(@Nullable PacketEntity riding, PacketEntity entity) {
        if (entity == null || entity.isDead || entity.getEntityId() == entityID) {
            return false;
        }

        if (riding != null && (riding == entity || riding.hasPassenger(entity) || entity.hasPassenger(riding))) {
            return false;
        }

        return EntityTypeUtil.isBoat(entity.type)
                || entity.isMinecart()
                || entity.type == EntityTypesCompat.SHULKER
                || EntityTypeUtil.isHappyGhast(entity.type);
    }

    public void closeInventorySafely() {
        if (bukkitPlayer != null) {
            FoliaCompatUtil.runTaskForEntity(bukkitPlayer, CultAPI.INSTANCE.getPlugin(), () -> bukkitPlayer.closeInventory(), null, 0);
        }
    }

    public void timedOut() {
        disconnect(MessageUtil.miniMessage(MessageUtil.replacePlaceholders(this, CultAPI.INSTANCE.getConfigManager().getDisconnectTimeout())));
    }

    private final AtomicBoolean hasDisconnected = new AtomicBoolean(false);

    public void disconnect(Component reason) {
        if (!hasDisconnected.compareAndSet(false, true)) {
            return;
        }

        final String textReason;
        if (reason instanceof TranslatableComponent translatableComponent) {
            textReason = translatableComponent.key();
        } else {
            textReason = LegacyComponentSerializer.legacySection().serialize(reason);
        }
        LogUtil.info("Disconnecting " + user.getProfile().getName() + " for " + MessageUtil.stripColor(textReason));
        try {
            user.sendPacket(new ClientboundDisconnectPacket(toNmsComponent(reason)));
        } catch (Exception ignored) { // The player may be in the wrong state to receive a disconnect packet
            LogUtil.warn("Failed to send disconnect packet to disconnect " + user.getProfile().getName() + "! Disconnecting anyways.");
        }
        user.closeConnection();
        if (platformPlayer != null) {
            CultAPI.INSTANCE.getScheduler().getEntityScheduler().execute(platformPlayer, CultAPI.INSTANCE.getGrimPlugin(),
                    () -> platformPlayer.kickPlayer(textReason), null, 1);
        }
    }

    private static net.minecraft.network.chat.Component toNmsComponent(Component reason) {
        if (reason instanceof TranslatableComponent translatableComponent) {
            return net.minecraft.network.chat.Component.translatable(translatableComponent.key());
        }
        String text = LegacyComponentSerializer.legacySection().serialize(reason);
        return net.minecraft.network.chat.Component.literal(MessageUtil.stripColor(text));
    }

    public void pollData() {
        // Send a transaction at least once a tick, for timer and post check purposes
        // Don't be the first to send the transaction, or we will stack overflow
        //
        // This will only really activate if there's no entities around the player being tracked
        // 80 is a magic value that is roughly every other tick, we don't want to spam too many packets.
        if (lastTransSent != 0 && lastTransSent + 80 < System.currentTimeMillis()) {
            sendTransaction(true); // send on netty thread
        }
        if ((System.nanoTime() - getPlayerClockAtLeast()) > maxTransactionTime * 1e9) {
            timedOut();
        }

        if (!CultAPI.INSTANCE.getPlayerDataManager().shouldCheck(user)) {
            CultAPI.INSTANCE.getPlayerDataManager().remove(user);
        }

        if (packetTracker == null && ViaVersionUtil.isAvailable()) {
            UserConnection connection = Via.getManager().getConnectionManager().getConnectedClient(playerUUID);
            packetTracker = connection != null ? connection.getPacketTracker() : null;
        }

        if (this.platformPlayer == null) {
            this.platformPlayer = CultAPI.INSTANCE.getPlatformPlayerFactory().getFromUUID(playerUUID);
            updatePermissions();
        }

        // Datastore session heartbeat — throttled internally to once per
        // `database.session.heartbeat-interval-ms`, so this runs every tick
        // but only emits a row upsert every N seconds. Bounds how stale
        // last_activity_epoch_ms can be when the server crashes.
        CultAPI.INSTANCE.getDataStoreLifecycle().sessionTracker()
                .pollHeartbeat(playerUUID, System.currentTimeMillis());
    }

    public boolean noModifyPacketPermission = false;
    public boolean noSetbackPermission = false;
    public boolean immunePermission = false;
    public boolean chatBypass = false;
    public boolean healthPermission = false;

    //TODO: Create a configurable timer for this
    @Override
    public void updatePermissions() {
        if (bukkitPlayer == null) return;
        try {
            boolean noModifyPacket = bukkitPlayer.hasPermission("cult.nomodifypacket");
            boolean noSetback = bukkitPlayer.hasPermission("cult.nosetback");
            boolean immune = bukkitPlayer.hasPermission("cult.immune");
            boolean showHealth = bukkitPlayer.hasPermission("cult.showhealth");
            boolean bypassChat = bukkitPlayer.hasPermission("cult.chatbypass");
            boolean cultDisabled = bukkitPlayer.hasPermission(DISABLED_PERMISSION);

            for (AbstractCheck check : getChecks()) {
                if (check instanceof Check c) {
                    c.updatePermissions();
                }
            }

            this.noModifyPacketPermission = noModifyPacket;
            this.noSetbackPermission = noSetback;
            this.immunePermission = immune;
            this.healthPermission = showHealth;
            this.chatBypass = bypassChat;
            this.disabled = cultDisabled;
        } catch (Exception e) {
            LogUtil.error("Failed to update permissions for " + getName() + "!", e);
        }
    }

    // start config
    private int spamThreshold = 100;
    private boolean debugPacketCancel;
    private int maxTransactionTime = 60;
    public boolean spoofHealth = false;
    public boolean throwError = false;
    public boolean chunkDebug = false;
    public boolean debugPlaces = false;
    public boolean debugBreaks = false;
    public boolean debugNoSlow = false;
    // end config

    public void onReload() {
        ConfigManager config = CultAPI.INSTANCE.getConfigManager().getConfig();
        spamThreshold = config.getIntElse("packet-spam-threshold", 100);
        spoofHealth = config.getBooleanElse("spoof-health", false);
    }

    // TODO: Create a configurable timer for this
    @Override
    public final void reload(ConfigManager config) {
        updatePermissions();
        featureManager.onReload(config);
        debugPacketCancel = config.getBooleanElse("debug-packet-cancel", false);
        maxTransactionTime = CultMath.clamp(config.getIntElse("max-transaction-time", 60), 1, 180);
        onReload();
        // reload all checks
        for (AbstractCheck value : getChecks()) value.reload();
        // reload punishment manager
        punishmentManager.reload(config);
    }

    @Override
    public void reload() {
        reload(CultAPI.INSTANCE.getConfigManager().getConfig());
    }

    public boolean isPointThree() {
        return false;
    }

    public double getMovementThreshold() {
        return 0.0002;
    }

    // Alright, someone at mojang decided to not send a flying packet every tick with 1.9
    // Thanks for wasting my time to save 1 MB an hour
    //
    // MEANING, to get an "acceptable" 1.9+ reach check, we must only treat it like a 1.8 clients
    // when it is acting like one and sending a packet every tick.
    //
    // There are two predictable scenarios where this happens:
    // 1. The player moves more than 0.03/0.0002 blocks every tick
    //     - This code runs after the prediction engine to prevent a false when immediately switching back to 1.9-like movements
    //     - 3 ticks is a magic value, but it should buffer out incorrect predictions somewhat.
    // 2. The player is in a vehicle
    public boolean isTickingReliablyFor(int ticks) {
        if (compensatedEntities.getSelf().inVehicle()) {
            return true;
        }
        return !checkManager.getSimulationProcessor().getLastTickSkip().hasOccurredSince(ticks);
    }

    @Contract(pure = true)
    public boolean supportsEndTick() {
        return getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_21_2); // PE ServerVersion.V_1_21_2
    }

    @Contract(pure = true)
    public boolean canSkipTicks() {
        return getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9) && !supportsEndTick();
    }

    public boolean canThePlayerBeCloseToZeroMovement(int ticks) {
        return checkManager.getSimulationProcessor().getLastTickSkip().hasOccurredSince(ticks);
    }

    public CompensatedInventory getInventory() {
        return checkManager.getInventory();
    }

    public List<Double> getPossibleEyeHeights() { // We don't return sleeping eye height
        double scale = getScale();
        return Arrays.asList(0.4 * scale, 1.27 * scale, 1.62 * scale);
    }

    @Override
    public PacketWorld getPacketWorld() {
        final CompensatedWorld world = compensatedWorld;
        return new PacketWorld() {
            @Override
            public int getBlockStateId(int x, int y, int z) {
                return Block.getId(world.getBlockStateAt(x, y, z));
            }

            @Override
            public boolean isChunkLoaded(int chunkX, int chunkZ) {
                return world.isChunkLoaded(chunkX, chunkZ);
            }
        };
    }

    @Override
    public int getTransactionPing() {
        return CultMath.floor(rawTransactionPing / 1e6);
    }

    @Override
    public int getKeepAlivePing() {
        return bukkitPlayer == null ? -1 : bukkitPlayer.getPing();
    }

    @Override
    public int getLastTransactionReceived() {
        return lastTransactionReceived.get();
    }

    @Override
    public int getLastTransactionSent() {
        return lastTransactionSent.get();
    }

    public long getClockBehindInMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - playerClockAtLeast);
    }

    public SetbackTeleportUtil getSetbackTeleportUtil() {
        return checkManager.getSetbackUtil();
    }

    public int getRidingVehicleId() {
        PacketEntity riding = compensatedEntities.getSelf().getRiding();
        return riding == null ? Integer.MIN_VALUE : riding.getEntityId();
    }

    public boolean canUseGameMasterBlocks() {
        return gamemode == GameMode.CREATIVE
                && canInstabuild
                && compensatedEntities.getSelf().getOpLevel() >= 2;
    }

    public boolean supportsBundles() {
        return getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_19_4);
    }

    @Override
    public void runSafely(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable");
        ChannelHelper.runInEventLoop(this.user.getChannel(), runnable);
    }

    @Override
    public void addRealTimeTask(int transaction, Runnable runnable) {
        latencyUtils.addRealTimeTask(transaction, runnable);
    }

    @Override
    public String getName() {
        return user.getName();
    }

    @Override
    public UUID getUniqueId() {
        return user.getProfile().getUUID();
    }

    @Override
    public @Nullable String getWorldName() {
        return platformPlayer != null ? platformPlayer.getWorld().getName() : null;
    }

    @Override
    public @Nullable UUID getWorldUID() {
        return platformPlayer != null ? platformPlayer.getWorld().getUID() : null;
    }

    @Override
    public String getBrand() {
        return brand;
    }

    public ClientVersion getClientVersion() {
        if (ViaVersionUtil.isAvailable()) {
            try {
                int protocolVersion = Via.getAPI().getPlayerProtocolVersion(playerUUID).getOriginalVersion();
                if (protocolVersion > 0) {
                    return ClientVersion.fromProtocolVersion(protocolVersion);
                }
            } catch (RuntimeException ignored) {
                // Fall through to the server's native protocol version
            }
        }
        return ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());
    }

    @Override
    public String getVersionName() {
        return getClientVersion().getReleaseName();
    }

    @Override
    public double getHorizontalSensitivity() {
        return checkManager.getCheck(AimProcessor.class).sensitivityX;
    }

    @Override
    public double getVerticalSensitivity() {
        return checkManager.getCheck(AimProcessor.class).sensitivityY;
    }

    @Override
    public boolean isVanillaMath() {
        return trigHandler.isVanillaMath();
    }

    @Override
    public Collection<? extends AbstractCheck> getChecks() {
        return checkManager.getAllChecks();
    }

    public boolean inVehicle() {
        return compensatedEntities.getSelf().inVehicle();
    }

    @Override
    public void sendMessage(String message) {
        if (bukkitPlayer != null) {
            bukkitPlayer.sendMessage(message);
        } else if (platformPlayer != null) {
            platformPlayer.sendMessage(message);
        }
    }

    public void sendMessage(Component message) {
        if (bukkitPlayer != null) {
            HumanFormatter.message(bukkitPlayer, message);
        } else if (platformPlayer != null) {
            platformPlayer.sendMessage(message);
        }
    }

    @Override
    public boolean hasPermission(String s) {
        if (bukkitPlayer != null) {
            return bukkitPlayer.hasPermission(s);
        }
        return platformPlayer != null && platformPlayer.hasPermission(s);
    }

    public boolean hasPermission(String s, boolean defaultIfUnset) {
        if (bukkitPlayer != null) {
            return bukkitPlayer.hasPermission(s);
        }
        return platformPlayer != null && platformPlayer.hasPermission(s, defaultIfUnset);
    }

    public void onEndOfTickEvent() {
        tick++;
        for (Runnable task : endOfTickTasks) task.run();
        endOfTickTasks.clear();
        packetEntityReplication.onEndOfTickEvent();
    }

    private final List<Runnable> endOfTickTasks = new ArrayList<>(4);

    public void addEndOfTickTask(Runnable task) {
        endOfTickTasks.add(task);
    }

    @Getter private int tick;

}
