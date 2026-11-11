package ac.cult.cultac.events.packets;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.combat.FairReach;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
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
import ac.cult.cultac.player.PlayerObserver;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundDamageEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveMinecart;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
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
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.data.TeleportAcceptData;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.VehicleTeleportData;
import ac.cult.cultac.utils.data.packetentity.ClientSaddle;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityFallingBlock;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHook;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHurtingProjectile;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRavager;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.data.packetentity.PacketEntityTrackXRot;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public class PacketEntityReplication extends CultProcessor implements CheckListener, ClientTickEndListener {
    private static final int VIA_INTERPOLATION_STEP_TICKS = 3;
    private static final Vec3 SELF_DISMOUNT_VELOCITY = Vec3.ZERO;
    /**
     * Per-axis size of the mount resync's snap nudge. Its length, about 1.21E-5, is past the 1.0E-5F at which
     * ClientPacketListener#handleMoveVehicle snaps. Each axis stays under the 1.0E-5F by which Paper's
     * ServerGamePacketListenerImpl#isEntityCollidingWithAnythingNew shrinks the box, so the client's echo of the
     * nudge reaches no block the vehicle rests against.
     */
    private static final double VEHICLE_MOVE_RESYNC_SNAP_NUDGE = 7.0E-6D;
    private final PacketHandlerEntityTransform transformScratch = new PacketHandlerEntityTransform();
    // Client ticks ended; a TrackerData handler transform holds only in the tick it was written in.
    private long transformTick;
    private int packetHandlerDeltaMovementSequence = 0;
    /**
     * Always on. Only the live smoketests turn it off, with {@code CULT_VALIDATION_REACH_BUNDLE_DELIMITER=false},
     * to exercise the transaction-only proof path.
     */
    private static final boolean REACH_BUNDLE_DELIMITER =
            !"false".equalsIgnoreCase(System.getenv("CULT_VALIDATION_REACH_BUNDLE_DELIMITER"));

    private boolean useReachBundleDelimiter = REACH_BUNDLE_DELIMITER;
    private boolean hasSentPreWavePacket = true;
    private int removedPlayerVehicleId = Integer.MIN_VALUE;

    /**
     * One clientbound entity movement. A relative move holds its delta until the packet handler decodes it against the
     * codec base; the movement is itself the task its proof runs.
     */
    private final class EntityMovement implements Runnable {
        private final int entityId;
        private final MovementKind kind;
        private boolean relative;
        private final boolean hasPosition;
        private double x;
        private double y;
        private double z;
        // A stepped client path; null for a linear one, which ends at x, y, z.
        private @Nullable EntityPositionPath path;
        private final boolean hasRotation;
        private final float rotationYaw;
        private final float rotationPitch;
        private final @Nullable Boolean onGround;
        private final DeltaMovementChange deltaMovement;
        private final boolean relativeToClientPosition;
        private int proofTransaction;
        private @Nullable ControlledRootTeleport controlledRootTeleport;

        private EntityMovement copy(DeltaMovementChange delta) {
            EntityMovement copy = new EntityMovement(
                    entityId,
                    kind,
                    relative,
                    hasPosition,
                    x,
                    y,
                    z,
                    path,
                    hasRotation,
                    rotationYaw,
                    rotationPitch,
                    onGround,
                    delta,
                    relativeToClientPosition);
            copy.controlledRootTeleport = controlledRootTeleport;
            return copy;
        }

        private EntityMovement withControlledRootTeleport(@Nullable ControlledRootTeleport teleport) {
            EntityMovement copy = copy(deltaMovement);
            copy.controlledRootTeleport = teleport;
            return copy;
        }

        private @Nullable ControlledRootTeleport controlledRootTeleport() {
            return controlledRootTeleport;
        }

        /** This movement as the client's handler applies it. */
        private EntityMovement asHandled() {
            return controlledRootTeleport == null ? this : controlledRootTeleport.movement();
        }

        private EntityMovement(
                int entityId,
                MovementKind kind,
                boolean relative,
                boolean hasPosition,
                double x,
                double y,
                double z,
                @Nullable EntityPositionPath path,
                boolean hasRotation,
                float rotationYaw,
                float rotationPitch,
                @Nullable Boolean onGround,
                DeltaMovementChange deltaMovement,
                boolean relativeToClientPosition) {
            this.entityId = entityId;
            this.kind = kind;
            this.relative = relative;
            this.hasPosition = hasPosition;
            this.x = x;
            this.y = y;
            this.z = z;
            this.path = path;
            this.hasRotation = hasRotation;
            this.rotationYaw = rotationYaw;
            this.rotationPitch = rotationPitch;
            this.onGround = onGround;
            this.deltaMovement = deltaMovement;
            this.relativeToClientPosition = relativeToClientPosition;
        }

        /** The client path to x, y, z; call only once the position is absolute. */
        private EntityPositionPath path() {
            return path != null ? path : EntityPositionPath.linear(new Vec3(x, y, z));
        }

        /** Replaces the delta with the position it decoded to, before the movement is scheduled. */
        private void decodedTo(double x, double y, double z) {
            relative = false;
            this.x = x;
            this.y = y;
            this.z = z;
            path = null;
        }

        @Nullable
        private Float yaw() {
            return hasRotation ? rotationYaw : null;
        }

        @Nullable
        private Float pitch() {
            return hasRotation ? rotationPitch : null;
        }

        @Override
        public void run() {
            applyAtProof(this);
        }
    }

    private EntityMovement relativeEntityPacket(
            int entityId,
            boolean hasPosition,
            EntityPositionPath path,
            boolean hasRotation,
            float yaw,
            float pitch,
            boolean onGround) {
        Vec3 end = path.endPosition();
        return new EntityMovement(
                entityId,
                MovementKind.RELATIVE_ENTITY,
                false,
                hasPosition,
                end.x,
                end.y,
                end.z,
                path,
                hasRotation,
                yaw,
                pitch,
                onGround,
                DeltaMovementChange.NONE,
                false);
    }

    private EntityMovement linearRelativeEntityPacket(
            int entityId,
            boolean hasPosition,
            double x,
            double y,
            double z,
            boolean hasRotation,
            float yaw,
            float pitch,
            boolean onGround) {
        return new EntityMovement(
                entityId,
                MovementKind.RELATIVE_ENTITY,
                false,
                hasPosition,
                x,
                y,
                z,
                null,
                hasRotation,
                yaw,
                pitch,
                onGround,
                DeltaMovementChange.NONE,
                false);
    }

    private EntityMovement legacyRelativeEntityPacket(
            int entityId,
            boolean hasPosition,
            int xa,
            int ya,
            int za,
            boolean hasRotation,
            float yaw,
            float pitch,
            boolean onGround) {
        return new EntityMovement(
                entityId,
                MovementKind.RELATIVE_ENTITY,
                true,
                hasPosition,
                (short) xa / 4096.0D,
                (short) ya / 4096.0D,
                (short) za / 4096.0D,
                null,
                hasRotation,
                yaw,
                pitch,
                onGround,
                DeltaMovementChange.NONE,
                false);
    }

    private EntityMovement teleportEntityPacket(
            int entityId,
            Vec3 position,
            float yaw,
            float pitch,
            boolean onGround,
            DeltaMovementChange deltaMovement,
            boolean relativeToClientPosition) {
        return new EntityMovement(
                entityId,
                MovementKind.TELEPORT_ENTITY,
                false,
                true,
                position.x,
                position.y,
                position.z,
                null,
                true,
                yaw,
                pitch,
                onGround,
                deltaMovement,
                relativeToClientPosition);
    }

    private EntityMovement positionSyncPacket(
            int entityId, EntityPositionPath path, float yaw, float pitch, boolean onGround) {
        Vec3 end = path.endPosition();
        return new EntityMovement(
                entityId,
                MovementKind.POSITION_SYNC,
                false,
                true,
                end.x,
                end.y,
                end.z,
                path,
                true,
                yaw,
                pitch,
                onGround,
                DeltaMovementChange.NONE,
                false);
    }

    private EntityMovement minecartPacket(int entityId, double x, double y, double z, float yaw, float pitch) {
        return new EntityMovement(
                entityId,
                MovementKind.MINECART,
                false,
                true,
                x,
                y,
                z,
                null,
                true,
                yaw,
                pitch,
                null,
                DeltaMovementChange.NONE,
                false);
    }

    /** {@code riderRelative} keeps a teleport relative to the rider's known movement; x, y and z then estimate it. */
    private record DeltaMovementChange(
            boolean present,
            double x,
            double y,
            double z,
            int packetHandlerSequence,
            @Nullable RiderRelativeDelta riderRelative) {
        private static final DeltaMovementChange NONE = new DeltaMovementChange(false, 0.0D, 0.0D, 0.0D, -1, null);

        private static DeltaMovementChange fixed(Vec3 movement, int packetHandlerSequence) {
            return new DeltaMovementChange(true, movement.x, movement.y, movement.z, packetHandlerSequence, null);
        }

        private static DeltaMovementChange riderRelative(Vec3 estimate, RiderRelativeDelta relative) {
            return new DeltaMovementChange(true, estimate.x, estimate.y, estimate.z, -1, relative);
        }

        private DeltaMovementChange withSequence(int packetHandlerSequence) {
            return new DeltaMovementChange(present, x, y, z, packetHandlerSequence, riderRelative);
        }

        private Vec3 movement() {
            return new Vec3(x, y, z);
        }
    }

    /** What PositionMoveRotation#calculateAbsolute adds to, or rotates, the known movement it starts from. */
    private record RiderRelativeDelta(
            Vec3 change, float sourceYaw, float sourcePitch, float finalYaw, float finalPitch, RelativeFlag flags) {}

    private enum MovementKind {
        RELATIVE_ENTITY(true),
        TELEPORT_ENTITY(false),
        POSITION_SYNC(true),
        MINECART(false);

        private final boolean skipLocalAuthoritativeRoot;

        MovementKind(boolean skipLocalAuthoritativeRoot) {
            this.skipLocalAuthoritativeRoot = skipLocalAuthoritativeRoot;
        }

        /** Whether the client will skip this packet, judged when it is sent. */
        private boolean shouldSkipAsSent(PacketEntityReplication replication, int entityId) {
            return skipLocalAuthoritativeRoot && replication.isLocalAuthoritativeRootVehicleAsSent(entityId);
        }

        /** Whether the client skipped this packet, judged once its proof shows the client handled it. */
        private boolean shouldSkipAsHandled(PacketEntityReplication replication, int entityId) {
            return skipLocalAuthoritativeRoot && replication.isLocalAuthoritativeVehicleAsHandled(entityId);
        }
    }

    /** A packet-handler transform read from TrackerData or the client's current state. */
    private static final class PacketHandlerEntityTransform {
        private double x;
        private double y;
        private double z;
        private float yRot;
        private float xRot;

        private PacketHandlerEntityTransform set(double x, double y, double z, float yRot, float xRot) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yRot = yRot;
            this.xRot = xRot;
            return this;
        }

        private Vec3 position() {
            return new Vec3(x, y, z);
        }
    }

    public PacketEntityReplication(CultPlayer player) {
        super(player);
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        if (player.isBedrockMovement()) {
            tickEntityInterpolationAtClientTickEnd();
            player.compensatedEntities.vehicles.onClientTickEnd();
            player.checkManager.getSimulationProcessor().recordRideTick();
        } else {
            // ClientLevel#tickEntities interpolates and pushes before the same tick's block-entity pass.
            try (var ignored =
                    player.compensatedWorld.beginBlockEntityReplay(player.compensatedWorld.lastBlockEntityPass())) {
                tickEntityInterpolationAtClientTickEnd();
                player.compensatedEntities.vehicles.onClientTickEnd();
                player.checkManager.getSimulationProcessor().recordRideTick();
            }
        }
        if (player.isBedrockMovement()
                || !player.checkManager.getSimulationProcessor().hasRideTick())
            player.compensatedEntities.recordRideTickSeat();
        PacketEntity boat = player.compensatedEntities.getSelf().getRiding();
        if (player.compensatedEntities.vehicles.isRiddenDrivenBoat(boat))
            ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaRiddenBoatPass.afterTick(player, boat);
        player.compensatedEntities.vehicles.updateServerControlledSafeSetbackPositionOnTickEnd();
        transformTick++;
    }

    /** A Bedrock client tick's lerp step for every remote actor, taken when its PlayerAuthInput is handled. */
    public void tickBedrockClient() {
        tickEntityInterpolationAtClientTickEnd();
        transformTick++;
    }

    private void tickEntityInterpolationAtClientTickEnd() {
        player.compensatedEntities.onClientTick();
        if (player.isBedrockMovement() && player.bedrockState != null) player.bedrockState.clientActors.tick();
        PacketEntity velocityVehicle = player.compensatedEntities.vehicles.getVelocityMovementVehicle();
        java.util.function.Consumer<PacketEntity> tick = entity -> {
            if (player.isBedrockMovement()
                    && entity
                            == ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl.controlledVehicle(
                                    player)) return;
            if (player.compensatedEntities.vehicles.isRiddenDrivenBoat(entity)) {
                ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaRiddenBoatTick.tick(player, entity);
                return;
            }
            if (entity == velocityVehicle && player.compensatedEntities.vehicles.isLocallyTickedVelocityRoot(entity)) {
                return;
            }
            if (player.isBedrockMovement() && player.compensatedEntities.isInBedrockLimbo(entity)) return;
            entity.onMovement(player, true);
            player.compensatedEntities.vehicles.tickRemoteLivingVelocity(entity);
            // LivingEntity#aiStep replaces frost after interpolating, for remote entities too.
            entity.tickClientFrost(player);
        };
        if (!player.isBedrockMovement() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_17)) {
            player.compensatedEntities.tickClientEntities(tick);
        } else {
            player.compensatedEntities.forEachTrackedEntity(tick);
        }
    }

    // Keep the compensated camera entity synchronized.
    @CultPacketHandler
    public void onSetCamera(
            PacketSendEvent<ClientboundSetCamera> event, CultPlayer player, ClientboundSetCamera packet) {
        player.cameraEntity.onSetCamera(packet.entityId());
    }

    private boolean existsAsSent(int entityId) {
        return player.compensatedEntities.existsAsSent(entityId);
    }

    @CultPacketHandler
    public void onAddEntity(
            PacketSendEvent<ClientboundAddEntity> event, CultPlayer player, ClientboundAddEntity packet) {
        if (!player.isBedrockMovement() && existsAsSent(packet.entityId()) && packet.entityId() != player.entityID) {
            // ClientLevel#addEntity first discards the entity holding this ID: it stops riding and ejects its riders.
            player.compensatedEntities.vehicles.onSentRemoval(packet.entityId());
            Integer serverVehicle = player.compensatedEntities.vehicles.serverPlayerVehicle;
            if (serverVehicle != null
                    && serverVehicle == packet.entityId()
                    && !player.compensatedEntities.vehicles.hasPendingServerDismount()) {
                player.compensatedEntities.vehicles.clearServerVehicle();
            }
        }
        // Geyser spawns no Bedrock entity for a type it does not define: the client never holds it, rides it or
        // collides with it, so it never enters a Bedrock player's world.
        if (player.isBedrockMovement()
                && !ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime.translatesEntity(packet.entityType()))
            return;
        if (removedPlayerVehicleId == packet.entityId()) {
            removedPlayerVehicleId = Integer.MIN_VALUE;
        }
        // A removal and a respawn of one id in the same flush share its ping and apply in packet order.
        int transaction = proveHandled(event, Proof.SAME_TASK);
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
                packet.uuid(),
                PacketCodecUtil.clientVelocityFromObserved(
                        event.getDecodedProtocol(),
                        player.getClientVersion(),
                        new Vec3(
                                packet.velocity().x(),
                                packet.velocity().y(),
                                packet.velocity().z())));
    }

    @CultPacketHandler
    public void onSetEntityMotion(
            PacketSendEvent<ClientboundEntityMotion> event, CultPlayer player, ClientboundEntityMotion packet) {
        ClientboundEntityMotion motion = packet;
        if (player.isBedrockMovement() && motion.entityId() != player.entityID) return;
        int velocityTransaction = player.checkManager.getKnockbackHandler().handleEntityVelocity(event, packet);
        if (velocityTransaction <= 0 && readsVelocityOnReceipt(motion.entityId())) {
            velocityTransaction = proveHandled(event, entityProof());
        }
        if (velocityTransaction <= 0) {
            velocityTransaction = player.lastTransactionSent.get();
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
                    entity.setPacketDeltaMovement(movement);
                }
                if (entity == player.compensatedEntities.playerEntity) {
                    player.checkManager.getSimulationProcessor().replaceRideTickVelocity(movement);
                }
            }
            clearPacketHandlerDeltaMovement(motion.entityId(), packetHandlerDeltaSequence);
        });
    }

    // Movement, projectile and falling block ticks and the camera's Entity#getKnownMovement consume receipt-time
    // velocity.
    // Other entities retain it until a later proven packet makes them the vehicle.
    private boolean readsVelocityOnReceipt(int entityId) {
        Integer serverVehicle = player.compensatedEntities.vehicles.serverPlayerVehicle;
        TrackerData tracked = player.compensatedEntities.getTrackedEntity(entityId);
        PacketEntity entity = player.compensatedEntities.getEntity(entityId);
        return entityId == player.entityID
                || serverVehicle != null && serverVehicle == entityId
                || player.cameraEntity.isServerCamera(entityId)
                || entity instanceof PacketEntityHook
                || tracked != null && tracked.getEntityType() == EntityTypeIds.FISHING_BOBBER
                || entity instanceof PacketEntityHurtingProjectile
                || entity instanceof PacketEntityFallingBlock
                || tracked != null
                        && (PacketEntityHurtingProjectile.isHurtingProjectile(tracked.getEntityType())
                                || tracked.getEntityType() == EntityTypeIds.FALLING_BLOCK);
    }

    @CultPacketHandler
    public void onMoveEntity(
            PacketSendEvent<ClientboundMoveEntity> event, CultPlayer player, ClientboundMoveEntity packet) {
        int entityId = packet.entityId();
        if (player.getClientVersion().isOlderThan(ClientVersion.V_26_3)) {
            // Pre-26.3 clients receive three shorts. ViaBackwards 26.3->26.2
            // (EntityPacketRewriter26_3#handleMovePos) sums a stepped delta into one.
            var delta = linearDelta(packet.delta());
            handleMoveEntity(
                    event,
                    legacyRelativeEntityPacket(
                            entityId,
                            packet.hasPosition(),
                            delta.x(),
                            delta.y(),
                            delta.z(),
                            packet.hasRotation(),
                            packet.yaw(),
                            packet.pitch(),
                            packet.onGround()));
            return;
        }
        TrackerData tracked = player.compensatedEntities.getTrackedEntity(entityId);
        if (tracked == null) return; // The client also discards movement for an unknown entity.
        // ClientPacketListener#handleMoveEntity only updates the position codec
        // for local-authoritative roots. Applying interpolation waits for the
        // mounted tick's Rot+MoveVehicle or Rot+TickEnd proof.
        handleMoveEntity(event, decodedRelativeEntityPacket(packet, tracked), tracked);
    }

    /** The move decoded against a copy of the codec base; only the packet handler commits the endpoint. */
    private EntityMovement decodedRelativeEntityPacket(ClientboundMoveEntity packet, TrackerData tracked) {
        double x = tracked.getCodecBaseX();
        double y = tracked.getCodecBaseY();
        double z = tracked.getCodecBaseZ();
        if (packet.hasPosition()) {
            EntityPositionPath path;
            if (packet.delta() instanceof EntityDelta.Linear linear) {
                x = PacketCodecUtil.decodeRelativeEntityCoordinate(x, (long) linear.x());
                y = PacketCodecUtil.decodeRelativeEntityCoordinate(y, (long) linear.y());
                z = PacketCodecUtil.decodeRelativeEntityCoordinate(z, (long) linear.z());
                path = receivesViaSteppedPaths() ? viaSteppedPath(new Vec3(x, y, z)) : null;
            } else {
                path = EntityPositionPath.decodeRelative(packet.delta(), new Vec3(x, y, z));
                if (receivesViaSteppedPaths()) path = viaSteppedPath(path.endPosition());
            }
            if (path != null) {
                return relativeEntityPacket(
                        packet.entityId(),
                        true,
                        path,
                        packet.hasRotation(),
                        packet.yaw(),
                        packet.pitch(),
                        packet.onGround());
            }
        }
        return linearRelativeEntityPacket(
                packet.entityId(),
                packet.hasPosition(),
                x,
                y,
                z,
                packet.hasRotation(),
                packet.yaw(),
                packet.pitch(),
                packet.onGround());
    }

    @CultPacketHandler
    public void onTeleportEntity(
            PacketSendEvent<ClientboundTeleportEntity> event, CultPlayer player, ClientboundTeleportEntity teleport) {
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
        EntityMovement path = teleportPath(teleport, change, packetFlags, entity, data, false);
        int sequence = recordPacketHandlerDeltaMovement(teleport.entityId(), path.deltaMovement.movement());
        EntityMovement movement = withDeltaSequence(path, sequence);
        if (!player.isBedrockMovement()
                && packetFlags.getMask() != 0
                && player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(entity)) {
            movement = movement.withControlledRootTeleport(
                    new ControlledRootTeleport(teleport, change, packetFlags, movement, sequence));
        }
        handleMoveEntity(event, movement);
    }

    /**
     * What ClientPacketListener#handleTeleportEntity sets, from the PositionMoveRotation#of(entity) it starts from:
     * the packet-handler transform at send, or the client's own transform once it has handled the packet.
     */
    private EntityMovement teleportPath(
            ClientboundTeleportEntity teleport,
            TeleportChange change,
            RelativeFlag flags,
            @Nullable PacketEntity entity,
            @Nullable TrackerData data,
            boolean handled) {
        PacketHandlerEntityTransform source = handled
                ? packetHandlerTransformFallback(entity, data, new PacketHandlerEntityTransform())
                : packetHandlerTransformSource(entity, data);
        Vec3 pos = calculateAbsolutePosition(source.position(), change.position(), flags);
        float finalYaw = calculateAbsoluteYaw(source.yRot, change.yRot(), flags);
        float finalPitch = calculateAbsolutePitch(source.xRot, change.xRot(), flags);
        DeltaMovementChange delta = teleportDeltaMovement(
                entity,
                new RiderRelativeDelta(change.deltaMovement(), source.yRot, source.xRot, finalYaw, finalPitch, flags));
        return teleportEntityPacket(
                teleport.entityId(), pos, finalYaw, finalPitch, teleport.onGround(), delta, flags.getMask() != 0);
    }

    /**
     * A relative teleport of a root the local player moves. ClientPacketListener#handleTeleportEntity adds it to the
     * client's transform when it handles the packet, and the client's own ticks keep moving that root until then. It
     * resolves once, at the first packet the client sends after handling it: the teleport's proof, or the MoveVehicle
     * that answers a teleport too far to interpolate from inside the handler.
     */
    private final class ControlledRootTeleport implements SetbackTeleportUtil.HandledVehicleTeleport {
        private final ClientboundTeleportEntity teleport;
        private final TeleportChange change;
        private final RelativeFlag flags;
        private final EntityMovement asSent;
        private final int sequence;
        private @Nullable EntityMovement handled;
        private @Nullable VehicleTeleportData state;

        private ControlledRootTeleport(
                ClientboundTeleportEntity teleport,
                TeleportChange change,
                RelativeFlag flags,
                EntityMovement asSent,
                int sequence) {
            this.teleport = teleport;
            this.change = change;
            this.flags = flags;
            this.asSent = asSent;
            this.sequence = sequence;
        }

        private EntityMovement movement() {
            resolve();
            return handled;
        }

        @Override
        public @Nullable VehicleTeleportData state(boolean resolve) {
            if (resolve) resolve();
            return state;
        }

        private void resolve() {
            if (handled != null) return;
            int entityId = teleport.entityId();
            PacketEntity entity = player.compensatedEntities.getEntity(entityId);
            handled = entity == null
                    ? asSent.withControlledRootTeleport(null)
                    : withDeltaSequence(
                            teleportPath(
                                    teleport,
                                    change,
                                    flags,
                                    entity,
                                    player.compensatedEntities.getTrackedEntity(entityId),
                                    true),
                            sequence);
            Vec3 target = new Vec3(handled.x, handled.y, handled.z);
            state = new VehicleTeleportData(
                    entityId,
                    target,
                    handled.yaw(),
                    handled.pitch(),
                    handled.onGround,
                    handled.deltaMovement.movement());
            // The client has handled the teleport by now, which placed the vehicle there.
            player.getSetbackTeleportUtil().proveSafeVehiclePosition(entityId, target);
        }
    }

    private EntityMovement withDeltaSequence(EntityMovement movement, int sequence) {
        return movement.copy(movement.deltaMovement.withSequence(sequence));
    }

    private void handleRemovedPlayerVehicleTeleport(
            PacketSendEvent<?> event, TeleportChange change, RelativeFlag packetFlags) {
        if (player.isBedrockMovement()) return;
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)) {
            // 26.3 ClientPacketListener#handleTeleportEntity still remaps a removed
            // vehicle's teleport onto the player, but no longer sends a PosRot echo.
            // The trailing proof places this rebase before the next actual travel.
            // Are you drunk Mojang??? I certainly am after reading your fucking code.
            int proofTransaction = proveHandled(event, Proof.BEFORE_NEXT_TICK);
            Runnable apply = () -> {
                Vec3 previous = new Vec3(player.x, player.y, player.z);
                Vec3 position = calculateAbsolutePosition(previous, change.position(), packetFlags);
                float sourceYaw = player.xRot, sourcePitch = player.yRot;
                float yaw = calculateAbsoluteYaw(sourceYaw, change.yRot(), packetFlags);
                float pitch = calculateAbsolutePitch(sourcePitch, change.xRot(), packetFlags);
                TeleportAcceptData accepted = new TeleportAcceptData();
                accepted.setTeleport(true);
                // Before 26.3 the PosRot echo of this exact position is the matched response.
                accepted.setMatchedTeleportPosition(true);
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
                CheckManagerListener.applyTeleportResponse(player, accepted, yaw, pitch);
            };
            player.latencyUtils.addRealTimeTask(proofTransaction, apply);
            return;
        }
        Vec3 position =
                calculateAbsolutePosition(new Vec3(player.x, player.y, player.z), change.position(), packetFlags);
        float finalYaw = calculateAbsoluteYaw(player.xRot, change.yRot(), packetFlags);
        float finalPitch = calculateAbsolutePitch(player.yRot, change.xRot(), packetFlags);
        Vec3 deltaMovement = calculateAbsoluteDelta(
                Vec3.ZERO, change.deltaMovement(), player.xRot, player.yRot, finalYaw, finalPitch, packetFlags);
        int proofTransaction = proof(event, Proof.AFTER_RESPONSE);

        player.getSetbackTeleportUtil()
                .javaTeleports()
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

    private PacketHandlerEntityTransform packetHandlerTransformSource(PacketEntity entity, TrackerData data) {
        return packetHandlerTransformSource(entity, data, new PacketHandlerEntityTransform());
    }

    /**
     * The transform the packet handler starts from, written into {@code out}: the one a packet handled in this client
     * tick wrote, else the client's current transform.
     */
    private PacketHandlerEntityTransform packetHandlerTransformSource(
            PacketEntity entity, TrackerData data, PacketHandlerEntityTransform out) {
        if (entity != null && data != null && data.hasHandlerTransformIn(transformTick)) {
            return out.set(
                    data.getHandlerX(),
                    data.getHandlerY(),
                    data.getHandlerZ(),
                    data.getHandlerYRot(),
                    data.getHandlerXRot());
        }
        return packetHandlerTransformFallback(entity, data, out);
    }

    /** {@link #packetHandlerTransformSource} without a transform written this client tick: the client's current one. */
    private PacketHandlerEntityTransform packetHandlerTransformFallback(
            PacketEntity entity, TrackerData data, PacketHandlerEntityTransform out) {
        ReachInterpolationData interpolationTarget = teleportSourceInterpolationTarget(entity);
        if (interpolationTarget != null) {
            // MCP-Reborn PositionMoveRotation#of(entity) reads the current
            // InterpolationHandler target in the packet handler. The next
            // InterpolationHandler#interpolate carry happens later, during the
            // entity tick, so do not pre-carry the target here.
            return out.set(
                    interpolationTarget.targetCenterX(),
                    interpolationTarget.targetMinY(),
                    interpolationTarget.targetCenterZ(),
                    interpolationTarget.getTargetYaw(),
                    interpolationTarget.getTargetPitch());
        }

        if (entity != null && entity.clientPhysicalPosition != null) {
            // MCP-Reborn PositionMoveRotation#of(entity) reads the client entity's
            // current position/yaw/pitch when no interpolation target is active.
            // TrackerData is the latest server-authored position Cult sent to the
            // client; it can be ahead of the transaction-proven client shadow.
            return out.set(
                    entity.clientPhysicalPosition.x,
                    entity.clientPhysicalPosition.y,
                    entity.clientPhysicalPosition.z,
                    entity.clientPhysicalYaw,
                    entity.clientPhysicalPitch);
        }

        if (data != null) {
            return out.set(data.getX(), data.getY(), data.getZ(), data.getXRot(), data.getYRot());
        }

        return out.set(0.0D, 0.0D, 0.0D, 0.0F, 0.0F);
    }

    /**
     * MCP-Reborn PositionMoveRotation#of(entity) reads Entity#getKnownMovement, which a vehicle the local player
     * controls takes from that player: LocalPlayer#getDeltaMovement, which its ride ticks and velocity packets write
     * and the vehicle's travel never does. Only the client handling the teleport fixes which ride tick that follows, so
     * a velocity-relative teleport of that vehicle stays relative until then.
     */
    private DeltaMovementChange teleportDeltaMovement(PacketEntity entity, RiderRelativeDelta relative) {
        if (player.isBedrockMovement()
                || entity == null
                || entity != player.compensatedEntities.vehicles.getVelocityMovementVehicle()
                || !readsKnownMovement(relative.flags()))
            return DeltaMovementChange.fixed(resolve(relative, teleportSourceDeltaMovement(entity)), -1);
        Vec3 known = player.checkManager
                .getSimulationProcessor()
                .riderKnownMovements()
                .getFirst();
        return DeltaMovementChange.riderRelative(resolve(relative, known), relative);
    }

    private Vec3 teleportSourceDeltaMovement(PacketEntity entity) {
        // A Bedrock bridge's vehicle correction starts from the player's last packet delta, and a teleport that sets a
        // Java vehicle's velocity outright reads none. Other entities use their own packet-handler delta.
        if (entity != null && entity == player.compensatedEntities.vehicles.getVelocityMovementVehicle()) {
            return player.compensatedEntities.getSelf().deltaMovement;
        }

        return packetHandlerDeltaMovementSource(entity);
    }

    private static boolean readsKnownMovement(RelativeFlag flags) {
        return flags.isSet(RelativeFlag.DELTA_X.getMask())
                || flags.isSet(RelativeFlag.DELTA_Y.getMask())
                || flags.isSet(RelativeFlag.DELTA_Z.getMask());
    }

    private Vec3 resolve(RiderRelativeDelta relative, Vec3 knownMovement) {
        return calculateAbsoluteDelta(
                knownMovement,
                relative.change(),
                relative.sourceYaw(),
                relative.sourcePitch(),
                relative.finalYaw(),
                relative.finalPitch(),
                relative.flags());
    }

    /**
     * At the client's handling of a velocity-relative teleport: each delta movement its rider can hold gives the
     * vehicle one. A vehicle the player no longer controls by then hands over its own.
     */
    private void applyRiderRelativeDelta(PacketEntity entity, RiderRelativeDelta relative) {
        var vehicles = player.compensatedEntities.vehicles;
        if (entity != vehicles.getVelocityMovementVehicle()) {
            entity.setPacketDeltaMovement(resolve(relative, entity.deltaMovement));
            return;
        }
        List<Vec3> velocities = new ArrayList<>();
        for (Vec3 known : player.checkManager.getSimulationProcessor().riderKnownMovements()) {
            Vec3 velocity = resolve(relative, known);
            if (!velocities.contains(velocity)) velocities.add(velocity);
        }
        vehicles.applyClientboundVehicleVelocities(entity, velocities);
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

    @CultPacketHandler
    public void onEntityPositionSync(
            PacketSendEvent<ClientboundEntityPositionSync> event,
            CultPlayer player,
            ClientboundEntityPositionSync packet) {
        PositionPath position = packet.position();
        // ViaBackwards 26.3->26.2 keeps only a stepped path's final position.
        if (player.getClientVersion().isOlderThan(ClientVersion.V_26_3))
            position = new PositionPath.Linear(position.endPosition());
        handleMoveEntity(
                event,
                positionSyncPacket(
                        packet.entityId(),
                        receivesViaSteppedPaths()
                                ? viaSteppedPath(EntityPositionPath.fromProtocol(position)
                                        .endPosition())
                                : EntityPositionPath.fromProtocol(position),
                        packet.yaw(),
                        packet.pitch(),
                        packet.onGround()));
    }

    /** A 26.3 Java client behind ViaVersion on a pre-26.3 host. */
    private boolean receivesViaSteppedPaths() {
        return !player.isBedrockMovement()
                && !player.getObservedProtocol().atLeast(ProtocolVersion.V26_3)
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3);
    }

    /**
     * The path a 26.3 client receives for a pre-26.3 host's positioned move or position sync: ViaVersion's
     * {@code EntityPacketRewriter26_3} sends it as one step of {@code INTERPOLATION_STEP_TICKS}.
     */
    private static EntityPositionPath viaSteppedPath(Vec3 position) {
        return new EntityPositionPath(
                position, List.of(new EntityPositionPath.Step(position, VIA_INTERPOLATION_STEP_TICKS)));
    }

    @CultPacketHandler
    public void onMoveMinecart(
            PacketSendEvent<ClientboundMoveMinecart> event, CultPlayer player, ClientboundMoveMinecart packet) {
        if (packet.steps().isEmpty()) return;
        var step = packet.steps().getLast();
        var position = step.position();
        // Wire decoding has already applied the native rotation-byte quantization.
        handleMoveEntity(
                event,
                minecartPacket(packet.entityId(), position.x(), position.y(), position.z(), step.yaw(), step.pitch()));
    }

    @CultPacketHandler
    public void onSetEntityData(PacketSendEvent<EntityMetadata> event, CultPlayer player, EntityMetadata packet) {
        List<EntityMetadata.Entry> metadata = packet.packedItems();
        // As for an entity spawn, entity move or block update, ClientPacketListener#handleSetEntityData applies the
        // data in the flush bundle's task, which answers the bundle's ping: its pong puts the data after the client
        // tick before it and before the tick it precedes. That proves what the next tick reads, such as
        // DATA_HEALTH_ID's LivingEntity#isImmobile, a pig's or strider's boost, a horse's standing flag (which a
        // local START_RIDING_JUMP also writes), a shulker's box and a happy ghast's collision flags.
        // A Bedrock flush group is not handled in one task, so its data keeps a ping after it.
        int flushProof = proof(event, entityProof());
        // With no ping to send, the data reaches the model at the last ping sent and is proven by the next.
        int proven = flushProof > 0 ? flushProof : player.lastTransactionSent.get() + 1;
        Runnable apply = () -> player.compensatedEntities.updateEntityMetadata(packet.id(), metadata, proven);
        if (flushProof > 0) player.latencyUtils.addRealTimeTask(flushProof, apply);
        else player.latencyUtils.addRealTimeTaskNow(apply);
        retirePoseAt(packet.id(), metadata, proven);
    }

    /** Retires the entity's size replacements at {@code proven} when the metadata replaces its pose. */
    private void retirePoseAt(int entityId, List<EntityMetadata.Entry> metadata, int proven) {
        if (WatchableIndexUtil.getIndex(metadata, WatchableIndexUtil.ENTITY_POSE) != null) {
            retireSizeAt(entityId, proven);
        }
    }

    @CultPacketHandler
    public void onSetEquipment(
            PacketSendEvent<InventoryPackets.Equipment> event, CultPlayer player, InventoryPackets.Equipment packet) {
        Integer serverVehicle = player.compensatedEntities.vehicles.serverPlayerVehicle;
        if (serverVehicle != null && serverVehicle == packet.entityId()) {
            // A saddle or harness hands the rider control of the root at the client tick after
            // ClientPacketListener#handleSetEquipment, which the trailing proof orders.
            CultPlayer.TrackedTransaction proof = trailingProof(event, List.of());
            if (proof != null) {
                player.latencyUtils.addRealTimeTask(
                        proof.transaction(),
                        () -> player.compensatedEntities.updateEntityEquipment(packet.entityId(), packet.slots()));
                return;
            }
        }
        // As with onSetEntityData, ClientPacketListener#handleSetEquipment writes the slots in the flush
        // bundle's task. A remote rider's hands decide whether it steers a pig or strider from the next tick.
        int flushProof = proof(event, entityProof());
        Runnable apply = () -> player.compensatedEntities.updateEntityEquipment(packet.entityId(), packet.slots());
        if (flushProof > 0) player.latencyUtils.addRealTimeTask(flushProof, apply);
        else player.latencyUtils.addRealTimeTaskNow(apply);
    }

    @CultPacketHandler
    public void onUpdateMobEffect(
            PacketSendEvent<ClientboundUpdateMobEffect> event, CultPlayer player, ClientboundUpdateMobEffect packet) {
        MovementEffect type = toMovementEffect(packet.effect());
        if (type == null) {
            return;
        }

        final Runnable applyMobEffect = () -> {
            PacketEntity entity = player.compensatedEntities.getEntity(packet.entityId());
            if (entity == null) return;

            entity.addPotionEffect(type, packet.amplifier());
        };
        applyMobEffectChange(event, packet.entityId(), applyMobEffect);
    }

    @CultPacketHandler
    public void onRemoveMobEffect(
            PacketSendEvent<ClientboundRemoveMobEffect> event, CultPlayer player, ClientboundRemoveMobEffect packet) {
        MovementEffect type = toMovementEffect(packet.effect());
        if (type == null) {
            return;
        }

        final Runnable removeMobEffect = () -> {
            PacketEntity entity = player.compensatedEntities.getEntity(packet.entityId());
            if (entity == null) return;

            entity.removePotionEffect(type);
        };
        applyMobEffectChange(event, packet.entityId(), removeMobEffect);
    }

    // ClientPacketListener#handleUpdateMobEffect and #handleRemoveMobEffect change the effect when the client handles
    // the packet; the local player's next tick reads its own and its vehicle's.
    private void applyMobEffectChange(PacketSendEvent<?> event, int entityId, Runnable change) {
        whenHandled(
                event, isDirectlyAffectingPlayer(player, entityId) ? Proof.BEFORE_NEXT_TICK : entityProof(), change);
    }

    @CultPacketHandler
    public void onUpdateAttributes(
            PacketSendEvent<ClientboundUpdateAttributes> event, CultPlayer player, ClientboundUpdateAttributes packet) {
        int entityID = packet.entityId();
        List<AttributeSnapshot> values = packet.attributes();

        // The local player's next tick reads its own and its vehicle's attributes.
        boolean affectsPlayer = entityID == player.entityID || isDirectlyAffectingPlayer(player, entityID);
        int proven = proveHandled(event, affectsPlayer ? Proof.BEFORE_NEXT_TICK : entityProof());
        player.latencyUtils.addRealTimeTask(
                proven, () -> player.compensatedEntities.updateAttributes(entityID, values, proven));
        if (hasScale(values)) retireSizeAt(entityID, proven);
    }

    private static boolean hasScale(List<AttributeSnapshot> values) {
        for (AttributeSnapshot snapshot : values) {
            if (snapshot.attribute().equals("minecraft:scale")) return true;
        }
        return false;
    }

    // Once the client answers provenAt, entityID's size replacements proven by it are retired.
    private void retireSizeAt(int entityID, int provenAt) {
        player.latencyUtils.addRealTimeTask(provenAt, () -> {
            PacketEntity entity = player.compensatedEntities.getEntity(entityID);
            if (entity != null) entity.onSizeHandled(provenAt);
        });
    }

    @CultPacketHandler
    public void onEntityEvent(
            PacketSendEvent<ClientboundEntityEvent> event, CultPlayer player, ClientboundEntityEvent packet) {
        int entityId = packet.entityId();
        byte status = packet.status();
        if (!player.isBedrockMovement()
                && (status == 69 && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_6)
                        || status == 39 && PacketEntityRavager.usesRoarCountdown(player.getClientVersion()))) {
            // Ravager#handleEntityEvent(69) pushes immediately, before any client entity ticks. Before 1.21.6,
            // event 39 instead starts the client's 40 stun ticks + 10 roar ticks. Via does not translate 69.
            long sequence = player.nextImpulseSequence();
            var proof = trailingProof(event, List.of());
            int transaction = proof == null ? player.lastTransactionSent.get() : proof.transaction();
            player.latencyUtils.addRealTimeTask(transaction, () -> {
                if (status == 69)
                    player.compensatedEntities.ravagerRoars.receive(player, entityId, transaction, sequence);
                else if (player.compensatedEntities.getEntity(entityId) instanceof PacketEntityRavager ravager)
                    ravager.stun();
            });
            return;
        }
        if (status == 9 && entityId == player.entityID && !player.isBedrockMovement()) {
            // Player#handleEntityEvent(9) calls completeUsingItem. The client never completes timed
            // consumption autonomously. Group the event and proof so its replacement is before one
            // precise client tick, retaining only the unreported selected-slot/active-use ambiguity.
            var proof = trailingProof(event, List.of());
            player.itemUseBurst.serverComplete(proof);
            Runnable apply = () -> {
                if (player.usesModernJavaItemUse()) {
                    ac.cult.cultac.utils.blockplace.ClientBlockActions.completeUse(player);
                    player.clientItemUse.release();
                }
                var storage = player.getInventory().inventory.getInventoryStorage();
                player.attackStrength.onServerUseCompletion(
                        slot -> storage.getItem(Inventory.HOTBAR_OFFSET + slot), player.canInstabuild);
            };
            player.latencyUtils.addRealTimeTask(
                    proof != null ? proof.transaction() : player.lastTransactionSent.get() + 1, apply);
        }
        if (status == 3) {
            // LivingEntity.handleEntityEvent sets non-player health to zero. Keep the action
            // predicate on the client-confirmed boundary; existing movement death handling stays below.
            whenHandled(event, Proof.BEFORE_NEXT_TICK, () -> {
                var received = player.compensatedEntities.getEntity(entityId);
                // LivingEntity#handleEntityEvent(3) only plays a Player's death sound; other living entities die.
                if (received != null && received.isLivingEntity() && received.type != EntityTypeIds.PLAYER) {
                    received.actionAlive = false;
                    received.isDead = true;
                    ClientSaddle.refresh(player, received);
                }
            });
        }

        // These are native server status codes, before ViaVersion rewrites the packet. AbstractBoat#handleEntityEvent
        // reads them from 26.3; earlier clients have no handler and keep their boat's velocity.
        if (!player.isBedrockMovement()
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)
                && (status == 71 || status == 72)) {
            whenHandled(
                    event,
                    Proof.BEFORE_NEXT_TICK,
                    () -> player.compensatedEntities.vehicles.applyBoatBubbleColumnEvent(entityId, status));
            return;
        }

        if (status == 31 && !player.isBedrockMovement()) {
            long sequence = player.nextImpulseSequence();
            var proof = trailingProof(event, List.of());
            int transaction = proof == null ? player.lastTransactionSent.get() : proof.transaction();
            player.latencyUtils.addRealTimeTask(
                    transaction,
                    () -> player.compensatedEntities.fishingPulls.receive(player, entityId, transaction, sequence));
            return;
        }
        if (status == 31) {
            final Runnable applyHookPull = () -> {
                PacketEntity hook = player.compensatedEntities.getEntity(entityId);
                if (!(hook instanceof PacketEntityHook)) return;

                PacketEntityHook hookEntity = (PacketEntityHook) hook;
                if (hookEntity.attached == player.entityID) {
                    PacketEntity owner = player.compensatedEntities.getEntity(hookEntity.owner);
                    if (owner == null) return;

                    player.compensatedEntities.fishingRodPulls.add(
                            player.compensatedEntities.currentMovementBox(owner));
                }
            };
            whenHandled(event, Proof.BEFORE_NEXT_TICK, applyHookPull);
        }

        if (status >= 24 && status <= 28 && entityId == player.entityID) {
            whenHandled(
                    event,
                    Proof.BEFORE_NEXT_TICK,
                    () -> player.compensatedEntities.getSelf().setOpLevel(status - 24));
        }

        if (status == 35 && entityId == player.entityID) {
            PlayerObserver observer = player.observer;
            if (observer != null) observer.totemPopped();
        }
    }

    @CultPacketHandler
    public void onDamageEvent(
            PacketSendEvent<ClientboundDamageEvent> event, CultPlayer player, ClientboundDamageEvent packet) {
        int entityId = packet.entityId();
        if (!(player.compensatedEntities.getEntity(entityId) instanceof PacketEntityStrider)) {
            return;
        }

        whenHandled(event, Proof.BEFORE_NEXT_TICK, () -> applyStriderDamageEvent(entityId));
    }

    private void applyStriderDamageEvent(int entityId) {
        PacketEntity entity = player.compensatedEntities.getEntity(entityId);
        if (entity instanceof PacketEntityStrider strider) {
            strider.handleDamageEvent();
        }
    }

    @CultPacketHandler
    public void onSetPassengers(
            PacketSendEvent<ClientboundSetPassengers> event, CultPlayer player, ClientboundSetPassengers packet) {
        // ClientPacketListener#handleSetEntityPassengersPacket ignores a vehicle the client does not hold, and starts
        // only the listed passengers it holds riding.
        if (!existsAsSent(packet.vehicleId())) return;
        List<Integer> heldPassengers =
                packet.passengers().stream().filter(this::existsAsSent).toList();
        packet = new ClientboundSetPassengers(packet.vehicleId(), heldPassengers);
        boolean mountsLocalPlayer = packet.passengers().contains(player.entityID);
        int[] passengers =
                packet.passengers().stream().mapToInt(Integer::intValue).toArray();
        // Entity#ejectPassengers stops only the passengers the vehicle holds, so the packet removes the player only
        // when it rides that vehicle once the client has handled every earlier packet, and has not left that seat on
        // its own. A repeated dismount changes nothing for the client.
        boolean removesLocalPlayer = !mountsLocalPlayer
                && player.compensatedEntities.vehicles.ridesAsSent(packet.vehicleId())
                && player.compensatedEntities.vehicles.isServerPlayerPassengerOf(packet.vehicleId());
        player.compensatedEntities.vehicles.onSentPassengers(packet.vehicleId(), passengers);
        PacketEntity vehicle = player.compensatedEntities.getEntity(packet.vehicleId());
        boolean setsSelfPassengers = packet.vehicleId() == player.entityID;
        boolean changesSelfPassengers =
                setsSelfPassengers || player.compensatedEntities.mayChangeSelfPassengers(packet.passengers());

        if (removesLocalPlayer) {
            // Entity#stopRiding moves nothing on the client, so the resync keeps the player at its last ride-tick
            // seat, whether or not it controlled the vehicle.
            Vec3 seat = vehicle == null ? null : player.compensatedEntities.dismountSeat(vehicle);
            SelfDismountResyncState dismountResyncState =
                    CultAPI.INSTANCE.getConfigManager().isObserveOnly()
                            ? null
                            : new SelfDismountResyncState(
                                    seat != null ? seat : new Vec3(player.x, player.y, player.z),
                                    Vec3.ZERO,
                                    player.getSetbackTeleportUtil().sender().nextTeleportId());
            CultPlayer.TrackedTransaction proof = proofForSetPassengers(event, null, dismountResyncState);
            int transaction = proof == null ? player.lastTransactionSent.get() + 1 : proof.transaction();
            if (changesSelfPassengers) player.compensatedEntities.markSelfPassengersChange(transaction);
            int vehicleId = packet.vehicleId();
            DismountResync resync =
                    dismountResyncState != null && proof != null ? DismountResync.TELEPORT : DismountResync.NONE;
            player.compensatedEntities.vehicles.setServerVehicleDismount(vehicleId, transaction);
            player.latencyUtils.addRealTimeTask(transaction, () -> {
                PacketEntity riding = player.compensatedEntities.playerEntity.getRiding();
                Vec3 dismountSeat = riding == null || riding.getEntityId() != vehicleId
                        ? null
                        : player.compensatedEntities.dismountSeat(riding);
                boolean hadRideTick =
                        player.checkManager.getSimulationProcessor().hasRideTick();
                player.compensatedEntities.vehicles.applyVehiclePassengers(vehicleId, passengers);
                if (riding != null && !player.compensatedEntities.getSelf().inVehicle()) {
                    leftVehicle(dismountSeat, hadRideTick, resync);
                }
                player.compensatedEntities.vehicles.clearServerVehicle(vehicleId, transaction);
                // Vehicle-switch state changes at the passenger transaction boundary.
                player.vehicleData.wasVehicleSwitch = true;
            });
            return;
        }

        VehicleMountResyncState mountResyncState = null;
        if (mountsLocalPlayer) {
            if (!CultAPI.INSTANCE.getConfigManager().isObserveOnly()
                    && player.compensatedEntities.vehicles.shouldProtocolResyncOnMount(vehicle, passengers)) {
                mountResyncState = vehicleMountResyncState(packet.vehicleId());
            }
        }

        CultPlayer.TrackedTransaction proof = proofForSetPassengers(event, mountResyncState, null);
        int transaction = proof == null ? player.lastTransactionSent.get() + 1 : proof.transaction();
        if (setsSelfPassengers) {
            player.compensatedEntities.sentSelfPassengers(packet.passengers(), transaction);
        } else if (changesSelfPassengers) {
            player.compensatedEntities.markSelfPassengersChange(transaction);
        }
        int vehicleId = packet.vehicleId();
        boolean seatsLocalPlayer =
                mountsLocalPlayer && !player.compensatedEntities.vehicles.isServerPlayerPassengerOf(vehicleId);
        if (mountsLocalPlayer) {
            player.compensatedEntities.vehicles.setServerVehicle(vehicleId, passengers, transaction);
        }
        VehicleMountResyncState resyncState = mountResyncState;
        if (resyncState != null) {
            queueVehicleProtocolResync(transaction, resyncState);
        } else if (seatsLocalPlayer) {
            placeSeatedVehicle(vehicleId, transaction);
        }
        player.latencyUtils.addRealTimeTask(transaction, () -> {
            PacketEntity mounted = player.compensatedEntities.getEntity(vehicleId);
            boolean firstMount = mountsLocalPlayer && mounted != null && !ridesIndirectly(mounted);
            if (mountsLocalPlayer
                    && mounted != null
                    && !player.isBedrockMovement()
                    && !player.compensatedEntities.getSelf().inVehicle()) {
                player.checkManager.getSimulationProcessor().beginRide();
            }
            player.compensatedEntities.vehicles.applyVehiclePassengers(vehicleId, passengers);
            if (mountsLocalPlayer) {
                player.vehicleData.wasVehicleSwitch = true;
                // A velocity the client handled before this mount was for the player; Entity#rideTick zeroes it.
                if (!player.isBedrockMovement()) {
                    player.checkManager.getKnockbackHandler().clearVelocitiesFromSourceEntity(player.entityID);
                }
            }
            // ClientPacketListener#handleSetEntityPassengersPacket turns a player newly aboard a boat to its yaw.
            if (firstMount && mounted.isBoat()) {
                player.headYaw = mounted.clientPhysicalYaw;
                player.unclampedHeadYaw = Float.NaN;
                player.headArcFrom = Float.NaN;
                player.boatYawBeforeTurn = mounted.clientPhysicalYaw;
                player.mountYaw = mounted.clientPhysicalYaw;
            }
            if (resyncState != null) {
                applyVehicleProtocolResync(resyncState);
            }
        });
    }

    // Entity#hasIndirectPassenger for the local player.
    private boolean ridesIndirectly(PacketEntity vehicle) {
        return player.compensatedEntities.ridesIndirectly(player.compensatedEntities.getSelf(), vehicle);
    }

    /** The trailing proof of a set_passengers and its resync packets, or null when none was written. */
    @Nullable
    private CultPlayer.TrackedTransaction proofForSetPassengers(
            PacketSendEvent<?> event,
            @Nullable VehicleMountResyncState mountResyncState,
            @Nullable SelfDismountResyncState dismountResyncState) {
        List<CultWrite> resyncPackets = CultAPI.INSTANCE.getConfigManager().isObserveOnly()
                ? List.of()
                : resyncPackets(mountResyncState, dismountResyncState);
        if (mountResyncState != null && !resyncPackets.isEmpty()) {
            // ClientPacketListener#handleMoveVehicle answers each resync MoveVehicle. A ping earlier in the flush
            // bundle is answered before those answers, so only a ping of its own after them proves them.
            CultPlayer.TrackedTransaction proof = player.appendResponseProofTransaction(event, resyncPackets);
            if (proof != null && !event.isInsideBundle()) event.bundle();
            return proof;
        }
        // The transaction-only proof path delimits only a group that carries resync packets.
        if (resyncPackets.isEmpty() && !useReachBundleDelimiter)
            return player.appendTrailingProofTransaction(event, resyncPackets);
        return trailingProof(event, resyncPackets);
    }

    private List<CultWrite> resyncPackets(
            @Nullable VehicleMountResyncState mountResyncState, @Nullable SelfDismountResyncState dismountResyncState) {
        var packets = new ArrayList<CultWrite>();
        if (mountResyncState != null && !isBedrockVehicleResync(mountResyncState.vehicleId())) {
            Vec3 snapNudge = mountResyncState.snapNudge();
            if (snapNudge != null) packets.add(vehicleMovePacket(mountResyncState, snapNudge));
            packets.add(vehicleMovePacket(mountResyncState, mountResyncState.position()));
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

    // queueVehicleProtocolResync records this packet's answer, so PacketServerTeleport must not record it again.
    private CultWrite vehicleMovePacket(VehicleMountResyncState resyncState, Vec3 position) {
        return new CultWrite(
                new ClientboundMoveVehicle(
                        new Vec3d(position.x, position.y, position.z), resyncState.yaw(), resyncState.pitch()),
                true);
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

    private void queueVehicleProtocolResync(int transaction, VehicleMountResyncState resyncState) {
        // A Bedrock client predicts the vehicle from where it shows it, so its mount only records the safe position.
        if (!isBedrockVehicleResync(resyncState.vehicleId())) {
            // ClientPacketListener#handleMoveVehicle answers each packet, the snap nudge first.
            if (resyncState.snapNudge() != null) {
                player.getSetbackTeleportUtil()
                        .vehicleTeleports()
                        .addVehicleTeleport(resyncState.vehicleId(), transaction, resyncState.snapNudge());
            }
            player.getSetbackTeleportUtil()
                    .vehicleTeleports()
                    .addVehicleTeleport(resyncState.vehicleId(), transaction, resyncState.position());
        }
        player.getSetbackTeleportUtil()
                .updateSafeVehiclePosition(resyncState.vehicleId(), transaction, resyncState.position());
    }

    /** The vehicle's position as the server last placed it, for setbacks from this set_passengers on. */
    private void placeSeatedVehicle(int vehicleId, int transaction) {
        if (player.isBedrockMovement()) return;
        TrackerData tracked = player.compensatedEntities.getTrackedEntity(vehicleId);
        if (tracked == null) return;
        player.getSetbackTeleportUtil()
                .updateSafeVehiclePosition(
                        vehicleId, transaction, new Vec3(tracked.getX(), tracked.getY(), tracked.getZ()));
    }

    private void applyVehicleProtocolResync(VehicleMountResyncState resyncState) {
        if (isBedrockVehicleResync(resyncState.vehicleId())) return;
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
        TrackerData trackedPosition = player.compensatedEntities.getTrackedEntity(vehicleId);
        if (trackedPosition == null) {
            return null;
        }

        Vec3 position = new Vec3(trackedPosition.getX(), trackedPosition.getY(), trackedPosition.getZ());
        return new VehicleMountResyncState(
                position,
                snapNudge(
                        player.compensatedEntities.getEntity(vehicleId),
                        position,
                        player.compensatedEntities.vehicles.isServerPlayerFirstPassengerOf(vehicleId)),
                trackedPosition.getXRot(),
                trackedPosition.getYRot(),
                null,
                PacketCodecUtil.quantizeLpVec3(Vec3.ZERO),
                vehicleId,
                false);
    }

    /**
     * ClientPacketListener#handleMoveVehicle snaps, cancelling the vehicle's interpolation, only for a packet more
     * than 1.0E-5F from the vehicle's interpolation target or position. When the resync position is closer, a first
     * packet at the returned nudge forces that snap, and the resync packet behind it in the same bundle snaps the
     * vehicle back before its next tick, so no tick runs at the nudge. Geyser's JavaMoveVehicleTranslator applies
     * every MoveVehicle, so a Bedrock client never needs one.
     *
     * <p>A vehicle the client does not already drive as sent holds the last position sent to it, as its interpolation
     * target or as its position once reached, even before that packet's proof returns. The resync at the tracked
     * position never moves it, so it always takes the nudge.
     */
    @Nullable
    private Vec3 snapNudge(@Nullable PacketEntity vehicle, Vec3 position, boolean drivenAsSent) {
        if (player.isBedrockMovement()) return null;
        Vec3 target = currentSerializedVehiclePosition(vehicle);
        if (!drivenAsSent) {
            if (target == null) target = position;
        } else if (target == null || position.distanceTo(target) > 1.0E-5F) return null;
        // Each axis points away from the target, so the nudge is at least its own length from it.
        return new Vec3(
                position.x + snapNudgeAwayFrom(position.x, target.x),
                position.y + snapNudgeAwayFrom(position.y, target.y),
                position.z + snapNudgeAwayFrom(position.z, target.z));
    }

    private static double snapNudgeAwayFrom(double coordinate, double target) {
        return coordinate < target ? -VEHICLE_MOVE_RESYNC_SNAP_NUDGE : VEHICLE_MOVE_RESYNC_SNAP_NUDGE;
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
            @Nullable Vec3 snapNudge,
            float yaw,
            float pitch,
            @Nullable Boolean onGround,
            Vec3 velocity,
            int vehicleId,
            boolean interpolates) {}

    private record SelfDismountResyncState(Vec3 position, Vec3 velocity, int teleportId) {}

    @CultPacketHandler
    public void onRemoveEntities(
            PacketSendEvent<ClientboundRemoveEntities> event, CultPlayer player, ClientboundRemoveEntities packet) {
        List<Integer> destroyEntityIds = packet.entityIds();
        // Removing the player's vehicle ejects it client-side with the velocity its last Entity#rideTick left.
        // A zero motion before the proof replaces that velocity, as the set_passengers dismount resync does.
        boolean resetsVelocity = !CultAPI.INSTANCE.getConfigManager().isObserveOnly()
                && !player.isBedrockMovement()
                && destroyEntityIds.stream().anyMatch(player.compensatedEntities.vehicles::isServerPlayerPassengerOf);
        int transaction = resetsVelocity
                ? transactionOf(trailingProof(event, List.of(entityMotion(player.entityID, SELF_DISMOUNT_VELOCITY))))
                : proof(event, Proof.SAME_TASK);
        DismountResync resync = resetsVelocity && transaction >= 0 ? DismountResync.VELOCITY : DismountResync.NONE;
        // With no ping to send, the client handles the removal before it answers the next ping.
        int removedAt = transaction >= 0 ? transaction : player.lastTransactionSent.get() + 1;
        if (player.compensatedEntities.mayChangeSelfPassengers(destroyEntityIds))
            player.compensatedEntities.markSelfPassengersChange(removedAt);

        for (int entityID : destroyEntityIds) {
            player.compensatedEntities.onSentRemoval(entityID);
            player.compensatedEntities.vehicles.onSentRemoval(entityID);
            if (player.compensatedEntities.vehicles.serverPlayerVehicle != null
                    && player.compensatedEntities.vehicles.serverPlayerVehicle == entityID) {
                PacketEntity vehicle = player.compensatedEntities.getEntity(entityID);
                if (vehicle != null && vehicle.passengers.contains(player.compensatedEntities.playerEntity)) {
                    removedPlayerVehicleId = entityID;
                }
                // A sent dismount stays pending until its own transaction; the client ejected the player before this.
                if (!player.compensatedEntities.vehicles.hasPendingServerDismount()) {
                    player.compensatedEntities.vehicles.clearServerVehicle();
                }
            }
            player.compensatedEntities.serverPositionsMap.remove(entityID);
        }

        // A Java login clears entities; an older level's removal must not remove a reused ID in the new one.
        int level = player.compensatedWorld.level();
        Runnable removeTask = () -> {
            PacketEntity riding = player.compensatedEntities.playerEntity.getRiding();
            boolean hadRideTick = player.checkManager.getSimulationProcessor().hasRideTick();
            // Neither removal reads the other's state, so each runs over the whole packet in turn.
            if (!player.isBedrockMovement() && level == player.compensatedWorld.level())
                player.compensatedEntities.removeEntities(destroyEntityIds);
            player.compensatedFireworks.removeFireworks(destroyEntityIds);
            if (riding != null && !player.compensatedEntities.getSelf().inVehicle()) {
                // CompensatedEntities#discard has already left the player at its last ride-tick seat.
                leftVehicle(null, hadRideTick, resync);
            }
        };
        player.latencyUtils.addRealTimeTask(removedAt, removeTask);
    }

    /** CultAC's own writes before a dismount's proof, which replace what the client keeps from its ride. */
    private enum DismountResync {
        /** None: the client keeps the velocity its last ride tick left. */
        NONE,
        /** A zero set_entity_motion of the player. */
        VELOCITY,
        /** A zero set_entity_motion and a teleport to the seat, which the client accepts before the proof. */
        TELEPORT
    }

    /**
     * The local player has left its vehicle on the client. ClientPacketListener#handleSetEntityPassengersPacket, through
     * Entity#ejectPassengers, and #handleRemoveEntities, through Entity#setRemoved, both only call Entity#stopRiding,
     * which moves nothing on the client: the player keeps the seat its last Entity#rideTick put it on, the velocity that
     * tick left and the onGround that tick's Player#tick cleared.
     *
     * @param seat the seat the player keeps, read while it still rode; null when the engine position does not come
     *     from it
     */
    private void leftVehicle(@Nullable Vec3 seat, boolean hadRideTick, DismountResync resync) {
        if (resync != DismountResync.NONE || player.isBedrockMovement()) {
            player.compensatedEntities.playerEntity.deltaMovement =
                    PacketCodecUtil.quantizeLpVec3(SELF_DISMOUNT_VELOCITY);
        }
        if (player.isBedrockMovement()) return;
        var simulation = player.checkManager.getSimulationProcessor();
        // A resync teleport, accepted before the proof, already set the position.
        if (resync != DismountResync.TELEPORT && seat != null) player.compensatedEntities.commitDismountSeat(seat);
        if (hadRideTick) {
            player.onGround = false;
            simulation.setLastOnGround(DesyncStatus.FALSE);
        }
        // Leaving the vehicle reseeds from the velocity the player held; the resync's zero motion follows it.
        if (resync == DismountResync.NONE) simulation.seedRideTickVelocity();
        else simulation.seedStartingVelocity(player.compensatedEntities.playerEntity.deltaMovement);
        simulation.endRide();
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
            transformed = transformed.xRot(player.getClientVersion(), (float) Math.toRadians(sourcePitch - finalPitch));
            transformed = transformed.yRot(player.getClientVersion(), (float) Math.toRadians(sourceYaw - finalYaw));
        }

        return new Vec3(
                flags.isSet(RelativeFlag.DELTA_X.getMask()) ? transformed.x + changeDelta.x : changeDelta.x,
                flags.isSet(RelativeFlag.DELTA_Y.getMask()) ? transformed.y + changeDelta.y : changeDelta.y,
                flags.isSet(RelativeFlag.DELTA_Z.getMask()) ? transformed.z + changeDelta.z : changeDelta.z);
    }

    /** When the client handles a packet relative to the ping that proves it. */
    private enum Proof {
        /** A flush ping, answered in the bundle task that handles the packet, before the next client tick. */
        SAME_TASK,
        /** A ping right after the packet, bundled with it: answered once the packet is handled, before the next tick. */
        BEFORE_NEXT_TICK,
        /** A ping of its own after the packet, answered after any reply the packet's handler sends. */
        AFTER_RESPONSE
    }

    /** The transaction of {@code kind} that proves the client handled the event's packet; -1 when none can be sent. */
    private int proof(PacketSendEvent<?> event, Proof kind) {
        return switch (kind) {
            case SAME_TASK -> player.appendFlushProofTransaction(event);
            case BEFORE_NEXT_TICK -> transactionOf(trailingProof(event, List.of()));
            case AFTER_RESPONSE -> transactionOf(player.appendResponseProofTransaction(event));
        };
    }

    /**
     * As {@link #proof}, and with no ping to send, the next ping CultAC sends: the client handles the packet before it
     * answers that one.
     */
    private int proveHandled(PacketSendEvent<?> event, Proof kind) {
        int transaction = proof(event, kind);
        return transaction >= 0 ? transaction : player.lastTransactionSent.get() + 1;
    }

    /** The proof of an ordinary entity update: a Bedrock flush group is not handled in one task, so it pings after. */
    private Proof entityProof() {
        return player.isBedrockMovement() ? Proof.BEFORE_NEXT_TICK : Proof.SAME_TASK;
    }

    /** Applies {@code change} once the client has handled the event's packet. */
    private void whenHandled(PacketSendEvent<?> event, Proof kind, Runnable change) {
        player.latencyUtils.addRealTimeTask(proveHandled(event, kind), change);
    }

    /**
     * Writes {@code packetsBeforeProof} and a {@link Proof#BEFORE_NEXT_TICK} ping after the event's packet; null when
     * no ping can be sent. The encoder owns delimiters and reuses an existing host bundle.
     */
    @Nullable
    private CultPlayer.TrackedTransaction trailingProof(PacketSendEvent<?> event, List<CultWrite> packetsBeforeProof) {
        CultPlayer.TrackedTransaction proof = player.appendTrailingProofTransaction(event, packetsBeforeProof);
        if (proof != null && !event.isInsideBundle()) event.bundle();
        return proof;
    }

    private static int transactionOf(@Nullable CultPlayer.TrackedTransaction proof) {
        return proof == null ? -1 : proof.transaction();
    }

    /** A {@link Proof#BEFORE_NEXT_TICK} ping after a vehicle packet another listener handles; null when none is sent. */
    @Nullable
    public CultPlayer.TrackedTransaction appendVehicleProtocolProofTransaction(PacketSendEvent<?> event) {
        return trailingProof(event, List.of());
    }

    private void handleMoveEntity(PacketSendEvent<?> event, EntityMovement movement) {
        handleMoveEntity(event, movement, player.compensatedEntities.getTrackedEntity(movement.entityId));
    }

    private void handleMoveEntity(PacketSendEvent<?> event, EntityMovement movement, @Nullable TrackerData data) {
        // ServerEntity#sendChanges sends one tracked movement per entity per tick; explicit
        // teleports may share a tick with it.
        var backendTicks = player.backendTicks();
        if (backendTicks != null && movement.kind != MovementKind.TELEPORT_ENTITY)
            backendTicks.onEntityTracked(movement.entityId, System.nanoTime());
        boolean bedrock = player.isBedrockMovement();

        boolean relative = movement.relative;
        boolean absolutePositionKnown = !relative;
        double absoluteX = movement.x;
        double absoluteY = movement.y;
        double absoluteZ = movement.z;
        boolean rotationChanged = false;
        if (data != null) {
            if (relative) {
                double decodedX = PacketCodecUtil.decodeRelativeEntityCoordinate(data.getCodecBaseX(), movement.x);
                double decodedY = PacketCodecUtil.decodeRelativeEntityCoordinate(data.getCodecBaseY(), movement.y);
                double decodedZ = PacketCodecUtil.decodeRelativeEntityCoordinate(data.getCodecBaseZ(), movement.z);
                if (movement.hasPosition) {
                    data.setX(decodedX);
                    data.setY(decodedY);
                    data.setZ(decodedZ);
                    data.setCodecBaseX(decodedX);
                    data.setCodecBaseY(decodedY);
                    data.setCodecBaseZ(decodedZ);
                }
                absolutePositionKnown = true;
                absoluteX = decodedX;
                absoluteY = decodedY;
                absoluteZ = decodedZ;
            } else if (movement.hasPosition) {
                data.setX(movement.x);
                data.setY(movement.y);
                data.setZ(movement.z);
                if (setsCodecBase(movement.kind, data.getEntityType())) {
                    data.setCodecBaseX(movement.x);
                    data.setCodecBaseY(movement.y);
                    data.setCodecBaseZ(movement.z);
                }
            }
            if (movement.hasRotation) {
                rotationChanged = Float.compare(data.getXRot(), movement.rotationYaw) != 0
                        || Float.compare(data.getYRot(), movement.rotationPitch) != 0;
                data.setXRot(movement.rotationYaw);
                data.setYRot(movement.rotationPitch);
            }
            if (movement.onGround != null) {
                data.setOnGround(movement.onGround);
            }
        }

        // Geyser may emit several client positions over later ticks. Java packets update server authority here; the
        // outgoing Bedrock stream owns visible transforms, proven by the latency markers the Geyser bridge writes.
        if (bedrock) {
            PacketEntity controlledVehicle = BedrockVehicleControl.controlledVehicle(player);
            if (movement.kind == MovementKind.TELEPORT_ENTITY
                    && movement.hasPosition
                    && absolutePositionKnown
                    && player.compensatedEntities.vehicles.serverPlayerVehicle != null
                    && player.compensatedEntities.vehicles.serverPlayerVehicle == movement.entityId
                    && controlledVehicle != null
                    && controlledVehicle.getEntityId() == movement.entityId) {
                Vec3 target = new Vec3(absoluteX, absoluteY, absoluteZ);
                int transaction = proveHandled(event, Proof.BEFORE_NEXT_TICK);
                player.getSetbackTeleportUtil()
                        .vehicleTeleports()
                        .addBedrockVehicleTeleport(movement.entityId, transaction, target);
                player.getSetbackTeleportUtil().updateSafeVehiclePosition(movement.entityId, transaction, target);
                player.getSetbackTeleportUtil()
                        .sender()
                        .sendBedrockMovementCorrection(
                                movement.entityId,
                                target,
                                movement.deltaMovement.movement(),
                                movement.yaw(),
                                movement.pitch(),
                                Boolean.TRUE.equals(movement.onGround),
                                transaction);
            }
            clearPacketHandlerDeltaMovement(movement);
            return;
        }

        // Without the reach bundle delimiter a movement wave keeps a ping before it, and the next ping settles it.
        boolean unbundled = !useReachBundleDelimiter;
        if (unbundled && !hasSentPreWavePacket) {
            hasSentPreWavePacket = true;
            player.sendTransaction();
        }
        if (unbundled && data != null && data.getLastTransactionHung() == player.lastTransactionSent.get()) {
            player.sendTransaction();
        }

        // Only ordinary tracker movement shares the flush bundle's ping. Explicit teleports retain
        // their own proof because a controlled vehicle can reply inside the handler.
        int proof = unbundled
                ? player.lastTransactionSent.get()
                : proveHandled(
                        event, movement.kind == MovementKind.TELEPORT_ENTITY ? Proof.AFTER_RESPONSE : Proof.SAME_TASK);
        if (data != null) {
            data.setLastTransactionHung(proof);
            if (data.getEntityType() == EntityTypeIds.PLAYER) {
                FairReach fairReach = player.checkManager.getListener(FairReach.class);
                if (movement.hasPosition) {
                    FairReach.PositionUpdate update = switch (movement.kind) {
                        case TELEPORT_ENTITY -> FairReach.PositionUpdate.TELEPORT;
                        case POSITION_SYNC -> FairReach.PositionUpdate.POSITION_SYNC;
                        case RELATIVE_ENTITY, MINECART -> FairReach.PositionUpdate.RELATIVE;
                    };
                    fairReach.handleEntityMove(movement.entityId, data.getX(), data.getY(), data.getZ(), update);
                } else if (movement.hasRotation) {
                    fairReach.handleEntityRotation(
                            movement.entityId, data.getX(), data.getY(), data.getZ(), rotationChanged);
                }
            }
        }

        boolean ignoredMovement = movement.kind.shouldSkipAsSent(this, movement.entityId);
        // ClientPacketListener#handleTeleportEntity can echo a MoveVehicle packet for local-authoritative ridden
        // roots. EntityPositionSync only updates the position codec for those roots and sends no response.
        if (movement.kind == MovementKind.TELEPORT_ENTITY
                && !ignoredMovement
                && movement.hasPosition
                && absolutePositionKnown
                && player.compensatedEntities.vehicles.serverPlayerVehicle != null
                && player.compensatedEntities.vehicles.serverPlayerVehicle == movement.entityId) {
            if (movement.controlledRootTeleport() != null) {
                // The teleport's target is known only once the client handles it; it then also places the vehicle.
                player.getSetbackTeleportUtil()
                        .vehicleTeleports()
                        .addVehicleTeleport(movement.entityId, proof, movement.controlledRootTeleport());
            } else {
                Vec3 vehicleTarget = new Vec3(absoluteX, absoluteY, absoluteZ);
                VehicleTeleportData vehicleTeleportData = new VehicleTeleportData(
                        movement.entityId,
                        vehicleTarget,
                        movement.yaw(),
                        movement.pitch(),
                        movement.onGround,
                        movement.deltaMovement.movement());
                player.getSetbackTeleportUtil()
                        .vehicleTeleports()
                        .addVehicleTeleport(movement.entityId, proof, vehicleTarget, vehicleTeleportData);
                player.getSetbackTeleportUtil().updateSafeVehiclePosition(movement.entityId, proof, vehicleTarget);
            }
        }
        if (!ignoredMovement) {
            recordPacketHandlerTransform(
                    movement.entityId,
                    data,
                    movement.hasPosition,
                    absoluteX,
                    absoluteY,
                    absoluteZ,
                    movement.hasRotation,
                    movement.rotationYaw,
                    movement.rotationPitch);
        }

        if (relative) {
            if (!absolutePositionKnown) return; // Unknown entities are discarded by the client.
            // Decode against the packet-order codec base before queuing the proof.
            // Later relative packets may advance that base before this task runs.
            movement.decodedTo(absoluteX, absoluteY, absoluteZ);
        }
        movement.proofTransaction = proof;
        if (unbundled) scheduleTransactionBoundMovement(movement, proof);
        else player.latencyUtils.addRealTimeTask(proof, movement);
    }

    /**
     * Whether the client's handler moves the relative-move codec base to this absolute position. 26.3
     * ClientPacketListener#handleTeleportEntity and #handleMinecartAlongTrack place the entity and keep the base, which
     * only #handleEntityPositionSync, #handleMoveEntity and Entity#recreateFromPacket set; ItemFrame#recalculateBoundingBox
     * syncs it on every Entity#setPos. Geyser applies relative moves to its last position.
     */
    private boolean setsCodecBase(MovementKind kind, int entityType) {
        return switch (kind) {
            case RELATIVE_ENTITY, POSITION_SYNC -> true;
            case TELEPORT_ENTITY ->
                teleportsSetCodecBase()
                        || EntityTypeUtil.isType(entityType, "item_frame")
                        || EntityTypeUtil.isType(entityType, "glow_item_frame");
            case MINECART -> teleportsSetCodecBase();
        };
    }

    private boolean teleportsSetCodecBase() {
        return player.isBedrockMovement();
    }

    /**
     * Whether the handler places {@code entity}, which the local player rides, without interpolating it, and so runs
     * Entity#positionRider on the player at once. ClientPacketListener#handleTeleportEntity interpolates a root the
     * client does not move unless the jump exceeds 64 blocks (setValuesFromPositionPacket); #handleEntityPositionSync
     * seats the player whenever the root is not interpolating afterwards: snapped, or without a running path.
     */
    private boolean seatsLocalRiderAtOnce(PacketEntity entity, EntityMovement movement) {
        if (player.isBedrockMovement() || player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)) return false;
        if (!movement.hasPosition || !ridesIndirectly(entity)) return false;
        // A root the client moves answers its teleport with a MoveVehicle (ControlledRootTeleport).
        if (player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(entity)) return false;
        return switch (movement.kind) {
            // PositionMoveRotation#of measures from the running interpolation target.
            case TELEPORT_ENTITY ->
                (entity.newPacketLocation != null && entity.newPacketLocation.hasActiveInterpolationTarget()
                                        ? entity.newPacketLocation.targetPosition()
                                        : entity.clientPhysicalPosition)
                                .distanceToSqr(movement.x, movement.y, movement.z)
                        > 4096.0D;
            case POSITION_SYNC ->
                entity.clientPhysicalPosition.distanceToSqr(movement.x, movement.y, movement.z) > 4096.0D
                        || !player.compensatedEntities.isTicking(entity)
                        || entity.placedWithoutInterpolation(player);
            default -> false;
        };
    }

    /** Entity#positionRider on the local player from the position the handler set; its next ride tick starts there. */
    private void seatLocalRider(PacketEntity entity, EntityMovement movement) {
        if (!ridesIndirectly(entity)) return;
        Vec3 seat = ac.cult.cultac.utils.nmsutil.BoundingBoxSize.getRidingOffsetFromVehicle(
                entity,
                player.compensatedEntities.getSelf(),
                player,
                ac.cult.cultac.utils.nmsutil.GetBoundingBox.getPacketEntityBoundingBox(
                        player, movement.x, movement.y, movement.z, entity));
        player.checkManager.getSimulationProcessor().moveRider(seat);
    }

    private void scheduleTransactionBoundMovement(EntityMovement movement, int firstTransaction) {
        var bound = new TransactionBoundMovement(movement, firstTransaction);
        player.latencyUtils.addRealTimeTaskWithNextTransaction(firstTransaction, bound::apply, bound::settle);
    }

    /**
     * A movement sent without the reach bundle delimiter, between the ping before it and the next one. Minecraft#runTick
     * handles queued packets before each frame's client ticks, so a client tick between the two pongs may meet the move
     * handled or not. Collision and prediction take it as a bundle would deliver it, handled with the ping before it:
     * one compensated position per entity. Until the next ping proves it handled, the reach bounds also keep the
     * position the entity would hold without it. The settling half waits in the applying half's place.
     */
    private final class TransactionBoundMovement {
        private final EntityMovement movement;
        private @Nullable PacketEntity held;
        private @Nullable Object reachLocation;

        private TransactionBoundMovement(EntityMovement movement, int firstTransaction) {
            this.movement = movement;
            movement.proofTransaction = firstTransaction;
        }

        private void apply() {
            PacketEntity entity = player.compensatedEntities.getEntity(movement.entityId);
            if (entity != null && !shouldIgnoreEntityMovement(movement)) {
                held = entity;
                reachLocation = entity.holdUnbundledReachLocation();
            }
            movement.run();
        }

        private void settle() {
            if (held != null) held.releaseUnbundledReachLocation(reachLocation);
        }
    }

    /**
     * Applies a movement once its proof's pong shows the client handled it, in the bundle task that holds it or before
     * its next tick.
     */
    private void applyAtProof(EntityMovement movement) {
        movement = movement.asHandled();
        PacketEntity entity = applicableMovementEntity(movement);
        if (entity == null) {
            return;
        }
        boolean seatsLocalRider = seatsLocalRiderAtOnce(entity, movement);
        applyEntityRotationAndGround(entity, movement);
        if (!applyClientPath(entity, movement)) {
            if (movement.kind == MovementKind.POSITION_SYNC) {
                // ClientPacketListener#handleEntityPositionSync calls moveOrInterpolateTo: an entity placed at its
                // packet position moves with it, any other interpolates from where it is.
                entity.onBundleTransaction(
                        false,
                        true,
                        movement.x,
                        movement.y,
                        movement.z,
                        movement.hasRotation,
                        movement.rotationYaw,
                        movement.rotationPitch,
                        player);
            } else {
                entity.onBundleFirstTransaction(
                        movement.relative,
                        movement.hasPosition,
                        movement.x,
                        movement.y,
                        movement.z,
                        movement.hasRotation,
                        movement.rotationYaw,
                        movement.rotationPitch,
                        player);
            }
        }
        markUncertainPosition(entity, movement);
        applyDeltaMovementUpdate(entity, movement);
        clearPacketHandlerDeltaMovement(movement);
        entity.onSecondTransaction();
        player.compensatedEntities.updatePassengerPositions();
        if (seatsLocalRider) seatLocalRider(entity, movement);
    }

    private boolean applyClientPath(PacketEntity entity, EntityMovement movement) {
        if (player.isBedrockMovement() || player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)) return false;
        if (movement.kind == MovementKind.POSITION_SYNC
                && (!player.compensatedEntities.isTicking(entity)
                        || entity.clientPhysicalPosition.distanceToSqr(
                                        movement.path().endPosition())
                                > 4096.0D)) {
            Vec3 target = movement.path().endPosition();
            entity.snapTo(
                    player,
                    ac.cult.cultac.utils.nmsutil.GetBoundingBox.getPacketEntityBoundingBox(
                            player, target.x, target.y, target.z, entity),
                    movement.yaw(),
                    movement.pitch());
            entity.onSecondTransaction();
            // A sync can be near in another feasible client position.
            entity.markPositionInexact();
            return true;
        }
        if (player.getClientVersion().isOlderThan(ClientVersion.V_26_3)) return false;
        entity.onPositionPath(
                movement.path(),
                movement.hasPosition,
                movement.hasRotation,
                movement.rotationYaw,
                movement.rotationPitch,
                player);
        return true;
    }

    // The model interpolates every teleport and legacy position sync, which a client may snap to instead.
    private void markUncertainPosition(PacketEntity entity, EntityMovement movement) {
        if (movement.kind == MovementKind.TELEPORT_ENTITY
                || (movement.kind == MovementKind.POSITION_SYNC
                        && player.getClientVersion().isOlderThan(ClientVersion.V_26_3))) {
            entity.markPositionInexact();
        }
    }

    private void applyEntityRotationAndGround(PacketEntity entity, EntityMovement movement) {
        if (entity instanceof PacketEntityTrackXRot && movement.hasRotation) {
            PacketEntityTrackXRot xRotEntity = (PacketEntityTrackXRot) entity;
            xRotEntity.packetYaw = movement.rotationYaw;
            xRotEntity.steps = 3;
        }
        Boolean onGround = movement.onGround;
        if (onGround != null) {
            entity.setPacketOnGround(onGround);
            player.checkManager.getSimulationProcessor().applyVehiclePacketGround(entity, onGround);
        }
    }

    @Nullable
    private PacketEntity applicableMovementEntity(EntityMovement movement) {
        PacketEntity entity = player.compensatedEntities.getEntity(movement.entityId);
        if (entity == null || shouldIgnoreEntityMovement(movement)) {
            clearPacketHandlerDeltaMovement(movement);
            return null;
        }
        return entity;
    }

    private boolean shouldIgnoreEntityMovement(EntityMovement movement) {
        return movement.entityId == player.entityID || movement.kind.shouldSkipAsHandled(this, movement.entityId);
    }

    private void recordPacketHandlerTransform(
            int entityId,
            @Nullable TrackerData data,
            boolean hasPos,
            double x,
            double y,
            double z,
            boolean hasRotation,
            float yaw,
            float pitch) {
        if (data == null) return; // The client discards movement for an entity it does not know.
        // A full transform replaces every component; only partial updates need the old client transform.
        if (!hasPos || !hasRotation) {
            PacketHandlerEntityTransform source = packetHandlerTransformSource(
                    player.compensatedEntities.getEntity(entityId), data, transformScratch);
            if (!hasPos) {
                x = source.x;
                y = source.y;
                z = source.z;
            }
            if (!hasRotation) {
                yaw = source.yRot;
                pitch = source.xRot;
            }
        }
        data.setHandlerTransform(x, y, z, yaw, pitch, transformTick);
    }

    private boolean isLocalAuthoritativeRootVehicleAsSent(int entityId) {
        return player.compensatedEntities.vehicles.serverPlayerVehicle != null
                && player.compensatedEntities.vehicles.serverPlayerVehicle == entityId
                && player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(
                        player.compensatedEntities.getEntity(entityId));
    }

    /**
     * Entity#isLocalInstanceAuthoritative as ClientPacketListener#handleEntityPositionSync and #handleMoveEntity read
     * it: from the passengers the client has handled by then. The server's vehicle is already the one it sent last, so
     * a sync for a boat the client still drives would otherwise move it once the server has sent the player elsewhere.
     */
    private boolean isLocalAuthoritativeVehicleAsHandled(int entityId) {
        return player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(
                player.compensatedEntities.getEntity(entityId));
    }

    private void applyDeltaMovementUpdate(PacketEntity entity, EntityMovement movementInput) {
        DeltaMovementChange update = movementInput.deltaMovement;
        if (!update.present()) {
            return;
        }
        if (update.riderRelative() != null) {
            applyRiderRelativeDelta(entity, update.riderRelative());
            return;
        }

        Vec3 movement = update.movement();
        if (!player.compensatedEntities.vehicles.applyClientboundVehicleVelocity(entity, movement)) {
            entity.setPacketDeltaMovement(movement);
        }
    }

    private int recordPacketHandlerDeltaMovement(int entityId, Vec3 movement) {
        int sequence = ++packetHandlerDeltaMovementSequence;
        TrackerData data = player.compensatedEntities.getTrackedEntity(entityId);
        if (data != null) data.setHandlerDeltaMovement(movement, sequence);
        return sequence;
    }

    private void clearPacketHandlerDeltaMovement(int entityId, int sequence) {
        if (sequence < 0) {
            return;
        }

        TrackerData data = player.compensatedEntities.getTrackedEntity(entityId);
        if (data != null) data.handlerDeltaMovementApplied(sequence);
    }

    private void clearPacketHandlerDeltaMovement(EntityMovement movement) {
        clearPacketHandlerDeltaMovement(movement.entityId, movement.deltaMovement.packetHandlerSequence());
    }

    private Vec3 packetHandlerDeltaMovementSource(PacketEntity entity) {
        if (entity == null) {
            return Vec3.ZERO;
        }

        TrackerData data = player.compensatedEntities.getTrackedEntity(entity.getEntityId());
        Vec3 movement = data == null ? null : data.getHandlerDeltaMovement();
        return movement == null ? entity.deltaMovement : movement;
    }

    public void addEntity(
            int entityID, int type, Vec3 position, float xRot, float yRot, int extraData, int transaction) {
        addEntity(entityID, type, position, xRot, yRot, extraData, transaction, null, Vec3.ZERO);
    }

    private void addEntity(
            int entityID,
            int type,
            Vec3 position,
            float xRot,
            float yRot,
            int extraData,
            int transaction,
            java.util.UUID uuid,
            Vec3 velocity) {
        int spawnTransaction = transaction >= 0 ? transaction : player.lastTransactionSent.get();
        TrackerData tracked = new TrackerData(position.x, position.y, position.z, xRot, yRot, type, spawnTransaction);
        player.compensatedEntities.serverPositionsMap.put(entityID, tracked);
        // ClientPacketListener#handleAddEntity: Entity#recreateFromPacket places the entity and sets its delta
        // movement, which later handlers in the same client tick start from.
        tracked.setHandlerTransform(position.x, position.y, position.z, xRot, yRot, transformTick);
        int spawnDeltaSequence = recordPacketHandlerDeltaMovement(entityID, velocity);
        // A Java login clears entities; Bedrock actors leave at Geyser's own removals.
        int level = player.compensatedWorld.level();
        Runnable addTask = () -> {
            if (!player.isBedrockMovement() && level != player.compensatedWorld.level()) return;
            player.compensatedEntities.addEntity(entityID, type, position, xRot, yRot, extraData);
            // Entity#recreateFromPacket sets the spawn's delta movement.
            PacketEntity spawned = player.compensatedEntities.getEntity(entityID);
            if (spawned != null) {
                spawned.deltaMovement = velocity;
                if (uuid != null) spawned.scoreboardName = uuid.toString();
            }
            clearPacketHandlerDeltaMovement(entityID, spawnDeltaSequence);
            if (uuid != null
                    && player.compensatedEntities.getEntity(entityID)
                            instanceof ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer remote) {
                remote.profile = uuid;
                var info = player.compensatedEntities.clientPlayerModes.get(uuid);
                if (info != null) remote.scoreboardName = info.name();
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
        player.sendEndOfTickTransaction();
        hasSentPreWavePacket = false;
    }

    public void onEndOfTickEvent(int serverTick) {
        player.sendEndOfTickTransaction(serverTick);
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
}
