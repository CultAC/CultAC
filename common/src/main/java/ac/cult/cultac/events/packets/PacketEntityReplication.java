package ac.cult.cultac.events.packets;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.combat.FairReach;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.network.packet.PacketCodecUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundDamageEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveMinecart;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveMobEffect;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetCamera;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateMobEffect;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.anticheat.update.PositionUpdate;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.data.TeleportAcceptData;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.VehicleTeleportData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHook;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.data.packetentity.PacketEntityTrackXRot;
import ac.cult.cultac.utils.data.packetentity.PacketEntityUtil;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public class PacketEntityReplication extends CultProcessor implements CheckListener, ClientTickEndListener {
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(ProtocolVersion.V26_3.protocol());
    private static final Vec3 SELF_DISMOUNT_VELOCITY = Vec3.ZERO;
    private static final double VEHICLE_MOVE_RESYNC_SNAP_EPSILON = 2.0E-5D;
    private final Map<Integer, PacketHandlerDeltaMovement> packetHandlerDeltaMovements = new HashMap<>();
    private final Map<Integer, PacketHandlerEntityTransform> packetHandlerEntityTransforms = new HashMap<>();
    private int packetHandlerDeltaMovementSequence = 0;
    private boolean useReachBundleDelimiter = true;
    private boolean hasSentPreWavePacket = true;
    private int removedPlayerVehicleId = Integer.MIN_VALUE;

    private record EntityMovement(
            int entityId,
            MovementKind kind,
            PositionChange position,
            @Nullable EntityRotation rotation,
            @Nullable Boolean onGround,
            DeltaMovementChange deltaMovement) {
        private static EntityMovement relativeEntityPacket(
                int entityId,
                boolean hasPosition,
                EntityPositionPath path,
                @Nullable EntityRotation rotation,
                boolean onGround) {
            return new EntityMovement(
                    entityId,
                    MovementKind.RELATIVE_ENTITY,
                    new PathPositionChange(hasPosition, path),
                    rotation,
                    onGround,
                    DeltaMovementChange.NONE);
        }

        private static EntityMovement legacyRelativeEntityPacket(
                int entityId,
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
                    DeltaMovementChange.NONE);
        }

        private static EntityMovement teleportEntityPacket(
                int entityId,
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
                    deltaMovement);
        }

        private static EntityMovement positionSyncPacket(
                int entityId, EntityPositionPath path, EntityRotation rotation, boolean onGround) {
            return new EntityMovement(
                    entityId,
                    MovementKind.POSITION_SYNC,
                    new PathPositionChange(true, path),
                    rotation,
                    onGround,
                    DeltaMovementChange.NONE);
        }

        private static EntityMovement minecartPacket(int entityId, Vec3 position, EntityRotation rotation) {
            return new EntityMovement(
                    entityId,
                    MovementKind.MINECART,
                    PositionChange.absolute(position),
                    rotation,
                    null,
                    DeltaMovementChange.NONE);
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

    private record PathPositionChange(boolean hasPosition, EntityPositionPath path) implements PositionChange {
        @Override
        public boolean relative() {
            return false;
        }

        @Override
        public double x() {
            return path.endPosition().x;
        }

        @Override
        public double y() {
            return path.endPosition().y;
        }

        @Override
        public double z() {
            return path.endPosition().z;
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

    private record EntityRotation(float yaw, float pitch) {}

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
        return ((long) (xa & 0xFFFF) << 32) | ((long) (ya & 0xFFFF) << 16) | (za & 0xFFFFL);
    }

    private static short unpackRelativeEntityMove(long packedMove, int shift) {
        return (short) (packedMove >> shift);
    }

    private record PacketHandlerDeltaMovement(double x, double y, double z, int sequence) {}

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
        useReachBundleDelimiter = getConfig().getBooleanElse("cult.networking.reach-use-bundle-delimiter", true);
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
        java.util.function.Consumer<PacketEntity> tick = entity -> {
            if (player.isBedrockMovement()
                    && entity
                            == ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl.controlledVehicle(
                                    player)) return;
            if (!player.isBedrockMovement()
                    && entity == velocityVehicle
                    && (player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(entity)
                            || player.packetStateData.clientTickVehicleMovePacketsThisClientTick > 0
                            || player.packetStateData.localAuthoritativeVehicleMovePacketsThisClientTick > 0)) {
                return;
            }
            entity.onMovement(player, tickingReliably);
        };
        if (!player.isBedrockMovement() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)) {
            player.compensatedEntities.tickClientEntities(tick);
        } else {
            player.compensatedEntities.entityMap.values().forEach(tick);
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
    public void onMoveEntity(
            PacketSendEvent<ClientboundMoveEntity> event, CultPlayer player, ClientboundMoveEntity packet) {
        handleMoveEntityPacket(event, packet);
    }

    @CultPacketHandler
    public void onPing(PacketSendEvent<ClientboundPing> event, CultPlayer player, ClientboundPing packet) {
        clearDespawnedEntitiesForServerTransaction();
    }

    @CultPacketHandler
    public void onAddEntity(
            PacketSendEvent<ClientboundAddEntity> event, CultPlayer player, ClientboundAddEntity packet) {
        handleAddEntity(event, packet);
    }

    @CultPacketHandler
    public void onTeleportEntity(
            PacketSendEvent<ClientboundTeleportEntity> event, CultPlayer player, ClientboundTeleportEntity packet) {
        handleTeleportEntity(event, packet);
    }

    @CultPacketHandler
    public void onEntityPositionSync(
            PacketSendEvent<ClientboundEntityPositionSync> event,
            CultPlayer player,
            ClientboundEntityPositionSync packet) {
        handleEntityPositionSync(event, packet);
    }

    @CultPacketHandler
    public void onMoveMinecart(
            PacketSendEvent<ClientboundMoveMinecart> event, CultPlayer player, ClientboundMoveMinecart packet) {
        handleMoveMinecart(event, packet);
    }

    @CultPacketHandler
    public void onSetEntityMotion(
            PacketSendEvent<ClientboundEntityMotion> event, CultPlayer player, ClientboundEntityMotion packet) {
        handleSetEntityMotion(event, packet);
    }

    @CultPacketHandler
    public void onSetEntityData(PacketSendEvent<EntityMetadata> event, CultPlayer player, EntityMetadata packet) {
        handleSetEntityData(event, packet);
    }

    @CultPacketHandler
    public void onSetEquipment(
            PacketSendEvent<InventoryPackets.Equipment> event, CultPlayer player, InventoryPackets.Equipment packet) {
        handleSetEquipment(packet);
    }

    @CultPacketHandler
    public void onUpdateMobEffect(
            PacketSendEvent<ClientboundUpdateMobEffect> event, CultPlayer player, ClientboundUpdateMobEffect packet) {
        handleUpdateMobEffect(event, packet);
    }

    @CultPacketHandler
    public void onRemoveMobEffect(
            PacketSendEvent<ClientboundRemoveMobEffect> event, CultPlayer player, ClientboundRemoveMobEffect packet) {
        handleRemoveMobEffect(event, packet);
    }

    @CultPacketHandler
    public void onUpdateAttributes(
            PacketSendEvent<ClientboundUpdateAttributes> event, CultPlayer player, ClientboundUpdateAttributes packet) {
        handleUpdateAttributes(event, packet);
    }

    @CultPacketHandler
    public void onEntityEvent(
            PacketSendEvent<ClientboundEntityEvent> event, CultPlayer player, ClientboundEntityEvent packet) {
        handleEntityEvent(event, packet);
    }

    @CultPacketHandler
    public void onDamageEvent(
            PacketSendEvent<ClientboundDamageEvent> event, CultPlayer player, ClientboundDamageEvent packet) {
        handleDamageEvent(event, packet);
    }

    @CultPacketHandler
    public void onSetPassengers(
            PacketSendEvent<ClientboundSetPassengers> event, CultPlayer player, ClientboundSetPassengers packet) {
        handleSetPassengers(event, packet);
    }

    @CultPacketHandler
    public void onRemoveEntities(
            PacketSendEvent<ClientboundRemoveEntities> event, CultPlayer player, ClientboundRemoveEntities packet) {
        handleRemoveEntities(event, packet);
    }

    // Keep the compensated camera entity synchronized.
    @CultPacketHandler
    public void onSetCamera(
            PacketSendEvent<ClientboundSetCamera> event, CultPlayer player, ClientboundSetCamera packet) {
        player.cameraEntity.onSetCamera(packet.entityId());
    }

    private void clearDespawnedEntitiesForServerTransaction() {
        if (player.packetStateData.lastServerTransWasValid) {
            despawnedEntitiesThisTransaction.clear();
        }
    }

    private void handleAddEntity(PacketSendEvent<ClientboundAddEntity> event, ClientboundAddEntity packet) {
        boolean despawnedThisTransaction = despawnedEntitiesThisTransaction.contains(packet.entityId());
        if (despawnedThisTransaction) {
            player.sendTransaction();
        }
        if (removedPlayerVehicleId == packet.entityId()) {
            removedPlayerVehicleId = Integer.MIN_VALUE;
        }
        int transaction = appendTrailingProofTransactionId(event);
        var model =
                player.user.getCultConnection().dispatcher().runtime().data().version();
        var client = player.getClientProtocol();
        // Older clients simulate Via's fallback type, e.g. a non-solid ghast for a happy ghast.
        String type = client == null
                ? packet.entityType()
                : ac.cult.cultac.protocol.data.ModelIdMappings.clientVisibleEntity(packet.entityType(), model, client);
        addEntity(
                packet.entityId(),
                java.util.Objects.requireNonNull(
                                ac.cult.blocksim.entity.EntityTypes.defaults().byKey(type),
                                "Unknown model entity " + type)
                        .id(),
                new Vec3(
                        packet.position().x(),
                        packet.position().y(),
                        packet.position().z()),
                packet.yaw(),
                packet.pitch(),
                packet.data(),
                transaction,
                packet.uuid());
    }

    private void handleSetEntityMotion(PacketSendEvent<ClientboundEntityMotion> event, ClientboundEntityMotion packet) {
        ClientboundEntityMotion motion = packet;
        if (player.isBedrockMovement() && motion.entityId() != player.entityID) return;
        int velocityTransaction = player.checkManager.getKnockbackHandler().handleEntityVelocity(event, packet);
        if (velocityTransaction <= 0) {
            CultPlayer.TrackedTransaction afterVelocity = appendTrailingProofTransaction(event);
            velocityTransaction =
                    afterVelocity == null ? player.lastTransactionSent.get() : afterVelocity.transaction();
        }
        Vec3 movement = PacketCodecUtil.clientVelocityFromObserved(
                event.getDecodedProtocol(),
                player.getClientVersion(),
                new Vec3(
                        motion.velocity().x(),
                        motion.velocity().y(),
                        motion.velocity().z()));
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

    private void handleMoveEntityPacket(PacketSendEvent<ClientboundMoveEntity> event, ClientboundMoveEntity packet) {
        int entityId = packet.entityId();
        if (player.getClientVersion().isOlderThan(ClientVersion.V_26_3)) {
            // Pre-26.3 clients receive three shorts. ViaBackwards 26.3->26.2
            // (EntityPacketRewriter26_3#handleMovePos) sums a stepped delta into one.
            var delta = linearDelta(packet.delta());
            handleMoveEntity(
                    event,
                    EntityMovement.legacyRelativeEntityPacket(
                            entityId,
                            packet.hasPosition(),
                            delta.x(),
                            delta.y(),
                            delta.z(),
                            packet.hasRotation() ? new EntityRotation(packet.yaw(), packet.pitch()) : null,
                            packet.onGround()));
            return;
        }
        TrackerData tracked = player.compensatedEntities.getTrackedEntity(entityId);
        if (tracked == null) return; // The client also discards movement for an unknown entity.
        Vec3 codecBase = new Vec3(tracked.getCodecBaseX(), tracked.getCodecBaseY(), tracked.getCodecBaseZ());
        EntityPositionPath path = packet.hasPosition()
                ? EntityPositionPath.decodeRelative(packet.delta(), codecBase)
                : EntityPositionPath.linear(codecBase);
        // ClientPacketListener#handleMoveEntity only updates the position codec
        // for local-authoritative roots. Applying interpolation waits for the
        // mounted tick's Rot+MoveVehicle or Rot+TickEnd proof.
        handleMoveEntity(
                event,
                EntityMovement.relativeEntityPacket(
                        entityId,
                        packet.hasPosition(),
                        path,
                        packet.hasRotation() ? new EntityRotation(packet.yaw(), packet.pitch()) : null,
                        packet.onGround()));
    }

    private void handleTeleportEntity(
            PacketSendEvent<ClientboundTeleportEntity> event, ClientboundTeleportEntity teleport) {
        if (teleport.entityId() == player.entityID) return;
        TeleportChange change = new TeleportChange(
                new Vec3(
                        teleport.position().x(),
                        teleport.position().y(),
                        teleport.position().z()),
                new Vec3(
                        teleport.delta().x(),
                        teleport.delta().y(),
                        teleport.delta().z()),
                teleport.yaw(),
                teleport.pitch());
        RelativeFlag packetFlags = new RelativeFlag(Relative.pack(teleport.relatives()));
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
        Vec3 finalDelta = calculateAbsoluteDelta(
                sourceDelta, change.deltaMovement(), sourceYaw, sourcePitch, finalYaw, finalPitch, packetFlags);
        int packetHandlerDeltaSequence = recordPacketHandlerDeltaMovement(teleport.entityId(), finalDelta);
        DeltaMovementChange deltaMovementChange = DeltaMovementChange.fixed(finalDelta, packetHandlerDeltaSequence);
        handleMoveEntity(
                event,
                EntityMovement.teleportEntityPacket(
                        teleport.entityId(),
                        pos,
                        new EntityRotation(finalYaw, finalPitch),
                        teleport.onGround(),
                        deltaMovementChange));
    }

    private void handleRemovedPlayerVehicleTeleport(
            PacketSendEvent<?> event, TeleportChange change, RelativeFlag packetFlags) {
        if (player.isBedrockMovement()) return;
        if (!player.isBedrockMovement() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)) {
            // 26.3 ClientPacketListener#handleTeleportEntity still remaps a removed
            // vehicle's teleport onto the player, but no longer sends a PosRot echo.
            // The trailing proof places this rebase before the next actual travel.
            // Are you drunk Mojang??? I certainly am after reading your fucking code.
            int proofTransaction = appendTrailingProofTransactionId(event);
            Runnable apply = () -> {
                Vec3 previous = new Vec3(player.x, player.y, player.z);
                Vec3 position = calculateAbsolutePosition(previous, change.position(), packetFlags);
                float sourceYaw = player.xRot, sourcePitch = player.yRot;
                float yaw = calculateAbsoluteYaw(sourceYaw, change.yRot(), packetFlags);
                float pitch = calculateAbsolutePitch(sourcePitch, change.xRot(), packetFlags);
                TeleportAcceptData accepted = new TeleportAcceptData();
                accepted.setTeleport(true);
                accepted.setTeleportData(new TeleportData(
                        position,
                        packetFlags,
                        change.deltaMovement(),
                        proofTransaction,
                        0,
                        sourceYaw,
                        sourcePitch,
                        yaw,
                        pitch));
                CheckManagerListener.applyTeleportAcknowledgementLook(player, yaw, pitch);
                player.checkManager.getSimulationProcessor().applyAcceptedJavaTeleport(accepted);
                player.getSetbackTeleportUtil()
                        .onPredictionComplete(new PredictionComplete(
                                new PositionUpdate(previous, position, yaw, pitch, player.onGround, accepted, null)));
            };
            if (proofTransaction >= 0) player.latencyUtils.addRealTimeTask(proofTransaction, apply);
            else player.latencyUtils.addRealTimeTaskNext(apply);
            return;
        }
        Vec3 position =
                calculateAbsolutePosition(new Vec3(player.x, player.y, player.z), change.position(), packetFlags);
        float finalYaw = calculateAbsoluteYaw(player.xRot, change.yRot(), packetFlags);
        float finalPitch = calculateAbsolutePitch(player.yRot, change.xRot(), packetFlags);
        Vec3 deltaMovement = calculateAbsoluteDelta(
                Vec3.ZERO, change.deltaMovement(), player.xRot, player.yRot, finalYaw, finalPitch, packetFlags);
        int proofTransaction = appendTrailingProofTransactionId(event);

        player.getSetbackTeleportUtil()
                .addImmediatePlayerTeleport(
                        position,
                        deltaMovement,
                        new RelativeFlag(0),
                        proofTransaction,
                        player.xRot,
                        player.yRot,
                        finalYaw,
                        finalPitch);
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
                    interpolationTarget.getTargetPitch());
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
                    entity.clientPhysicalPitch);
        }

        if (data != null) {
            return new PacketHandlerEntityTransform(
                    data.getX(), data.getY(), data.getZ(), data.getXRot(), data.getYRot());
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
        return new Vec3((box.maxX - box.minX) / 2.0D + box.minX, box.minY, (box.maxZ - box.minZ) / 2.0D + box.minZ);
    }

    private static EntityDelta.Linear linearDelta(EntityDelta delta) {
        if (delta instanceof EntityDelta.Linear linear) return linear;
        int x = 0, y = 0, z = 0;
        for (var step : ((EntityDelta.Stepped) delta).steps()) {
            x += step.x();
            y += step.y();
            z += step.z();
        }
        return new EntityDelta.Linear((short) x, (short) y, (short) z);
    }

    private void handleEntityPositionSync(
            PacketSendEvent<ClientboundEntityPositionSync> event, ClientboundEntityPositionSync packet) {
        PositionPath position = packet.position();
        // ViaBackwards 26.3->26.2 keeps only a stepped path's final position.
        if (player.getClientVersion().isOlderThan(ClientVersion.V_26_3))
            position = new PositionPath.Linear(position.endPosition());
        handleMoveEntity(
                event,
                EntityMovement.positionSyncPacket(
                        packet.entityId(),
                        EntityPositionPath.fromProtocol(position),
                        new EntityRotation(packet.yaw(), packet.pitch()),
                        packet.onGround()));
    }

    private void handleMoveMinecart(PacketSendEvent<ClientboundMoveMinecart> event, ClientboundMoveMinecart packet) {
        if (packet.steps().isEmpty()) return;
        var step = packet.steps().getLast();
        var position = step.position();
        // Wire decoding has already applied the native rotation-byte quantization.
        handleMoveEntity(
                event,
                EntityMovement.minecartPacket(
                        packet.entityId(),
                        new Vec3(position.x(), position.y(), position.z()),
                        new EntityRotation(step.yaw(), step.pitch())));
    }

    private void handleSetEntityData(PacketSendEvent<EntityMetadata> event, EntityMetadata packet) {
        List<EntityMetadata.Entry> metadata = packet.packedItems();
        PacketEntity entity = player.compensatedEntities.getEntity(packet.id());
        TrackerData tracked = player.compensatedEntities.getTrackedEntity(packet.id());
        int type = entity == null ? (tracked == null ? -1 : tracked.getEntityType()) : entity.type;
        WatchableIndexUtil.Layout layout = WatchableIndexUtil.forModel(player.user.getCultConnection());
        boolean updatesBoost =
                (type == EntityTypeIds.PIG && WatchableIndexUtil.getIndex(metadata, layout.pigBoostTime()) != null)
                        || (type == EntityTypeIds.STRIDER
                                && WatchableIndexUtil.getIndex(metadata, layout.striderBoostTime()) != null);
        if (updatesBoost) {
            CultPlayer.TrackedTransaction proof = player.createTrackedTransactionPacketForDeferredSend();
            if (proof != null) {
                player.latencyUtils.addRealTimeTask(
                        proof.transaction(),
                        () -> player.compensatedEntities.updateEntityMetadata(packet.id(), metadata));
                // A preceding ping cannot prove receipt of the boost. Match the
                // inventory path: send the proof after the complete outbound group.
                event.getTasksAfterSend().add(() -> {
                    player.user.write(proof.packet());
                    player.markTrackedTransactionPacketSent(proof);
                });
                return;
            }
        }
        boolean updatesHorseFlags = player.compensatedEntities.getEntity(packet.id()) instanceof PacketEntityHorse
                && WatchableIndexUtil.getIndex(metadata, layout.horseFlags()) != null;
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
                player.latencyUtils.addRealTimeTask(
                        transaction, () -> player.compensatedEntities.updateEntityMetadata(packet.id(), metadata));
            } else {
                player.latencyUtils.addRealTimeTaskNow(
                        () -> player.compensatedEntities.updateEntityMetadata(packet.id(), metadata));
            }
        } else {
            player.latencyUtils.addRealTimeTaskNow(
                    () -> player.compensatedEntities.updateEntityMetadata(packet.id(), metadata));
        }
    }

    private void handleSetEquipment(InventoryPackets.Equipment packet) {
        player.latencyUtils.addRealTimeTaskNow(
                () -> player.compensatedEntities.updateEntityEquipment(packet.entityId(), packet.slots()));
    }

    private void handleUpdateMobEffect(
            PacketSendEvent<ClientboundUpdateMobEffect> event, ClientboundUpdateMobEffect packet) {
        MovementEffect type = toMovementEffect(packet.effect());
        if (type == null) {
            return;
        }

        if (isDirectlyAffectingPlayer(player, packet.entityId())) {
            event.getTasksAfterSend().add(player::sendTransaction);
        }

        final Runnable applyMobEffect = () -> {
            PacketEntity entity = player.compensatedEntities.getEntity(packet.entityId());
            if (entity == null) return;

            entity.addPotionEffect(type, packet.amplifier());
        };
        player.latencyUtils.addRealTimeTaskNow(applyMobEffect);
    }

    private void handleRemoveMobEffect(
            PacketSendEvent<ClientboundRemoveMobEffect> event, ClientboundRemoveMobEffect packet) {
        MovementEffect type = toMovementEffect(packet.effect());
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

    private void handleUpdateAttributes(
            PacketSendEvent<ClientboundUpdateAttributes> event, ClientboundUpdateAttributes packet) {
        int entityID = packet.entityId();
        List<AttributeSnapshot> values = packet.attributes();

        if (isDirectlyAffectingPlayer(player, entityID)) {
            CultPlayer.TrackedTransaction proof = player.createTrackedTransactionPacketForDeferredSend();
            if (proof == null) {
                player.compensatedEntities.updateAttributes(entityID, values);
                return;
            }
            event.getWritesAfterSend().add(proof.packet());
            event.getTasksAfterSend().add(() -> player.markTrackedTransactionPacketSent(proof));
            player.latencyUtils.addRealTimeTask(
                    proof.transaction(), () -> player.compensatedEntities.updateAttributes(entityID, values));
            return;
        }

        PacketEntity entity = player.compensatedEntities.getEntity(entityID);
        if (entity == null || PacketEntityUtil.isRideable(entity.type)) {
            player.compensatedEntities.updateAttributes(entityID, values);
        } else {
            player.latencyUtils.addRealTimeTaskNow(() -> player.compensatedEntities.updateAttributes(entityID, values));
        }
    }

    private void handleEntityEvent(PacketSendEvent<ClientboundEntityEvent> event, ClientboundEntityEvent packet) {
        int entityId = packet.entityId();
        byte status = packet.status();
        if (status == 3) {
            // LivingEntity.handleEntityEvent sets non-player health to zero. Keep the action
            // predicate on the client-confirmed boundary; existing movement death handling stays below.
            var proof = appendTrailingProofTransaction(event);
            Runnable apply = () -> {
                var received = player.compensatedEntities.getEntity(entityId);
                if (received != null && received.isLivingEntity() && received.type != EntityTypeIds.PLAYER)
                    received.actionAlive = false;
            };
            if (proof != null) player.latencyUtils.addRealTimeTask(proof.transaction(), apply);
            else player.latencyUtils.addRealTimeTaskNow(apply);
            PacketEntity entity = player.compensatedEntities.getEntity(entityId);

            if (entity == null) return;
            entity.isDead = true;
        }

        // These are native server status codes, before ViaVersion rewrites the packet.
        if (!player.isBedrockMovement()
                && SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_26_3)
                && (status == 71 || status == 72)) {
            CultPlayer.TrackedTransaction proof = appendTrailingProofTransaction(event, List.of(), true);
            Runnable apply = () -> player.compensatedEntities.vehicles.applyBoatBubbleColumnEvent(entityId, status);
            if (proof != null) player.latencyUtils.addRealTimeTask(proof.transaction(), apply);
            else player.latencyUtils.addRealTimeTaskNow(apply);
            return;
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

    private void handleDamageEvent(PacketSendEvent<ClientboundDamageEvent> event, ClientboundDamageEvent packet) {
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

    private void handleSetPassengers(PacketSendEvent<ClientboundSetPassengers> event, ClientboundSetPassengers packet) {
        boolean mountsLocalPlayer = packet.passengers().contains(player.entityID);
        int[] passengers =
                packet.passengers().stream().mapToInt(Integer::intValue).toArray();
        PacketEntity vehicle = player.compensatedEntities.getEntity(packet.vehicleId());
        Integer immediateVehicle = player.compensatedEntities.vehicles.serverPlayerVehicle;
        boolean delayedVehicleCanStillBeCurrent = immediateVehicle == null
                ? removedPlayerVehicleId != packet.vehicleId()
                : immediateVehicle == packet.vehicleId();
        boolean removesLocalPlayer = !mountsLocalPlayer
                && (player.compensatedEntities.vehicles.isServerPlayerPassengerOf(packet.vehicleId())
                        || delayedVehicleCanStillBeCurrent
                                && vehicle != null
                                && vehicle.passengers.contains(player.compensatedEntities.playerEntity));

        if (removesLocalPlayer) {
            Vec3 dismountPosition = new Vec3(player.x, player.y, player.z);
            Vec3 dismountVelocity = Vec3.ZERO;
            SelfDismountResyncState dismountResyncState = new SelfDismountResyncState(
                    dismountPosition,
                    dismountVelocity,
                    player.getSetbackTeleportUtil().nextTeleportId());
            int transaction = transactionForSetPassengers(event, null, dismountResyncState);
            int vehicleId = packet.vehicleId();
            player.compensatedEntities.vehicles.setServerVehicleDismount(vehicleId, transaction);
            player.latencyUtils.addRealTimeTask(transaction, () -> {
                player.compensatedEntities.playerEntity.deltaMovement =
                        PacketCodecUtil.quantizeLpVec3(SELF_DISMOUNT_VELOCITY);
                player.compensatedEntities.vehicles.applyVehiclePassengers(vehicleId, passengers);
                player.compensatedEntities.vehicles.clearServerVehicle(vehicleId, transaction);
                // Vehicle-switch state changes at the passenger transaction boundary.
                player.vehicleData.wasVehicleSwitch = true;
            });
            return;
        }

        VehicleMountResyncState mountResyncState = null;
        if (mountsLocalPlayer) {
            if (shouldResyncVehicleOnMount(vehicle, passengers)) {
                mountResyncState = vehicleMountResyncState(packet.vehicleId());
            }
        }

        int transaction = transactionForSetPassengers(event, mountResyncState, null);
        int vehicleId = packet.vehicleId();
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
                applyVehicleProtocolResync(transaction, resyncState);
            }
        });
    }

    private int transactionForSetPassengers(
            PacketSendEvent<?> event,
            @Nullable VehicleMountResyncState mountResyncState,
            @Nullable SelfDismountResyncState dismountResyncState) {
        List<CultWrite> resyncPackets = resyncPackets(mountResyncState, dismountResyncState);
        boolean useDelimiter = resyncPackets.isEmpty()
                ? shouldUseEntityTrackingBundleDelimiter()
                : shouldUseVehicleProtocolBundleDelimiter();
        CultPlayer.TrackedTransaction proof = appendTrailingProofTransaction(event, resyncPackets, useDelimiter);
        return proof == null ? player.lastTransactionSent.get() : proof.transaction();
    }

    private List<CultWrite> resyncPackets(
            @Nullable VehicleMountResyncState mountResyncState, @Nullable SelfDismountResyncState dismountResyncState) {
        var packets = new ArrayList<CultWrite>();
        if (mountResyncState != null && !isBedrockVehicleResync(mountResyncState.vehicleId())) {
            packets.add(vehicleMovePacket(mountResyncState));
            packets.add(entityMotion(mountResyncState.vehicleId(), mountResyncState.velocity()));
        }
        if (dismountResyncState != null) {
            packets.add(entityMotion(player.entityID, dismountResyncState.velocity()));
            packets.add(selfDismountTeleportPacket(
                    dismountResyncState.teleportId(), dismountResyncState.position(), dismountResyncState.velocity()));
        }
        return packets;
    }

    private static CultWrite entityMotion(int entityId, Vec3 velocity) {
        return new CultWrite(
                new ClientboundEntityMotion(entityId, new Vec3d(velocity.x, velocity.y, velocity.z)), false);
    }

    private CultWrite vehicleMovePacket(VehicleMountResyncState resyncState) {
        Vec3 position = resyncState.position();
        return new CultWrite(
                new ClientboundMoveVehicle(
                        new Vec3d(position.x, position.y, position.z), resyncState.yaw(), resyncState.pitch()),
                false);
    }

    private CultWrite selfDismountTeleportPacket(int teleportId, Vec3 position, Vec3 velocity) {
        return new CultWrite(
                new ClientboundPlayerPosition(
                        teleportId,
                        new Vec3d(position.x, position.y, position.z),
                        new Vec3d(velocity.x, velocity.y, velocity.z),
                        player.xRot,
                        player.yRot,
                        Set.of()),
                false);
    }

    private boolean shouldResyncVehicleOnMount(PacketEntity vehicle, int[] passengers) {
        return player.compensatedEntities.vehicles.shouldProtocolResyncOnMount(vehicle, passengers);
    }

    private void queueVehicleProtocolResync(int transaction, VehicleMountResyncState resyncState) {
        if (isBedrockVehicleResync(resyncState.vehicleId())) {
            player.getSetbackTeleportUtil()
                    .addBedrockVehicleTeleport(resyncState.vehicleId(), transaction, resyncState.position());
        } else if (player.isBedrockMovement() || player.getClientVersion() != ClientVersion.V_1_21_2) {
            player.getSetbackTeleportUtil()
                    .addVehicleTeleport(resyncState.vehicleId(), transaction, resyncState.position());
        }
        // Protocol 768's generated MoveVehicle packet goes through the outbound
        // PacketServerTeleport listener, which records its exact echo. A second
        // entry here consumes the following real vehicle tick at the same
        // position, losing its gravity/ground carry (no onGround wire field).
        player.getSetbackTeleportUtil().updateSafeVehiclePosition(resyncState.position());
    }

    private void queueVehicleProtocolResyncAndApply(int transaction, VehicleMountResyncState resyncState) {
        queueVehicleProtocolResync(transaction, resyncState);
        player.latencyUtils.addRealTimeTask(transaction, () -> applyVehicleProtocolResync(transaction, resyncState));
    }

    private void applyVehicleProtocolResync(int transaction, VehicleMountResyncState resyncState) {
        if (isBedrockVehicleResync(resyncState.vehicleId())) {
            player.getSetbackTeleportUtil()
                    .sendBedrockMovementCorrection(
                            resyncState.vehicleId(),
                            resyncState.position(),
                            resyncState.velocity(),
                            resyncState.yaw(),
                            resyncState.pitch(),
                            Boolean.TRUE.equals(resyncState.onGround()),
                            transaction);
            return;
        }
        player.compensatedEntities.vehicles.applyAcceptedVehicleTeleportEntityState(
                resyncState.vehicleId(),
                resyncState.position(),
                resyncState.yaw(),
                resyncState.pitch(),
                resyncState.onGround(),
                resyncState.velocity(),
                resyncState.interpolates());
    }

    private boolean isBedrockVehicleResync(int vehicleId) {
        return player.isBedrockMovement()
                && BedrockVehicleControl.isSupported(player.compensatedEntities.getEntity(vehicleId));
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
        if (!isBedrockVehicleResync(vehicleId) && (forceSnapNudge || vehicleMoveWouldNotSnap(vehicle, position))) {
            position = nudgeVehicleMoveResyncPosition(position);
        }

        return new VehicleMountResyncState(
                position,
                trackedPosition.getXRot(),
                trackedPosition.getYRot(),
                null,
                PacketCodecUtil.quantizeLpVec3(Vec3.ZERO),
                vehicleId,
                false);
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

    private record VehicleMountResyncState(
            Vec3 position,
            float yaw,
            float pitch,
            @Nullable Boolean onGround,
            Vec3 velocity,
            int vehicleId,
            boolean interpolates) {}

    private record SelfDismountResyncState(Vec3 position, Vec3 velocity, int teleportId) {}

    private void handleRemoveEntities(
            PacketSendEvent<ClientboundRemoveEntities> event, ClientboundRemoveEntities packet) {
        List<Integer> destroyEntityIds = packet.entityIds();
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
            boolean wasPassenger = player.compensatedEntities.getSelf().inVehicle();
            for (int integer : destroyEntityIds) {
                if (!player.isBedrockMovement()) player.compensatedEntities.removeEntity(integer);
                player.compensatedFireworks.removeFirework(integer);
            }
            if (wasPassenger
                    && !player.compensatedEntities.getSelf().inVehicle()
                    && !player.isBedrockMovement()
                    && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)) {
                // Player#tick clears the passenger's ground state; vehicle reports
                // update the runner with the root's state instead. RC1 no longer
                // sends a PosRot teleport echo to restore it before the next travel.
                player.onGround = player.packetStateData.packetPlayerOnGround;
                player.checkManager
                        .getSimulationProcessor()
                        .setLastOnGround(
                                ac.cult.cultac.checks.impl.prediction.DesyncStatus.fromBoolean(player.onGround));
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

    private Vec3 calculateAbsoluteDelta(
            Vec3 currentDelta,
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
                flags.isSet(RelativeFlag.DELTA_Z.getMask()) ? transformed.z + changeDelta.z : changeDelta.z);
    }

    private int appendTrailingProofTransactionId(PacketSendEvent<?> event) {
        CultPlayer.TrackedTransaction proof = appendTrailingProofTransaction(event);
        return proof == null ? -1 : proof.transaction();
    }

    private CultPlayer.TrackedTransaction appendTrailingProofTransaction(PacketSendEvent<?> event) {
        return appendTrailingProofTransaction(event, List.of(), shouldUseEntityTrackingBundleDelimiter());
    }

    @Nullable
    public CultPlayer.TrackedTransaction appendVehicleProtocolProofTransaction(PacketSendEvent<?> event) {
        return appendTrailingProofTransaction(event, List.of(), shouldUseVehicleProtocolBundleDelimiter());
    }

    @Nullable
    public CultPlayer.TrackedTransaction appendPlainProofTransaction(PacketSendEvent<?> event) {
        return appendTrailingProofTransaction(event, List.of(), false);
    }

    private CultPlayer.TrackedTransaction appendTrailingProofTransaction(
            PacketSendEvent<?> event, List<CultWrite> packetsBeforeProof, boolean requestedDelimiter) {
        CultPlayer.TrackedTransaction proof = player.createTrackedTransactionPacketForBundle();
        if (proof == null) {
            return null;
        }

        if (requestedDelimiter) event.getWritesBeforeSend().add(bundleDelimiter());
        event.getWritesAfterSend().addAll(packetsBeforeProof);
        event.getWritesAfterSend().add(proof.packet());
        if (requestedDelimiter) event.getWritesAfterSend().add(bundleDelimiter());
        event.getTasksAfterSend().add(() -> player.markTrackedTransactionPacketSent(proof));
        return proof;
    }

    private void writeProofGroupNow(CultPlayer.TrackedTransaction proof, List<CultWrite> packetsBeforeProof) {
        var packets = new ArrayList<CultWrite>(packetsBeforeProof);
        packets.add(proof.packet());
        player.user.write(packets, shouldUseVehicleProtocolBundleDelimiter());
        player.markTrackedTransactionPacketSent(proof);
    }

    private static CultWrite bundleDelimiter() {
        return new CultWrite(ac.cult.cultac.protocol.packet.ClientboundPackets.BUNDLE_DELIMITER.opaqueValue(), false);
    }

    private boolean shouldUseEntityTrackingBundleDelimiter() {
        return useReachBundleDelimiter && player.supportsBundles();
    }

    private boolean shouldUseVehicleProtocolBundleDelimiter() {
        return player.supportsBundles();
    }

    private void handleMoveEntity(PacketSendEvent<?> event, EntityMovement movement) {
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
                        position.z());
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

        // Geyser may emit several client positions over later ticks. Java packets update
        // server authority here; the outgoing Bedrock stream owns visible transforms.
        if (player.isBedrockMovement()) {
            PacketEntity controlledVehicle = BedrockVehicleControl.controlledVehicle(player);
            if (movement.kind() == MovementKind.TELEPORT_ENTITY
                    && movement.hasPosition()
                    && absolutePositionKnown
                    && player.compensatedEntities.vehicles.serverPlayerVehicle != null
                    && player.compensatedEntities.vehicles.serverPlayerVehicle == movement.entityId()
                    && controlledVehicle != null
                    && controlledVehicle.getEntityId() == movement.entityId()) {
                Vec3 target = new Vec3(absoluteX, absoluteY, absoluteZ);
                int transaction = appendTrailingProofTransactionId(event);
                if (transaction < 0) transaction = player.lastTransactionSent.get();
                player.getSetbackTeleportUtil().addBedrockVehicleTeleport(movement.entityId(), transaction, target);
                player.getSetbackTeleportUtil().updateSafeVehiclePosition(target);
                player.getSetbackTeleportUtil()
                        .sendBedrockMovementCorrection(
                                movement.entityId(),
                                target,
                                movement.deltaMovement().movement(),
                                movement.yaw(),
                                movement.pitch(),
                                Boolean.TRUE.equals(movement.onGround()),
                                transaction);
            }
            clearPacketHandlerDeltaMovement(movement);
            return;
        }

        CultPlayer.TrackedTransaction proof =
                useDelimiterProof ? appendTrailingProofTransaction(event, List.of(), true) : null;
        int firstTransaction = proof == null ? player.lastTransactionSent.get() : proof.transaction();
        if (data != null) {
            data.setLastTransactionHung(firstTransaction);
            if (data.getEntityType() == EntityTypeIds.PLAYER) {
                player.checkManager
                        .getListener(FairReach.class)
                        .handleEntityMove(movement.entityId(), data.getX(), data.getY(), data.getZ());
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
                    movement.deltaMovement().movement());
            player.getSetbackTeleportUtil()
                    .addVehicleTeleport(movement.entityId(), firstTransaction, vehicleTarget, vehicleTeleportData);
            player.getSetbackTeleportUtil().updateSafeVehiclePosition(vehicleTarget);
        }
        if (!ignoredMovement) {
            recordPacketHandlerTransform(
                    movement.entityId(),
                    movement.hasPosition(),
                    absoluteX,
                    absoluteY,
                    absoluteZ,
                    movement.yaw(),
                    movement.pitch());
        }

        if (position.relative()
                && !player.isBedrockMovement()
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)) {
            if (!absolutePositionKnown) return; // Unknown entities are discarded by the client.
            // Decode against the packet-order codec base before queuing the proof.
            // Later relative packets may advance that base before this task runs.
            movement = EntityMovement.relativeEntityPacket(
                    movement.entityId(),
                    movement.hasPosition(),
                    EntityPositionPath.linear(new Vec3(absoluteX, absoluteY, absoluteZ)),
                    movement.rotation(),
                    movement.onGround());
        }
        if (proof != null) {
            scheduleBundledMovement(movement, proof.transaction());
        } else {
            scheduleTransactionBoundMovement(movement, firstTransaction);
        }
    }

    private void scheduleBundledMovement(EntityMovement movement, int proofTransaction) {
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
            if (applyClientPath(entity, movement, true)) {
                // Native RC1 interpolation path was appended at this proof.
            } else if (movement.hasPosition()) {
                entity.onBundledPositionSyncTransaction(
                        movement.position().x(),
                        movement.position().y(),
                        movement.position().z(),
                        movement.yaw(),
                        movement.pitch(),
                        player);
            } else {
                applyBundledMovement(entity, movement);
            }
            applyDeltaMovementUpdate(entity, movement.deltaMovement());
            clearPacketHandlerDeltaMovement(movement);
            player.compensatedEntities.updatePassengerPositions();
        });
    }

    private void scheduleDelimiterBoundMovement(EntityMovement movement, int proofTransaction) {
        int entityId = movement.entityId();
        MovementKind kind = movement.kind();
        int deltaMovementSequence = movement.deltaMovement().packetHandlerSequence();
        player.latencyUtils.addRealTimeTaskWithNextTransaction(
                proofTransaction,
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

    private void scheduleTransactionBoundMovement(EntityMovement movement, int firstTransaction) {
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
            player.latencyUtils.addRealTimeTask(
                    firstTransaction + 1, () -> applySecondMovement(entityId, kind, deltaMovementSequence, false));
        });
    }

    private boolean applyClientPath(PacketEntity entity, EntityMovement movement, boolean bundled) {
        if (player.isBedrockMovement() || player.getClientVersion().isOlderThan(ClientVersion.V_26_3)) return false;
        PositionChange position = movement.position();
        EntityPositionPath path = position instanceof PathPositionChange p
                ? p.path()
                : EntityPositionPath.linear(new Vec3(position.x(), position.y(), position.z()));
        if (movement.kind() == MovementKind.POSITION_SYNC
                && (!player.compensatedEntities.isTicking(entity)
                        || entity.clientPhysicalPosition.distanceToSqr(path.endPosition()) > 4096.0D)) {
            Vec3 target = path.endPosition();
            entity.setPositionRaw(
                    ac.cult.cultac.utils.nmsutil.GetBoundingBox.getPacketEntityBoundingBox(
                            player, target.x, target.y, target.z, entity),
                    movement.yaw(),
                    movement.pitch());
            entity.onSecondTransaction();
            return true;
        }
        entity.onPositionPath(path, position.hasPosition(), movement.yaw(), movement.pitch(), player, bundled);
        return true;
    }

    private void applyFirstMovement(PacketEntity entity, EntityMovement movement) {
        // TODO: Figure out how to accomplish this pathing type interpolation without bundles
        if (applyClientPath(entity, movement, false)) return;
        PositionChange position = movement.position();
        entity.onFirstTransaction(
                position.relative(),
                position.hasPosition(),
                position.x(),
                position.y(),
                position.z(),
                movement.yaw(),
                movement.pitch(),
                player);
    }

    private void applyBundleFirstMovement(PacketEntity entity, EntityMovement movement) {
        if (applyClientPath(entity, movement, true)) return;
        PositionChange position = movement.position();
        entity.onBundleFirstTransaction(
                position.relative(),
                position.hasPosition(),
                position.x(),
                position.y(),
                position.z(),
                movement.yaw(),
                movement.pitch(),
                player);
    }

    private void applyBundledMovement(PacketEntity entity, EntityMovement movement) {
        if (applyClientPath(entity, movement, true)) return;
        PositionChange position = movement.position();
        entity.onBundleTransaction(
                position.relative(),
                position.hasPosition(),
                position.x(),
                position.y(),
                position.z(),
                movement.yaw(),
                movement.pitch(),
                player);
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

    private void applySecondMovement(
            int entityId, MovementKind kind, int packetHandlerDeltaMovementSequence, boolean updatePassengerPositions) {
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
        return movement.entityId() == player.entityID || movement.kind().shouldSkip(this, movement.entityId());
    }

    private boolean queuesVehicleTeleportResponse(EntityMovement movement) {
        // ClientPacketListener#handleTeleportEntity can echo a MoveVehicle
        // packet for local-authoritative ridden roots. EntityPositionSync only
        // updates the position codec for those roots and sends no response.
        return movement.kind() == MovementKind.TELEPORT_ENTITY;
    }

    private void recordPacketHandlerTransform(
            int entityId, boolean hasPos, double x, double y, double z, @Nullable Float yaw, @Nullable Float pitch) {
        PacketEntity entity = player.compensatedEntities.getEntity(entityId);
        TrackerData data = player.compensatedEntities.getTrackedEntity(entityId);
        PacketHandlerEntityTransform source = packetHandlerTransformSource(entity, data);
        double nextX = hasPos ? x : source.x();
        double nextY = hasPos ? y : source.y();
        double nextZ = hasPos ? z : source.z();
        packetHandlerEntityTransforms.put(
                entityId,
                new PacketHandlerEntityTransform(
                        nextX, nextY, nextZ, yaw == null ? source.yRot() : yaw, pitch == null ? source.xRot() : pitch));
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
        packetHandlerDeltaMovements.put(
                entityId, new PacketHandlerDeltaMovement(movement.x, movement.y, movement.z, sequence));
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
        clearPacketHandlerDeltaMovement(
                movement.entityId(), movement.deltaMovement().packetHandlerSequence());
    }

    private Vec3 packetHandlerDeltaMovementSource(PacketEntity entity) {
        if (entity == null) {
            return Vec3.ZERO;
        }

        PacketHandlerDeltaMovement movement = packetHandlerDeltaMovements.get(entity.getEntityId());
        return movement == null ? entity.deltaMovement : new Vec3(movement.x(), movement.y(), movement.z());
    }

    public void addEntity(
            int entityID, int type, Vec3 position, float xRot, float yRot, int extraData, int transaction) {
        addEntity(entityID, type, position, xRot, yRot, extraData, transaction, null);
    }

    private void addEntity(
            int entityID,
            int type,
            Vec3 position,
            float xRot,
            float yRot,
            int extraData,
            int transaction,
            java.util.UUID uuid) {
        int spawnTransaction = transaction >= 0 ? transaction : player.lastTransactionSent.get();
        player.compensatedEntities.serverPositionsMap.put(
                entityID, new TrackerData(position.x, position.y, position.z, xRot, yRot, type, spawnTransaction));
        Runnable addTask = () -> {
            player.compensatedEntities.addEntity(entityID, type, position, xRot, yRot, extraData);
            if (uuid != null
                    && player.compensatedEntities.getEntity(entityID)
                            instanceof ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer remote) {
                remote.profile = uuid;
                // ClientPacketListener skips player spawns without a preceding PlayerInfo entry.
                remote.actionPresent = player.compensatedEntities.clientPlayerModes.get(uuid) != null;
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
        return (player.compensatedEntities.vehicles.serverPlayerVehicle == null && entityID == player.entityID)
                || (player.compensatedEntities.vehicles.serverPlayerVehicle != null
                        && entityID == player.compensatedEntities.vehicles.serverPlayerVehicle);
    }

    public void onEndOfTickEvent() {
        player.sendTransaction(false);
    }

    public void tickStartTick() {
        hasSentPreWavePacket = false;
    }

    private static MovementEffect toMovementEffect(String effect) {
        return switch (effect) {
            case "minecraft:blindness" -> MovementEffect.BLINDNESS;
            case "minecraft:conduit_power" -> MovementEffect.CONDUIT_POWER;
            case "minecraft:dolphins_grace" -> MovementEffect.DOLPHINS_GRACE;
            case "minecraft:haste" -> MovementEffect.HASTE;
            case "minecraft:jump_boost" -> MovementEffect.JUMP_BOOST;
            case "minecraft:levitation" -> MovementEffect.LEVITATION;
            case "minecraft:mining_fatigue" -> MovementEffect.MINING_FATIGUE;
            case "minecraft:speed" -> MovementEffect.SPEED;
            case "minecraft:slowness" -> MovementEffect.SLOWNESS;
            case "minecraft:slow_falling" -> MovementEffect.SLOW_FALLING;
            case "minecraft:weaving" -> MovementEffect.WEAVING;
            default -> null;
        };
    }

    private record TeleportChange(Vec3 position, Vec3 deltaMovement, float yRot, float xRot) {}

    private record TeleportEntityData(int entityId, TeleportChange change, RelativeFlag flags, boolean onGround) {}
}
