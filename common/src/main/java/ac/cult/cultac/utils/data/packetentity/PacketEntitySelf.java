package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.sprint.SprintD;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientAttributeInstance;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

public class PacketEntitySelf extends PacketEntity {
    public double playerSpeed = 0.1f;
    public double jumpStrength = 0.42F;
    // 26.2 movement attributes (Attributes#BOUNCINESS / FRICTION_MODIFIER /
    // AIR_DRAG_MODIFIER), tracked from server attribute packets. Defaults are
    // the vanilla values and leave movement unchanged.
    public double blockBreakSpeed = 1.0D;
    public double miningEfficiency = 0.0D;
    public double submergedMiningSpeed = 0.2D;

    private final CultPlayer player;

    @Getter
    @Setter
    int opLevel;

    @Getter
    double health;

    public void setHealth(double health) {
        this.health = health;
        // SET_HEALTH and entity metadata both write LivingEntity's DATA_HEALTH_ID on the client.
        this.actionAlive = health > 0.0D;
    }

    // Player#blockInteractionRange and #entityInteractionRange read these attributes. Only server
    // attribute packets change them: ServerPlayer#updatePlayerAttributes owns the creative modifiers.
    public final ClientAttributeInstance blockInteractionRange = new ClientAttributeInstance(4.5D, 0.0D, 64.0D);
    public final ClientAttributeInstance entityInteractionRange = new ClientAttributeInstance(3.0D, 0.0D, 64.0D);
    // LocalPlayer#modifyInput reads the synced attribute, including plugin and enchantment modifiers.
    public final ClientAttributeInstance sneakingSpeed = new ClientAttributeInstance(0.3D, 0.0D, 1.0D);
    // The instances behind the attribute-backed scalars: handleRespawn copies bases and modifiers apart.
    private final ClientAttributeInstance scaleAttribute = new ClientAttributeInstance(1.0D, 0.0625D, 16.0D);
    private final ClientAttributeInstance gravityAttribute = new ClientAttributeInstance(0.08D, -1.0D, 1.0D);
    private final ClientAttributeInstance stepHeight = new ClientAttributeInstance(0.6D, 0.0D, 10.0D);
    // playerSpeed leaves out the sprinting modifier, which is modelled apart.
    private final ClientAttributeInstance movementSpeedAttribute =
            new ClientAttributeInstance(0.1F, 0.0D, 1024.0D, Set.of(CompensatedEntities.SPRINTING_SPEED_MODIFIER.id()));
    private final ClientAttributeInstance bouncinessAttribute = new ClientAttributeInstance(0.0D, 0.0D, 1.0D);
    private final ClientAttributeInstance frictionModifierAttribute = new ClientAttributeInstance(1.0D, 0.0D, 2048.0D);
    private final ClientAttributeInstance airDragModifierAttribute = new ClientAttributeInstance(1.0D, 0.0D, 2048.0D);
    private final ClientAttributeInstance jumpStrengthAttribute = new ClientAttributeInstance(0.42F, 0.0D, 32.0D);
    private final ClientAttributeInstance blockBreakSpeedAttribute = new ClientAttributeInstance(1.0D, 0.0D, 1024.0D);
    private final ClientAttributeInstance miningEfficiencyAttribute = new ClientAttributeInstance(0.0D, 0.0D, 1024.0D);
    private final ClientAttributeInstance submergedMiningSpeedAttribute =
            new ClientAttributeInstance(0.2D, 0.0D, 20.0D);
    private final ClientAttributeInstance movementEfficiencyAttribute = new ClientAttributeInstance(0.0D, 0.0D, 1.0D);
    private final ClientAttributeInstance waterMovementEfficiencyAttribute =
            new ClientAttributeInstance(0.0D, 0.0D, 1.0D);

    public PacketEntitySelf(CultPlayer player) {
        this(player, null);
    }

    public PacketEntitySelf(CultPlayer player, PacketEntitySelf old) {
        super(EntityTypeIds.PLAYER, player.entityID);
        this.player = player;
        if (old != null) {
            carryStateFrom(old);
        }
    }

    private void carryStateFrom(PacketEntitySelf old) {
        this.opLevel = old.getOpLevel();
        this.scaleAttribute.copyFrom(old.scaleAttribute);
        this.gravityAttribute.copyFrom(old.gravityAttribute);
        this.stepHeight.copyFrom(old.stepHeight);
        this.movementSpeedAttribute.copyFrom(old.movementSpeedAttribute);
        this.bouncinessAttribute.copyFrom(old.bouncinessAttribute);
        this.frictionModifierAttribute.copyFrom(old.frictionModifierAttribute);
        this.airDragModifierAttribute.copyFrom(old.airDragModifierAttribute);
        this.jumpStrengthAttribute.copyFrom(old.jumpStrengthAttribute);
        this.blockBreakSpeedAttribute.copyFrom(old.blockBreakSpeedAttribute);
        this.miningEfficiencyAttribute.copyFrom(old.miningEfficiencyAttribute);
        this.submergedMiningSpeedAttribute.copyFrom(old.submergedMiningSpeedAttribute);
        this.movementEfficiencyAttribute.copyFrom(old.movementEfficiencyAttribute);
        this.waterMovementEfficiencyAttribute.copyFrom(old.waterMovementEfficiencyAttribute);
        refreshAttributeValues();
        this.blockInteractionRange.copyFrom(old.blockInteractionRange);
        this.entityInteractionRange.copyFrom(old.entityInteractionRange);
        this.sneakingSpeed.copyFrom(old.sneakingSpeed);
    }

    /** ClientPacketListener#handleUpdateAttributes for the attributes behind this player's cached scalars. */
    public void onAttributeSnapshot(AttributeSnapshot snapshot) {
        ClientAttributeInstance instance = switch (snapshot.attribute()) {
            case "minecraft:scale" -> scaleAttribute;
            case "minecraft:gravity" -> gravityAttribute;
            case "minecraft:step_height" -> stepHeight;
            case "minecraft:movement_speed" -> movementSpeedAttribute;
            case "minecraft:bounciness" -> bouncinessAttribute;
            case "minecraft:friction_modifier" -> frictionModifierAttribute;
            case "minecraft:air_drag_modifier" -> airDragModifierAttribute;
            case "minecraft:jump_strength" -> jumpStrengthAttribute;
            case "minecraft:block_break_speed" -> blockBreakSpeedAttribute;
            case "minecraft:mining_efficiency" -> miningEfficiencyAttribute;
            case "minecraft:submerged_mining_speed" -> submergedMiningSpeedAttribute;
            case "minecraft:movement_efficiency" -> movementEfficiencyAttribute;
            case "minecraft:water_movement_efficiency" -> waterMovementEfficiencyAttribute;
            default -> null;
        };
        if (instance == null) return;
        instance.onServerSnapshot(instance == movementSpeedAttribute ? withoutClientFrost(snapshot) : snapshot);
        refreshAttributeValues();
    }

    /**
     * LivingEntity#aiStep runs removeFrost and tryAddFrost only on the server since 1.21.5, so the client moves with
     * the server's minecraft:powder_snow modifier. Older clients replace it every tick with their own.
     */
    private AttributeSnapshot withoutClientFrost(AttributeSnapshot snapshot) {
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_5)) return snapshot;
        List<AttributeModifier> kept = new ArrayList<>(snapshot.modifiers());
        kept.removeIf(modifier -> modifier.id().equals(CompensatedEntities.SNOW_MODIFIER_ID));
        return new AttributeSnapshot(snapshot.attribute(), snapshot.base(), kept);
    }

    /** movement_speed with {@code sprinting} as its minecraft:sprinting modifier; {@link #playerSpeed} has none. */
    public double playerSpeedWith(AttributeModifier sprinting) {
        return movementSpeedAttribute.valueWith(sprinting);
    }

    private void refreshAttributeValues() {
        scale = (float) scaleAttribute.value();
        gravity = gravityAttribute.value();
        stepHeightAttribute = stepHeight.value();
        playerSpeed = movementSpeedAttribute.value();
        bounciness = bouncinessAttribute.value();
        frictionModifier = frictionModifierAttribute.value();
        airDragModifier = airDragModifierAttribute.value();
        jumpStrength = jumpStrengthAttribute.value();
        blockBreakSpeed = blockBreakSpeedAttribute.value();
        miningEfficiency = miningEfficiencyAttribute.value();
        submergedMiningSpeed = submergedMiningSpeedAttribute.value();
        movementEfficiency = movementEfficiencyAttribute.value();
        waterMovementEfficiency = waterMovementEfficiencyAttribute.value();
    }

    public boolean inVehicle() {
        return getRiding() != null;
    }

    @Override
    public void onPositionPath(
            EntityPositionPath path,
            boolean hasPosition,
            @Nullable Float yaw,
            @Nullable Float pitch,
            CultPlayer player,
            boolean bundled) {
        // please don't send this type of packet to the player self, it's not possible in vanilla
    }

    @Override
    public void addPotionEffect(MovementEffect effect, int amplifier) {
        // Blindness does not cancel sprinting that began before the effect.
        if (effect == MovementEffect.BLINDNESS
                && (potionsMap == null || !potionsMap.containsKey(MovementEffect.BLINDNESS))) {
            SprintD check = player.checkManager.getCheck(SprintD.class);
            if (check != null) {
                check.startedSprintingBeforeBlind = player.isSprinting;
            }
        }
        super.addPotionEffect(effect, amplifier);
    }

    public double getBlockInteractionRange() {
        return blockInteractionRange.value();
    }

    public double getEntityInteractionRange() {
        return entityInteractionRange.value();
    }

    /**
     * MultiPlayerGameMode#setLocalMode leaves Java range attributes untouched. Bedrock keeps the
     * existing game-mode defaults, which attribute packets may then replace.
     */
    public void onGameModeChange(boolean creative) {
        if (!player.isBedrockMovement()) return;
        blockInteractionRange.replace(creative ? 5.0D : 4.5D, List.of());
        entityInteractionRange.replace(creative ? 5.0D : 3.0D, List.of());
    }

    /** ClientPacketListener#handleRespawn: KEEP_ATTRIBUTE_MODIFIERS copies modifiers, otherwise only bases. */
    public void onRespawn(boolean keepAttributeModifiers, boolean creative) {
        if (player.isBedrockMovement()) {
            onGameModeChange(creative);
        } else if (!keepAttributeModifiers) {
            blockInteractionRange.clearModifiers();
            entityInteractionRange.clearModifiers();
            sneakingSpeed.clearModifiers();
            float keptScale = scale;
            scaleAttribute.clearModifiers();
            gravityAttribute.clearModifiers();
            stepHeight.clearModifiers();
            movementSpeedAttribute.clearModifiers();
            bouncinessAttribute.clearModifiers();
            frictionModifierAttribute.clearModifiers();
            airDragModifierAttribute.clearModifiers();
            jumpStrengthAttribute.clearModifiers();
            blockBreakSpeedAttribute.clearModifiers();
            miningEfficiencyAttribute.clearModifiers();
            submergedMiningSpeedAttribute.clearModifiers();
            movementEfficiencyAttribute.clearModifiers();
            waterMovementEfficiencyAttribute.clearModifiers();
            refreshAttributeValues();
            if (scale != keptScale) player.refreshPlayerPose();
        }
    }

    @Override
    public void onFirstTransaction(
            boolean relative, boolean hasPos, double relX, double relY, double relZ, CultPlayer player) {
        // Player ignores this
    }

    @Override
    public void onFirstTransaction(
            boolean relative,
            boolean hasPos,
            double relX,
            double relY,
            double relZ,
            @Nullable Float yaw,
            @Nullable Float pitch,
            CultPlayer player) {
        // Player ignores this
    }

    @Override
    public void onBundleTransaction(
            boolean relative, boolean hasPos, double relX, double relY, double relZ, CultPlayer player) {
        // Player ignores this
    }

    @Override
    public void onBundleTransaction(
            boolean relative,
            boolean hasPos,
            double relX,
            double relY,
            double relZ,
            @Nullable Float yaw,
            @Nullable Float pitch,
            CultPlayer player) {
        // Player ignores this
    }

    @Override
    public void onBundleFirstTransaction(
            boolean relative, boolean hasPos, double relX, double relY, double relZ, CultPlayer player) {
        // Player ignores this
    }

    @Override
    public void onBundleFirstTransaction(
            boolean relative,
            boolean hasPos,
            double relX,
            double relY,
            double relZ,
            @Nullable Float yaw,
            @Nullable Float pitch,
            CultPlayer player) {
        // Player ignores this
    }

    @Override
    public void onBundledPositionSyncTransaction(double x, double y, double z, CultPlayer player) {
        // Player ignores this
    }

    @Override
    public void onBundledPositionSyncTransaction(
            double x, double y, double z, @Nullable Float yaw, @Nullable Float pitch, CultPlayer player) {
        // Player ignores this
    }

    @Override
    public void onSecondTransaction() {
        // Player ignores this
    }

    @Override
    public SimpleCollisionBox getPossibleCollisionBoxes() {
        return player.boundingBox.copy(); // Copy to retain behavior of PacketEntity
    }

    @Override
    public SimpleCollisionBox getPossibleCollisionBoxes(SimpleCollisionBox out, SimpleCollisionBox scratch) {
        return out.set(player.boundingBox);
    }

    @Override
    public SimpleCollisionBox getPossibleMovementCollisionBoxes() {
        return player.boundingBox.copy();
    }

    @Override
    public SimpleCollisionBox getPossibleMovementCollisionBoxes(SimpleCollisionBox out, SimpleCollisionBox scratch) {
        return out.set(player.boundingBox);
    }

    @Override
    public List<SimpleCollisionBox> getPossibleMovementCollisionBoxCandidates() {
        return Collections.singletonList(player.boundingBox.copy());
    }
}
