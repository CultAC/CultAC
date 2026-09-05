package ac.cult.cultac.events.packets;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.combat.FairReach;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.network.packet.PacketCodecUtil;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.VehicleTeleportData;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHook;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.data.packetentity.PacketEntityTrackXRot;
import ac.cult.cultac.utils.data.packetentity.PacketEntityUtil;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.utils.nmsutil.MobEffectsCompat;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import net.minecraft.core.Holder;
import net.minecraft.network.PacketBundleUnpacker;
import net.minecraft.network.protocol.BundleDelimiterPacket;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundleDelimiterPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class PacketEntityReplication extends CultProcessor implements CheckListener, ClientTickEndListener {
    private static final Vec3 SELF_DISMOUNT_VELOCITY = Vec3.ZERO;
    private static final double VEHICLE_MOVE_RESYNC_SNAP_EPSILON = 2.0E-5D;
    private static final AtomicBoolean LOGGED_SMOKETEST_BUNDLE_DELIMITER_LOOKUP = new AtomicBoolean(false);

    private Packet<?> clientboundBundleDelimiterPacket;
    private final Map<Integer, PacketHandlerDeltaMovement> packetHandlerDeltaMovements = new HashMap<>();
    private final Map<Integer, PacketHandlerEntityTransform> packetHandlerEntityTransforms = new HashMap<>();
    private int packetHandlerDeltaMovementSequence = 0;
    private boolean useReachBundleDelimiter = true;
    private boolean hasSentPreWavePacket = true;
    private int removedPlayerVehicleId = Integer.MIN_VALUE;

    private record EntityMovement(int entityId,
                                  MovementKind kind,
                                  PositionChange position,
                                  @Nullable EntityRotation rotation,
                                  @Nullable Boolean onGround,
                                  DeltaMovementChange deltaMovement) {
        private static EntityMovement relativeEntityPacket(int entityId,
                                                           boolean hasPosition,
                                                           int xa,
                                                           int ya,
                                                           int za,
                                                           @Nullable EntityRotation rotation,
                                                           boolean onGround) {
            return new EntityMovement(
                    entityId,
                    MovementKind.RELATIVE_ENTITY,
                    PositionChange.relative(hasPosition, xa, ya, za),
                    rotation,
                    onGround,
                    DeltaMovementChange.NONE
            );
        }

        private static EntityMovement teleportEntityPacket(int entityId,
                                                           Vec3 position,
                                                           EntityRotation rotation,
                                                           boolean onGround,
                                                           DeltaMovementChange deltaMovement) {
            return new EntityMovement(
                    entityId,
                    MovementKind.TELEPORT_ENTITY,
                    PositionChange.absolute(position),
                    rotation,
                    onGround,
                    deltaMovement
            );
        }

        private static EntityMovement positionSyncPacket(int entityId,
                                                         Vec3 position,
                                                         EntityRotation rotation,
                                                         boolean onGround) {
            return new EntityMovement(
                    entityId,
                    MovementKind.POSITION_SYNC,
                    PositionChange.absolute(position),
                    rotation,
                    onGround,
                    DeltaMovementChange.NONE
            );
        }

        private static EntityMovement minecartPacket(int entityId,
                                                     Vec3 position,
                                                     EntityRotation rotation) {
            return new EntityMovement(
                    entityId,
                    MovementKind.MINECART,
                    PositionChange.absolute(position),
                    rotation,
                    null,
                    DeltaMovementChange.NONE
            );
        }

        private boolean hasPosition() {
            return position.hasPosition();
        }

        @Nullable
        private Float yaw() {
            return rotation == null ? null : rotation.yaw();
        }

        @Nullable
        private Float pitch() {
            return rotation == null ? null : rotation.pitch();
        }
    }

    private interface PositionChange {
        boolean relative();

        boolean hasPosition();

        double x();

        double y();

        double z();

        static PositionChange relative(boolean hasPosition, int xa, int ya, int za) {
            return new RelativePositionChange(hasPosition, packRelativeEntityMove(xa, ya, za));
        }

        static PositionChange absolute(Vec3 position) {
            return new AbsolutePositionChange(position.x, position.y, position.z);
        }
    }

    private record RelativePositionChange(boolean hasPosition, long packedMove) implements PositionChange {
        @Override
        public boolean relative() {
            return true;
        }

        @Override
        public double x() {
            return unpackRelativeEntityMove(packedMove, 32) / 4096.0D;
        }

        @Override
        public double y() {
            return unpackRelativeEntityMove(packedMove, 16) / 4096.0D;
        }

        @Override
        public double z() {
            return unpackRelativeEntityMove(packedMove, 0) / 4096.0D;
        }
    }

    private record AbsolutePositionChange(double x, double y, double z) implements PositionChange {
        @Override
        public boolean relative() {
            return false;
        }

        @Override
        public boolean hasPosition() {
            return true;
        }
    }

    private record EntityRotation(float yaw, float pitch) {
    }

    private record DeltaMovementChange(boolean present, double x, double y, double z, int packetHandlerSequence) {
        private static final DeltaMovementChange NONE = new DeltaMovementChange(false, 0.0D, 0.0D, 0.0D, -1);

        private static DeltaMovementChange fixed(Vec3 movement, int packetHandlerSequence) {
            return new DeltaMovementChange(true, movement.x, movement.y, movement.z, packetHandlerSequence);
        }

        private Vec3 movement() {
            return new Vec3(x, y, z);
        }
    }

    private enum MovementKind {
        RELATIVE_ENTITY(true, false),
        TELEPORT_ENTITY(false, false),
        POSITION_SYNC(true, true),
        MINECART(false, false);

        private final boolean skipLocalAuthoritativeRoot;
        private final boolean exactAtProof;

        MovementKind(boolean skipLocalAuthoritativeRoot, boolean exactAtProof) {
            this.skipLocalAuthoritativeRoot = skipLocalAuthoritativeRoot;
            this.exactAtProof = exactAtProof;
        }

        private boolean shouldSkip(PacketEntityReplication replication, int entityId) {
            return skipLocalAuthoritativeRoot
                    && replication.isEntityMovementIgnoredForLocalAuthoritativeVehicle(entityId);
        }

        private boolean exactAtProof() {
            return exactAtProof;
        }
    }

    private static long packRelativeEntityMove(int xa, int ya, int za) {
        return ((long) (xa & 0xFFFF) << 32)
                | ((long) (ya & 0xFFFF) << 16)
                | (za & 0xFFFFL);
    }

    private static short unpackRelativeEntityMove(long packedMove, int shift) {
        return (short) (packedMove >> shift);
    }

    private record PacketHandlerDeltaMovement(double x, double y, double z, int sequence) {
    }

    private record PacketHandlerEntityTransform(double x, double y, double z, float yRot, float xRot) {
        private Vec3 position() {
            return new Vec3(x, y, z);
        }
    }

    // Let's imagine the player is on a boat.
    // The player breaks this boat
    // If we were to despawn the boat without an extra transaction, then the boat would disappear before
    // it disappeared on the client side, creating a ghost boat to flag checks with
    //
    // If we were to despawn the tick after, spawning must occur the transaction before to stop the same exact
    // problem with ghost boats in reverse.
    //
    // Therefore, we despawn the transaction after, and spawn the tick before.
    //
    // If we despawn then spawn an entity in the same transaction, then this solution would despawn the new entity
    // instead of the old entity, so we wouldn't see the boat at all
    //
    // Therefore, if the server sends a despawn and then a spawn in the same transaction for the same entity,
    // We should simply add a transaction (which will clear this list!)
    //
    // Another valid solution is to simply spam more transactions, but let's not waste bandwidth.
    private final List<Integer> despawnedEntitiesThisTransaction = new ArrayList<>();

    public boolean wasDespawnedThisTransaction(int entityId) {
        return despawnedEntitiesThisTransaction.contains(entityId);
    }

    public PacketEntityReplication(CultPlayer player) {
        super(player);
    }

    @Override
    public void reload() {
        useReachBundleDelimiter = getConfig().getBooleanElse("Reach.use-bundle-delimiter", true);
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        tickEntityInterpolationAtClientTickEnd();
        player.compensatedEntities.vehicles.onClientTickEnd();
        player.compensatedEntities.vehicles.updateServerControlledSafeSetbackPositionOnTickEnd();
        packetHandlerEntityTransforms.clear();
    }

    private void tickEntityInterpolationAtClientTickEnd() {
        boolean tickingReliably = player.isTickingReliablyFor(3);
        PacketEntity velocityVehicle = player.compensatedEntities.vehicles.getVelocityMovementVehicle();
        for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
            if (entity == velocityVehicle
                    && (player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(entity)
                    || player.packetStateData.clientTickVehicleMovePacketsThisClientTick > 0
                    || player.packetStateData.localAuthoritativeVehicleMovePacketsThisClientTick > 0)) {
                continue;
            }
            entity.onMovement(player, tickingReliably);
        }
    }

    public void resyncTrackedVehicle(@Nullable PacketEntity vehicle) {
        if (vehicle == null) {
            return;
        }

        VehicleMountResyncState resyncState = vehicleMountResyncState(vehicle.getEntityId());
        if (resyncState == null) {
            return;
        }

        sendVehicleProtocolResync(resyncState);
    }

    private void sendVehicleProtocolResync(VehicleMountResyncState resyncState) {
        CultPlayer.TrackedTransaction proof = player.createTrackedTransactionPacketForBundle();
        if (proof == null) {
            return;
        }

        queueVehicleProtocolResyncAndApply(proof.transaction(), resyncState);
        writeProofGroupNow(proof, resyncPackets(resyncState, null));
    }

    @CultPacketHandler
    @CultPacketGroup(PacketGroup.CLIENTBOUND_ENTITY_MOVEMENT)
    public void onMoveEntity(PacketSendEvent event, CultPlayer player, ClientboundMoveEntityPacket packet) {
        handleMoveEntityPacket(event, packet);
    }

    @CultPacketHandler
    public void onPing(PacketSendEvent event, CultPlayer player, ClientboundPingPacket packet) {
        clearDespawnedEntitiesForServerTransaction();
    }

    @CultPacketHandler
    public void onAddEntity(PacketSendEvent event, CultPlayer player, ClientboundAddEntityPacket packet) {
        handleAddEntity(event, packet);
    }

    @CultPacketHandler
    public void onTeleportEntity(PacketSendEvent event, CultPlayer player, ClientboundTeleportEntityPacket packet) {
        handleTeleportEntity(event, packet);
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket")
    public void onEntityPositionSync(PacketSendEvent event, CultPlayer player, Packet<?> packet) {
        handleEntityPositionSync(event, packet);
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ClientboundMoveMinecartPacket")
    public void onMoveMinecart(PacketSendEvent event, CultPlayer player, Packet<?> packet) {
        handleMoveMinecart(event, packet);
    }

    @CultPacketHandler
    public void onSetEntityMotion(PacketSendEvent event, CultPlayer player, ClientboundSetEntityMotionPacket packet) {
        handleSetEntityMotion(event, packet);
    }

    @CultPacketHandler
    public void onSetEntityData(PacketSendEvent event, CultPlayer player, ClientboundSetEntityDataPacket packet) {
        handleSetEntityData(event, packet);
    }

    @CultPacketHandler
    public void onSetEquipment(PacketSendEvent event, CultPlayer player, ClientboundSetEquipmentPacket packet) {
        handleSetEquipment(packet);
    }

    @CultPacketHandler
    public void onUpdateMobEffect(PacketSendEvent event, CultPlayer player, ClientboundUpdateMobEffectPacket packet) {
        handleUpdateMobEffect(event, packet);
    }

    @CultPacketHandler
    public void onRemoveMobEffect(PacketSendEvent event, CultPlayer player, ClientboundRemoveMobEffectPacket packet) {
        handleRemoveMobEffect(event, packet);
    }

    @CultPacketHandler
    public void onUpdateAttributes(PacketSendEvent event, CultPlayer player, ClientboundUpdateAttributesPacket packet) {
        handleUpdateAttributes(event, packet);
    }

    @CultPacketHandler
    public void onEntityEvent(PacketSendEvent event, CultPlayer player, ClientboundEntityEventPacket packet) {
        handleEntityEvent(packet);
    }

    @CultPacketHandler
    public void onDamageEvent(PacketSendEvent event, CultPlayer player, ClientboundDamageEventPacket packet) {
        handleDamageEvent(event, packet);
    }

    @CultPacketHandler
    public void onSetPassengers(PacketSendEvent event, CultPlayer player, ClientboundSetPassengersPacket packet) {
        handleSetPassengers(event, packet);
    }

    @CultPacketHandler
    public void onRemoveEntities(PacketSendEvent event, CultPlayer player, ClientboundRemoveEntitiesPacket packet) {
        handleRemoveEntities(event, packet);
    }

    // Keep the compensated camera entity synchronized.
    @CultPacketHandler
    public void onSetCamera(PacketSendEvent event, CultPlayer player, ClientboundSetCameraPacket packet) {
        player.cameraEntity.onSetCamera(packet);
    }

    private void clearDespawnedEntitiesForServerTransaction() {
        if (player.packetStateData.lastServerTransWasValid) {
            despawnedEntitiesThisTransaction.clear();
        }
    }

    private void handleAddEntity(PacketSendEvent event, ClientboundAddEntityPacket packet) {
        boolean despawnedThisTransaction = despawnedEntitiesThisTransaction.contains(packet.getId());
        if (despawnedThisTransaction) {
            player.sendTransaction();
        }
        if (removedPlayerVehicleId == packet.getId()) {
            removedPlayerVehicleId = Integer.MIN_VALUE;
        }
        int transaction = appendTrailingProofTransactionId(event);
        addEntity(
                packet.getId(),
                packet.getType(),
                new Vec3(packet.getX(), packet.getY(), packet.getZ()),
                packet.getYRot(),
                packet.getXRot(),
                null,
                packet.getData(),
                transaction
        );
    }

    private void handleSetEntityMotion(PacketSendEvent event, ClientboundSetEntityMotionPacket packet) {
        NmsPacketUtil.EntityMotionData motion = NmsPacketUtil.readEntityMotion(packet);
        int velocityTransaction = player.checkManager.getKnockbackHandler().handleEntityVelocity(event, packet);
        if (velocityTransaction <= 0) {
            CultPlayer.TrackedTransaction afterVelocity = appendTrailingProofTransaction(event);
            velocityTransaction = afterVelocity == null ? player.lastTransactionSent.get() : afterVelocity.transaction();
        }
        Vec3 movement = PacketCodecUtil.quantizeClientboundVelocity(player.getClientVersion(), motion.movement());
        int packetHandlerDeltaSequence = recordPacketHandlerDeltaMovement(motion.entityId(), movement);
        player.latencyUtils.addRealTimeTask(velocityTransaction, () -> {
            PacketEntity entity = player.compensatedEntities.getEntity(motion.entityId());
            if (entity != null) {
                if (!player.compensatedEntities.vehicles.applyClientboundVehicleVelocity(entity, movement)) {
                    entity.deltaMovement = movement;
                }
            }
            clearPacketHandlerDeltaMovement(motion.entityId(), packetHandlerDeltaSequence);
        });
    }

    private void handleMoveEntityPacket(PacketSendEvent event, ClientboundMoveEntityPacket packet) {
        int entityId = NmsPacketUtil.readMoveEntityId(packet);
        // ClientPacketListener#handleMoveEntity only updates the position codec
        // for local-authoritative roots. Applying interpolation waits for the
        // mounted tick's Rot+MoveVehicle or Rot+TickEnd proof.
        handleMoveEntity(event, EntityMovement.relativeEntityPacket(
                entityId,
                packet.hasPosition(),
                packet.getXa(),
                packet.getYa(),
                packet.getZa(),
                packet.hasRotation() ? new EntityRotation(
                        NmsPacketUtil.floatValue(packet, "getYRot", "getyRot"),
                        NmsPacketUtil.floatValue(packet, "getXRot", "getxRot")
                ) : null,
                packet.isOnGround()
        ));
    }

    private void handleTeleportEntity(PacketSendEvent event, ClientboundTeleportEntityPacket packet) {
        TeleportEntityData teleport = readTeleportEntity(packet);
        TeleportChange change = teleport.change();
        RelativeFlag packetFlags = teleport.flags();
        if (teleport.entityId() == removedPlayerVehicleId) {
            handleRemovedPlayerVehicleTeleport(event, change, packetFlags);
            return;
        }
        PacketEntity entity = player.compensatedEntities.getEntity(teleport.entityId());
        TrackerData data = player.compensatedEntities.getTrackedEntity(teleport.entityId());
        Vec3 sourcePosition = teleportSourcePosition(entity, data);
        float sourceYaw = teleportSourceYaw(entity, data);
        float sourcePitch = teleportSourcePitch(entity, data);
        Vec3 pos = calculateAbsolutePosition(sourcePosition, change.position(), packetFlags);
        float finalYaw = calculateAbsoluteYaw(sourceYaw, change.yRot(), packetFlags);
        float finalPitch = calculateAbsolutePitch(sourcePitch, change.xRot(), packetFlags);
        Vec3 sourceDelta = teleportSourceDeltaMovement(entity);
        Vec3 finalDelta = calculateAbsoluteDelta(sourceDelta, change.deltaMovement(), sourceYaw, sourcePitch, finalYaw, finalPitch, packetFlags);
        int packetHandlerDeltaSequence = recordPacketHandlerDeltaMovement(teleport.entityId(), finalDelta);
        DeltaMovementChange deltaMovementChange = DeltaMovementChange.fixed(finalDelta, packetHandlerDeltaSequence);
        handleMoveEntity(event, EntityMovement.teleportEntityPacket(
                teleport.entityId(),
                pos,
                new EntityRotation(finalYaw, finalPitch),
                teleport.onGround(),
                deltaMovementChange
        ));
    }

    private static TeleportEntityData readTeleportEntity(Packet<?> packet) {
        try {
            packet.getClass().getMethod("change");
            Object value = NmsPacketUtil.invokeNoArg(packet, "change");
            TeleportChange change = new TeleportChange(
                    (Vec3) NmsPacketUtil.invokeNoArg(value, "position"),
                    (Vec3) NmsPacketUtil.invokeNoArg(value, "deltaMovement"),
                    NmsPacketUtil.floatValue(value, "yRot"),
                    NmsPacketUtil.floatValue(value, "xRot")
            );
            Object relatives = NmsPacketUtil.invokeNoArg(packet, "relatives");
            return new TeleportEntityData(
                    NmsPacketUtil.intValue(packet, "id"),
                    change,
                    new RelativeFlag(relativeMask((Iterable<?>) relatives)),
                    NmsPacketUtil.booleanValue(packet, "onGround")
            );
        } catch (NoSuchMethodException ignored) {
            TeleportChange change = new TeleportChange(
                    new Vec3(
                            ((Number) NmsPacketUtil.invokeNoArg(packet, "getX")).doubleValue(),
                            ((Number) NmsPacketUtil.invokeNoArg(packet, "getY")).doubleValue(),
                            ((Number) NmsPacketUtil.invokeNoArg(packet, "getZ")).doubleValue()
                    ),
                    Vec3.ZERO,
                    rotationFromByte(NmsPacketUtil.intValue(packet, "getyRot", "getYRot")),
                    rotationFromByte(NmsPacketUtil.intValue(packet, "getxRot", "getXRot"))
            );
            return new TeleportEntityData(
                    NmsPacketUtil.intValue(packet, "getId"),
                    change,
                    new RelativeFlag(0),
                    NmsPacketUtil.booleanValue(packet, "isOnGround")
            );
        }
    }

    private static int relativeMask(Iterable<?> relatives) {
        int mask = 0;
        for (Object relative : relatives) {
            if (!(relative instanceof Enum<?> value)) {
                continue;
            }
            mask |= switch (value.name()) {
                case "X" -> RelativeFlag.X.getMask();
                case "Y" -> RelativeFlag.Y.getMask();
                case "Z" -> RelativeFlag.Z.getMask();
                case "Y_ROT" -> RelativeFlag.Y_ROT.getMask();
                case "X_ROT" -> RelativeFlag.X_ROT.getMask();
                case "DELTA_X" -> RelativeFlag.DELTA_X.getMask();
                case "DELTA_Y" -> RelativeFlag.DELTA_Y.getMask();
                case "DELTA_Z" -> RelativeFlag.DELTA_Z.getMask();
                case "ROTATE_DELTA" -> RelativeFlag.ROTATE_DELTA.getMask();
                default -> 0;
            };
        }
        return mask;
    }

    private static float rotationFromByte(int value) {
        return (byte) value * 360.0F / 256.0F;
    }

    private void handleRemovedPlayerVehicleTeleport(PacketSendEvent event, TeleportChange change, RelativeFlag packetFlags) {
        Vec3 position = calculateAbsolutePosition(new Vec3(player.x, player.y, player.z), change.position(), packetFlags);
        float finalYaw = calculateAbsoluteYaw(player.xRot, change.yRot(), packetFlags);
        float finalPitch = calculateAbsolutePitch(player.yRot, change.xRot(), packetFlags);
        Vec3 deltaMovement = calculateAbsoluteDelta(Vec3.ZERO, change.deltaMovement(), player.xRot, player.yRot, finalYaw, finalPitch, packetFlags);
        int proofTransaction = appendTrailingProofTransactionId(event);

        player.getSetbackTeleportUtil().addImmediatePlayerTeleport(
                position,
                deltaMovement,
                new RelativeFlag(0),
                proofTransaction,
                player.xRot,
                player.yRot,
                finalYaw,
                finalPitch
        );
    }

    private Vec3 teleportSourcePosition(PacketEntity entity, TrackerData data) {
        return packetHandlerTransformSource(entity, data).position();
    }

    private float teleportSourceYaw(PacketEntity entity, TrackerData data) {
        return packetHandlerTransformSource(entity, data).yRot();
    }

    private float teleportSourcePitch(PacketEntity entity, TrackerData data) {
        return packetHandlerTransformSource(entity, data).xRot();
    }

    private PacketHandlerEntityTransform packetHandlerTransformSource(PacketEntity entity, TrackerData data) {
        if (entity != null) {
            PacketHandlerEntityTransform deferredTransform = packetHandlerEntityTransforms.get(entity.getEntityId());
            if (deferredTransform != null) {
                return deferredTransform;
            }
        }

        ReachInterpolationData interpolationTarget = teleportSourceInterpolationTarget(entity);
        if (interpolationTarget != null) {
            // MCP-Reborn PositionMoveRotation#of(entity) reads the current
            // InterpolationHandler target in the packet handler. The next
            // InterpolationHandler#interpolate carry happens later, during the
            // entity tick, so do not pre-carry the target here.
            Vec3 position = positionFromPacketEntityBox(interpolationTarget.getTargetLocation());
            return new PacketHandlerEntityTransform(
                    position.x,
                    position.y,
                    position.z,
                    interpolationTarget.getTargetYaw(),
                    interpolationTarget.getTargetPitch()
            );
        }

        if (entity != null && entity.clientPhysicalPosition != null) {
            // MCP-Reborn PositionMoveRotation#of(entity) reads the client entity's
            // current position/yaw/pitch when no interpolation target is active.
            // TrackerData is the latest server-authored position Cult sent to the
            // client; it can be ahead of the transaction-proven client shadow.
            return new PacketHandlerEntityTransform(
                    entity.clientPhysicalPosition.x,
                    entity.clientPhysicalPosition.y,
                    entity.clientPhysicalPosition.z,
                    entity.clientPhysicalYaw,
                    entity.clientPhysicalPitch
            );
        }

        if (data != null) {
            return new PacketHandlerEntityTransform(data.getX(), data.getY(), data.getZ(), data.getXRot(), data.getYRot());
        }

        return new PacketHandlerEntityTransform(0.0D, 0.0D, 0.0D, 0.0F, 0.0F);
    }

    private Vec3 teleportSourceDeltaMovement(PacketEntity entity) {
        // MCP-Reborn PositionMoveRotation#of(entity) reads Entity#getKnownMovement.
        // Player-controlled ridden entities use the controlling player's known
        // movement there; non-controlled entities use their own packet-handler delta.
        if (entity != null && entity == player.compensatedEntities.vehicles.getVelocityMovementVehicle()) {
            return player.compensatedEntities.getSelf().deltaMovement;
        }

        return packetHandlerDeltaMovementSource(entity);
    }

    private ReachInterpolationData teleportSourceInterpolationTarget(PacketEntity entity) {
        if (entity == null
                || entity.newPacketLocation == null
                || !entity.newPacketLocation.hasActiveInterpolationTarget()) {
            return null;
        }

        return entity.newPacketLocation;
    }

    private Vec3 positionFromPacketEntityBox(SimpleCollisionBox box) {
        return new Vec3(
                (box.maxX - box.minX) / 2.0D + box.minX,
                box.minY,
                (box.maxZ - box.minZ) / 2.0D + box.minZ
        );
    }

    private void handleEntityPositionSync(PacketSendEvent event, Packet<?> packet) {
        Object change = NmsPacketUtil.invokeNoArg(packet, "values");
        Vec3 pos = (Vec3) NmsPacketUtil.invokeNoArg(change, "position");

        // MCP-Reborn ClientPacketListener#handleEntityPositionSync updates the
        // position codec for every entity, but skips interpolation/snap/onGround
        // when the entity is local-authoritative.
        handleMoveEntity(event, EntityMovement.positionSyncPacket(
                NmsPacketUtil.intValue(packet, "id"),
                pos,
                new EntityRotation(NmsPacketUtil.floatValue(change, "yRot"), NmsPacketUtil.floatValue(change, "xRot")),
                NmsPacketUtil.booleanValue(packet, "onGround")
        ));
    }

    private void handleMoveMinecart(PacketSendEvent event, Packet<?> packet) {
        List<?> lerpSteps = (List<?>) NmsPacketUtil.invokeNoArg(packet, "lerpSteps");
        if (lerpSteps.isEmpty()) {
            return;
        }
        Object step = lerpSteps.getLast();
        Vec3 pos = (Vec3) NmsPacketUtil.invokeNoArg(step, "position");
        handleMoveEntity(event, EntityMovement.minecartPacket(
                NmsPacketUtil.intValue(packet, "entityId"),
                pos,
                new EntityRotation(
                        PacketCodecUtil.quantizeRotationByte(NmsPacketUtil.floatValue(step, "yRot")),
                        PacketCodecUtil.quantizeRotationByte(NmsPacketUtil.floatValue(step, "xRot"))
                )
        ));
    }

    private void handleSetEntityData(PacketSendEvent event, ClientboundSetEntityDataPacket packet) {
        List<SynchedEntityData.DataValue<?>> metadata = packet.packedItems();
        PacketEntity entity = player.compensatedEntities.getEntity(packet.id());
        TrackerData tracked = player.compensatedEntities.getTrackedEntity(packet.id());
        EntityType<?> type = entity == null ? (tracked == null ? null : tracked.getEntityType()) : entity.type;
        boolean updatesBoost = (type == EntityTypesCompat.PIG
                && WatchableIndexUtil.getIndex(metadata, WatchableIndexUtil.PIG_BOOST_TIME) != null)
                || (type == EntityTypesCompat.STRIDER
                && WatchableIndexUtil.getIndex(metadata, WatchableIndexUtil.STRIDER_BOOST_TIME) != null);
        if (updatesBoost) {
            CultPlayer.TrackedTransaction proof = player.createTrackedTransactionPacketForDeferredSend();
            if (proof != null) {
                player.latencyUtils.addRealTimeTask(proof.transaction(),
                        () -> player.compensatedEntities.updateEntityMetadata(packet.id(), metadata));
                // A preceding ping cannot prove receipt of the boost. Match the
                // inventory path: send the proof after the complete outbound group.
                event.getTasksAfterSend().add(() -> {
                    player.user.writePacket(proof.packet());
                    player.markTrackedTransactionPacketSent(proof);
                });
                return;
            }
        }
        boolean updatesHorseFlags = player.compensatedEntities.getEntity(packet.id()) instanceof PacketEntityHorse
                && WatchableIndexUtil.getIndex(metadata, WatchableIndexUtil.HORSE_FLAGS) != null;
        if (WatchableIndexUtil.getIndex(metadata, WatchableIndexUtil.ENTITY_NO_GRAVITY) != null) {
            player.sendTransaction();
        }
        if (updatesHorseFlags) {
            // Horse standing changes the rider attachment point. A local
            // START_RIDING_JUMP also writes that client-visible bit, so a
            // server metadata packet can only replace it once transaction
            // ordering proves the client processed the metadata packet.
            int transaction = player.sendTransactionAndGetId();
            if (transaction >= 0) {
                player.latencyUtils.addRealTimeTask(transaction,
                        () -> player.compensatedEntities.updateEntityMetadata(packet.id(), metadata));
            } else {
                player.latencyUtils.addRealTimeTaskNow(() -> player.compensatedEntities.updateEntityMetadata(packet.id(), metadata));
            }
        } else {
            player.latencyUtils.addRealTimeTaskNow(() -> player.compensatedEntities.updateEntityMetadata(packet.id(), metadata));
        }
    }

    private void handleSetEquipment(ClientboundSetEquipmentPacket packet) {
        player.latencyUtils.addRealTimeTaskNow(() -> player.compensatedEntities.updateEntityEquipment(packet.getEntity(), packet.getSlots()));
    }

    private void handleUpdateMobEffect(PacketSendEvent event, ClientboundUpdateMobEffectPacket packet) {
        PotionEffectType type = toPotionEffectType(packet.getEffect());
        if (type == null) {
            return;
        }

        if (isDirectlyAffectingPlayer(player, packet.getEntityId())) {
            event.getTasksAfterSend().add(player::sendTransaction);
        }

        final Runnable applyMobEffect = () -> {
            PacketEntity entity = player.compensatedEntities.getEntity(packet.getEntityId());
            if (entity == null) return;

            entity.addPotionEffect(type, packet.getEffectAmplifier());
        };
        player.latencyUtils.addRealTimeTaskNow(applyMobEffect);
    }

    private void handleRemoveMobEffect(PacketSendEvent event, ClientboundRemoveMobEffectPacket packet) {
        PotionEffectType type = toPotionEffectType(packet.effect());
        if (type == null) {
            return;
        }

        if (isDirectlyAffectingPlayer(player, packet.entityId())) {
            event.getTasksAfterSend().add(player::sendTransaction);
        }

        final Runnable removeMobEffect = () -> {
            PacketEntity entity = player.compensatedEntities.getEntity(packet.entityId());
            if (entity == null) return;

            entity.removePotionEffect(type);
        };
        player.latencyUtils.addRealTimeTaskNow(removeMobEffect);
    }

    private void handleUpdateAttributes(PacketSendEvent event, ClientboundUpdateAttributesPacket packet) {
        int entityID = packet.getEntityId();
        List<ClientboundUpdateAttributesPacket.AttributeSnapshot> values = new ArrayList<>(packet.getValues());

        if (isDirectlyAffectingPlayer(player, entityID)) {
            CultPlayer.TrackedTransaction proof = player.createTrackedTransactionPacketForDeferredSend();
            if (proof == null) {
                player.compensatedEntities.updateAttributes(entityID, values);
                return;
            }
            event.getPacketsAfterSend().add(proof.packet());
            event.getTasksAfterSend().add(() -> player.markTrackedTransactionPacketSent(proof));
            player.latencyUtils.addRealTimeTask(
                    proof.transaction(),
                    () -> player.compensatedEntities.updateAttributes(entityID, values));
            return;
        }

        PacketEntity entity = player.compensatedEntities.getEntity(entityID);
        if (entity == null || PacketEntityUtil.isRideable(entity.type)) {
            player.compensatedEntities.updateAttributes(entityID, values);
        } else {
            player.latencyUtils.addRealTimeTaskNow(() -> player.compensatedEntities.updateAttributes(entityID, values));
        }
    }

    private void handleEntityEvent(ClientboundEntityEventPacket packet) {
        NmsPacketUtil.EntityEventData entityEvent = NmsPacketUtil.readEntityEvent(packet);
        int entityId = entityEvent.entityId();
        byte status = entityEvent.status();
        if (status == 3) {
            PacketEntity entity = player.compensatedEntities.getEntity(entityId);

            if (entity == null) return;
            entity.isDead = true;
        }

        if (status == 31) {
            player.sendTransaction();

            final Runnable applyHookPull = () -> {
                PacketEntity hook = player.compensatedEntities.getEntity(entityId);
                if (!(hook instanceof PacketEntityHook)) return;

                PacketEntityHook hookEntity = (PacketEntityHook) hook;
                if (hookEntity.attached == player.entityID) {
                    PacketEntity owner = player.compensatedEntities.getEntity(hookEntity.owner);
                    if (owner == null) return;

                    player.compensatedEntities.fishingRodPulls.add(owner.getPossibleMovementCollisionBoxes());
                }
            };
            player.latencyUtils.addRealTimeTaskNow(applyHookPull);
        }

        if (status >= 24 && status <= 28 && entityId == player.entityID) {
            player.compensatedEntities.getSelf().setOpLevel(status - 24);
        }

        if (status == 35 && entityId == player.entityID) {
            player.sendTransaction();
        }
    }

    private void handleDamageEvent(PacketSendEvent event, ClientboundDamageEventPacket packet) {
        int entityId = packet.entityId();
        if (!(player.compensatedEntities.getEntity(entityId) instanceof PacketEntityStrider)) {
            return;
        }

        CultPlayer.TrackedTransaction proof = appendTrailingProofTransaction(event);
        if (proof == null) {
            player.latencyUtils.addRealTimeTaskNow(() -> applyStriderDamageEvent(entityId));
            return;
        }

        player.latencyUtils.addRealTimeTask(proof.transaction(), () -> applyStriderDamageEvent(entityId));
    }

    private void applyStriderDamageEvent(int entityId) {
        PacketEntity entity = player.compensatedEntities.getEntity(entityId);
        if (entity instanceof PacketEntityStrider strider) {
            strider.handleDamageEvent();
        }
    }

    private void handleSetPassengers(PacketSendEvent event, ClientboundSetPassengersPacket packet) {
        boolean mountsLocalPlayer = mountsLocalPlayer(packet);
        PacketEntity vehicle = player.compensatedEntities.getEntity(packet.getVehicle());
        Integer immediateVehicle = player.compensatedEntities.vehicles.serverPlayerVehicle;
        boolean delayedVehicleCanStillBeCurrent = immediateVehicle == null
                ? removedPlayerVehicleId != packet.getVehicle()
                : immediateVehicle == packet.getVehicle();
        boolean removesLocalPlayer = !mountsLocalPlayer
                && (player.compensatedEntities.vehicles.isServerPlayerPassengerOf(packet.getVehicle())
                || delayedVehicleCanStillBeCurrent
                && vehicle != null
                && vehicle.passengers.contains(player.compensatedEntities.playerEntity));

        if (removesLocalPlayer) {
            Vec3 dismountPosition = new Vec3(player.x, player.y, player.z);
            Vec3 dismountVelocity = Vec3.ZERO;
            SelfDismountResyncState dismountResyncState = new SelfDismountResyncState(dismountPosition, dismountVelocity,
                    player.getSetbackTeleportUtil().nextTeleportId());
            int transaction = transactionForSetPassengers(event, null, dismountResyncState);
            int vehicleId = packet.getVehicle();
            int[] passengers = packet.getPassengers().clone();
            player.compensatedEntities.vehicles.setServerVehicleDismount(vehicleId, transaction);
            player.latencyUtils.addRealTimeTask(transaction, () -> {
                player.compensatedEntities.playerEntity.deltaMovement = PacketCodecUtil.quantizeLpVec3(SELF_DISMOUNT_VELOCITY);
                player.compensatedEntities.vehicles.applyVehiclePassengers(vehicleId, passengers);
                player.compensatedEntities.vehicles.clearServerVehicle(vehicleId, transaction);
                // Vehicle-switch state changes at the passenger transaction boundary.
                player.vehicleData.wasVehicleSwitch = true;
            });
            return;
        }

        VehicleMountResyncState mountResyncState = null;
        if (mountsLocalPlayer) {
            if (shouldResyncVehicleOnMount(vehicle, packet.getPassengers())) {
                mountResyncState = vehicleMountResyncState(packet.getVehicle());
            }
        }

        int transaction = transactionForSetPassengers(event, mountResyncState, null);
        int vehicleId = packet.getVehicle();
        int[] passengers = packet.getPassengers().clone();
        if (mountsLocalPlayer) {
            player.compensatedEntities.vehicles.setServerVehicle(vehicleId, passengers, transaction);
        }
        VehicleMountResyncState resyncState = mountResyncState;
        if (resyncState != null) {
            queueVehicleProtocolResync(transaction, resyncState);
        }
        player.latencyUtils.addRealTimeTask(transaction, () -> {
            player.compensatedEntities.vehicles.applyVehiclePassengers(vehicleId, passengers);
            if (mountsLocalPlayer) {
                player.vehicleData.wasVehicleSwitch = true;
            }
            if (resyncState != null) {
                player.compensatedEntities.vehicles.applyAcceptedVehicleTeleportEntityState(
                        resyncState.vehicleId(),
                        resyncState.position(),
                        resyncState.yaw(),
                        resyncState.pitch(),
                        resyncState.onGround(),
                        resyncState.velocity(),
                        resyncState.interpolates());
            }
        });
    }

    private int transactionForSetPassengers(PacketSendEvent event,
                                            @Nullable VehicleMountResyncState mountResyncState,
                                            @Nullable SelfDismountResyncState dismountResyncState) {
        List<Packet<? super ClientGamePacketListener>> resyncPackets = resyncPackets(mountResyncState, dismountResyncState);
        boolean useDelimiter = resyncPackets.isEmpty()
                ? shouldUseEntityTrackingBundleDelimiter()
                : shouldUseVehicleProtocolBundleDelimiter();
        CultPlayer.TrackedTransaction proof = appendTrailingProofTransaction(event, resyncPackets, useDelimiter);
        return proof == null ? player.lastTransactionSent.get() : proof.transaction();
    }

    private List<Packet<? super ClientGamePacketListener>> resyncPackets(@Nullable VehicleMountResyncState mountResyncState,
                                                                        @Nullable SelfDismountResyncState dismountResyncState) {
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        if (mountResyncState != null) {
            packets.add(vehicleMovePacket(mountResyncState));
            packets.add(new ClientboundSetEntityMotionPacket(mountResyncState.vehicleId(), mountResyncState.velocity()));
        }
        if (dismountResyncState != null) {
            packets.add(new ClientboundSetEntityMotionPacket(player.entityID, dismountResyncState.velocity()));
            packets.add(selfDismountTeleportPacket(dismountResyncState.teleportId(), dismountResyncState.position(), dismountResyncState.velocity()));
        }
        return packets;
    }

    private ClientboundMoveVehiclePacket vehicleMovePacket(VehicleMountResyncState resyncState) {
        return NmsPacketUtil.clientboundMoveVehiclePacket(
                resyncState.position(), resyncState.yaw(), resyncState.pitch());
    }

    private ClientboundPlayerPositionPacket selfDismountTeleportPacket(int teleportId, Vec3 position, Vec3 velocity) {
        try {
            Class<?> changeType = Class.forName("net.minecraft.world.entity.PositionMoveRotation");
            Object change = changeType
                    .getConstructor(Vec3.class, Vec3.class, float.class, float.class)
                    .newInstance(position, velocity, player.xRot, player.yRot);
            return (ClientboundPlayerPositionPacket) ClientboundPlayerPositionPacket.class
                    .getMethod("of", int.class, changeType, Set.class)
                    .invoke(null, teleportId, change, Set.of());
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            try {
                return ClientboundPlayerPositionPacket.class
                        .getConstructor(double.class, double.class, double.class, float.class, float.class, Set.class, int.class)
                        .newInstance(position.x, position.y, position.z, player.xRot, player.yRot, Set.of(), teleportId);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to create legacy player teleport packet", exception);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to create player teleport packet", exception);
        }
    }

    private boolean mountsLocalPlayer(ClientboundSetPassengersPacket packet) {
        for (int passenger : packet.getPassengers()) {
            if (passenger == player.entityID) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldResyncVehicleOnMount(PacketEntity vehicle, int[] passengers) {
        return player.compensatedEntities.vehicles.shouldProtocolResyncOnMount(vehicle, passengers);
    }

    private void queueVehicleProtocolResync(int transaction, VehicleMountResyncState resyncState) {
        player.getSetbackTeleportUtil().addVehicleTeleport(resyncState.vehicleId(), transaction, resyncState.position());
        player.getSetbackTeleportUtil().updateSafeVehiclePosition(resyncState.position());
    }

    private void queueVehicleProtocolResyncAndApply(int transaction, VehicleMountResyncState resyncState) {
        queueVehicleProtocolResync(transaction, resyncState);
        player.latencyUtils.addRealTimeTask(transaction, () ->
                player.compensatedEntities.vehicles.applyAcceptedVehicleTeleportEntityState(
                        resyncState.vehicleId(),
                        resyncState.position(),
                        resyncState.yaw(),
                        resyncState.pitch(),
                        resyncState.onGround(),
                        resyncState.velocity(),
                        resyncState.interpolates()
                ));
    }

    @Nullable
    private VehicleMountResyncState vehicleMountResyncState(int vehicleId) {
        return vehicleMountResyncState(vehicleId, false);
    }

    @Nullable
    private VehicleMountResyncState vehicleMountResyncState(int vehicleId, boolean forceSnapNudge) {
        TrackerData trackedPosition = player.compensatedEntities.getTrackedEntity(vehicleId);
        if (trackedPosition == null) {
            return null;
        }

        Vec3 position = new Vec3(trackedPosition.getX(), trackedPosition.getY(), trackedPosition.getZ());
        PacketEntity vehicle = player.compensatedEntities.getEntity(vehicleId);
        if (forceSnapNudge || vehicleMoveWouldNotSnap(vehicle, position)) {
            position = nudgeVehicleMoveResyncPosition(position);
        }

        return new VehicleMountResyncState(
                position,
                trackedPosition.getXRot(),
                trackedPosition.getYRot(),
                null,
                PacketCodecUtil.quantizeLpVec3(Vec3.ZERO),
                vehicleId,
                false
        );
    }

    private boolean vehicleMoveWouldNotSnap(@Nullable PacketEntity vehicle, Vec3 packetPosition) {
        Vec3 serializedPosition = currentSerializedVehiclePosition(vehicle);
        return serializedPosition != null && packetPosition.distanceTo(serializedPosition) <= 1.0E-5F;
    }

    private Vec3 nudgeVehicleMoveResyncPosition(Vec3 position) {
        long xBucket = Math.round(position.x / VEHICLE_MOVE_RESYNC_SNAP_EPSILON);
        double offset = (xBucket & 1L) == 0L ? VEHICLE_MOVE_RESYNC_SNAP_EPSILON : -VEHICLE_MOVE_RESYNC_SNAP_EPSILON;
        return position.add(offset, 0.0D, 0.0D);
    }

    @Nullable
    private Vec3 currentSerializedVehiclePosition(@Nullable PacketEntity vehicle) {
        if (vehicle == null) {
            return null;
        }

        if (vehicle.newPacketLocation != null && vehicle.newPacketLocation.hasActiveInterpolationTarget()) {
            return positionFromPacketEntityBox(vehicle.newPacketLocation.getTargetLocation());
        }
        return vehicle.clientPhysicalPosition != null ? vehicle.clientPhysicalPosition : vehicle.desyncClientPos;
    }

    private record VehicleMountResyncState(Vec3 position,
                                           float yaw,
                                           float pitch,
                                           @Nullable Boolean onGround,
                                           Vec3 velocity,
                                           int vehicleId,
                                           boolean interpolates) {
    }

    private record SelfDismountResyncState(Vec3 position,
                                           Vec3 velocity,
                                           int teleportId) {
    }

    private void handleRemoveEntities(PacketSendEvent event, ClientboundRemoveEntitiesPacket packet) {
        int[] destroyEntityIds = packet.getEntityIds().toIntArray();
        int transaction = appendTrailingProofTransactionId(event);

        for (int entityID : destroyEntityIds) {
            despawnedEntitiesThisTransaction.add(entityID);
            if (player.compensatedEntities.vehicles.serverPlayerVehicle != null
                    && player.compensatedEntities.vehicles.serverPlayerVehicle == entityID) {
                PacketEntity vehicle = player.compensatedEntities.getEntity(entityID);
                if (vehicle != null && vehicle.passengers.contains(player.compensatedEntities.playerEntity)) {
                    removedPlayerVehicleId = entityID;
                }
                player.compensatedEntities.vehicles.clearServerVehicle();
            }
            player.compensatedEntities.serverPositionsMap.remove(entityID);
        }

        Runnable removeTask = () -> {
            for (int integer : destroyEntityIds) {
                player.compensatedEntities.removeEntity(integer);
                player.compensatedFireworks.removeFirework(integer);
            }
        };
        if (transaction >= 0) {
            player.latencyUtils.addRealTimeTask(transaction, removeTask);
        } else {
            player.latencyUtils.addRealTimeTaskNext(removeTask);
        }
    }

    private Vec3 calculateAbsolutePosition(Vec3 current, Vec3 change, RelativeFlag flags) {
        double x = flags.isSet(RelativeFlag.X.getMask()) ? current.x + change.x : change.x;
        double y = flags.isSet(RelativeFlag.Y.getMask()) ? current.y + change.y : change.y;
        double z = flags.isSet(RelativeFlag.Z.getMask()) ? current.z + change.z : change.z;
        return new Vec3(x, y, z);
    }

    private float calculateAbsoluteYaw(float current, float change, RelativeFlag flags) {
        return flags.isSet(RelativeFlag.Y_ROT.getMask()) ? current + change : change;
    }

    private float calculateAbsolutePitch(float current, float change, RelativeFlag flags) {
        float pitch = flags.isSet(RelativeFlag.X_ROT.getMask()) ? current + change : change;
        return Math.max(-90.0F, Math.min(90.0F, pitch));
    }

    private Vec3 calculateAbsoluteDelta(Vec3 currentDelta,
                                        Vec3 changeDelta,
                                        float sourceYaw,
                                        float sourcePitch,
                                        float finalYaw,
                                        float finalPitch,
                                        RelativeFlag flags) {
        Vec3 transformed = currentDelta;
        if (flags.isSet(RelativeFlag.ROTATE_DELTA.getMask())) {
            transformed = transformed.xRot((float) Math.toRadians(sourcePitch - finalPitch));
            transformed = transformed.yRot((float) Math.toRadians(sourceYaw - finalYaw));
        }

        return new Vec3(
                flags.isSet(RelativeFlag.DELTA_X.getMask()) ? transformed.x + changeDelta.x : changeDelta.x,
                flags.isSet(RelativeFlag.DELTA_Y.getMask()) ? transformed.y + changeDelta.y : changeDelta.y,
                flags.isSet(RelativeFlag.DELTA_Z.getMask()) ? transformed.z + changeDelta.z : changeDelta.z
        );
    }

    private int appendTrailingProofTransactionId(PacketSendEvent event) {
        CultPlayer.TrackedTransaction proof = appendTrailingProofTransaction(event);
        return proof == null ? -1 : proof.transaction();
    }

    private CultPlayer.TrackedTransaction appendTrailingProofTransaction(PacketSendEvent event) {
        return appendTrailingProofTransaction(event, List.of(), shouldUseEntityTrackingBundleDelimiter());
    }

    @Nullable
    public CultPlayer.TrackedTransaction appendVehicleProtocolProofTransaction(PacketSendEvent event) {
        return appendTrailingProofTransaction(event, List.of(), shouldUseVehicleProtocolBundleDelimiter());
    }

    @Nullable
    public CultPlayer.TrackedTransaction appendPlainProofTransaction(PacketSendEvent event) {
        return appendTrailingProofTransaction(event, List.of(), false);
    }

    private CultPlayer.TrackedTransaction appendTrailingProofTransaction(PacketSendEvent event,
                                                                        List<Packet<? super ClientGamePacketListener>> packetsBeforeProof,
                                                                        boolean requestedDelimiter) {
        CultPlayer.TrackedTransaction proof = player.createTrackedTransactionPacketForBundle();
        if (proof == null) {
            return null;
        }

        Packet<?> delimiterPacket = requestedDelimiter ? getClientboundBundleDelimiterPacket() : null;
        if (delimiterPacket != null) {
            event.getPacketsBeforeSend().add(delimiterPacket);
        }
        addProofGroupPackets(event.getPacketsAfterSend(), proof, packetsBeforeProof, delimiterPacket);
        event.getTasksAfterSend().add(() -> player.markTrackedTransactionPacketSent(proof));
        return proof;
    }

    private void writeProofGroupNow(CultPlayer.TrackedTransaction proof,
                                    List<Packet<? super ClientGamePacketListener>> packetsBeforeProof) {
        Packet<?> delimiterPacket = shouldUseVehicleProtocolBundleDelimiter()
                ? getClientboundBundleDelimiterPacket()
                : null;
        List<Packet<?>> packets = new ArrayList<>(packetsBeforeProof.size() + (delimiterPacket == null ? 1 : 3));
        addProofGroupPackets(packets, proof, packetsBeforeProof, delimiterPacket);
        if (delimiterPacket != null) {
            packets.add(0, delimiterPacket);
        }
        writePacketsDirect(packets);
        player.markTrackedTransactionPacketSent(proof);
    }

    private void addProofGroupPackets(List<Packet<?>> packets,
                                      CultPlayer.TrackedTransaction proof,
                                      List<Packet<? super ClientGamePacketListener>> packetsBeforeProof,
                                      @Nullable Packet<?> delimiterPacket) {
        packets.addAll(packetsBeforeProof);
        packets.add(proof.packet());
        if (delimiterPacket != null) {
            packets.add(delimiterPacket);
        }
    }

    private boolean shouldUseEntityTrackingBundleDelimiter() {
        return useReachBundleDelimiter && player.supportsBundles();
    }

    private boolean shouldUseVehicleProtocolBundleDelimiter() {
        return player.supportsBundles();
    }

    private void handleMoveEntity(PacketSendEvent event, EntityMovement movement) {
        boolean useDelimiterProof = shouldUseEntityTrackingBundleDelimiter();
        if (!useDelimiterProof && !hasSentPreWavePacket) {
            hasSentPreWavePacket = true;
            player.sendTransaction();
        }

        TrackerData data = player.compensatedEntities.getTrackedEntity(movement.entityId());
        PositionChange position = movement.position();
        boolean absolutePositionKnown = !position.relative();
        double absoluteX = position.x();
        double absoluteY = position.y();
        double absoluteZ = position.z();
        if (data != null) {
            if (position.relative()) {
                Vec3 decodedPosition = PacketCodecUtil.decodeRelativeEntityPosition(
                        new Vec3(data.getCodecBaseX(), data.getCodecBaseY(), data.getCodecBaseZ()),
                        position.x(),
                        position.y(),
                        position.z()
                );
                data.setX(decodedPosition.x);
                data.setY(decodedPosition.y);
                data.setZ(decodedPosition.z);
                data.setCodecBaseX(decodedPosition.x);
                data.setCodecBaseY(decodedPosition.y);
                data.setCodecBaseZ(decodedPosition.z);
                absolutePositionKnown = true;
                absoluteX = decodedPosition.x;
                absoluteY = decodedPosition.y;
                absoluteZ = decodedPosition.z;
            } else {
                data.setX(position.x());
                data.setY(position.y());
                data.setZ(position.z());
                data.setCodecBaseX(position.x());
                data.setCodecBaseY(position.y());
                data.setCodecBaseZ(position.z());
            }
            if (movement.yaw() != null) {
                data.setXRot(movement.yaw());
                data.setYRot(movement.pitch());
            }
            if (movement.onGround() != null) {
                data.setOnGround(movement.onGround());
            }

            if (!useDelimiterProof && data.getLastTransactionHung() == player.lastTransactionSent.get()) {
                player.sendTransaction();
            }
        }

        CultPlayer.TrackedTransaction proof = useDelimiterProof
                ? appendTrailingProofTransaction(event, List.of(), true)
                : null;
        int firstTransaction = proof == null ? player.lastTransactionSent.get() : proof.transaction();
        if (data != null) {
            data.setLastTransactionHung(firstTransaction);
            if (data.getEntityType() == EntityTypesCompat.PLAYER) {
                player.checkManager.getListener(FairReach.class).handleEntityMove(movement.entityId(), data.getX(), data.getY(), data.getZ());
            }
        }

        boolean ignoredMovement = movement.kind().shouldSkip(this, movement.entityId());
        if (queuesVehicleTeleportResponse(movement)
                && !ignoredMovement
                && movement.hasPosition()
                && absolutePositionKnown
                && player.compensatedEntities.vehicles.serverPlayerVehicle != null
                && player.compensatedEntities.vehicles.serverPlayerVehicle == movement.entityId()) {
            Vec3 vehicleTarget = new Vec3(absoluteX, absoluteY, absoluteZ);
            VehicleTeleportData vehicleTeleportData = new VehicleTeleportData(
                    movement.entityId(),
                    vehicleTarget,
                    movement.yaw(),
                    movement.pitch(),
                    movement.onGround(),
                    movement.deltaMovement().movement()
            );
            player.getSetbackTeleportUtil().addVehicleTeleport(movement.entityId(), firstTransaction, vehicleTarget, vehicleTeleportData);
            player.getSetbackTeleportUtil().updateSafeVehiclePosition(vehicleTarget);
        }
        if (!ignoredMovement) {
            recordPacketHandlerTransform(movement.entityId(), movement.hasPosition(), absoluteX, absoluteY, absoluteZ, movement.yaw(), movement.pitch());
        }

        if (proof != null) {
            scheduleBundledMovement(movement, proof.transaction());
        } else {
            scheduleTransactionBoundMovement(movement, firstTransaction);
        }
    }

    private void scheduleBundledMovement(EntityMovement movement,
                                          int proofTransaction) {
        if (!movement.kind().exactAtProof()) {
            scheduleDelimiterBoundMovement(movement, proofTransaction);
            return;
        }

        player.latencyUtils.addRealTimeTask(proofTransaction, () -> {
            PacketEntity entity = applicableMovementEntity(movement);
            if (entity == null) {
                return;
            }
            applyEntityRotationAndGround(entity, movement.yaw(), movement.onGround());
            if (movement.hasPosition()) {
                entity.onBundledPositionSyncTransaction(movement.position().x(), movement.position().y(), movement.position().z(),
                        movement.yaw(), movement.pitch(), player);
            } else {
                applyBundledMovement(entity, movement);
            }
            applyDeltaMovementUpdate(entity, movement.deltaMovement());
            clearPacketHandlerDeltaMovement(movement);
            player.compensatedEntities.updatePassengerPositions();
        });
    }

    private void scheduleDelimiterBoundMovement(EntityMovement movement,
                                                int proofTransaction) {
        int entityId = movement.entityId();
        MovementKind kind = movement.kind();
        int deltaMovementSequence = movement.deltaMovement().packetHandlerSequence();
        player.latencyUtils.addRealTimeTaskWithNextTransaction(proofTransaction,
                () -> {
                    PacketEntity entity = applicableMovementEntity(movement);
                    if (entity == null) {
                        return;
                    }
                    applyEntityRotationAndGround(entity, movement.yaw(), movement.onGround());
                    applyBundleFirstMovement(entity, movement);
                    applyDeltaMovementUpdate(entity, movement.deltaMovement());
                    clearPacketHandlerDeltaMovement(movement);
                    player.compensatedEntities.updatePassengerPositions();
                },
                () -> applySecondMovement(entityId, kind, deltaMovementSequence, true));
    }

    private void scheduleTransactionBoundMovement(EntityMovement movement,
                                                  int firstTransaction) {
        int entityId = movement.entityId();
        MovementKind kind = movement.kind();
        int deltaMovementSequence = movement.deltaMovement().packetHandlerSequence();
        player.latencyUtils.addRealTimeTask(firstTransaction, () -> {
            PacketEntity entity = applicableMovementEntity(movement);
            if (entity == null) {
                return;
            }
            applyEntityRotationAndGround(entity, movement.yaw(), movement.onGround());
            applyFirstMovement(entity, movement);
            applyDeltaMovementUpdate(entity, movement.deltaMovement());
            clearPacketHandlerDeltaMovement(movement);
            player.compensatedEntities.updatePassengerPositions();
            player.latencyUtils.addRealTimeTask(firstTransaction + 1,
                    () -> applySecondMovement(entityId, kind, deltaMovementSequence, false));
        });
    }

    private void applyFirstMovement(PacketEntity entity, EntityMovement movement) {
        PositionChange position = movement.position();
        entity.onFirstTransaction(position.relative(), position.hasPosition(),
                position.x(), position.y(), position.z(), movement.yaw(), movement.pitch(), player);
    }

    private void applyBundleFirstMovement(PacketEntity entity, EntityMovement movement) {
        PositionChange position = movement.position();
        entity.onBundleFirstTransaction(position.relative(), position.hasPosition(),
                position.x(), position.y(), position.z(), movement.yaw(), movement.pitch(), player);
    }

    private void applyBundledMovement(PacketEntity entity, EntityMovement movement) {
        PositionChange position = movement.position();
        entity.onBundleTransaction(position.relative(), position.hasPosition(),
                position.x(), position.y(), position.z(), movement.yaw(), movement.pitch(), player);
    }

    private void applyEntityRotationAndGround(PacketEntity entity, @Nullable Float yaw, @Nullable Boolean onGround) {
        if (entity instanceof PacketEntityTrackXRot && yaw != null) {
            PacketEntityTrackXRot xRotEntity = (PacketEntityTrackXRot) entity;
            xRotEntity.packetYaw = yaw;
            xRotEntity.steps = 3;
        }
        if (onGround != null) {
            entity.onGround = onGround;
        }
    }

    private void applySecondMovement(int entityId,
                                     MovementKind kind,
                                     int packetHandlerDeltaMovementSequence,
                                     boolean updatePassengerPositions) {
        PacketEntity entity = player.compensatedEntities.getEntity(entityId);
        if (entity == null || kind.shouldSkip(this, entityId)) {
            clearPacketHandlerDeltaMovement(entityId, packetHandlerDeltaMovementSequence);
            return;
        }

        entity.onSecondTransaction();
        clearPacketHandlerDeltaMovement(entityId, packetHandlerDeltaMovementSequence);
        if (updatePassengerPositions) {
            player.compensatedEntities.updatePassengerPositions();
        }
    }

    @Nullable
    private PacketEntity applicableMovementEntity(EntityMovement movement) {
        PacketEntity entity = player.compensatedEntities.getEntity(movement.entityId());
        if (entity == null || shouldIgnoreEntityMovement(movement)) {
            clearPacketHandlerDeltaMovement(movement);
            return null;
        }
        return entity;
    }

    private boolean shouldIgnoreEntityMovement(EntityMovement movement) {
        return movement.kind().shouldSkip(this, movement.entityId());
    }

    private boolean queuesVehicleTeleportResponse(EntityMovement movement) {
        // ClientPacketListener#handleTeleportEntity can echo a MoveVehicle
        // packet for local-authoritative ridden roots. EntityPositionSync only
        // updates the position codec for those roots and sends no response.
        return movement.kind() == MovementKind.TELEPORT_ENTITY;
    }

    private void recordPacketHandlerTransform(int entityId,
                                              boolean hasPos,
                                              double x,
                                              double y,
                                              double z,
                                              @Nullable Float yaw,
                                              @Nullable Float pitch) {
        PacketEntity entity = player.compensatedEntities.getEntity(entityId);
        TrackerData data = player.compensatedEntities.getTrackedEntity(entityId);
        PacketHandlerEntityTransform source = packetHandlerTransformSource(entity, data);
        double nextX = hasPos ? x : source.x();
        double nextY = hasPos ? y : source.y();
        double nextZ = hasPos ? z : source.z();
        packetHandlerEntityTransforms.put(entityId, new PacketHandlerEntityTransform(
                nextX,
                nextY,
                nextZ,
                yaw == null ? source.yRot() : yaw,
                pitch == null ? source.xRot() : pitch
        ));
    }

    private boolean isEntityMovementIgnoredForLocalAuthoritativeVehicle(int entityId) {
        return isLocalAuthoritativeRootVehicle(entityId);
    }

    private boolean isLocalAuthoritativeRootVehicle(int entityId) {
        PacketEntity vehicle = player.compensatedEntities.getEntity(entityId);
        return player.compensatedEntities.vehicles.serverPlayerVehicle != null
                && player.compensatedEntities.vehicles.serverPlayerVehicle == entityId
                && player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(vehicle);
    }

    private void applyDeltaMovementUpdate(PacketEntity entity, DeltaMovementChange update) {
        if (!update.present()) {
            return;
        }

        Vec3 movement = update.movement();
        if (!player.compensatedEntities.vehicles.applyClientboundVehicleVelocity(entity, movement)) {
            entity.deltaMovement = movement;
        }
    }

    private int recordPacketHandlerDeltaMovement(int entityId, Vec3 movement) {
        int sequence = ++packetHandlerDeltaMovementSequence;
        packetHandlerDeltaMovements.put(entityId, new PacketHandlerDeltaMovement(movement.x, movement.y, movement.z, sequence));
        return sequence;
    }

    private void clearPacketHandlerDeltaMovement(int entityId, int sequence) {
        if (sequence < 0) {
            return;
        }

        PacketHandlerDeltaMovement movement = packetHandlerDeltaMovements.get(entityId);
        if (movement != null && movement.sequence() == sequence) {
            packetHandlerDeltaMovements.remove(entityId);
        }
    }

    private void clearPacketHandlerDeltaMovement(EntityMovement movement) {
        clearPacketHandlerDeltaMovement(movement.entityId(), movement.deltaMovement().packetHandlerSequence());
    }

    private Vec3 packetHandlerDeltaMovementSource(PacketEntity entity) {
        if (entity == null) {
            return Vec3.ZERO;
        }

        PacketHandlerDeltaMovement movement = packetHandlerDeltaMovements.get(entity.getEntityId());
        return movement == null ? entity.deltaMovement : new Vec3(movement.x(), movement.y(), movement.z());
    }

    @Nullable
    private Packet<?> getClientboundBundleDelimiterPacket() {
        if (clientboundBundleDelimiterPacket != null) {
            return clientboundBundleDelimiterPacket;
        }

        Object channelObject = player.user.getChannel();
        if (!(channelObject instanceof Channel channel)) {
            logSmoketestBundleDelimiterLookup("no-channel", null, null);
            return null;
        }

        for (Map.Entry<String, ChannelHandler> entry : channel.pipeline()) {
            Packet<?> delimiterPacket = findClientboundBundleDelimiterPacket(entry.getValue());
            if (delimiterPacket != null) {
                clientboundBundleDelimiterPacket = delimiterPacket;
                logSmoketestBundleDelimiterLookup("found", entry.getKey(), delimiterPacket);
                return delimiterPacket;
            }
        }

        logSmoketestBundleDelimiterLookup("missing", null, null);
        return null;
    }

    private void logSmoketestBundleDelimiterLookup(String result, @Nullable String handler, @Nullable Packet<?> packet) {
        if (!Boolean.getBoolean("cult.validation.smoketestControl")
                || !LOGGED_SMOKETEST_BUNDLE_DELIMITER_LOOKUP.compareAndSet(false, true)) {
            return;
        }
        StringBuilder message = new StringBuilder("Smoketest reach bundle delimiter lookup attempted=true result=")
                .append(result);
        if (handler != null) {
            message.append(" handler=").append(handler);
        }
        if (packet != null) {
            message.append(" packetClass=").append(packet.getClass().getName());
        }
        LogUtil.info(message.toString());
    }

    @Nullable
    private Packet<?> findClientboundBundleDelimiterPacket(Object source) {
        if (!(source instanceof PacketBundleUnpacker) && !(source instanceof BundlerInfo)) {
            return null;
        }

        Class<?> type = source.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(source);
                    if (value instanceof ClientboundBundleDelimiterPacket delimiter) {
                        return delimiter;
                    }
                    if (value instanceof BundleDelimiterPacket<?> delimiter) {
                        return (Packet<?>) delimiter;
                    }
                    if (value instanceof BundlerInfo) {
                        Packet<?> delimiter = findClientboundBundleDelimiterPacket(value);
                        if (delimiter != null) {
                            return delimiter;
                        }
                    }
                } catch (IllegalAccessException | RuntimeException ignored) {
                }
            }
            type = type.getSuperclass();
        }

        return null;
    }

    private void writePacketDirect(Packet<?> packet) {
        writePacketsDirect(List.of(packet));
    }

    private void writePacketsDirect(List<Packet<?>> packets) {
        Object channelObject = player.user.getChannel();
        if (channelObject instanceof Channel channel) {
            Runnable write = () -> {
                for (Packet<?> packet : packets) {
                    channel.writeAndFlush(packet);
                }
            };
            if (channel.eventLoop().inEventLoop()) {
                write.run();
            } else {
                channel.eventLoop().execute(write);
            }
            return;
        }
        for (Packet<?> packet : packets) {
            player.user.writePacket(packet);
        }
    }

    public void addEntity(int entityID, EntityType type, Vec3 position, float xRot, float yRot,
                          List<SynchedEntityData.DataValue<?>> entityMetadata, int extraData, int transaction) {
        int spawnTransaction = transaction >= 0 ? transaction : player.lastTransactionSent.get();
        player.compensatedEntities.serverPositionsMap.put(entityID, new TrackerData(position.x, position.y, position.z, xRot, yRot, type, spawnTransaction));
        Runnable addTask = () -> {
            player.compensatedEntities.addEntity(entityID, type, position, xRot, yRot, extraData);
            if (entityMetadata != null) {
                player.compensatedEntities.updateEntityMetadata(entityID, entityMetadata);
            }
        };
        if (transaction >= 0) {
            player.latencyUtils.addRealTimeTask(transaction, addTask);
            return;
        }
        player.latencyUtils.addRealTimeTaskNow(addTask);
    }

    private boolean isDirectlyAffectingPlayer(CultPlayer player, int entityID) {
        // The attributes for this entity is active, currently
        return (player.compensatedEntities.vehicles.serverPlayerVehicle == null && entityID == player.entityID) ||
                (player.compensatedEntities.vehicles.serverPlayerVehicle != null && entityID == player.compensatedEntities.vehicles.serverPlayerVehicle);
    }

    public void onEndOfTickEvent() { player.sendTransaction(false); }

    public void tickStartTick() {
        hasSentPreWavePacket = false;
    }

    private PotionEffectType toPotionEffectType(Holder<MobEffect> effect) {
        if (effect == null) {
            return null;
        }
        if (effect.is(MobEffectsCompat.BLINDNESS)) return PotionEffectType.BLINDNESS;
        if (effect.is(MobEffectsCompat.CONDUIT_POWER)) return PotionEffectType.CONDUIT_POWER;
        if (effect.is(MobEffectsCompat.DOLPHINS_GRACE)) return PotionEffectType.DOLPHINS_GRACE;
        if (effect.is(MobEffectsCompat.HASTE)) return PotionEffectType.HASTE;
        if (effect.is(MobEffectsCompat.JUMP_BOOST)) return PotionEffectType.JUMP_BOOST;
        if (effect.is(MobEffectsCompat.LEVITATION)) return PotionEffectType.LEVITATION;
        if (effect.is(MobEffectsCompat.MINING_FATIGUE)) return PotionEffectType.MINING_FATIGUE;
        if (effect.is(MobEffectsCompat.SPEED)) return PotionEffectType.SPEED;
        if (effect.is(MobEffectsCompat.SLOWNESS)) return PotionEffectType.SLOWNESS;
        if (effect.is(MobEffectsCompat.SLOW_FALLING)) return PotionEffectType.SLOW_FALLING;
        if (effect.is(MobEffectsCompat.WEAVING)) return PotionEffectType.WEAVING;
        return null;
    }

    private record TeleportChange(Vec3 position, Vec3 deltaMovement, float yRot, float xRot) {
    }

    private record TeleportEntityData(int entityId, TeleportChange change, RelativeFlag flags, boolean onGround) {
    }
}
