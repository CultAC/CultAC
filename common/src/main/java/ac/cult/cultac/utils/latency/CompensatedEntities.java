package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.InventoryPackets.EquipmentEntry;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ShulkerData;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.packetentity.*;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.lists.EvictingQueue;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.*;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

public class CompensatedEntities {
    public final ClientPlayerModes clientPlayerModes = new ClientPlayerModes();
    private static final String SPRINTING_MODIFIER_ID = "minecraft:sprinting";
    public static final String SNOW_MODIFIER_ID = "minecraft:powder_snow";
    public static final Set<String> EXCLUDED_MODIFIER_IDS = Set.of(SPRINTING_MODIFIER_ID, SNOW_MODIFIER_ID);
    private static final String ATTRIBUTE_NAMESPACE = "minecraft:";
    private static final long LOCAL_PLAYER_CLIENT_TICK_ORDER = 0L;
    private static final int HELD_UPDATES = 4;
    private static final int HELD_ENTITIES = 256;
    public final Int2ObjectOpenHashMap<PacketEntity> entityMap = new Int2ObjectOpenHashMap<>(40, 0.7f);
    public final Int2ObjectOpenHashMap<TrackerData> serverPositionsMap = new Int2ObjectOpenHashMap<>(40, 0.7f);
    private final Int2ObjectOpenHashMap<List<HeldAttributes>> pendingAttributes = new Int2ObjectOpenHashMap<>(10, 0.7f);
    private final Int2ObjectOpenHashMap<List<EquipmentEntry>> pendingEquipment = new Int2ObjectOpenHashMap<>(10, 0.7f);
    public final CompensatedVehicleState vehicles;
    public List<SimpleCollisionBox> fishingRodPulls = new EvictingQueue<>(100); // sanity limit to prevent leaks.
    private long nextClientEntityTickOrder = 1L;
    // The entityMap values in the order addEntity put them.
    private final ArrayList<PacketEntity> addOrder = new ArrayList<>();
    // The sum of passengers.size() over entityMap values.
    private int trackedPassengers;

    CultPlayer player;

    public TrackerData selfTrackedEntity;
    public PacketEntitySelf playerEntity;
    private Vec3 rideTickSeat;

    public CompensatedEntities(CultPlayer player) {
        this.player = player;
        this.playerEntity = new PacketEntitySelf(player);
        this.vehicles = new CompensatedVehicleState(player, this);
        resetClientTickOrder();
        this.selfTrackedEntity = new TrackerData(0, 0, 0, 0, 0, EntityTypeIds.PLAYER, player.lastTransactionSent.get());
    }

    public void resetClientTickOrder() {
        nextClientEntityTickOrder = 1L;
        playerEntity.setClientTickOrder(LOCAL_PLAYER_CLIENT_TICK_ORDER);
    }

    /** ClientLevel#addEntity appends a LocalPlayer replacing one in a retained level after every entity there. */
    public void appendSelfToClientTickOrder() {
        playerEntity.setClientTickOrder(nextClientEntityTickOrder++);
    }

    /** A replacement ClientLevel cannot retain remote entities or their queued values. */
    public void clearClientLevel() {
        clearEntityMap();
        pendingAttributes.clear();
        pendingEquipment.clear();
        fishingRodPulls.clear();
        resetClientTickOrder();
    }

    public void tick() {
        this.playerEntity.setPositionRaw(player.boundingBox);
        updatePassengerPositions();
    }

    /** Drops every tracked entity, keeping their riding links. */
    public void clearEntityMap() {
        for (PacketEntity entity : entityMap.values()) untrack(entity);
        entityMap.clear();
        addOrder.clear();
        player.compensatedFireworks.clearLevel();
    }

    private void track(PacketEntity entity) {
        entity.setTracker(this);
        trackedPassengers += entity.passengers.size();
    }

    private void untrack(PacketEntity entity) {
        if (entity.getTracker() != this) return;
        entity.setTracker(null);
        trackedPassengers -= entity.passengers.size();
    }

    /** Called when PacketEntity#mount adds a passenger to an entity in this tracker's entityMap. */
    public void onPassengerAdded() {
        trackedPassengers++;
    }

    /** Called when PacketEntity#eject removes a passenger from an entity in this tracker's entityMap. */
    public void onPassengerRemoved() {
        trackedPassengers--;
    }

    /**
     * Positions the riders of the local player's own riding trees, the only riders whose positions are simulated:
     * every other rider is untracked (see {@link #isUntrackedRider(PacketEntity)}).
     */
    public void updatePassengerPositions() {
        if (trackedPassengers == 0) return;
        PacketEntity selfRoot = selfRidingRoot();
        PacketEntity serverRoot = serverVehicleRoot();
        if (selfRoot != null) positionTree(selfRoot);
        if (serverRoot != null && serverRoot != selfRoot) positionTree(serverRoot);
    }

    private void positionTree(PacketEntity root) {
        for (PacketEntity passenger : root.passengers) {
            tickPassenger(root, passenger);
        }
    }

    /**
     * The root of the local player's riding chain while it rides an entity this player tracks, else null. The
     * riders below it form the own riding tree.
     */
    @Nullable
    public PacketEntity selfRidingRoot() {
        PacketEntity vehicle = playerEntity.getRiding();
        return vehicle == null ? null : trackedRoot(vehicle);
    }

    /**
     * The root above the vehicle the server last put the local player on, which the client may not show it riding
     * yet; its riders are simulated too, so a vehicle switch reads a positioned vehicle.
     */
    @Nullable
    private PacketEntity serverVehicleRoot() {
        Integer vehicleId = vehicles.serverPlayerVehicle;
        if (vehicleId == null) return null;
        PacketEntity vehicle = entityMap.get(vehicleId.intValue());
        return vehicle == null ? null : trackedRoot(vehicle);
    }

    @Nullable
    private PacketEntity trackedRoot(PacketEntity vehicle) {
        PacketEntity root = rootOf(vehicle);
        return root != null && entityMap.get(root.getEntityId()) == root ? root : null;
    }

    /**
     * Whether {@code entity} rides outside the local player's own riding trees. Its position is not simulated, so
     * nothing may read its boxes or position: checks and collision skip it until it dismounts.
     */
    public boolean isUntrackedRider(PacketEntity entity) {
        if (entity.getRiding() == null) return false;
        PacketEntity root = rootOf(entity);
        return root == null || root != selfRidingRoot() && root != serverVehicleRoot();
    }

    /**
     * Before an untracked rider leaves its vehicle: anchors it at the seat its vehicle chain gives it now, the
     * position the client leaves the vehicle from, so its normal tracking resumes from there and not from wherever
     * it was when it stopped being simulated. Call it before any rider of the same change leaves: the seat depends
     * on the vehicle's passenger list.
     */
    public void settleIfUntrackedRider(PacketEntity rider) {
        if (!isUntrackedRider(rider)) return;
        SimpleCollisionBox seat = untrackedRiderSeat(rider, 0);
        if (seat != null) rider.settleAtSeat(player, seat);
    }

    /**
     * The movement box of {@code entity} as the client has it now; for an untracked rider, computed from its vehicle
     * chain on this call. For event-driven readers only: it allocates for every untracked rider in the chain.
     */
    public SimpleCollisionBox currentMovementBox(PacketEntity entity) {
        if (isUntrackedRider(entity)) {
            SimpleCollisionBox seat = untrackedRiderSeat(entity, 0);
            if (seat != null) return seat;
        }
        return entity.getPossibleMovementCollisionBoxes();
    }

    // The seat box positionPassenger would give rider, with every untracked vehicle above it seated the same way.
    @Nullable
    private SimpleCollisionBox untrackedRiderSeat(PacketEntity rider, int hops) {
        PacketEntity vehicle = rider.getRiding();
        if (vehicle == null || hops > entityMap.size()) return null;
        SimpleCollisionBox vehicleBox;
        if (isUntrackedRider(vehicle)) {
            vehicleBox = untrackedRiderSeat(vehicle, hops + 1);
            if (vehicleBox == null) return null;
        } else {
            vehicleBox = vehicle.getPossibleMovementCollisionBoxes();
        }
        Vec3 seat = BoundingBoxSize.getRidingOffsetFromVehicle(vehicle, rider, player, vehicleBox);
        return GetBoundingBox.getPacketEntityBoundingBox(player, seat.x, seat.y, seat.z, rider);
    }

    public void removeEntity(int entityID) {
        PacketEntity previousRoot = playerEntity.getRiding();
        PacketEntity removing = entityMap.get(entityID);
        if (removing != null) {
            for (PacketEntity passenger : removing.passengers) settleIfUntrackedRider(passenger);
        }
        PacketEntity entity = entityMap.remove(entityID);
        pendingAttributes.remove(entityID);
        pendingEquipment.remove(entityID);
        if (entity == null) return;
        untrack(entity);
        addOrder.remove(entity);

        boolean removedSelfVehicle = vehicles.serverPlayerVehicle != null && vehicles.serverPlayerVehicle == entityID;
        if (removedSelfVehicle) {
            vehicles.clearServerVehicle();
        }
        Vec3 dismountSeat = dismountSeat(entity);
        for (PacketEntity passenger : new ArrayList<>(entity.passengers)) {
            passenger.eject();
        }
        if (dismountSeat != null) commitDismountSeat(dismountSeat);
        if (player.isBedrockMovement()) {
            vehicles.seedStartingVelocityIfRootChanged(previousRoot);
            if (previousRoot == null) player.getSetbackTeleportUtil().transferBedrockVehicleSetback(entityID);
        }
    }

    public Integer getJumpAmplifier() {
        return getPotionLevelForPlayer(MovementEffect.JUMP_BOOST);
    }

    public Integer getLevitationAmplifier() {
        return getPotionLevelForPlayer(MovementEffect.LEVITATION);
    }

    public Integer getSlowFallingAmplifier() {
        return getPotionLevelForPlayer(MovementEffect.SLOW_FALLING);
    }

    public Integer getDolphinsGraceAmplifier() {
        return getPotionLevelForPlayer(MovementEffect.DOLPHINS_GRACE);
    }

    public Integer getPotionLevelForPlayer(MovementEffect type) {
        PacketEntity desiredEntity = getEntityInControl();

        HashMap<MovementEffect, Integer> effects = desiredEntity.potionsMap;
        if (effects == null) return null;

        return effects.get(type);
    }

    public boolean hasPotionEffect(MovementEffect type) {
        HashMap<MovementEffect, Integer> effects = playerEntity.potionsMap;
        if (effects == null) return false;
        return effects.containsKey(type);
    }

    public PacketEntity getEntityInControl() {
        return playerEntity.getRiding() != null ? playerEntity.getRiding() : playerEntity;
    }

    /** The ClientLevel#tickEntities place of {@code entity}; a passenger ticks with the root of its vehicle chain. */
    public long clientTickOrder(PacketEntity entity) {
        if (entity.getRiding() == null) return entity.getClientTickOrder();
        PacketEntity root = rootOf(entity);
        return root == null ? entity.getClientTickOrder() : root.getClientTickOrder();
    }

    /** Entity#isPassengerOfSameVehicle against the local player, which makes Entity#push skip the pair. */
    public boolean sharesRootVehicleWithSelf(PacketEntity entity) {
        PacketEntity root = rootOf(entity);
        return root != null && root == rootOf(playerEntity);
    }

    // Null inside a riding cycle (a server mounting a vehicle on its own passenger), which has no root.
    @Nullable
    private PacketEntity rootOf(PacketEntity entity) {
        PacketEntity root = entity;
        for (int hops = 0; root.getRiding() != null; hops++) {
            if (hops > entityMap.size()) return null;
            root = root.getRiding();
        }
        return root;
    }

    // TODO: This doesn't belong here at all
    public double getPlayerMovementSpeed() {
        double speed = player.compensatedEntities.getSelf().playerSpeed;
        return CultMath.clampFloat((float) speed, 0.0F, 1024.0F);
    }

    // NetHandlerPlayClient#handleEntityProperties only updates attributes present
    // in the client's attribute map. Via drops attributes older clients lack.
    private boolean supportsAttributes(ClientVersion since) {
        return player.isBedrockMovement() || player.getClientVersion().isNewerThanOrEquals(since);
    }

    public void updateAttributes(int entityID, List<AttributeSnapshot> objects) {
        updateAttributes(entityID, objects, player.lastTransactionSent.get() + 1);
    }

    /** Applies objects, which the client has applied once it answers provenAt. */
    public void updateAttributes(int entityID, List<AttributeSnapshot> objects, int provenAt) {
        boolean selfScaleChanged = false;
        if (entityID == player.entityID) {
            for (AttributeSnapshot snapshot : objects) {
                if (!player.isBedrockMovement()) player.combatAttributes.onServerSnapshot(snapshot);
                if (matchesAttribute(snapshot, "movement_speed")) {

                    boolean foundSprintingModifier = false;
                    for (AttributeModifier modifier : snapshot.modifiers()) {
                        if (modifier.id().equals(SPRINTING_MODIFIER_ID)) {
                            foundSprintingModifier = true;
                        }
                    }

                    // The server replaces the client's sprinting modifier without touching its sprint flag
                    if (!player.isBedrockMovement()) player.sprintState.onServerModifier(foundSprintingModifier);
                }

                if (matchesAttribute(snapshot, "scale")) {
                    selfScaleChanged = true;
                }

                // 26.2 attributes (Attributes#BOUNCINESS / FRICTION_MODIFIER /
                // AIR_DRAG_MODIFIER). Matched by registry path because the static
                // constants do not exist on pre-26.2 servers.
                boolean since26_2 = matchesAttribute(snapshot, "bounciness")
                        || matchesAttribute(snapshot, "friction_modifier")
                        || matchesAttribute(snapshot, "air_drag_modifier");
                if (!since26_2 || supportsAttributes(ClientVersion.V_26_2)) {
                    player.compensatedEntities.getSelf().onAttributeSnapshot(snapshot);
                }

                if (matchesAttribute(snapshot, "block_interaction_range")) {
                    player.compensatedEntities.getSelf().blockInteractionRange.onServerSnapshot(snapshot);
                }

                if (matchesAttribute(snapshot, "entity_interaction_range")) {
                    player.compensatedEntities.getSelf().entityInteractionRange.onServerSnapshot(snapshot);
                }

                if (matchesAttribute(snapshot, "sneaking_speed")) {
                    player.compensatedEntities.getSelf().sneakingSpeed.onServerSnapshot(snapshot);
                }
            }
        }

        PacketEntity entity = player.compensatedEntities.getEntity(entityID);
        boolean entityScaleChanged = false;
        if (entity == null && entityID != player.entityID) {
            holdAttributes(entityID, objects, provenAt);
            return;
        }

        if (entity != null && entityID != player.entityID) {
            for (AttributeSnapshot snapshot : objects) {
                if (matchesAttribute(snapshot, "scale")) {
                    entity.onScale((float) calculateAttribute(snapshot, 0.0625, 16.0), provenAt);
                    entityScaleChanged = true;
                }

                if (matchesAttribute(snapshot, "gravity")) {
                    entity.gravity = calculateAttribute(snapshot, -1.0, 1.0);
                }

                if (matchesAttribute(snapshot, "step_height")) {
                    entity.stepHeightAttribute = calculateAttribute(snapshot, 0.0, 10.0);
                }

                if (matchesAttribute(snapshot, "movement_efficiency")) {
                    entity.movementEfficiency = calculateAttribute(snapshot, 0.0, 1.0);
                }

                if (matchesAttribute(snapshot, "water_movement_efficiency")) {
                    entity.waterMovementEfficiency = calculateAttribute(snapshot, 0.0, 1.0);
                }

                if (supportsAttributes(ClientVersion.V_26_2) && matchesAttribute(snapshot, "bounciness")) {
                    entity.bounciness = calculateAttribute(snapshot, 0.0, 1.0);
                }

                if (supportsAttributes(ClientVersion.V_26_2) && matchesAttribute(snapshot, "friction_modifier")) {
                    entity.frictionModifier = calculateAttribute(snapshot, 0.0, 2048.0);
                }

                if (supportsAttributes(ClientVersion.V_26_2) && matchesAttribute(snapshot, "air_drag_modifier")) {
                    entity.airDragModifier = calculateAttribute(snapshot, 0.0, 2048.0);
                }
            }
        }

        if (entity instanceof PacketEntityHorse horse) {
            for (AttributeSnapshot snapshot : objects) {
                if (matchesAttribute(snapshot, "movement_speed")) {
                    horse.movementSpeedAttribute = (float) calculateAttribute(snapshot, 0.0, 1024.0);
                }

                if (matchesAttribute(snapshot, "jump_strength")) {
                    horse.jumpStrength = calculateAttribute(snapshot, 0.0, 32.0);
                }
            }
        }

        if (entity instanceof PacketEntityRideable) {
            for (AttributeSnapshot snapshot : objects) {
                if (matchesAttribute(snapshot, "movement_speed")) {
                    ((PacketEntityRideable) entity).movementSpeedAttribute = calculateAttribute(snapshot, 0.0, 1024.0);
                }

                if (matchesAttribute(snapshot, "flying_speed")) {
                    ((PacketEntityRideable) entity).flyingSpeedAttribute =
                            (float) calculateAttribute(snapshot, 0.0, 1024.0);
                }
            }
        }

        if (entity instanceof PacketEntityHappyGhast happyGhast) {
            for (AttributeSnapshot snapshot : objects) {
                if (matchesAttribute(snapshot, "movement_speed")) {
                    happyGhast.movementSpeedAttribute = (float) calculateAttribute(snapshot, 0.0, 1024.0);
                }

                if (matchesAttribute(snapshot, "flying_speed")) {
                    happyGhast.flyingSpeedAttribute = (float) calculateAttribute(snapshot, 0.0, 1024.0);
                }
            }
        }

        if (entity instanceof PacketEntityNautilus nautilus) {
            for (AttributeSnapshot snapshot : objects) {
                if (matchesAttribute(snapshot, "movement_speed")) {
                    nautilus.movementSpeedAttribute = calculateAttribute(snapshot, 0.0, 1024.0);
                }
            }
        }

        // A Java player's own dimensions refresh at LivingEntity#tick's end; the vehicle runner has no such refresh.
        if (selfScaleChanged
                && (player.isBedrockMovement()
                        || player.compensatedEntities.getSelf().inVehicle())) {
            player.refreshPlayerPose();
        }
        if (entityScaleChanged && entityID != player.entityID) {
            entity.refreshDimensions(player);
        }
    }

    private boolean matchesAttribute(AttributeSnapshot snapshot, String path) {
        String attribute = snapshot.attribute();
        return attribute.length() == ATTRIBUTE_NAMESPACE.length() + path.length()
                && attribute.startsWith(ATTRIBUTE_NAMESPACE)
                && attribute.endsWith(path);
    }

    private double calculateAttribute(AttributeSnapshot snapshot, double minValue, double maxValue) {
        return calculateAttribute(snapshot, minValue, maxValue, EXCLUDED_MODIFIER_IDS);
    }

    private double calculateAttribute(
            AttributeSnapshot snapshot, double minValue, double maxValue, Set<String> excludedModifierIds) {
        return CultMath.clampFloat(
                (float) calculateAttributeValue(snapshot, excludedModifierIds), (float) minValue, (float) maxValue);
    }

    public static double calculateAttributeValue(AttributeSnapshot snapshot, Set<String> excludedModifierIds) {
        double d0 = snapshot.base();

        List<AttributeModifier> modifiers = new ArrayList<>(snapshot.modifiers());
        modifiers.removeIf(modifier -> {
            for (String excludedModifierId : excludedModifierIds) {
                if (modifier.id().equals(excludedModifierId)) {
                    return true;
                }
            }
            return false;
        });

        for (AttributeModifier attributeModifier : modifiers) {
            if (attributeModifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                d0 += attributeModifier.amount();
            }
        }

        double d1 = d0;

        for (AttributeModifier attributeModifier : modifiers) {
            if (attributeModifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) {
                d1 += d0 * attributeModifier.amount();
            }
        }

        for (AttributeModifier attributeModifier : modifiers) {
            if (attributeModifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) {
                d1 *= 1.0D + attributeModifier.amount();
            }
        }

        return d1;
    }

    private void tickPassenger(PacketEntity riding, PacketEntity passenger) {
        if (riding == null || passenger == null) {
            return;
        }

        positionPassenger(riding, passenger);
        for (PacketEntity passengerPassenger : passenger.passengers) {
            tickPassenger(passenger, passengerPassenger);
        }
    }

    /**
     * Records the seat that this client tick's Entity#rideTick ended on, after the tick-end passenger update. It is
     * computed from the compensated vehicle alone; a position packet the client sends while riding never reaches it.
     */
    public void recordRideTickSeat() {
        PacketEntity riding = playerEntity.getRiding();
        rideTickSeat = riding == null ? null : BoundingBoxSize.getRidingOffsetFromVehicle(riding, playerEntity, player);
    }

    /** Whether the local player was a passenger at the end of the last client tick. */
    public boolean rodeLastClientTick() {
        return rideTickSeat != null;
    }

    /**
     * The seat the local player stays at when it leaves {@code vehicle} now, or null when the engine position must
     * not come from the seat. Client-side Entity#stopRiding moves nothing, so the player keeps the position the last
     * Entity#rideTick gave it through Entity#positionRider. A vehicle the player could control carries the engine
     * position through its MoveVehicle packets instead, so this never applies to one, whatever the client sends.
     */
    public Vec3 dismountSeat(PacketEntity vehicle) {
        if (player.isBedrockMovement() || rideTickSeat == null || playerEntity.getRiding() != vehicle) return null;
        PacketEntity root = rootOf(vehicle);
        if (root == null || vehicles.mayLocalPlayerControl(vehicle) || vehicles.mayLocalPlayerControl(root)) {
            return null;
        }
        return rideTickSeat;
    }

    /**
     * Makes the ride-tick seat the engine's player position once the player has left the vehicle. The seat comes
     * after a ride tick, whose Player#tick cleared onGround and whose travel cannot land: Entity#rideTick zeroed the
     * velocity and nothing before LivingEntity#travel's Entity#move makes it point down.
     */
    public void commitDismountSeat(Vec3 seat) {
        player.lastX = player.x;
        player.lastY = player.y;
        player.lastZ = player.z;
        player.x = seat.x;
        player.y = seat.y;
        player.z = seat.z;
        player.packetStateData.clientSidePosition = seat;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, seat.x, seat.y, seat.z);
        player.onGround = false;
        player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
    }

    public void positionPassenger(PacketEntity riding, PacketEntity passenger) {
        Vec3 passengerPosition = BoundingBoxSize.getRidingOffsetFromVehicle(riding, passenger, player);
        passenger.setPassengerPosition(
                player,
                GetBoundingBox.getPacketEntityBoundingBox(
                        player, passengerPosition.x, passengerPosition.y, passengerPosition.z, passenger));
        if (passenger == playerEntity) {
            player.packetStateData.clientSidePosition = passengerPosition;
        }
    }

    /** Adds the entity and replays its held attributes; returns the greatest replayed proof, or Integer.MIN_VALUE. */
    public int addEntity(int entityID, int entityType, Vec3 position, float xRot, float yRot, int data) {
        // Dropped items are all server sided and players can't interact with them (except create them!), save the
        // performance
        if (entityType == EntityTypeIds.ITEM) return Integer.MIN_VALUE;

        PacketEntity packetEntity = createTrackedEntity(entityID, entityType, position, xRot, data);
        packetEntity.setClientPhysicalRotation(xRot, yRot);
        if (packetEntity.newPacketLocation != null) {
            packetEntity.newPacketLocation.setCurrentAndTargetRotation(xRot, yRot);
        }

        // MCP-Reborn ClientPacketListener#handleLogin adds the LocalPlayer first,
        // and later ClientboundAddEntityPacket handlers call ClientLevel#addEntity
        // in receive order. ClientLevel#tickEntities then iterates EntityTickList
        // in that insertion order, so mirror the client's add sequence instead of
        // assuming server entity IDs match tick order.
        packetEntity.setClientTickOrder(nextClientEntityTickOrder++);
        PacketEntity replaced = entityMap.put(entityID, packetEntity);
        if (replaced != null) {
            untrack(replaced);
            addOrder.remove(replaced);
        }
        addOrder.add(packetEntity);
        track(packetEntity);
        int replayedProof = Integer.MIN_VALUE;
        List<HeldAttributes> held = pendingAttributes.remove(entityID);
        if (held != null) {
            // Each held update keeps its own proof; those already answered retire with the spawn.
            for (HeldAttributes update : held) {
                if (update.leastScale() == update.leastScale())
                    packetEntity.onScale(update.leastScale(), update.provenAt());
                updateAttributes(entityID, update.values(), update.provenAt());
                replayedProof = Math.max(replayedProof, update.provenAt());
            }
            packetEntity.onSizeHandled(player.lastTransactionReceived.get());
        }
        List<EquipmentEntry> pendingEquipmentUpdates = pendingEquipment.remove(entityID);
        if (pendingEquipmentUpdates != null) {
            updateEntityEquipment(entityID, pendingEquipmentUpdates);
        }
        return replayedProof;
    }

    private PacketEntity createTrackedEntity(int entityID, int entityType, Vec3 position, float xRot, int data) {
        String typeKey = EntityTypeUtil.modelType(entityType).key();
        if (typeKey.equals("minecraft:painting")
                || typeKey.equals("minecraft:item_frame")
                || typeKey.equals("minecraft:glow_item_frame"))
            return new PacketEntityHanging(player, entityID, entityType, position, data, typeKey);
        if (typeKey.equals("minecraft:cat")) return new PacketEntityCat(player, entityID, entityType, position);
        if (typeKey.equals("minecraft:player")) return new PacketEntityPlayer(player, entityID, entityType, position);
        if (typeKey.equals("minecraft:armor_stand"))
            return new PacketEntityArmorStand(player, entityID, entityType, position);
        if (typeKey.equals("minecraft:creaking"))
            return new PacketEntityCreaking(player, entityID, entityType, position);
        if (typeKey.equals("minecraft:interaction"))
            return new PacketEntityInteraction(player, entityID, entityType, position);
        if (typeKey.equals("minecraft:spider") || typeKey.equals("minecraft:cave_spider"))
            return new PacketEntitySpider(player, entityID, entityType, position);
        if (EntityTypeUtil.isCamelFamily(entityType)) {
            return new PacketEntityCamel(player, entityID, entityType, position.x, position.y, position.z, xRot);
        }
        if (EntityTypeUtil.isHorseFamily(entityType)) {
            return new PacketEntityHorse(player, entityID, entityType, position.x, position.y, position.z, xRot);
        }
        if (entityType == EntityTypeIds.SLIME
                || entityType == EntityTypeIds.MAGMA_CUBE
                || entityType == EntityTypeIds.PHANTOM) {
            return new PacketEntitySizeable(player, entityID, entityType, position.x, position.y, position.z);
        }
        if ((entityType == EntityTypeIds.PIG)) {
            return new PacketEntityRideable(player, entityID, entityType, position.x, position.y, position.z);
        }
        if (EntityTypeUtil.isHappyGhast(entityType)) {
            return new PacketEntityHappyGhast(player, entityID, entityType, position.x, position.y, position.z, xRot);
        }
        if (EntityTypeUtil.isNautilusFamily(entityType)) {
            return new PacketEntityNautilus(player, entityID, entityType, position.x, position.y, position.z, xRot);
        }
        if ((entityType == EntityTypeIds.SHULKER)) {
            return new PacketEntityShulker(player, entityID, entityType, position.x, position.y, position.z);
        }
        if ((entityType == EntityTypeIds.STRIDER)) {
            return new PacketEntityStrider(player, entityID, entityType, position.x, position.y, position.z);
        }
        if (EntityTypeUtil.isBoat(entityType) || (entityType == EntityTypeIds.CHICKEN)) {
            return new PacketEntityTrackXRot(player, entityID, entityType, position.x, position.y, position.z, xRot);
        }
        if ((entityType == EntityTypeIds.FISHING_BOBBER)) {
            return new PacketEntityHook(player, entityID, entityType, position.x, position.y, position.z, data);
        }
        return new PacketEntity(player, entityID, entityType, position.x, position.y, position.z);
    }

    public PacketEntity getEntity(int entityID) {
        if (entityID == player.entityID) {
            return playerEntity;
        }
        return entityMap.get(entityID);
    }

    public PacketEntitySelf getSelf() {
        return playerEntity;
    }

    public TrackerData getTrackedEntity(int id) {
        if (id == player.entityID) {
            return selfTrackedEntity;
        }
        return serverPositionsMap.get(id);
    }

    public void updateEntityEquipment(int entityID, List<EquipmentEntry> slots) {
        PacketEntity entity = getEntity(entityID);
        if (entity == null) {
            List<EquipmentEntry> merged = new ArrayList<>();
            List<EquipmentEntry> existing = pendingEquipment.remove(entityID);
            if (existing != null) {
                merged.addAll(existing);
            }
            for (EquipmentEntry slot : slots) {
                int existingIndex = -1;
                for (int i = 0; i < merged.size(); i++) {
                    if (merged.get(i).slot() == slot.slot()) {
                        existingIndex = i;
                        break;
                    }
                }
                EquipmentEntry copiedSlot =
                        new EquipmentEntry(slot.slot(), slot.item().copy());
                if (existingIndex >= 0) {
                    merged.set(existingIndex, copiedSlot);
                } else {
                    merged.add(copiedSlot);
                }
            }
            pendingEquipment.put(entityID, merged);
            return;
        }

        for (EquipmentEntry slot : slots) {
            updateSelfEquipment(entityID, slot.slot(), slot.item());

            if (slot.slot() == EquipmentSlot.SADDLE) {
                boolean hasSaddle = !slot.item().isEmpty();
                if (entity instanceof PacketEntityRideable rideable) {
                    rideable.hasSaddle = hasSaddle;
                }
                if (entity instanceof PacketEntityHorse horse) {
                    horse.hasSaddle = hasSaddle;
                }
                if (entity instanceof PacketEntityNautilus nautilus) {
                    nautilus.hasSaddle = hasSaddle;
                }
            } else if (slot.slot() == EquipmentSlot.BODY && entity instanceof PacketEntityHappyGhast happyGhast) {
                happyGhast.hasBodyArmor = !slot.item().isEmpty();
            }
        }
    }

    private void updateSelfEquipment(int entityID, EquipmentSlot slot, ac.cult.blocksim.engine.SimItemStack stack) {
        if (entityID != player.entityID) {
            return;
        }

        int inventorySlot;
        if (slot == EquipmentSlot.MAINHAND) {
            inventorySlot = Inventory.HOTBAR_OFFSET + player.getInventory().inventory.selected;
        } else if (slot == EquipmentSlot.OFFHAND) {
            inventorySlot = Inventory.SLOT_OFFHAND;
        } else if (slot == EquipmentSlot.HEAD) {
            inventorySlot = Inventory.SLOT_HELMET;
        } else if (slot == EquipmentSlot.CHEST) {
            inventorySlot = Inventory.SLOT_CHESTPLATE;
        } else if (slot == EquipmentSlot.LEGS) {
            inventorySlot = Inventory.SLOT_LEGGINGS;
        } else if (slot == EquipmentSlot.FEET) {
            inventorySlot = Inventory.SLOT_BOOTS;
        } else if (slot == EquipmentSlot.BODY) {
            inventorySlot = Inventory.SLOT_BODY;
        } else if (slot == EquipmentSlot.SADDLE) {
            inventorySlot = Inventory.SLOT_SADDLE;
        } else {
            inventorySlot = -1;
        }

        if (inventorySlot == -1) {
            return;
        }

        // Vanilla's regular equipment broadcast is tracking-only, but a plugin
        // can still send this packet to the player. If it arrives, MCP's client
        // handler applies LivingEntity#setItemSlot even for the local entity.
        SimItemStack converted = stack.copy();
        player.getInventory().inventory.getInventoryStorage().setItem(inventorySlot, converted);
    }

    public void updateEntityMetadata(int entityID, List<EntityMetadata.Entry> watchableObjects) {
        updateEntityMetadata(entityID, watchableObjects, player.lastTransactionSent.get() + 1);
    }

    /** Applies watchableObjects, which the client has applied once it answers provenAt. */
    public void updateEntityMetadata(int entityID, List<EntityMetadata.Entry> watchableObjects, int provenAt) {
        PacketEntity entity = player.compensatedEntities.getEntity(entityID);
        if (entity == null) return;
        if (entity instanceof PacketEntityHanging hanging) hanging.updateActionMetadata(watchableObjects);
        if (entity instanceof PacketEntityCat cat)
            cat.updateActionMetadata(
                    watchableObjects, player.user.getCultConnection().getObservedProtocol());
        if (entity instanceof PacketEntityArmorStand stand) stand.updateActionMetadata(watchableObjects);
        var flags = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.ENTITY_SHARED_FLAGS);
        if (flags != null && flags.value() instanceof Byte value) entity.actionSharedFlags = value;
        if (entity.isLivingEntity()) {
            var pose = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.ENTITY_POSE);
            if (pose != null && pose.value() instanceof EntityPose value) {
                entity.onPose(value, provenAt);
            }
            var health = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.LIVING_HEALTH);
            if (health != null && health.value() instanceof Float value) entity.actionAlive = value > 0.0F;
        }
        if (entity instanceof PacketEntityCreaking creaking) {
            var canMove = WatchableIndexUtil.getIndex(watchableObjects, 16);
            if (canMove != null && canMove.value() instanceof Boolean value) creaking.actionCanMove = value;
        }
        if (entity instanceof PacketEntitySpider spider) {
            var climbing = WatchableIndexUtil.getIndex(watchableObjects, 16);
            if (climbing != null && climbing.value() instanceof Byte value) spider.actionClimbing = (value & 1) != 0;
        }
        if (entity instanceof PacketEntityInteraction interaction) {
            var width = WatchableIndexUtil.getIndex(watchableObjects, 8);
            var height = WatchableIndexUtil.getIndex(watchableObjects, 9);
            if (width != null && width.value() instanceof Float value) interaction.actionWidth = value;
            if (height != null && height.value() instanceof Float value) interaction.actionHeight = value;
        }
        var modelType = EntityTypeUtil.modelType(entity.type);
        if (modelType != null && modelType.has(ac.cult.blocksim.entity.EntityTypes.ARROW)) {
            var inGround = WatchableIndexUtil.getIndex(watchableObjects, 10);
            if (inGround != null && inGround.value() instanceof Boolean value) entity.actionArrowInGround = value;
        }

        WatchableIndexUtil.Layout layout = WatchableIndexUtil.forModel(player.user.getCultConnection());
        applyAgeableMetadata(entity, watchableObjects);
        applySizeMetadata(entity, watchableObjects, layout);
        if (entity instanceof PacketEntityShulker) {
            applyShulkerMetadata(entity, watchableObjects);
        }
        if (entity instanceof PacketEntityRideable) {
            applyRideableMetadata(entity, watchableObjects, layout);
        }
        if (entity instanceof PacketEntityHorse) {
            applyHorseMetadata(entity, watchableObjects, layout);
        }
        if (entity instanceof PacketEntityHappyGhast happyGhast) {
            applyHappyGhastMetadata(happyGhast, watchableObjects, layout);
        }
        if (entity instanceof PacketEntityNautilus nautilus) {
            EntityMetadata.Entry dashData = WatchableIndexUtil.getIndex(watchableObjects, layout.nautilusDash());
            if (dashData != null && dashData.value() instanceof Boolean dashing) {
                nautilus.setDashingFromMetadata(dashing);
            }
        }
        applyGravityMetadata(entity, watchableObjects);

        if (entity.type == EntityTypeIds.FIREWORK_ROCKET) {
            EntityMetadata.Entry fireworkData =
                    WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.FIREWORK_ATTACHED_TO_TARGET);
            if (fireworkData == null) return;
            trackFireworkIfAttachedToPlayer(entityID, fireworkData.value());
        }

        if (entity instanceof PacketEntityHook) {
            EntityMetadata.Entry hookedData =
                    WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.FISHING_HOOKED_ENTITY);
            if (hookedData == null) return;
            ((PacketEntityHook) entity).attached = (Integer) hookedData.value() - 1; // the server adds 1 to the ID
        }

        if (entity instanceof PacketEntityRideable) {
            applyNoAiMetadata(entity, watchableObjects);
        }
    }

    private void applyAgeableMetadata(PacketEntity entity, List<EntityMetadata.Entry> watchableObjects) {
        if (!entity.isAgeable()) return;

        EntityMetadata.Entry babyData = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.AGEABLE_BABY);
        if (babyData == null) return;

        Object value = babyData.value();
        // Required because bukkit Ageable doesn't align with minecraft's ageable
        if (value instanceof Boolean) {
            entity.isBaby = (boolean) value;
        } else if (value instanceof Byte) {
            entity.isBaby = ((Byte) value) < 0;
        }
    }

    private void applySizeMetadata(
            PacketEntity entity, List<EntityMetadata.Entry> watchableObjects, WatchableIndexUtil.Layout layout) {
        if (!entity.isSize()) return;

        int sizeIndex = entity.type == EntityTypeIds.PHANTOM ? WatchableIndexUtil.PHANTOM_SIZE : layout.slimeSize();
        EntityMetadata.Entry sizeData = WatchableIndexUtil.getIndex(watchableObjects, sizeIndex);
        if (sizeData == null) return;

        Object value = sizeData.value();
        if (value instanceof Integer) {
            ((PacketEntitySizeable) entity).size = Math.max((int) value, 1);
        } else if (value instanceof Byte) {
            ((PacketEntitySizeable) entity).size = Math.max((byte) value, 1);
        }
    }

    private void applyShulkerMetadata(PacketEntity entity, List<EntityMetadata.Entry> watchableObjects) {
        EntityMetadata.Entry attachFaceData =
                WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.SHULKER_ATTACH_FACE);
        if (attachFaceData != null) {
            // This NMS -> Bukkit conversion is great and works in all 11 versions.
            ((PacketEntityShulker) entity).facing =
                    Direction.valueOf(attachFaceData.value().toString().toUpperCase());
        }

        EntityMetadata.Entry peekData = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.SHULKER_PEEK);
        if (peekData == null) return;

        int peek = Byte.toUnsignedInt((byte) peekData.value());
        if (peek == 0) {
            ShulkerData opened = new ShulkerData(entity, player.lastTransactionSent.get(), true);
            player.compensatedWorld.openShulkerBoxes.remove(opened);
            player.compensatedWorld.openShulkerBoxes.add(opened);
            return;
        }

        // MCP-Reborn Shulker#updatePeekAmount changes currentPeekAmount
        // by 0.05 toward DATA_PEEK_ID * 0.01 each client tick.
        int closingTicks = Math.max(1, (peek + 4) / 5);
        ShulkerData closing = new ShulkerData(entity, player.lastTransactionSent.get(), false, closingTicks);
        player.compensatedWorld.openShulkerBoxes.remove(closing);
        player.compensatedWorld.openShulkerBoxes.add(closing);
    }

    private void applyRideableMetadata(
            PacketEntity entity, List<EntityMetadata.Entry> watchableObjects, WatchableIndexUtil.Layout layout) {
        int saddleIndex = entity.type == EntityTypeIds.PIG ? layout.pigSaddle() : layout.striderSaddle();
        EntityMetadata.Entry saddleData = WatchableIndexUtil.getIndex(watchableObjects, saddleIndex);
        if (saddleData != null) {
            ((PacketEntityRideable) entity).hasSaddle = (boolean) saddleData.value();
        }
        if (entity.type == EntityTypeIds.PIG) {
            EntityMetadata.Entry boostData = WatchableIndexUtil.getIndex(watchableObjects, layout.pigBoostTime());
            if (boostData != null) {
                ((PacketEntityRideable) entity).boost.onSynced((int) boostData.value());
            }
            return;
        }

        if (entity instanceof PacketEntityStrider) {
            EntityMetadata.Entry boostData = WatchableIndexUtil.getIndex(watchableObjects, layout.striderBoostTime());
            if (boostData != null) {
                ((PacketEntityRideable) entity).boost.onSynced((int) boostData.value());
            }
        }
    }

    private void applyHorseMetadata(
            PacketEntity entity, List<EntityMetadata.Entry> watchableObjects, WatchableIndexUtil.Layout layout) {
        EntityMetadata.Entry flagsData = WatchableIndexUtil.getIndex(watchableObjects, layout.horseFlags());
        if (flagsData != null) {
            byte flags = (byte) flagsData.value();
            PacketEntityHorse horse = (PacketEntityHorse) entity;
            horse.isTame = (flags & 0x02) != 0;
            // Through 1.21.4, AbstractHorse#isSaddled reads flag 0x04 from
            // DATA_ID_FLAGS. Newer clients read EquipmentSlot.SADDLE instead.
            if (!layout.saddleEquipment()) {
                horse.hasSaddle = (flags & 0x04) != 0;
            }
            horse.setRearingFromMetadata((flags & 0x20) != 0);
        }

        if (entity instanceof PacketEntityCamel camel) {
            EntityMetadata.Entry dashData = WatchableIndexUtil.getIndex(watchableObjects, layout.camelDash());
            if (dashData != null) {
                camel.setDashingFromMetadata((boolean) dashData.value());
            }
        }
    }

    private void applyHappyGhastMetadata(
            PacketEntityHappyGhast happyGhast,
            List<EntityMetadata.Entry> watchableObjects,
            WatchableIndexUtil.Layout layout) {
        EntityMetadata.Entry staysStillData =
                WatchableIndexUtil.getIndex(watchableObjects, layout.happyGhastStaysStill());
        if (staysStillData != null && staysStillData.value() instanceof Boolean staysStill) {
            happyGhast.staysStill = staysStill;
        }
    }

    private void applyGravityMetadata(PacketEntity entity, List<EntityMetadata.Entry> watchableObjects) {
        EntityMetadata.Entry gravityData =
                WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.ENTITY_NO_GRAVITY);
        if (gravityData != null && gravityData.value() instanceof Boolean noGravity) {
            // Vanilla uses hasNoGravity, which is a bad name IMO
            // hasGravity > hasNoGravity
            entity.hasGravity = !noGravity;
        }
    }

    private void trackFireworkIfAttachedToPlayer(int entityID, Object rawValue) {
        boolean attachedToPlayer = rawValue instanceof OptionalInt optionalInt
                && optionalInt.isPresent()
                && optionalInt.getAsInt() == player.entityID;
        if (!attachedToPlayer && rawValue instanceof Optional<?> optional) {
            attachedToPlayer = optional.isPresent()
                    && optional.get() instanceof Integer referencedEntity
                    && referencedEntity == player.entityID;
        }
        if (attachedToPlayer) {
            player.compensatedFireworks.addNewFirework(entityID);
        }
    }

    private void applyNoAiMetadata(PacketEntity entity, List<EntityMetadata.Entry> watchableObjects) {
        EntityMetadata.Entry mobFlags = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.MOB_FLAGS);
        if (mobFlags != null) {
            entity.noAI = ((byte) mobFlags.value() & 0x01) != 0;
        }
    }

    /** ClientLevel root/passenger order and client ticking membership. */
    public boolean isTicking(PacketEntity entity) {
        if (entity.type == EntityTypeIds.PLAYER) return true;
        Vec3 position = entity.clientPhysicalPosition;
        return position != null
                && player.compensatedWorld.isChunkLoaded(
                        CultMath.floor(position.x) >> 4, CultMath.floor(position.z) >> 4);
    }

    public void tickClientEntities(Consumer<PacketEntity> tick) {
        List<PacketEntity> roots = rootsInAddOrder();
        if (roots == null) {
            roots = new ArrayList<>(entityMap.values());
            roots.add(getSelf());
            roots.removeIf(entity -> entity.getRiding() != null || !isTicking(entity));
            roots.sort(Comparator.comparingLong(PacketEntity::getClientTickOrder));
        }
        boolean frozen =
                player.packetStateData.serverTicksFrozen && player.packetStateData.serverFrozenTickStepsRemaining == 0;
        PacketEntity selfRoot = selfRidingRoot();
        PacketEntity serverRoot = serverVehicleRoot();
        for (PacketEntity root : roots) {
            if (frozen && !containsPlayer(root)) continue;
            if (root == selfRoot || root == serverRoot) {
                tickEntityTree(root, tick);
            } else if (root != getSelf()) {
                // Untracked riders are neither ticked nor positioned; they settle when they dismount.
                tick.accept(root);
            }
        }
    }

    /** Runs {@code tick} for every entity this player tracks except untracked riders, in entity map order. */
    public void forEachTrackedEntity(Consumer<PacketEntity> tick) {
        for (PacketEntity entity : entityMap.values()) {
            if (!isUntrackedRider(entity)) tick.accept(entity);
        }
    }

    /**
     * The ticking roots, self included, in the stable client tick order sort of entityMap values then self; null
     * unless the add order holds exactly the entityMap values with strictly increasing tick orders.
     */
    private List<PacketEntity> rootsInAddOrder() {
        int size = addOrder.size();
        if (size != entityMap.size()) return null;
        PacketEntity self = getSelf();
        long selfOrder = self.getClientTickOrder();
        boolean selfPending = self.getRiding() == null && isTicking(self);
        // Tracked passengers ride, so they are never roots.
        List<PacketEntity> roots = new ArrayList<>(Math.max(1, size + 1 - trackedPassengers));
        long previous = 0L;
        for (int i = 0; i < size; i++) {
            PacketEntity entity = addOrder.get(i);
            long order = entity.getClientTickOrder();
            if (i > 0 && order <= previous) return null;
            // Distinct orders make the entries distinct, so with equal sizes this proves the sets equal.
            if (entityMap.get(entity.getEntityId()) != entity) return null;
            previous = order;
            // Self ticks after an entity sharing its order.
            if (selfPending && selfOrder < order) {
                roots.add(self);
                selfPending = false;
            }
            if (entity.getRiding() == null && isTicking(entity)) roots.add(entity);
        }
        if (selfPending) roots.add(self);
        return roots;
    }

    private static boolean containsPlayer(PacketEntity entity) {
        if (entity.type == EntityTypeIds.PLAYER) return true;
        for (PacketEntity passenger : entity.passengers) if (containsPlayer(passenger)) return true;
        return false;
    }

    private void tickEntityTree(PacketEntity entity, Consumer<PacketEntity> tick) {
        // Player.tick reads isSpectator before its normal tick. Remote players cache PlayerInfo here.
        if (entity instanceof PacketEntityPlayer remote && remote.actionPresent) remote.cacheInfo(clientPlayerModes);
        if (entity != getSelf()) tick.accept(entity);
        if (entity.getRiding() != null) {
            positionPassenger(entity.getRiding(), entity);
        }
        for (PacketEntity passenger : entity.passengers) {
            if (passenger.getRiding() == entity && isTicking(passenger)) tickEntityTree(passenger, tick);
        }
    }

    /** The number of attribute updates held for entityID until it is added. */
    public int heldAttributeUpdates(int entityID) {
        List<HeldAttributes> held = pendingAttributes.get(entityID);
        return held == null ? 0 : held.size();
    }

    private void holdAttributes(int entityID, List<AttributeSnapshot> values, int provenAt) {
        List<HeldAttributes> held = pendingAttributes.get(entityID);
        if (held == null) {
            // A tracked spawn's attributes always hold; the client drops those for an id it lacks, so untracked ids
            // beyond the bound go unheld.
            if (pendingAttributes.size() >= HELD_ENTITIES && !serverPositionsMap.containsKey(entityID)) return;
            pendingAttributes.put(entityID, held = new ArrayList<>(HELD_UPDATES));
        }
        if (held.size() == HELD_UPDATES) {
            HeldAttributes oldest = held.remove(0);
            held.set(0, merge(oldest, held.get(0)));
        }
        held.add(new HeldAttributes(List.copyOf(values), provenAt, Float.NaN));
    }

    // The later values win, the earlier's scale stays an alternative, and the later proof proves both.
    private HeldAttributes merge(HeldAttributes earlier, HeldAttributes later) {
        Map<String, AttributeSnapshot> values = new LinkedHashMap<>();
        for (AttributeSnapshot snapshot : earlier.values()) values.put(snapshot.attribute(), snapshot);
        for (AttributeSnapshot snapshot : later.values()) values.put(snapshot.attribute(), snapshot);
        float leastScale = least(least(earlier.leastScale(), heldScale(earlier.values())), later.leastScale());
        return new HeldAttributes(
                List.copyOf(values.values()), Math.max(earlier.provenAt(), later.provenAt()), leastScale);
    }

    private float heldScale(List<AttributeSnapshot> values) {
        float scale = Float.NaN;
        for (AttributeSnapshot snapshot : values) {
            if (matchesAttribute(snapshot, "scale")) scale = (float) calculateAttribute(snapshot, 0.0625, 16.0);
        }
        return scale;
    }

    // The lesser of two scales, NaN standing for none.
    private static float least(float a, float b) {
        return b < a || a != a ? b : a;
    }

    /**
     * Attributes for an entity not yet added, which the client has applied once it answers provenAt; leastScale is
     * a smaller scale merged earlier updates set, or NaN.
     */
    private record HeldAttributes(List<AttributeSnapshot> values, int provenAt, float leastScale) {}
}
