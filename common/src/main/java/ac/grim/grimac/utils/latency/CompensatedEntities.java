package ac.grim.grimac.utils.latency;
import ac.grim.grimac.network.protocol.util.SpigotConversionUtil;
import ac.grim.grimac.network.protocol.player.User;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.ShulkerData;
import ac.grim.grimac.utils.data.TrackerData;
import ac.grim.grimac.utils.data.packetentity.*;
import ac.grim.grimac.utils.inventory.Inventory;
import ac.grim.grimac.utils.lists.EvictingQueue;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.nmsutil.BoundingBoxSize;
import ac.grim.grimac.utils.nmsutil.EntityTypesCompat;
import ac.grim.grimac.utils.nmsutil.WatchableIndexUtil;
import ac.grim.grimac.utils.nmsutil.WatchableIndexUtil.MetadataAccessor;
import ac.grim.grimac.utils.nmsutil.EntityTypeUtil;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import ac.grim.grimac.utils.nmsutil.NmsIdentifierUtil;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.bukkit.block.BlockFace;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.network.syncher.SynchedEntityData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public class CompensatedEntities {
    private static final EquipmentSlot SADDLE_EQUIPMENT_SLOT = resolveEquipmentSlot("saddle");
    private static final String SPRINTING_MODIFIER_ID = "minecraft:sprinting";
    public static final String SNOW_MODIFIER_ID = "minecraft:powder_snow";
    private static final long LOCAL_PLAYER_CLIENT_TICK_ORDER = 0L;
    private static final boolean USES_SADDLE_EQUIPMENT_SLOT = Arrays.stream(EquipmentSlot.values())
            .anyMatch(slot -> slot.name().equals("SADDLE"));
    private static final float DEFAULT_BEDROCK_PLAYER_JUMP_STRENGTH = 0.42F;
    public final Int2ObjectOpenHashMap<PacketEntity> entityMap = new Int2ObjectOpenHashMap<>(40, 0.7f);
    // Team checks use these profiles to resolve player names to UUIDs.
    public final Object2ObjectOpenHashMap<UUID, User.Profile> profiles = new Object2ObjectOpenHashMap<>();
    public final Int2ObjectOpenHashMap<TrackerData> serverPositionsMap = new Int2ObjectOpenHashMap<>(40, 0.7f);
    private final Int2ObjectOpenHashMap<List<ClientboundUpdateAttributesPacket.AttributeSnapshot>> pendingAttributes = new Int2ObjectOpenHashMap<>(10, 0.7f);
    private final Int2ObjectOpenHashMap<List<Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>>> pendingEquipment = new Int2ObjectOpenHashMap<>(10, 0.7f);
    public final CompensatedVehicleState vehicles;
    public boolean hasSprintingAttributeEnabled = false;
    private double bedrockPlayerMovementSpeed = 0.1f;
    private float bedrockPlayerJumpStrength = DEFAULT_BEDROCK_PLAYER_JUMP_STRENGTH;
    public List<SimpleCollisionBox> fishingRodPulls = new EvictingQueue<>(100); // sanity limit to prevent leaks.
    private long nextClientEntityTickOrder = 1L;

    GrimPlayer player;

    public TrackerData selfTrackedEntity;
    public PacketEntitySelf playerEntity;

    public CompensatedEntities(GrimPlayer player) {
        this.player = player;
        this.playerEntity = new PacketEntitySelf(player);
        this.vehicles = new CompensatedVehicleState(player, this);
        resetClientTickOrder();
        this.selfTrackedEntity = new TrackerData(0, 0, 0, 0, 0, EntityTypesCompat.PLAYER, player.lastTransactionSent.get());
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

        if (removedSelfVehicle) {
            hasSprintingAttributeEnabled = false;
            resetBedrockMovementSpeedAttribute();
        }
    }

    public Integer getJumpAmplifier() {
        return getPotionLevelForPlayer(PotionEffectType.JUMP_BOOST);
    }

    public Integer getLevitationAmplifier() {
        return getPotionLevelForPlayer(PotionEffectType.LEVITATION);
    }

    public Integer getSlowFallingAmplifier() {
        return getPotionLevelForPlayer(PotionEffectType.SLOW_FALLING);
    }

    public Integer getDolphinsGraceAmplifier() {
        return getPotionLevelForPlayer(PotionEffectType.DOLPHINS_GRACE);
    }

    public Integer getPotionLevelForPlayer(PotionEffectType type) {
        PacketEntity desiredEntity = getEntityInControl();

        HashMap<PotionEffectType, Integer> effects = desiredEntity.potionsMap;
        if (effects == null) return null;

        return effects.get(type);
    }

    public boolean hasPotionEffect(PotionEffectType type) {
        HashMap<PotionEffectType, Integer> effects = playerEntity.potionsMap;
        if (effects == null) return false;
        return effects.containsKey(type);
    }

    public PacketEntity getEntityInControl() {
        return playerEntity.getRiding() != null ? playerEntity.getRiding() : playerEntity;
    }

    // TODO: This doesn't belong here at all
    public double getPlayerMovementSpeed() {
        double speed = player.compensatedEntities.getSelf().playerSpeed;
        if (hasSprintingAttributeEnabled) {
            // MCP-Reborn LivingEntity#setSprinting adds the "sprinting"
            // ADD_MULTIPLIED_TOTAL modifier with amount 0.3. Attribute snapshots
            // strip that modifier so command-order compensation can apply it at
            // the exact packet boundary where the server and client both do.
            speed *= 1.3D;
        }
        return GrimMath.clampFloat((float) speed, 0.0F, 1024.0F);
    }

    public double getBedrockPlayerMovementSpeed() {
        return GrimMath.clampFloat((float) bedrockPlayerMovementSpeed, 0.0F, 1024.0F);
    }

    public float getBedrockPlayerJumpStrength() {
        return bedrockPlayerJumpStrength;
    }

    public void resetBedrockMovementSpeedAttribute() {
        bedrockPlayerMovementSpeed = 0.1f;
    }

    public void updateAttributes(int entityID, List<ClientboundUpdateAttributesPacket.AttributeSnapshot> objects) {
        boolean selfScaleChanged = false;
        if (entityID == player.entityID) {
            for (ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : objects) {
                if (matchesAttribute(snapshot, "movement_speed")) {

                    boolean foundSprintingModifier = false;
                    for (AttributeModifier modifier : snapshot.modifiers()) {
                        if (NmsIdentifierUtil.attributeModifierId(modifier).equals(SPRINTING_MODIFIER_ID)) {
                            foundSprintingModifier = true;
                        }
                    }

                    // The server can set the player's sprinting attribute
                    hasSprintingAttributeEnabled = foundSprintingModifier;
                    bedrockPlayerMovementSpeed = calculateAttribute(
                            snapshot,
                            0.0,
                            1024.0,
                            Set.of(SPRINTING_MODIFIER_ID, SNOW_MODIFIER_ID)
                    );
                    player.compensatedEntities.getSelf().playerSpeed = calculateAttribute(
                            snapshot,
                            0.0,
                            1024.0,
                            Set.of(SPRINTING_MODIFIER_ID, SNOW_MODIFIER_ID)
                    );
                }

                if (matchesAttribute(snapshot, "jump_strength")) {
                    // Geyser translates Java's transaction-ordered
                    // JUMP_STRENGTH snapshot to Bedrock's jump-strength
                    // attribute. Use that server-authored value, never the
                    // client-to-server MovementPredictionSync echo.
                    bedrockPlayerJumpStrength = calculateBedrockAttributeCurrent(snapshot);
                }

                if (matchesAttribute(snapshot, "scale")) {
                    player.compensatedEntities.getSelf().scale = (float) calculateAttribute(snapshot, 0.0625, 16.0);
                    selfScaleChanged = true;
                }

                if (matchesAttribute(snapshot, "gravity")) {
                    player.compensatedEntities.getSelf().gravity = calculateAttribute(snapshot, 0.0, 1024.0);
                }

                if (matchesAttributeName(snapshot, "step_height")) {
                    player.compensatedEntities.getSelf().stepHeightAttribute = calculateAttribute(snapshot, 0.0, 10.0);
                }

                // 26.2 attributes (Attributes#BOUNCINESS / FRICTION_MODIFIER /
                // AIR_DRAG_MODIFIER). Matched by registry path because the static
                // constants do not exist on pre-26.2 servers.
                if (matchesAttributeName(snapshot, "bounciness")) {
                    player.compensatedEntities.getSelf().bounciness = calculateAttribute(snapshot, 0.0, 1.0);
                }

                if (matchesAttributeName(snapshot, "friction_modifier")) {
                    player.compensatedEntities.getSelf().frictionModifier = calculateAttribute(snapshot, 0.0, 2048.0);
                }

                if (matchesAttributeName(snapshot, "air_drag_modifier")) {
                    player.compensatedEntities.getSelf().airDragModifier = calculateAttribute(snapshot, 0.0, 2048.0);
                }

                if (matchesAttribute(snapshot, "block_interaction_range")) {
                    player.compensatedEntities.getSelf().setBlockInteractionRange(calculateAttribute(snapshot, 0.0, 64.0));
                }

                if (matchesAttributeName(snapshot, "block_break_speed")) {
                    player.compensatedEntities.getSelf().blockBreakSpeed = calculateAttribute(snapshot, 0.0, 1024.0);
                }

                if (matchesAttributeName(snapshot, "mining_efficiency")) {
                    player.compensatedEntities.getSelf().miningEfficiency = calculateAttribute(snapshot, 0.0, 1024.0);
                }

                if (matchesAttributeName(snapshot, "submerged_mining_speed")) {
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
            for (ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : objects) {
                if (matchesAttribute(snapshot, "scale")) {
                    entity.scale = (float) calculateAttribute(snapshot, 0.0625, 16.0);
                    entityScaleChanged = true;
                }

                if (matchesAttribute(snapshot, "gravity")) {
                    entity.gravity = calculateAttribute(snapshot, 0.0, 1024.0);
                }

                if (matchesAttributeName(snapshot, "step_height")) {
                    entity.stepHeightAttribute = calculateAttribute(snapshot, 0.0, 10.0);
                }

                if (matchesAttributeName(snapshot, "bounciness")) {
                    entity.bounciness = calculateAttribute(snapshot, 0.0, 1.0);
                }

                if (matchesAttributeName(snapshot, "friction_modifier")) {
                    entity.frictionModifier = calculateAttribute(snapshot, 0.0, 2048.0);
                }

                if (matchesAttributeName(snapshot, "air_drag_modifier")) {
                    entity.airDragModifier = calculateAttribute(snapshot, 0.0, 2048.0);
                }
            }
        }

        if (entity instanceof PacketEntityHorse) {
            for (ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : objects) {
                if (matchesAttribute(snapshot, "movement_speed")) {
                    ((PacketEntityHorse) entity).movementSpeedAttribute = (float) calculateAttribute(snapshot, 0.0, 1024.0);
                }

                if (matchesAttribute(snapshot, "jump_strength")) {
                    ((PacketEntityHorse) entity).jumpStrength = calculateAttribute(snapshot, 0.0, 2.0);
                }
            }
        }

        if (entity instanceof PacketEntityRideable) {
            for (ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : objects) {
                if (matchesAttribute(snapshot, "movement_speed")) {
                    ((PacketEntityRideable) entity).movementSpeedAttribute = (float) calculateAttribute(snapshot, 0.0, 1024.0);
                }

                if (matchesAttribute(snapshot, "flying_speed")) {
                    ((PacketEntityRideable) entity).flyingSpeedAttribute = (float) calculateAttribute(snapshot, 0.0, 1024.0);
                }
            }
        }

        if (entity instanceof PacketEntityHappyGhast happyGhast) {
            for (ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : objects) {
                if (matchesAttribute(snapshot, "movement_speed")) {
                    happyGhast.movementSpeedAttribute = (float) calculateAttribute(snapshot, 0.0, 1024.0);
                }

                if (matchesAttribute(snapshot, "flying_speed")) {
                    happyGhast.flyingSpeedAttribute = (float) calculateAttribute(snapshot, 0.0, 1024.0);
                }
            }
        }

        if (entity instanceof PacketEntityNautilus nautilus) {
            for (ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : objects) {
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

    private boolean matchesAttributeName(ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot, String path) {
        return snapshot.attribute()
                .unwrapKey()
                .map(key -> NmsIdentifierUtil.resourceKey(key).equals("minecraft:" + path))
                .orElse(false);
    }

    private boolean matchesAttribute(ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot, String suffix) {
        if ("movement_speed".equals(suffix)) {
            return snapshot.attribute().is(Attributes.MOVEMENT_SPEED);
        }
        if ("jump_strength".equals(suffix)) {
            return snapshot.attribute().is(Attributes.JUMP_STRENGTH);
        }
        if ("flying_speed".equals(suffix)) {
            return snapshot.attribute().is(Attributes.FLYING_SPEED);
        }
        if ("scale".equals(suffix)) {
            return snapshot.attribute().is(Attributes.SCALE);
        }
        if ("gravity".equals(suffix)) {
            return snapshot.attribute().is(Attributes.GRAVITY);
        }
        if ("block_interaction_range".equals(suffix)) {
            return snapshot.attribute().is(Attributes.BLOCK_INTERACTION_RANGE);
        }
        return false;
    }

    private double calculateAttribute(ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot, double minValue, double maxValue) {
        return calculateAttribute(snapshot, minValue, maxValue, Set.of(SPRINTING_MODIFIER_ID, SNOW_MODIFIER_ID));
    }

    private double calculateAttribute(
            ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot,
            double minValue,
            double maxValue,
            Set<String> excludedModifierIds
    ) {
        return GrimMath.clampFloat(
                (float) calculateAttributeValue(snapshot, excludedModifierIds),
                (float) minValue,
                (float) maxValue);
    }

    private float calculateBedrockAttributeCurrent(ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot) {
        // Geyser applies every modifier, casts the calculated double to float, and
        // puts that value into AttributeData without clamping it to the advertised
        // attribute bounds (LivingEntity.java:673,734-735; AttributeUtils.java:39-57;
        // GeyserAttributeType.java:78-87). vanilla likewise stores the supplied current
        // value directly .
        return (float) calculateAttributeValue(snapshot, Set.of());
    }

    private double calculateAttributeValue(
            ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot,
            Set<String> excludedModifierIds
    ) {
        double d0 = snapshot.base();

        List<AttributeModifier> modifiers = new ArrayList<>(snapshot.modifiers());
        modifiers.removeIf(modifier -> {
            for (String excludedModifierId : excludedModifierIds) {
                if (NmsIdentifierUtil.attributeModifierId(modifier).equals(excludedModifierId)) {
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

        Vec3 passengerPosition = BoundingBoxSize.getRidingOffsetFromVehicle(riding, passenger, player);
        passenger.setPositionRaw(GetBoundingBox.getPacketEntityBoundingBox(
                player,
                passengerPosition.x,
                passengerPosition.y,
                passengerPosition.z,
                passenger
        ));
        if (passenger == playerEntity) {
            player.packetStateData.clientSidePosition = passengerPosition;
        }

        for (PacketEntity passengerPassenger : passenger.passengers) {
            tickPassenger(passenger, passengerPassenger);
        }
    }

    public void addEntity(int entityID, EntityType entityType, Vec3 position, float xRot, float yRot, int data) {
        // Dropped items are all server sided and players can't interact with them (except create them!), save the performance
        if (entityType == EntityTypesCompat.ITEM) return;

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
        List<ClientboundUpdateAttributesPacket.AttributeSnapshot> pending = pendingAttributes.remove(entityID);
        if (pending != null) {
            updateAttributes(entityID, pending);
        }
        List<Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>> pendingEquipmentUpdates = pendingEquipment.remove(entityID);
        if (pendingEquipmentUpdates != null) {
            updateEntityEquipment(entityID, pendingEquipmentUpdates);
        }
    }

    private PacketEntity createTrackedEntity(int entityID, EntityType entityType, Vec3 position, float xRot, int data) {
        if (EntityTypeUtil.isCamelFamily(entityType)) {
            return new PacketEntityCamel(player, entityID, entityType, position.x, position.y, position.z, xRot);
        }
        if (EntityTypeUtil.isHorseFamily(entityType)) {
            return new PacketEntityHorse(player, entityID, entityType, position.x, position.y, position.z, xRot);
        }
        if (entityType == EntityTypesCompat.SLIME || entityType == EntityTypesCompat.MAGMA_CUBE || entityType == EntityTypesCompat.PHANTOM) {
            return new PacketEntitySizeable(player, entityID, entityType, position.x, position.y, position.z);
        }
        if (EntityTypesCompat.PIG.equals(entityType)) {
            return new PacketEntityRideable(player, entityID, entityType, position.x, position.y, position.z);
        }
        if (EntityTypeUtil.isHappyGhast(entityType)) {
            return new PacketEntityHappyGhast(player, entityID, entityType, position.x, position.y, position.z, xRot);
        }
        if (EntityTypeUtil.isNautilusFamily(entityType)) {
            return new PacketEntityNautilus(player, entityID, entityType, position.x, position.y, position.z, xRot);
        }
        if (EntityTypesCompat.SHULKER.equals(entityType)) {
            return new PacketEntityShulker(player, entityID, entityType, position.x, position.y, position.z);
        }
        if (EntityTypesCompat.STRIDER.equals(entityType)) {
            return new PacketEntityStrider(player, entityID, entityType, position.x, position.y, position.z);
        }
        if (EntityTypeUtil.isBoat(entityType) || EntityTypesCompat.CHICKEN.equals(entityType)) {
            return new PacketEntityTrackXRot(player, entityID, entityType, position.x, position.y, position.z, xRot);
        }
        if (EntityTypesCompat.FISHING_BOBBER.equals(entityType)) {
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

    public void updateEntityEquipment(int entityID, List<Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>> slots) {
        PacketEntity entity = getEntity(entityID);
        if (entity == null) {
            List<Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>> merged = new ArrayList<>();
            List<Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>> existing = pendingEquipment.remove(entityID);
            if (existing != null) {
                merged.addAll(existing);
            }
            for (Pair<EquipmentSlot, net.minecraft.world.item.ItemStack> slot : slots) {
                int existingIndex = -1;
                for (int i = 0; i < merged.size(); i++) {
                    if (merged.get(i).getFirst() == slot.getFirst()) {
                        existingIndex = i;
                        break;
                    }
                }
                Pair<EquipmentSlot, net.minecraft.world.item.ItemStack> copiedSlot =
                        Pair.of(slot.getFirst(), slot.getSecond().copy());
                if (existingIndex >= 0) {
                    merged.set(existingIndex, copiedSlot);
                } else {
                    merged.add(copiedSlot);
                }
            }
            pendingEquipment.put(entityID, merged);
            return;
        }

        for (Pair<EquipmentSlot, net.minecraft.world.item.ItemStack> slot : slots) {
            updateSelfEquipment(entityID, slot.getFirst(), slot.getSecond());

            if (slot.getFirst() == SADDLE_EQUIPMENT_SLOT) {
                boolean hasSaddle = !slot.getSecond().isEmpty();
                if (entity instanceof PacketEntityRideable rideable) {
                    rideable.hasSaddle = hasSaddle;
                }
                if (entity instanceof PacketEntityHorse horse) {
                    horse.hasSaddle = hasSaddle;
                }
                if (entity instanceof PacketEntityNautilus nautilus) {
                    nautilus.hasSaddle = hasSaddle;
                }
            } else if (slot.getFirst() == EquipmentSlot.BODY && entity instanceof PacketEntityHappyGhast happyGhast) {
                happyGhast.hasBodyArmor = !slot.getSecond().isEmpty();
            }
        }
    }

    private void updateSelfEquipment(int entityID, EquipmentSlot slot, net.minecraft.world.item.ItemStack stack) {
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
        } else if (slot == SADDLE_EQUIPMENT_SLOT) {
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
        ItemStack converted = SpigotConversionUtil.fromNmsItemStack(stack);
        player.getInventory().inventory.getInventoryStorage().setItem(inventorySlot, converted);
    }

    private static EquipmentSlot resolveEquipmentSlot(String name) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (name.equals(slot.getName())) {
                return slot;
            }
        }
        return null;
    }

    public void updateEntityMetadata(int entityID, List<SynchedEntityData.DataValue<?>> watchableObjects) {
        PacketEntity entity = player.compensatedEntities.getEntity(entityID);
        if (entity == null) return;

        applyAgeableMetadata(entity, watchableObjects);
        applySizeMetadata(entity, watchableObjects);
        if (entity instanceof PacketEntityShulker) {
            applyShulkerMetadata(entity, watchableObjects);
        }
        if (entity instanceof PacketEntityRideable) {
            applyRideableBoostMetadata(entity, watchableObjects);
        }
        if (entity instanceof PacketEntityHorse) {
            applyHorseMetadata(entity, watchableObjects);
        }
        if (entity instanceof PacketEntityHappyGhast happyGhast) {
            applyHappyGhastMetadata(happyGhast, watchableObjects);
        }
        if (entity instanceof PacketEntityNautilus nautilus) {
            SynchedEntityData.DataValue<?> dashData = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.NAUTILUS_DASH);
            if (dashData != null && dashData.value() instanceof Boolean dashing) {
                nautilus.setDashingFromMetadata(dashing);
            }
        }
        applyGravityMetadata(entity, watchableObjects);

        if (entity.type == EntityTypesCompat.FIREWORK_ROCKET) {
            SynchedEntityData.DataValue<?> fireworkData = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.FIREWORK_ATTACHED_TO_TARGET);
            if (fireworkData == null) return;
            trackFireworkIfAttachedToPlayer(entityID, fireworkData.value());
        }

        if (entity instanceof PacketEntityHook) {
            SynchedEntityData.DataValue<?> hookedData = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.FISHING_HOOKED_ENTITY);
            if (hookedData == null) return;
            ((PacketEntityHook) entity).attached = (Integer) hookedData.value() - 1; // the server adds 1 to the ID
        }

        if (entity instanceof PacketEntityRideable) {
            applyNoAiMetadata(entity, watchableObjects);
        }
    }

    private void applyAgeableMetadata(PacketEntity entity, List<SynchedEntityData.DataValue<?>> watchableObjects) {
        if (!entity.isAgeable()) return;

        SynchedEntityData.DataValue<?> babyData = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.AGEABLE_BABY);
        if (babyData == null) return;

        Object value = babyData.value();
        // Required because bukkit Ageable doesn't align with minecraft's ageable
        if (value instanceof Boolean) {
            entity.isBaby = (boolean) value;
        } else if (value instanceof Byte) {
            entity.isBaby = ((Byte) value) < 0;
        }
    }

    private void applySizeMetadata(PacketEntity entity, List<SynchedEntityData.DataValue<?>> watchableObjects) {
        if (!entity.isSize()) return;

        MetadataAccessor<Integer> sizeIndex = entity.type == EntityTypesCompat.PHANTOM ? WatchableIndexUtil.PHANTOM_SIZE : WatchableIndexUtil.SLIME_SIZE;
        SynchedEntityData.DataValue<?> sizeData = WatchableIndexUtil.getIndex(watchableObjects, sizeIndex);
        if (sizeData == null) return;

        Object value = sizeData.value();
        if (value instanceof Integer) {
            ((PacketEntitySizeable) entity).size = Math.max((int) value, 1);
        } else if (value instanceof Byte) {
            ((PacketEntitySizeable) entity).size = Math.max((byte) value, 1);
        }
    }

    private void applyShulkerMetadata(PacketEntity entity, List<SynchedEntityData.DataValue<?>> watchableObjects) {
        SynchedEntityData.DataValue<?> attachFaceData = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.SHULKER_ATTACH_FACE);
        if (attachFaceData != null) {
            // This NMS -> Bukkit conversion is great and works in all 11 versions.
            ((PacketEntityShulker) entity).facing = BlockFace.valueOf(attachFaceData.value().toString().toUpperCase());
        }

        SynchedEntityData.DataValue<?> peekData = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.SHULKER_PEEK);
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

    private void applyRideableBoostMetadata(PacketEntity entity, List<SynchedEntityData.DataValue<?>> watchableObjects) {
        if (entity.type == EntityTypesCompat.PIG) {
            SynchedEntityData.DataValue<?> saddleData = WatchableIndexUtil.PIG_SADDLE.resolved()
                    ? WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.PIG_SADDLE)
                    : null;
            if (saddleData != null && saddleData.value() instanceof Boolean saddled) {
                ((PacketEntityRideable) entity).hasSaddle = saddled;
            }

            SynchedEntityData.DataValue<?> boostData = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.PIG_BOOST_TIME);
            if (boostData != null) {
                ((PacketEntityRideable) entity).boostTimeMax = (int) boostData.value();
                ((PacketEntityRideable) entity).currentBoostTime = 0;
            }
            return;
        }

        if (entity instanceof PacketEntityStrider) {
            SynchedEntityData.DataValue<?> saddleData = WatchableIndexUtil.STRIDER_SADDLE.resolved()
                    ? WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.STRIDER_SADDLE)
                    : null;
            if (saddleData != null && saddleData.value() instanceof Boolean saddled) {
                ((PacketEntityRideable) entity).hasSaddle = saddled;
            }

            SynchedEntityData.DataValue<?> boostData = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.STRIDER_BOOST_TIME);
            if (boostData != null) {
                ((PacketEntityRideable) entity).boostTimeMax = (int) boostData.value();
                ((PacketEntityRideable) entity).currentBoostTime = 0;
            }
        }
    }

    private void applyHorseMetadata(PacketEntity entity, List<SynchedEntityData.DataValue<?>> watchableObjects) {
        SynchedEntityData.DataValue<?> flagsData = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.HORSE_FLAGS);
        if (flagsData != null) {
            byte flags = (byte) flagsData.value();
            PacketEntityHorse horse = (PacketEntityHorse) entity;
            horse.isTame = (flags & 0x02) != 0;
            // Through 1.21.3, AbstractHorse#isSaddled reads flag 0x04 from
            // DATA_ID_FLAGS. Newer clients read EquipmentSlot.SADDLE instead.
            if (!USES_SADDLE_EQUIPMENT_SLOT) {
                horse.hasSaddle = (flags & 0x04) != 0;
            }
            horse.setRearingFromMetadata((flags & 0x20) != 0);
        }

        if (entity instanceof PacketEntityCamel camel) {
            SynchedEntityData.DataValue<?> dashData = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.CAMEL_DASH);
            if (dashData != null) {
                camel.setDashingFromMetadata((boolean) dashData.value());
            }
        }
    }

    private void applyHappyGhastMetadata(PacketEntityHappyGhast happyGhast, List<SynchedEntityData.DataValue<?>> watchableObjects) {
        SynchedEntityData.DataValue<?> staysStillData = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.HAPPY_GHAST_STAYS_STILL);
        if (staysStillData != null && staysStillData.value() instanceof Boolean staysStill) {
            happyGhast.staysStill = staysStill;
        }
    }

    private void applyGravityMetadata(PacketEntity entity, List<SynchedEntityData.DataValue<?>> watchableObjects) {
        SynchedEntityData.DataValue<?> gravityData = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.ENTITY_NO_GRAVITY);
        if (gravityData != null && gravityData.value() instanceof Boolean noGravity) {
            // Vanilla uses hasNoGravity, which is a bad name IMO
            // hasGravity > hasNoGravity
            entity.hasGravity = !noGravity;
        }
    }

    private void trackFireworkIfAttachedToPlayer(int entityID, Object rawValue) {
        boolean attachedToPlayer = rawValue instanceof OptionalInt optionalInt && optionalInt.isPresent() && optionalInt.getAsInt() == player.entityID;
        if (!attachedToPlayer && rawValue instanceof Optional<?> optional) {
            attachedToPlayer = optional.isPresent() && Objects.equals(optional.get(), player.entityID);
        }
        if (attachedToPlayer) {
            player.compensatedFireworks.addNewFirework(entityID);
        }
    }

    private void applyNoAiMetadata(PacketEntity entity, List<SynchedEntityData.DataValue<?>> watchableObjects) {
        SynchedEntityData.DataValue<?> mobFlags = WatchableIndexUtil.getIndex(watchableObjects, WatchableIndexUtil.MOB_FLAGS);
        if (mobFlags != null) {
            entity.noAI = ((byte) mobFlags.value() & 0x01) != 0;
        }
    }
}
