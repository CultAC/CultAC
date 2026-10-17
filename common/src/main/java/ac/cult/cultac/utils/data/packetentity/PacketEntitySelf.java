package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.sprint.SprintD;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientAttributeInstance;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

public class PacketEntitySelf extends PacketEntity {
    public double playerSpeed = 0.1f;
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
    @Setter
    double health;

    // Player#blockInteractionRange and #entityInteractionRange read these attributes. Only server
    // attribute packets change them: ServerPlayer#updatePlayerAttributes owns the creative modifiers.
    public final ClientAttributeInstance blockInteractionRange = new ClientAttributeInstance(4.5D, 0.0D, 64.0D);
    public final ClientAttributeInstance entityInteractionRange = new ClientAttributeInstance(3.0D, 0.0D, 64.0D);
    // LocalPlayer#modifyInput reads the synced attribute, including plugin and enchantment modifiers.
    public final ClientAttributeInstance sneakingSpeed = new ClientAttributeInstance(0.3D, 0.0D, 1.0D);

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
        this.scale = old.scale;
        this.gravity = old.gravity;
        this.stepHeightAttribute = old.stepHeightAttribute;
        this.playerSpeed = old.playerSpeed;
        this.bounciness = old.bounciness;
        this.frictionModifier = old.frictionModifier;
        this.airDragModifier = old.airDragModifier;
        this.blockBreakSpeed = old.blockBreakSpeed;
        this.miningEfficiency = old.miningEfficiency;
        this.submergedMiningSpeed = old.submergedMiningSpeed;
        this.blockInteractionRange.copyFrom(old.blockInteractionRange);
        this.entityInteractionRange.copyFrom(old.entityInteractionRange);
        this.sneakingSpeed.copyFrom(old.sneakingSpeed);
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
