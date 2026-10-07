package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
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

public class CompensatedEntities {
    public final ClientPlayerModes clientPlayerModes = new ClientPlayerModes();
    private static final String SPRINTING_MODIFIER_ID = "minecraft:sprinting";
    public static final String SNOW_MODIFIER_ID = "minecraft:powder_snow";
    private static final long LOCAL_PLAYER_CLIENT_TICK_ORDER = 0L;
    public final Int2ObjectOpenHashMap<PacketEntity> entityMap = new Int2ObjectOpenHashMap<>(40, 0.7f);
    public final Int2ObjectOpenHashMap<TrackerData> serverPositionsMap = new Int2ObjectOpenHashMap<>(40, 0.7f);
    private final Int2ObjectOpenHashMap<List<AttributeSnapshot>> pendingAttributes =
            new Int2ObjectOpenHashMap<>(10, 0.7f);
    private final Int2ObjectOpenHashMap<List<EquipmentEntry>> pendingEquipment = new Int2ObjectOpenHashMap<>(10, 0.7f);
    public final CompensatedVehicleState vehicles;
    public boolean hasSprintingAttributeEnabled = false;
    public List<SimpleCollisionBox> fishingRodPulls = new EvictingQueue<>(100); // sanity limit to prevent leaks.
    private long nextClientEntityTickOrder = 1L;

    CultPlayer player;

    public TrackerData selfTrackedEntity;
    public PacketEntitySelf playerEntity;

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

    public void tick() {
        this.playerEntity.setPositionRaw(player.boundingBox);
        updatePassengerPositions();
    }

    public void updatePassengerPositions() {
        for (PacketEntity vehicle : entityMap.values()) {
            for (PacketEntity passenger : vehicle.passengers) {
                tickPassenger(vehicle, passenger);
            }
        }
    }

    public void removeEntity(int entityID) {
        PacketEntity previousRoot = playerEntity.getRiding();
        PacketEntity entity = entityMap.remove(entityID);
        pendingAttributes.remove(entityID);
        pendingEquipment.remove(entityID);
        if (entity == null) return;

        boolean removedSelfVehicle = vehicles.serverPlayerVehicle != null && vehicles.serverPlayerVehicle == entityID;
        if (removedSelfVehicle) {
            vehicles.clearServerVehicle();
        }
        for (PacketEntity passenger : new ArrayList<>(entity.passengers)) {
            passenger.eject();
        }
        if (player.isBedrockMovement()) {
            vehicles.seedStartingVelocityIfRootChanged(previousRoot);
            if (previousRoot == null) player.getSetbackTeleportUtil().transferBedrockVehicleSetback(entityID);
        }

        if (removedSelfVehicle) {
            hasSprintingAttributeEnabled = false;
        }
    }

    public Integer getJumpAmplifier() {
        return getPotionLevelForPlayer(MovementEffect.JUMP_BOOST);
    }

    public Integer getLevitationAmplifier() {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_9)) return null;
        return getPotionLevelForPlayer(MovementEffect.LEVITATION);
    }

    public Integer getSlowFallingAmplifier() {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_13)) return null;
        return getPotionLevelForPlayer(MovementEffect.SLOW_FALLING);
    }

    public Integer getDolphinsGraceAmplifier() {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_13)) return null;
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
        boolean selfScaleChanged = false;
        if (entityID == player.entityID) {
            for (AttributeSnapshot snapshot : objects) {
                if (matchesAttribute(snapshot, "movement_speed")) {

                    boolean foundSprintingModifier = false;
                    for (AttributeModifier modifier : snapshot.modifiers()) {
                        if (modifier.id().equals(SPRINTING_MODIFIER_ID)) {
                            foundSprintingModifier = true;
                        }
                    }

                    // The server can set the player's sprinting attribute
                    if (!player.isBedrockMovement()) hasSprintingAttributeEnabled = foundSprintingModifier;
                    player.compensatedEntities.getSelf().playerSpeed =
                            calculateAttribute(snapshot, 0.0, 1024.0, Set.of(SPRINTING_MODIFIER_ID, SNOW_MODIFIER_ID));
                }

                if (supportsAttributes(ClientVersion.V_1_20_5) && matchesAttribute(snapshot, "scale")) {
                    player.compensatedEntities.getSelf().scale = (float) calculateAttribute(snapshot, 0.0625, 16.0);
                    selfScaleChanged = true;
                }

                if (supportsAttributes(ClientVersion.V_1_20_5) && matchesAttribute(snapshot, "gravity")) {
                    player.compensatedEntities.getSelf().gravity = calculateAttribute(snapshot, 0.0, 1024.0);
                }

                if (supportsAttributes(ClientVersion.V_1_20_5) && matchesAttribute(snapshot, "step_height")) {
                    player.compensatedEntities.getSelf().stepHeightAttribute = calculateAttribute(snapshot, 0.0, 10.0);
                }

                // 26.2 attributes (Attributes#BOUNCINESS / FRICTION_MODIFIER /
                // AIR_DRAG_MODIFIER). Matched by registry path because the static
                // constants do not exist on pre-26.2 servers.
                if (supportsAttributes(ClientVersion.V_26_2) && matchesAttribute(snapshot, "bounciness")) {
                    player.compensatedEntities.getSelf().bounciness = calculateAttribute(snapshot, 0.0, 1.0);
                }

                if (supportsAttributes(ClientVersion.V_26_2) && matchesAttribute(snapshot, "friction_modifier")) {
                    player.compensatedEntities.getSelf().frictionModifier = calculateAttribute(snapshot, 0.0, 2048.0);
                }

                if (supportsAttributes(ClientVersion.V_26_2) && matchesAttribute(snapshot, "air_drag_modifier")) {
                    player.compensatedEntities.getSelf().airDragModifier = calculateAttribute(snapshot, 0.0, 2048.0);
                }

                if (matchesAttribute(snapshot, "block_interaction_range")) {
                    player.compensatedEntities
                            .getSelf()
                            .setBlockInteractionRange(calculateAttribute(snapshot, 0.0, 64.0));
                }

                if (matchesAttribute(snapshot, "block_break_speed")) {
                    player.compensatedEntities.getSelf().blockBreakSpeed = calculateAttribute(snapshot, 0.0, 1024.0);
                }

                if (matchesAttribute(snapshot, "mining_efficiency")) {
                    player.compensatedEntities.getSelf().miningEfficiency = calculateAttribute(snapshot, 0.0, 1024.0);
                }

                if (matchesAttribute(snapshot, "submerged_mining_speed")) {
                    player.compensatedEntities.getSelf().submergedMiningSpeed = calculateAttribute(snapshot, 0.0, 20.0);
                }
            }
        }

        PacketEntity entity = player.compensatedEntities.getEntity(entityID);
        boolean entityScaleChanged = false;
        if (entity == null && entityID != player.entityID) {
            pendingAttributes.put(entityID, new ArrayList<>(objects));
            return;
        }

        if (entity != null) {
            for (AttributeSnapshot snapshot : objects) {
                if (supportsAttributes(ClientVersion.V_1_20_5) && matchesAttribute(snapshot, "scale")) {
                    entity.scale = (float) calculateAttribute(snapshot, 0.0625, 16.0);
                    entityScaleChanged = true;
                }

                if (supportsAttributes(ClientVersion.V_1_20_5) && matchesAttribute(snapshot, "gravity")) {
                    entity.gravity = calculateAttribute(snapshot, 0.0, 1024.0);
                }

                if (supportsAttributes(ClientVersion.V_1_20_5) && matchesAttribute(snapshot, "step_height")) {
                    entity.stepHeightAttribute = calculateAttribute(snapshot, 0.0, 10.0);
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
                    horse.jumpStrength = calculateAttribute(snapshot, 0.0, 2.0);
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

        if (selfScaleChanged) {
            player.refreshPlayerPose();
        }
        if (entityScaleChanged && entityID != player.entityID) {
            entity.refreshDimensions(player);
        }
    }

    private boolean matchesAttribute(AttributeSnapshot snapshot, String path) {
        return snapshot.attribute().equals("minecraft:" + path);
    }

    private double calculateAttribute(AttributeSnapshot snapshot, double minValue, double maxValue) {
        return calculateAttribute(snapshot, minValue, maxValue, Set.of(SPRINTING_MODIFIER_ID, SNOW_MODIFIER_ID));
    }

    private double calculateAttribute(
            AttributeSnapshot snapshot, double minValue, double maxValue, Set<String> excludedModifierIds) {
        return CultMath.clampFloat(
                (float) calculateAttributeValue(snapshot, excludedModifierIds), (float) minValue, (float) maxValue);
    }

    private double calculateAttributeValue(AttributeSnapshot snapshot, Set<String> excludedModifierIds) {
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

    public void addEntity(int entityID, int entityType, Vec3 position, float xRot, float yRot, int data) {
        // Dropped items are all server sided and players can't interact with them (except create them!), save the
        // performance
        if (entityType == EntityTypeIds.ITEM) return;

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
        entityMap.put(entityID, packetEntity);
        List<AttributeSnapshot> pending = pendingAttributes.remove(entityID);
        if (pending != null) {
            updateAttributes(entityID, pending);
        }
        List<EquipmentEntry> pendingEquipmentUpdates = pendingEquipment.remove(entityID);
        if (pendingEquipmentUpdates != null) {
            updateEntityEquipment(entityID, pendingEquipmentUpdates);
        }
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
            if (pose != null && pose.value() instanceof EntityPose value) entity.actionPose = value;
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
        if (!player.isBedrockMovement() && player.getClientVersion().isOlderThan(ClientVersion.V_1_10)) return;
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
        var roots = new ArrayList<>(entityMap.values());
        roots.add(getSelf());
        roots.removeIf(entity -> entity.getRiding() != null || !isTicking(entity));
        roots.sort(Comparator.comparingLong(PacketEntity::getClientTickOrder));
        boolean frozen =
                player.packetStateData.serverTicksFrozen && player.packetStateData.serverFrozenTickStepsRemaining == 0;
        for (PacketEntity root : roots) {
            if (!frozen || containsPlayer(root)) tickEntityTree(root, tick);
        }
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
}
