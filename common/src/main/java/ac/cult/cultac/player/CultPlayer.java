package ac.cult.cultac.player;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.aim.processor.AimProcessor;
import ac.cult.cultac.checks.impl.combat.FairReach;
import ac.cult.cultac.checks.impl.combat.KeepSprint;
import ac.cult.cultac.checks.impl.movement.GhostBlockMitigator;
import ac.cult.cultac.checks.impl.movement.timer.ItemUseBurst;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderProcessor;
import ac.cult.cultac.checks.impl.ping.TransactionOrder;
import ac.cult.cultac.checks.impl.prediction.checks.ItemUseDesync;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles;
import ac.cult.cultac.checks.impl.prediction.runner.ExplosionHandler;
import ac.cult.cultac.checks.impl.prediction.runner.KnockbackHandler;
import ac.cult.cultac.checks.verdict.FlagArbiter;
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
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.protocol.util.viaversion.ViaVersionUtil;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionLifecycle;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketBundlePolicy;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.cult.cultac.utils.anticheat.NettyScheduler;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.BoatData;
import ac.cult.cultac.utils.data.ClientAttackStrength;
import ac.cult.cultac.utils.data.ClientCombatAttributes;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.ClientSprintState;
import ac.cult.cultac.utils.data.ClientSwimState;
import ac.cult.cultac.utils.data.PacketStateData;
import ac.cult.cultac.utils.data.VehicleData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.latency.CompensatedCameraEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.latency.CompensatedFireworks;
import ac.cult.cultac.utils.latency.CompensatedInventory;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.latency.KeepAliveProcessor;
import ac.cult.cultac.utils.latency.LatencyUtils;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.TrigHandler;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoatRiderYaw;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.visibility.VisibilityDebug;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.PacketWorld;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.event.events.GrimTransactionSendEvent;
import ac.grim.grimac.api.handler.ResyncHandler;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketTracker;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import lombok.Getter;
import lombok.Setter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// Everything in this class should be sync'd to the anticheat thread.
// Put variables sync'd to the Netty thread in PacketStateData
// Variables that need lag compensation should have their own class
// Soon there will be a generic class for lag compensation
public class CultPlayer implements GrimUser {
    private static final class TransactionChannels {
        private static final GrimTransactionSendEvent.Channel SEND =
                CultAPI.INSTANCE.getEventBus().get(GrimTransactionSendEvent.class);
    }

    /** A transaction and the ping that carries it; a {@code null} packet shares a ping already written. */
    public record TrackedTransaction(
            int transaction, @Nullable CultWrite packet) {
        public int id() {
            return ((ClientboundPing) packet.packet()).id();
        }
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
    public @MonotonicNonNull PlatformPlayer platformPlayer;
    // Start transaction handling stuff
    // Determining player ping
    // The difference between keepalive and transactions is that keepalive is async while transactions are sync
    private Queue<SentTransaction> transactionsSent = new ArrayDeque<>();
    /** The ids in {@link #transactionsSent}, which never holds one id twice. */
    private final IntOpenHashSet transactionIdsSent = new IntOpenHashSet();

    private final Int2ObjectOpenHashMap<@NotNull TrackedTransaction> transactionsPendingSend =
            new Int2ObjectOpenHashMap<>(4);
    /** Ping ids a client cannot predict from the ones it has answered. */
    private final SecureRandom wireIds = wireIdSource();

    private final WireIds wireIdBlock = new WireIds();
    private final IntSupplier nextWireWord = () -> wireIdBlock.next(wireIds);
    /** Backend pings in flight, whose ids a CultAC ping must not take or the backend's pong is swallowed. */
    private final ForeignPings foreignPings = new ForeignPings();

    public final @NotNull AtomicInteger lastTransactionSent = new AtomicInteger(0);
    public final @NotNull AtomicInteger lastTransactionReceived = new AtomicInteger(0);
    private final AtomicLong impulseSequence = new AtomicLong();
    /** Whether transactions record their provenance; the rings below are null until it first turns on. */
    private boolean recordProvenance;
    /** The wire ids of the last {@link #RECENT_WIRE_IDS} transactions, by transaction. */
    private int[] recentWireIds;
    /** The transaction each slot holds, so a stale slot is never read as a newer one. */
    private int[] recentTransactions;
    /** The kind of each of those pings, a {@code PING_} value; 0 for an empty slot. */
    private byte[] recentKinds;
    /** The server tick of each end-of-tick ping; 0 for the others. */
    private int[] recentTicks;
    // End transaction handling stuff
    // Manager like classes
    public @NotNull CheckManager checkManager;
    public @NotNull ActionManager actionManager;
    public final @NotNull PunishmentManager punishmentManager;

    @Getter
    private final @NotNull FeatureManagerImpl featureManager = new FeatureManagerImpl(this);
    // The resync handler defaults to a no-op until the packet-sending implementation is
    // available on this platform's networking stack.
    @Getter
    @Setter
    private @NotNull ResyncHandler resyncHandler = NoOpResyncHandler.INSTANCE;
    // Read on the packet thread, installed from the proxy bridge's event loop.
    @Getter
    @Setter
    private volatile @Nullable FlagArbiter flagArbiter;
    // End manager like classes
    public int riptideSpinAttackTicks;
    /** Whether the client is certainly in a riptide spin attack. */
    public boolean riptideSpinCertain;

    private @MonotonicNonNull PacketTracker packetTracker;
    public final @NotNull PacketOrderProcessor packetOrderProcessor = new PacketOrderProcessor(this);
    // Debug stimulus: drops interactions so the resync paths can be exercised.
    public final ac.cult.cultac.utils.latency.InteractionPause interactionPause =
            new ac.cult.cultac.utils.latency.InteractionPause(this);
    public final ac.cult.cultac.utils.latency.BlockActionReconciler blockActionReconciler =
            new ac.cult.cultac.utils.latency.BlockActionReconciler(this);
    public long rawTransactionPing = 0;
    public long lastTransSent = 0;

    @Getter
    private long playerClockAtLeast = System.nanoTime();
    /**
     * The last poll before the first ping or during a protocol switch. A client attached in configuration
     * gets no ping until play traffic starts one, so the timeout runs from no earlier than this.
     */
    private long pingsHeldAt = playerClockAtLeast;

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
    // LocalPlayer's yHeadRot: Player#aiStep copies the yaw, a boat's positionRider adds deltaRotation before
    // clampRotation clamps only the yaw, and ClientPacketListener#handleSetEntityPassengersPacket sets a boat's yaw on
    // a first mount.
    public float headYaw;
    // The head yaw a boat's clampRotation left unclamped this tick, NaN when the head may only follow the yaw.
    public float unclampedHeadYaw = Float.NaN;
    // The head yaws from headArcFrom through headArcLength degrees further that the head may hold instead, NaN when
    // none: those a boat's clampRotation may have pulled the yaw back from after an unseen mouse turn.
    public float headArcFrom = Float.NaN;
    public float headArcLength;
    // The boat yaw at the last tick end or first mount, which the next mouse turn's clampRotation bounds against.
    public float boatYawBeforeTurn = Float.NaN;
    // The yaw the client held before this tick's rotation packet.
    public float yawBeforeLook;
    // The yaw a first boat mount set before the client's next rotation packet, NaN when none did.
    public float mountYaw = Float.NaN;
    public boolean onGround;
    public boolean lastOnGround;
    public boolean isSneaking;
    // The client's last START/STOP_SPRINTING (LocalPlayer#wasSprinting)
    public boolean isSprinting;
    // LocalPlayer#aiStep cleared the sprint flag of a passenger without a STOP_SPRINTING; the next tick that reaches
    // LocalPlayer#sendIsSprintingIfNeeded ends it.
    public boolean sprintClearedAsPassenger;
    public final ClientSprintState sprintState = new ClientSprintState();
    public final ClientSwimState swimState = new ClientSwimState();
    public final ClientAttackStrength attackStrength = new ClientAttackStrength();
    public final ClientCombatAttributes combatAttributes = new ClientCombatAttributes();
    public final VisibilityDebug visibilityDebug = new VisibilityDebug(this);
    public boolean isFlying;
    public boolean canFly;
    public boolean canInstabuild;
    public boolean isInvulnerable;
    public boolean isGliding;
    public boolean isRiptidePose = false;
    public @NotNull SimpleCollisionBox boundingBox;
    public @NotNull Pose pose = Pose.STANDING;
    /** The scale of the current bounding box; vanilla resizes the box only in Entity#refreshDimensions. */
    public float dimensionScale = 1.0F;

    public boolean isInBed = false;
    public final ac.cult.cultac.utils.latency.CompensatedOpenWindow openWindow;
    public boolean hasInventoryOpen = false;
    public long lastOpenedInventory;
    public int food = 20;
    public float flySpeed;
    public final ClientItemUseState clientItemUse = new ClientItemUseState();
    public final ItemUseBurst itemUseBurst;
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
    public final @NotNull KeepSprint keepSprint;
    public final @NotNull PluginChannelManager pluginChannelManager;
    /** The Lunar Client mods the player's server last reported through Apollo; null before any report. */
    public volatile ac.cult.cultac.bridge.protocol.BridgeMessage.@Nullable ClientMods clientMods;

    public final @NotNull PacketEntityReplication packetEntityReplication;
    private final @NotNull GhostBlockMitigator ghostBlockMitigator;
    private final @NotNull ItemUseDesync itemUseDesync;

    public GhostBlockMitigator getGhostBlockMitigator() {
        return ghostBlockMitigator;
    }

    public ItemUseDesync getItemUseDesync() {
        return itemUseDesync;
    }

    public long lastRotated;
    public final @NotNull VehicleData vehicleData = new VehicleData();
    public boolean serverOpenedInventoryThisTick;
    // Whether this tick's movement intersected a nether portal block (see MultiActionsD)
    public boolean intersectedWithNetherPortal;

    @Getter
    private final @NotNull MovementData movementData;

    public int minPlayerAttackSlow = 0;
    public int maxPlayerAttackSlow = 0;
    public @MonotonicNonNull GameMode gamemode;
    /** The mode of the last packet the server sent, which the client only adopts once it acknowledges that packet. */
    public @MonotonicNonNull GameMode serverGamemode;
    public @MonotonicNonNull String dimension;
    public @Nullable String world;
    public @Nullable Vec3 bedPosition;
    public long lastBlockPlaceUseItem = 0;
    public int totalFlyingPacketsSent;
    public final @NotNull AtomicInteger cancelledPackets = new AtomicInteger(0);
    private boolean loggedPacketSpam;
    /**
     * Client tick ends received while an {@link #observer} is set, counted before any handler runs, so a skipped handler
     * cannot shift the count.
     */
    public final @NotNull AtomicLong clientTickEnds = new AtomicLong();

    public final @NotNull ClientTickEndTimeout clientTickEndTimeout = new ClientTickEndTimeout(this);
    /** Follows this player's engine events; null while nothing does. */
    public volatile @Nullable PlayerObserver observer;

    public boolean isDead() {
        return compensatedEntities.getSelf().isDead;
    }

    public boolean isBedrockMovement() {
        return movementPlatform == MovementPlatform.BEDROCK;
    }

    public boolean shouldEnforceMovementSetbacks() {
        return movementPlatform == MovementPlatform.JAVA
                || (bedrockState != null && bedrockState.shouldEnforceSetbacks());
    }

    /** A login or respawn replaced the LocalPlayer, which has not ticked yet. */
    public void onLocalPlayerCreated() {
        packetStateData.localPlayerTickedSinceSpawn = false;
        packetStateData.lastSentTickYaw = 0;
        packetStateData.lastSentTickPitch = 0;
        packetStateData.lastSendPositionYaw = 0;
        packetStateData.lastSendPositionPitch = 0;
        headArcFrom = Float.NaN;
        unclampedHeadYaw = Float.NaN;
        boatYawBeforeTurn = Float.NaN;
    }

    /**
     * The head yaw a client tick ends with: the yaw it sends, or, in a boat whose clampRotation pulled the yaw back,
     * the yaw Player#aiStep copied plus the boat's deltaRotation, from the last yaw when no mouse turn came between, or
     * from within 105 degrees of the boat yaw the mouse turn's clampRotation bounded it to.
     */
    public void tickHeadYaw() {
        float before = yawBeforeLook;
        yawBeforeLook = xRot;
        // LocalPlayer#tick, and with it Player#aiStep, only runs once the client has loaded; before 1.21.4, from login.
        if (!packetStateData.playerLoadedIntoLevel && !getClientVersion().isOlderThan(ClientVersion.V_1_21_4)) return;
        float boatYawBefore = boatYawBeforeTurn;
        headYaw = xRot;
        unclampedHeadYaw = Float.NaN;
        headArcFrom = Float.NaN;
        boatYawBeforeTurn = Float.NaN;
        PacketEntity self = compensatedEntities.getSelf();
        PacketEntity boat = self.getRiding();
        if (boat == null || !boat.isBoat()) return;
        float boatYaw = boat.clientPhysicalYaw;
        boatYawBeforeTurn = boatYaw;
        float turn = boatTurn(boat);
        float yaw = before + turn;
        if (Math.abs(CultMath.wrapDegrees(yaw - boatYaw)) > 105.0F && clampToBoat(yaw, boatYaw) == xRot) {
            unclampedHeadYaw = yaw;
        }
        if (Float.isNaN(boatYawBefore)) return;
        // The head lies more than 105 degrees to the sent yaw's side of the boat, and within 105 of the turned
        // reference, the boat yaw the mouse turn clamped against advanced by deltaRotation.
        float side = CultMath.wrapDegrees(xRot - boatYaw) > 0 ? 1.0F : -1.0F;
        float reference = side * CultMath.wrapDegrees(boatYawBefore + turn - boatYaw);
        float near;
        float far;
        if (reference >= 0) {
            near = 105.0F;
            far = Math.min(180.0F, reference + 105.0F);
        } else if (reference <= -75.0F) {
            near = Math.max(105.0F, reference + 255.0F);
            far = 180.0F;
        } else {
            return;
        }
        if (far <= near) return;
        float edge = xRot + side * (far - 105.0F);
        if (clampToBoat(edge, boatYaw) != xRot) return;
        headArcFrom = xRot + (side > 0 ? near - 105.0F : 105.0F - far);
        headArcLength = far - near;
    }

    /**
     * The yaw a client tick that began at {@code before} sends without a mouse turn: AbstractBoat#positionRider turns a
     * rider by the deltaRotation of the boat it drives, and clampRotation holds the yaw within 105 degrees of the boat.
     * Only valid once the tick's vehicle move is processed, which the client sends after the rider's Rot.
     */
    public float yawWithoutLook(float before) {
        PacketEntity boat = compensatedEntities.getSelf().getRiding();
        if (boat == null || !boat.isBoat()) return before;
        return clampToBoat(before + boatTurn(boat), boat.clientPhysicalYaw);
    }

    /**
     * The yaw a client before 26.3 holds once Entity#rideTick ends, read at its tick end: AbstractBoat#positionRider
     * turns the rider from the yaw its Rot sent by the boat's deltaRotation, and clampRotation holds it within 105
     * degrees of the boat. A boat the player does not drive keeps the deltaRotation the player's last controlled tick
     * of it left, as AbstractBoat#tick only changes deltaRotation on the instance that controls it. NaN when the boat
     * the player drives reports a yaw that is not its turn.
     */
    public float yawAfterRideTick() {
        PacketEntity boat = compensatedEntities.getSelf().getRiding();
        if (boat == null || !boat.isBoat()) return xRot;
        if (!boatYawFollowsTurn()) return Float.NaN;
        float turn = drivesBoat(boat) ? boatData.deltaRotation : boatData.deltaRotationOf(boat);
        return clampToBoat(xRot + turn, boat.clientPhysicalYaw);
    }

    /**
     * Whether a boat the player drives reports the yaw AbstractBoat#controlBoat gives it: the last tick's yaw turned by
     * deltaRotation. The client states that yaw in its vehicle move, so a yaw that differs says nothing about the turn
     * it gave the rider.
     */
    public boolean boatYawFollowsTurn() {
        PacketEntity boat = compensatedEntities.getSelf().getRiding();
        if (boat == null || !boat.isBoat() || !drivesBoat(boat)) return true;
        return boat.clientPhysicalYaw == boatYawBeforeTurn + boatData.deltaRotation;
    }

    // AbstractBoat#deltaRotation only turns a boat its controlling passenger drives.
    private float boatTurn(PacketEntity boat) {
        return drivesBoat(boat) ? boatData.deltaRotation : 0;
    }

    private boolean drivesBoat(PacketEntity boat) {
        return !boat.passengers.isEmpty() && boat.passengers.get(0) == compensatedEntities.getSelf();
    }

    // AbstractBoat#clampRotation's yaw for a player passenger, in its float operations, which need not return an
    // unclamped yaw unchanged.
    private static float clampToBoat(float yaw, float boatYaw) {
        return BoatRiderYaw.clampRotation(yaw, boatYaw);
    }

    public void onPacketCancel() {
        if (spamThreshold != -1 && cancelledPackets.incrementAndGet() > spamThreshold) {
            if (CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
                if (!loggedPacketSpam) {
                    loggedPacketSpam = true;
                    LogUtil.info("Not disconnecting " + getName() + " for spamming invalid packets in observe-only");
                }
                cancelledPackets.set(0);
                return;
            }
            LogUtil.info("Disconnecting " + getName()
                    + " for spamming invalid packets, packets cancelled within a second " + cancelledPackets);
            disconnect(MessageUtil.miniMessage(MessageUtil.replacePlaceholders(
                    this, CultAPI.INSTANCE.getConfigManager().getDisconnectClosed())));
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
    @Setter
    private boolean disabled = false;

    public boolean isDisabled() {
        // An unresolved level comes only from server-supplied registry data; its empty model would false flag.
        return disabled || (compensatedWorld != null && compensatedWorld.isLevelUnresolved());
    }

    @Getter
    @Setter
    private boolean experimentalChecks;

    @Getter
    @Setter
    private boolean exemptElytra;

    @Getter
    @Setter
    private boolean forceStuckSpeed = true;

    @Getter
    @Setter
    private boolean forceSlowMovement = true;

    private static final String DISABLED_PERMISSION = "cult.disabled";
    private static final int RECENT_WIRE_IDS = 128;
    /** The kinds of ping CultAC writes: at the end of a server tick, after and before a packet it proves. */
    public static final int PING_END_OF_TICK = 1;

    public static final int PING_TRAILING = 2;
    public static final int PING_LEADING = 3;

    public void updateDisabled() {
        if (platformPlayer == null) return;
        boolean current = platformPlayer.hasPermission(DISABLED_PERMISSION, false);
        if (current != disabled) {
            disabled = current;
        }
    }

    public CultPlayer(@NotNull User user) {
        this(user, MovementPlatform.JAVA, null);
    }

    public CultPlayer(
            @NotNull User user, @NotNull MovementPlatform movementPlatform, @Nullable BedrockPlayerState bedrockState) {
        this.user = Objects.requireNonNull(user, "user");
        this.playerUUID = Objects.requireNonNull(user.getUUID(), "uuid");
        this.movementPlatform = movementPlatform;
        this.bedrockState = bedrockState;
        this.platformPlayer = user.getPlayer();
        this.timeJoined = System.currentTimeMillis();
        onReload();

        this.openWindow = new ac.cult.cultac.utils.latency.CompensatedOpenWindow(this);
        this.nettyScheduler = new NettyScheduler(this);
        this.packetEntityReplication = new PacketEntityReplication(this);
        this.ghostBlockMitigator = new GhostBlockMitigator(this);
        this.itemUseDesync = new ItemUseDesync(this);
        this.itemUseBurst = new ItemUseBurst(this);
        this.movementData = new MovementData(this);
        this.knockbackHandler = new KnockbackHandler(this);
        this.explosionHandler = new ExplosionHandler(this);
        this.keepSprint = new KeepSprint(this);
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
        if (platformPlayer != null && user.getCultConnection().platform() != null) {
            var binding = user.getCultConnection().platform().playerBinding();
            if (binding != null && binding.isCurrent()) binding.initialize(this);
        }
        // reload last: pulls config, per-check permissions, and the punishment manager
        reload();
    }

    public void onRemove() {
        Runnable cleanup = () -> user.getCultConnection().runInModel(() -> {
            nettyScheduler.removeScheduler();
            compensatedWorld.clearChunks();
            FairReach fairReach = checkManager.getListener(FairReach.class);
            if (fairReach != null) {
                fairReach.onPlayerQuit();
            }
        });
        // Disposal runs after packet admission has sealed during plugin shutdown.
        // It must keep owner confinement without submitting another packet task.
        var owner = user.getPacketExecutor();
        if (owner.inEventLoop() || owner.isTerminated()) cleanup.run();
        else {
            try {
                owner.execute(cleanup);
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                owner.terminationFuture().addListener(ignored -> cleanup.run());
            }
        }
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
        if (!isBedrockMovement() && skipped > 0 && keepAliveProcessor.lastKeepAlivePing > 0) {
            checkManager.getCheck(TransactionOrder.class).skipped(skipped);
        }

        SentTransaction acknowledged = acknowledgeThrough(id);
        if (!isBedrockMovement() && getSetbackTeleportUtil().hasWithheldJavaTeleport()) {
            withheld("answered a ping sent after a teleport it did not answer");
        }

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
            transactionIdsSent.remove(current.id());
            lastTransactionReceived.set(current.transaction());
            rawTransactionPing = System.nanoTime() - current.sentAt();
            playerClockAtLeast = current.sentAt();
            if (usesModernJavaItemUse()) itemUseBurst.acknowledge(current.transaction());
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
        sendTransaction(async, PING_LEADING, 0);
    }

    /** A ping written after the packets it proves, outside their group; its transaction, or -1 when none is sent. */
    public int sendTrailingTransaction() {
        return sendTransaction(false, PING_TRAILING, 0);
    }

    /** Ends server tick {@code serverTick}, after the tick's packets; see {@link #endServerTick}. */
    public void sendEndOfTickTransaction(int serverTick) {
        endServerTick(PING_END_OF_TICK, serverTick);
    }

    /** Ends an estimated server tick, which has no tick number; see {@link #endServerTick}. */
    public void sendEndOfTickTransaction() {
        endServerTick(PING_LEADING, 0);
    }

    /**
     * Proves the tick's packets at their outbound position. ClientPacketListener#handleBundlePacket handles a whole
     * bundle in one task and handlePing answers inside it, so when every packet written since the last CultAC ping
     * shares its bundle, that ping proves the tick the way a ping after them would; the tick then ends on it and
     * writes none. Geyser hands a Bedrock client no bundle, so any packet after the ping, such as a movement wave,
     * needs the tick's own ping.
     */
    private void endServerTick(int kind, int serverTick) {
        user.executeAfterWrites(() -> {
            PlayerObserver observer = this.observer;
            if (kind == PING_END_OF_TICK && observer != null) observer.serverTickEnded(serverTick);
            if (user.getCultConnection().provingPing() <= 0) sendTransaction(false, kind, serverTick);
        });
    }

    private int sendTransaction(boolean async, int kind, int serverTick) {
        if (async) {
            // The model owner can run ahead of I/O's queued packets. Allocate
            // the proof at its outbound position so acknowledgements stay ordered.
            user.executeAfterWrites(() -> sendTransaction(false, kind, serverTick));
            return -1;
        }

        if (!canSendTransactionNow()) {
            return -1;
        }

        lastTransSent = System.currentTimeMillis();
        TrackedTransaction transaction = createTrackedTransaction(kind, serverTick);
        user.write(transaction.packet());
        return transaction.transaction();
    }

    /**
     * ClientPacketListener#handleConfigurationStart clears the client level, and the clientbound
     * protocol has no play packets until the next login. Read on the connection owner.
     */
    public boolean canSendPlayPackets() {
        return user.getEncoderState() == ConnectionPhase.PLAY;
    }

    private boolean canSendTransactionNow() {
        var phase = user.getEncoderState();
        return user.getPlayer() != null
                && !protocolSwitchPending()
                && (phase == ConnectionPhase.PLAY || phase == ConnectionPhase.CONFIGURATION);
    }

    /** A ping the client reads with an unacknowledged terminal packet hits ProtocolSwapHandler's empty pipeline. */
    private boolean protocolSwitchPending() {
        return user.getEncoderState() != user.getConnectionState();
    }

    public int sendTransactionAndGetId() {
        // Only use this on the player's outbound thread. Async transactions are still sent through
        // sendTransaction(true), where the ID is deliberately allocated inside the event loop task.
        if (!canSendTransactionNow()) {
            return -1;
        }

        lastTransSent = System.currentTimeMillis();
        TrackedTransaction transaction = createTrackedTransaction(PING_LEADING, 0);
        user.write(transaction.packet());
        return transaction.transaction();
    }

    /**
     * The transaction that proves an ordinary entity, block or chunk update, or -1 when none can be sent. The
     * encoder keeps one flush bundle open from the first such update to the next Netty flush and starts it with
     * a ping. ClientPacketListener#handleBundlePacket handles the whole bundle in one task and handlePing answers
     * inside it, so the update shares the latest CultAC ping already in that bundle. Without one, a ping is
     * written directly before the update and opens the bundle, or makes the host bundle it is in the flush bundle.
     * A Bedrock player's flush group shares its ping the same way, to keep one ping per flush, though Geyser does
     * not hand the client the group as one task. Standalone level/player packets require a trailing proof instead.
     */
    public int appendFlushProofTransaction(PacketSendEvent<?> event) {
        // A listener may already have bracketed this packet. A new leading ping would be allocated later
        // but emitted before those older proofs, violating transaction order on the wire.
        if (event.receiptProof() > 0) return event.receiptProof();
        var connection = user.getCultConnection();
        if (!connection.isPreparingWrites() || PacketBundlePolicy.requiresStandalone(event.getPacketType())) {
            TrackedTransaction proof = appendTrailingProofTransaction(event);
            return proof == null ? -1 : proof.transaction();
        }
        int shared = event.flushPing();
        if (shared > 0) return shared;
        TrackedTransaction lead = createTrackedTransactionPacketForDeferredSend(PING_LEADING);
        if (lead == null) return -1;
        event.leadWith(lead.packet(), lead.transaction());
        event.getTasksAfterSend().add(() -> markTrackedTransactionPacketSent(lead));
        return lead.transaction();
    }

    /**
     * As {@link #appendFlushProofTransaction}, but only a ping the client answers before it handles the event's
     * packet, in the same bundle task; -1 when the packet cannot have one.
     */
    public int appendLeadingFlushProofTransaction(PacketSendEvent<?> event) {
        int shared = event.flushPing();
        if (shared > 0) return shared;
        if (event.receiptProof() > 0
                || !user.getCultConnection().isPreparingWrites()
                || PacketBundlePolicy.requiresStandalone(event.getPacketType())) return -1;
        return appendFlushProofTransaction(event);
    }

    public long nextImpulseSequence() {
        return impulseSequence.incrementAndGet();
    }

    @Nullable
    public TrackedTransaction createTrackedTransactionPacketForBundle() {
        return createTrackedTransactionPacketForDeferredSend();
    }

    @Nullable
    public TrackedTransaction appendTrailingProofTransaction(PacketSendEvent<?> event) {
        return appendTrailingProofTransaction(event, List.of());
    }

    /**
     * Writes {@code packetsBeforeProof} and then a tracked ping directly after the event's packet. The
     * encoder bundles a packet that expands to several frames, and ClientPacketListener#handleBundlePacket
     * handles the sub-packets in order in one task, so the pong proves the packet was applied before the
     * client's next tick. In a Java flush bundle that already holds a CultAC ping, that ping is handled in the
     * same task and proves the packet without another. Standalone packets instead retain a FIFO trailing proof,
     * without claiming bundle atomicity or application before the next client tick. Protocol transitions cannot
     * carry an old-phase proof. Returns null, writing nothing, when no transaction can be sent.
     */
    @Nullable
    public TrackedTransaction appendTrailingProofTransaction(
            PacketSendEvent<?> event, List<CultWrite> packetsBeforeProof) {
        int shared = !isBedrockMovement() && PacketBundlePolicy.requiresStandalone(event.getPacketType())
                ? 0
                : event.flushPing();
        if (shared > 0) {
            event.getWritesAfterSend().addAll(packetsBeforeProof);
            return new TrackedTransaction(shared, null);
        }
        return writeTrailingProofTransaction(event, packetsBeforeProof);
    }

    /**
     * Writes a tracked ping of its own directly after the event's packet, for a packet whose client handler sends a
     * response, such as ClientPacketListener#handleRotatePlayer's Rot. A ping earlier in the bundle is answered
     * before that handler runs, so only a ping after the packet orders its pong after the response. Returns null,
     * writing nothing, when no transaction can be sent.
     */
    @Nullable
    public TrackedTransaction appendResponseProofTransaction(PacketSendEvent<?> event) {
        return writeTrailingProofTransaction(event, List.of());
    }

    @Nullable
    private TrackedTransaction writeTrailingProofTransaction(
            PacketSendEvent<?> event, List<CultWrite> packetsBeforeProof) {
        if (ConnectionLifecycle.handles(event.getPacketType())) return null;
        TrackedTransaction proof = createTrackedTransactionPacketForDeferredSend();
        if (proof == null) {
            return null;
        }
        event.getWritesAfterSend().addAll(packetsBeforeProof);
        event.getWritesAfterSend().add(proof.packet());
        event.getTasksAfterSend().add(() -> markTrackedTransactionPacketSent(proof));
        return proof;
    }

    @Nullable
    public TrackedTransaction createTrackedTransactionPacketForDeferredSend() {
        return createTrackedTransactionPacketForDeferredSend(PING_TRAILING);
    }

    /** As {@link #createTrackedTransactionPacketForDeferredSend()}, for a ping of {@code kind}, a {@code PING_} value. */
    @Nullable
    public TrackedTransaction createTrackedTransactionPacketForDeferredSend(int kind) {
        // Use this when the caller will write the ping later in the same outbound packet group.
        // The caller must mark the packet sent after the group is flushed, because the generated
        // ping may not pass through Cult's normal outbound ping listener.
        if (!canSendTransactionNow()) {
            return null;
        }

        lastTransSent = System.currentTimeMillis();
        return createTrackedTransaction(kind, 0);
    }

    private synchronized TrackedTransaction createTrackedTransaction(int kind, int serverTick) {
        int transactionID = nextTransactionId();
        CultWrite packet = new CultWrite(new ClientboundPing(transactionID), false);
        TrackedTransaction transaction = new TrackedTransaction(lastTransactionSent.incrementAndGet(), packet);
        transactionsPendingSend.put(transactionID, transaction);
        if (recordProvenance) {
            int slot = Math.floorMod(transaction.transaction(), RECENT_WIRE_IDS);
            recentTransactions[slot] = transaction.transaction();
            recentWireIds[slot] = transactionID;
            recentKinds[slot] = (byte) kind;
            recentTicks[slot] = serverTick;
        }
        return transaction;
    }

    /** Starts or stops recording each transaction's provenance for {@link #pingProvenance} and {@link #wireIdOf}. */
    public synchronized void recordProvenance(boolean record) {
        if (record && recentWireIds == null) {
            recentWireIds = new int[RECENT_WIRE_IDS];
            recentTransactions = new int[RECENT_WIRE_IDS];
            recentKinds = new byte[RECENT_WIRE_IDS];
            recentTicks = new int[RECENT_WIRE_IDS];
        }
        recordProvenance = record;
    }

    /**
     * The ping of wire id {@code wireId} as CultAC wrote it: its {@code PING_} kind in the low byte and, for an
     * end-of-tick ping, its server tick above; 0 when it is not one of the last {@link #RECENT_WIRE_IDS} CultAC wrote,
     * or provenance is not recorded.
     */
    public synchronized long pingProvenance(int wireId) {
        if (!recordProvenance) return 0;
        int newest = lastTransactionSent.get();
        for (int back = 0; back < RECENT_WIRE_IDS; back++) {
            int slot = Math.floorMod(newest - back, RECENT_WIRE_IDS);
            if (recentKinds[slot] != 0 && recentWireIds[slot] == wireId) {
                return (long) recentTicks[slot] << 8 | recentKinds[slot];
            }
        }
        return 0;
    }

    /**
     * The ping id {@code transaction} went out with; null when it is not one of the last {@link #RECENT_WIRE_IDS}, or
     * provenance is not recorded.
     */
    public synchronized @Nullable Integer wireIdOf(int transaction) {
        if (!recordProvenance) return null;
        int slot = Math.floorMod(transaction, RECENT_WIRE_IDS);
        return transaction > 0 && recentTransactions[slot] == transaction ? recentWireIds[slot] : null;
    }

    public void markTrackedTransactionPacketSent(@Nullable TrackedTransaction transaction) {
        if (transaction == null || transaction.packet() == null) {
            return;
        }

        // Replacement bundles created inside a packet-send handler are flattened
        // after handler dispatch, so the generated ping sub-packet may not pass
        // through the outbound ping listener. Mirror the send-side tracking here.
        if (markTransactionPacketSent(transaction.packet())) {
            packetStateData.lastServerTransWasValid = true;
        }
    }

    public boolean markTransactionPacketSent(CultWrite packet) {
        return markTransactionPacketSent(((ClientboundPing) packet.packet()).id(), System.currentTimeMillis());
    }

    public boolean markTransactionPacketSent(int id, long timestamp) {
        return markTrackedPingSent(id, timestamp) > 0;
    }

    /** As {@link #markTransactionPacketSent(int, long)}; the transaction of CultAC's ping {@code id}, or -1. */
    public synchronized int markTrackedPingSent(int id, long timestamp) {
        TrackedTransaction transaction = transactionsPendingSend.remove(id);
        if (transaction == null) {
            return -1;
        }

        if (isBedrockMovement()) {
            bedrockTransactions().add(new BedrockTransaction(transaction.transaction(), transaction.id()));
        } else {
            transactionsSent.add(new SentTransaction(transaction.transaction(), transaction.id(), System.nanoTime()));
        }
        transactionIdsSent.add(transaction.id());
        if (!TransactionChannels.SEND.isEmpty()) {
            TransactionChannels.SEND.fire(
                    ac.cult.cultac.runtime.RuntimeEnvironment.outwardUser(this), transaction.id(), timestamp);
        }
        return transaction.transaction();
    }

    /** Records a ping CultAC did not write, so no CultAC ping takes its id while it is in flight. */
    public synchronized void foreignPingSent(int id) {
        foreignPings.add(id);
    }

    public synchronized void foreignPongReceived(int id) {
        foreignPings.remove(id);
    }

    /** Whether {@code id} is a ping CultAC created whose response has not arrived yet. */
    public synchronized boolean awaitsTransactionResponse(int id) {
        if (transactionsPendingSend.containsKey(id)) return true;
        for (SentTransaction sent : transactionsSent) {
            if (sent.id() == id) return true;
        }
        return false;
    }

    private int nextTransactionId() {
        return nextTransactionId(nextWireWord);
    }

    int nextTransactionId(IntSupplier random) {
        int id;
        boolean used;
        do {
            id = nextWireId(random);
            used = transactionIdsSent.contains(id);
            used |= transactionsPendingSend.containsKey(id);
            used |= foreignPings.contains(id);
        } while (used);
        return id;
    }

    /** A ping id in [MIN_VALUE, -32769]; backend anticheats use non-negative ids or the signed-short range. */
    static int nextWireId(IntSupplier random) {
        int id;
        do {
            id = random.getAsInt() | Integer.MIN_VALUE;
        } while (id >= Short.MIN_VALUE);
        return id;
    }

    /** A generator of its own per player, so players never wait on each other's ping ids. */
    private static SecureRandom wireIdSource() {
        try {
            return SecureRandom.getInstance("DRBG");
        } catch (NoSuchAlgorithmException unavailable) {
            return new SecureRandom();
        }
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
        transactionIdsSent.add(transaction.id());
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
        return pose.eyeHeight * dimensionScale;
    }

    public float getBukkitHeight() {
        return pose.height * dimensionScale;
    }

    public void refreshPlayerPose() {
        pose = determineCurrentPoseLikeVanilla();
        dimensionScale = getScale();

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
        return updatedPose(swimState.swimming(), x, y, z);
    }

    /** Player#updatePlayerPose with the player at {@code (x, y, z)} and Entity#isSwimming {@code swimming}. */
    public Pose updatedPose(boolean swimming, double x, double y, double z) {
        Pose desiredPose = getDesiredPoseLikeVanilla(swimming);
        if (compensatedWorld == null || compensatedEntities == null) {
            return desiredPose;
        }

        // The vanilla local player only evaluates pose fallback when it can fit the
        // swimming box, then falls back from the desired pose to crouching and finally
        // to swimming based on exact collision tests at the current position.
        if (!canPlayerFitWithinBlocksAndEntitiesWhen(Pose.SWIMMING, x, y, z)) {
            return desiredPose;
        }

        if (gamemode == GameMode.SPECTATOR
                || compensatedEntities.getSelf().inVehicle()
                || canPlayerFitWithinBlocksAndEntitiesWhen(desiredPose, x, y, z)) {
            return desiredPose;
        }

        if (canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING, x, y, z)) {
            return Pose.CROUCHING;
        }

        return Pose.SWIMMING;
    }

    private Pose getDesiredPoseLikeVanilla(boolean swimming) {
        if (isInBed) {
            return Pose.SLEEPING;
        }
        // Player#isSwimming
        if (swimming && !isFlying && gamemode != GameMode.SPECTATOR) {
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

    /** Player#canPlayerFitWithinBlocksAndEntitiesWhen with the player at {@code (x, y, z)}. */
    public boolean canPlayerFitWithinBlocksAndEntitiesWhen(Pose newPose, double x, double y, double z) {
        SimpleCollisionBox box = GetBoundingBox.getBoundingBoxFromPosAndSize(
                        x, y, z, newPose.width * getScale(), newPose.height * getScale())
                .expand(-1.0E-7D);
        return Collisions.isEmpty(this, box, y) && hasNoEntityCollision(box);
    }

    /**
     * Whether the {@code newPose} box at the position may fit within blocks and entities: false when blocks or an
     * entity at each of its possible positions overlap it.
     */
    public boolean mayPlayerFitWithinBlocksAndEntitiesWhen(Pose newPose, double x, double y, double z) {
        SimpleCollisionBox box = GetBoundingBox.getBoundingBoxFromPosAndSize(
                        x, y, z, newPose.width * getScale(), newPose.height * getScale())
                .expand(-1.0E-7D);
        if (!Collisions.isEmpty(this, box, y)) return false;
        PacketEntity riding = compensatedEntities.getSelf().getRiding();
        SimpleCollisionBox entityBox = new SimpleCollisionBox();
        for (PacketEntity entity : compensatedEntities.poseCollisionCandidates()) {
            if (canCollideWithForPoseCheck(riding, entity) && overlapsAtEachPossiblePosition(entity, box, entityBox)) {
                return false;
            }
        }
        return true;
    }

    private static boolean overlapsAtEachPossiblePosition(
            PacketEntity entity, SimpleCollisionBox playerBox, SimpleCollisionBox entityBox) {
        for (int i = 0, count = entity.possibleMovementCollisionBoxCandidateCount(); i < count; i++) {
            if (!entity.possibleMovementCollisionBoxCandidate(i, entityBox).isIntersected(playerBox)) return false;
        }
        return true;
    }

    private boolean hasNoEntityCollision(SimpleCollisionBox playerBox) {
        PacketEntity riding = compensatedEntities.getSelf().getRiding();
        SimpleCollisionBox entityBox = new SimpleCollisionBox();
        SimpleCollisionBox scratch = new SimpleCollisionBox();
        for (PacketEntity entity : compensatedEntities.poseCollisionCandidates()) {
            if (!canCollideWithForPoseCheck(riding, entity)) {
                continue;
            }

            if (entity.getPossibleMovementCollisionBoxes(entityBox, scratch).isIntersected(playerBox)
                    && overlapsAtAPossiblePosition(entity, playerBox, entityBox)) {
                return false;
            }
        }
        return true;
    }

    private static boolean overlapsAtAPossiblePosition(
            PacketEntity entity, SimpleCollisionBox playerBox, SimpleCollisionBox entityBox) {
        for (int i = 0, count = entity.possibleMovementCollisionBoxCandidateCount(); i < count; i++) {
            if (entity.possibleMovementCollisionBoxCandidate(i, entityBox).isIntersected(playerBox)) return true;
        }
        return false;
    }

    /** The box at each possible position of each entity Entity#collide stops this player against. */
    public List<SimpleCollisionBox> entityCollisionBoxes() {
        PacketEntity riding = compensatedEntities.getSelf().getRiding();
        List<SimpleCollisionBox> boxes = new ArrayList<>();
        for (PacketEntity entity : compensatedEntities.poseCollisionCandidates()) {
            if (canCollideWithForPoseCheck(riding, entity)) {
                boxes.addAll(entity.getPossibleMovementCollisionBoxCandidates());
            }
        }
        return boxes;
    }

    private boolean canCollideWithForPoseCheck(@Nullable PacketEntity riding, PacketEntity entity) {
        if (entity == null
                || !(entity.isBoat()
                        || entity.isMinecart()
                        || entity.type == EntityTypeIds.SHULKER
                        || entity.isHappyGhast())) {
            return false;
        }

        if (entity.isDead || entity.getEntityId() == entityID) {
            return false;
        }

        if (compensatedEntities.isUntrackedRider(entity)) {
            return false;
        }

        return riding == null || !(riding == entity || riding.hasPassenger(entity) || entity.hasPassenger(riding));
    }

    public void closeInventorySafely() {
        if (platformPlayer != null) {
            platformPlayer.closeInventory();
        }
    }

    public void timedOut() {
        withheld("no ping answered within " + maxTransactionTime + " s");
    }

    /** A client withholding a packet a vanilla client always sends is treated as one withholding its pongs. */
    public void withheld(String what) {
        // Not failsOpen(): the client decides whether to answer a ping, so withholding them must not buy an exemption.
        if (CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
            LogUtil.warn("Not inspecting " + getName() + " any more; " + what);
            CultAPI.INSTANCE.getPlayerDataManager().exemptUser(user);
            return;
        }
        LogUtil.warn(getName() + " " + what);
        disconnect(MessageUtil.miniMessage(MessageUtil.replacePlaceholders(
                this, CultAPI.INSTANCE.getConfigManager().getDisconnectTimeout())));
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
            user.write(new CultWrite(MessageUtil.disconnectPacket(reason), false));
        } catch (Exception ignored) { // The player may be in the wrong state to receive a disconnect packet
            LogUtil.warn("Failed to send disconnect packet to disconnect "
                    + user.getProfile().getName() + "! Disconnecting anyways.");
        }
        user.closeConnection();
        if (platformPlayer != null) {
            CultAPI.INSTANCE
                    .getScheduler()
                    .getEntityScheduler()
                    .execute(
                            platformPlayer,
                            CultAPI.INSTANCE.getGrimPlugin(),
                            () -> platformPlayer.kickPlayer(textReason),
                            null,
                            1);
        }
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
        long now = System.nanoTime();
        // Pings flow in configuration as in play (ConfigurationProtocols registers ping and pong), so the
        // timeout runs there too.
        if (protocolSwitchPending() || lastTransSent == 0) {
            pingsHeldAt = now;
        } else if (now - Math.max(getPlayerClockAtLeast(), pingsHeldAt) > maxTransactionTime * 1e9) {
            timedOut();
        }

        if (!CultAPI.INSTANCE.getPlayerDataManager().shouldCheck(user)) {
            CultAPI.INSTANCE.getPlayerDataManager().remove(user);
        }

        if (packetTracker == null && ViaVersionUtil.isAvailable()) {
            UserConnection connection = Via.getManager().getConnectionManager().getConnectedClient(playerUUID);
            packetTracker = connection != null ? connection.getPacketTracker() : null;
        }

        if (this.platformPlayer == null && user.getPlayer() != null) {
            this.platformPlayer = user.getPlayer();
            updatePermissions();
        }

        // Datastore session heartbeat — throttled internally to once per
        // `database.session.heartbeat-interval-ms`, so this runs every tick
        // but only emits a row upsert every N seconds. Bounds how stale
        // last_activity_epoch_ms can be when the server crashes.
        CultAPI.INSTANCE
                .getDataStoreLifecycle()
                .sessionTracker()
                .pollHeartbeat(playerUUID, user.getConnectionId(), System.currentTimeMillis());
    }

    public boolean noModifyPacketPermission = false;
    public boolean noSetbackPermission = false;
    public boolean immunePermission = false;
    public boolean chatBypass = false;
    public boolean healthPermission = false;

    // TODO: Create a configurable timer for this
    @Override
    public void updatePermissions() {
        if (platformPlayer == null) return;
        try {
            boolean noModifyPacket = platformPlayer.hasPermission("cult.nomodifypacket");
            boolean noSetback = platformPlayer.hasPermission("cult.nosetback");
            boolean immune = platformPlayer.hasPermission("cult.immune");
            boolean showHealth = platformPlayer.hasPermission("cult.showhealth");
            boolean bypassChat = platformPlayer.hasPermission("cult.chatbypass");
            boolean cultDisabled = platformPlayer.hasPermission(DISABLED_PERMISSION, false);

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
    private boolean observeOnly = CultAPI.INSTANCE.getConfigManager().isObserveOnly();
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
        spoofHealth = config.getBooleanElse("cult.networking.spoof-health", false);
    }

    // TODO: Create a configurable timer for this
    @Override
    public final void reload(ConfigManager config) {
        updatePermissions();
        featureManager.onReload(config);
        debugPacketCancel = config.getBooleanElse("debug-packet-cancel", false);
        maxTransactionTime = CultMath.clamp(config.getIntElse("max-transaction-time", 60), 1, 180);
        boolean nowObserveOnly = CultAPI.INSTANCE.getConfigManager().isObserveOnly();
        if (nowObserveOnly != observeOnly) {
            observeOnly = nowObserveOnly;
            if (nowObserveOnly) getSetbackTeleportUtil().observeOnlyStarted();
            else {
                // Violations counted while nothing acted would otherwise set back on the next flag.
                for (AbstractCheck check : getChecks()) {
                    if (check instanceof Check c) c.violations = 0;
                }
            }
        }
        onReload();
        // reload all checks
        for (AbstractCheck value : getChecks()) value.reload();
        itemUseDesync.reload(config);
        // reload punishment manager
        punishmentManager.reload(config);
    }

    @Override
    public void reload() {
        reload(CultAPI.INSTANCE.getConfigManager().getConfig());
    }

    public double getMovementThreshold() {
        // LocalPlayer reports coordinates only after squared displacement exceeds
        // (2.0E-4)^2; a status/rotation packet is not zero movement.
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
    //     - This code runs after the prediction engine to prevent a false when immediately switching back to 1.9-like
    // movements
    //     - 3 ticks is a magic value, but it should buffer out incorrect predictions somewhat.
    // 2. The player is in a vehicle
    public boolean isTickingReliablyFor(int ticks) {
        if (compensatedEntities.getSelf().inVehicle()) {
            return true;
        }
        return !checkManager.getSimulationProcessor().getLastTickSkip().hasOccurredSince(ticks);
    }

    @Contract(pure = true)
    public boolean usesModernJavaItemUse() {
        return !isBedrockMovement();
    }

    public boolean isClientUsingItem() {
        return usesModernJavaItemUse()
                ? clientItemUse.definitelyUsing() && clientItemUse.handKnown()
                : packetStateData.isSlowedByUsingItem();
    }

    public ac.cult.cultac.protocol.value.Hand getClientItemUseHand() {
        return usesModernJavaItemUse() ? clientItemUse.hand() : packetStateData.itemInUseHand;
    }

    public boolean canThePlayerBeCloseToZeroMovement(int ticks) {
        return checkManager.getSimulationProcessor().getLastTickSkip().hasOccurredSince(ticks);
    }

    public ac.cult.cultac.utils.latency.ClientWorldRegistries getWorldRegistries() {
        if (registryState == null) registryState = new ac.cult.cultac.utils.latency.ClientComponentRegistries();
        return registryState.worldRegistries(user.getCultConnection().platform()::initialWorldData);
    }

    public CompensatedInventory getInventory() {
        return checkManager.getInventory();
    }

    public List<Double> getPossibleEyeHeights() { // We don't return sleeping eye height
        double scale = dimensionScale;
        return Arrays.asList(0.4 * scale, 1.27 * scale, 1.62 * scale);
    }

    @Override
    public PacketWorld getPacketWorld() {
        final CompensatedWorld world = compensatedWorld;
        return new PacketWorld() {
            @Override
            public int getBlockStateId(int x, int y, int z) {
                return world.getBlockStateIdAt(x, y, z);
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
        return platformPlayer == null ? -1 : platformPlayer.getPing();
    }

    @Override
    public int getLastTransactionReceived() {
        return lastTransactionReceived.get();
    }

    @Override
    public int getLastTransactionSent() {
        return lastTransactionSent.get();
    }

    /** Send time of the latest acknowledged transaction: a lower bound on the client's clock. */
    public long playerClockAtLeast() {
        return playerClockAtLeast;
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

    /** Until the client provably handles new abilities, only the values that refuse item-use starts are certain. */
    public void abilitiesSent(boolean instabuild, boolean invulnerable) {
        canInstabuild &= instabuild;
        isInvulnerable &= invulnerable;
    }

    public void abilitiesHandled(boolean instabuild, boolean invulnerable) {
        canInstabuild = instabuild;
        isInvulnerable = invulnerable;
    }

    /**
     * Runs {@code task} at a trailing proof written after the event's packet, or at the ping sent after it when no
     * proof can be written. Returns whether the proof shares the packet's bundle: ClientPacketListener#handleBundlePacket
     * then handles both in one task, and the client is never in the packet's state before the proof's pong.
     */
    public boolean runAtTrailingProof(PacketSendEvent<?> event, Runnable task) {
        TrackedTransaction proof = appendTrailingProofTransaction(event);
        if (proof == null) {
            latencyUtils.addRealTimeTaskNext(task);
            event.getTasksAfterSend().add(this::sendTransaction);
            return false;
        }
        latencyUtils.addRealTimeTask(proof.transaction(), task);
        if (PacketBundlePolicy.requiresStandalone(event.getPacketType())
                || !MovementProfiles.forPlayer(this).shouldUseBundledPacketProof(this)) return false;
        if (!event.isInsideBundle()) event.bundle();
        return true;
    }

    @Override
    public void runSafely(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable");
        this.user.execute(runnable);
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
        var check = checkManager.getCheck(ac.cult.cultac.checks.impl.misc.ClientBrand.class);
        return check == null ? "Unknown" : check.getBrand();
    }

    private volatile ClientVersion resolvedClientVersion;

    public ac.cult.cultac.protocol.ProtocolVersion getObservedProtocol() {
        return user.getObservedProtocol();
    }

    @org.jetbrains.annotations.Nullable
    public ac.cult.cultac.protocol.ProtocolVersion getClientProtocol() {
        return user.getCultConnection().getClientProtocol();
    }

    public ClientVersion getClientVersion() {
        ClientVersion resolved = resolvedClientVersion;
        return resolved != null ? resolved : resolveClientVersion();
    }

    private ClientVersion resolveClientVersion() {
        ClientVersion resolved;
        // Packet decoding uses the observed protocol. Physics uses the original
        // client identity, even when Via has translated the stream before Cult.
        var clientProtocol = user.getCultConnection().getClientProtocol();
        if (clientProtocol != null) {
            resolved = ClientVersion.fromProtocolVersion(clientProtocol.protocol());
            resolvedClientVersion = resolved;
            return resolved;
        }
        // Offline fixtures and pre-authentication callers may have no identity.
        // Never cache this fallback or resolve a different session by account UUID.
        return ClientVersion.fromProtocolVersion(ProtocolVersion.V26_3.protocol());
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

    /**
     * Whether the client's sprint flag is set. LocalPlayer#aiStep clears it for a passenger whose vehicle cannot
     * sprint or that it does not control, and LocalPlayer#sendChanges sends no STOP_SPRINTING for a vehicle it does
     * not control, so {@link #isSprinting} keeps the last START_SPRINTING. A tick on foot or on a camel the player
     * controls ends in LocalPlayer#sendIsSprintingIfNeeded, after which the flag is the last command sent.
     */
    public boolean isClientSprinting() {
        return isSprinting && !sprintClearedAsPassenger;
    }

    /**
     * A client tick mounted on {@code vehicle}. Every other vehicle ends the tick with the flag cleared (only
     * Camel#canSprint); a camel the player controls ends it in LocalPlayer#sendIsSprintingIfNeeded. Control follows
     * the passenger list the client has applied.
     */
    public void onMountedClientTick(PacketEntity vehicle) {
        sprintClearedAsPassenger = !EntityTypeUtil.isCamelFamily(vehicle.type)
                || !compensatedEntities.vehicles.mayLocalPlayerControl(vehicle);
    }

    /** A client tick on foot: LocalPlayer#sendPosition ends it in LocalPlayer#sendIsSprintingIfNeeded. */
    public void onFootClientTick() {
        sprintClearedAsPassenger = false;
    }

    @Override
    public void sendMessage(String message) {
        if (platformPlayer != null) {
            platformPlayer.sendMessage(message);
        }
    }

    public void sendMessage(Component message) {
        if (platformPlayer != null) {
            platformPlayer.sendMessage(message);
        }
    }

    @Override
    public boolean hasPermission(String s) {
        return platformPlayer != null && platformPlayer.hasPermission(s);
    }

    public boolean hasPermission(String s, boolean defaultIfUnset) {
        return platformPlayer != null && platformPlayer.hasPermission(s, defaultIfUnset);
    }

    // The player's own shared-flags metadata entry as most recently sent by the server.
    public volatile ac.cult.cultac.network.packet.EntityMetadata.Entry serverSentSelfSharedFlags;

    /**
     * Resends the server's shared flags for this player. The client keeps its locally predicted
     * fall-flying flag until the server's value replaces it, so this ends an unconfirmed glide.
     */
    public void resyncPose() {
        runSafely(() -> {
            if (!canSendPlayPackets()) return;
            var flags = serverSentSelfSharedFlags;
            if (flags == null) {
                flags = ac.cult.cultac.network.packet.EntityMetadata.Entry.byteValue(
                        ac.cult.cultac.utils.nmsutil.WatchableIndexUtil.ENTITY_SHARED_FLAGS, (byte) 0);
            }
            user.write(new ac.cult.cultac.network.packet.EntityMetadata(entityID, List.of(flags)));
        });
    }

    @Nullable
    public ac.cult.cultac.manager.tick.BackendTickEstimator backendTicks() {
        return user.getCultConnection().platform().backendTicks();
    }

    /** True when a debug interaction pause drops this serverbound packet. Owner thread only. */
    public boolean discardsPausedInteraction(Object packet) {
        return interactionPause.discards(packet);
    }

    /** An estimated server tick boundary: ends this tick and starts the next. Owner thread only. */
    public void onServerTickEnd() {
        onEndOfTickEvent();
        packetEntityReplication.tickStartTick();
    }

    public void onEndOfTickEvent() {
        runEndOfTickTasks();
        packetEntityReplication.onEndOfTickEvent();
    }

    /** Paper's end of server tick {@code serverTick}. Owner thread only. */
    public void onEndOfTickEvent(int serverTick) {
        runEndOfTickTasks();
        packetEntityReplication.onEndOfTickEvent(serverTick);
    }

    private void runEndOfTickTasks() {
        tick++;
        for (Runnable task : endOfTickTasks) task.run();
        endOfTickTasks.clear();
    }

    private final List<Runnable> endOfTickTasks = new ArrayList<>(4);

    public void addEndOfTickTask(Runnable task) {
        endOfTickTasks.add(task);
    }

    @Getter
    private int tick;
}
