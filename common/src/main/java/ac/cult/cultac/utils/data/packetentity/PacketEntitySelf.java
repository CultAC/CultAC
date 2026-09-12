package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.checks.impl.sprint.SprintD;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import ac.cult.cultac.network.packet.EntityPositionPath;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

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

    @Getter
    private double blockInteractionRange = 4.5D;

    public PacketEntitySelf(CultPlayer player) {
        this(player, null);
    }

    public PacketEntitySelf(CultPlayer player, PacketEntitySelf old) {
        super(EntityTypesCompat.PLAYER, player.entityID);
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
        this.blockInteractionRange = old.blockInteractionRange;
    }

    public boolean inVehicle() {
        return getRiding() != null;
    }

    @Override
    public void onPositionPath(EntityPositionPath path, boolean hasPosition, @Nullable Float yaw,
                               @Nullable Float pitch, CultPlayer player, boolean bundled) {
        // please don't send this type of packet to the player self, it's not possible in vanilla
    }

    @Override
    public void addPotionEffect(PotionEffectType effect, int amplifier) {
        // Blindness does not cancel sprinting that began before the effect.
        if (effect == PotionEffectType.BLINDNESS && (potionsMap == null || !potionsMap.containsKey(PotionEffectType.BLINDNESS))) {
            SprintD check = player.checkManager.getCheck(SprintD.class);
            if (check != null) {
                check.startedSprintingBeforeBlind = player.isSprinting;
            }
        }
        super.addPotionEffect(effect, amplifier);
    }

    public void setBlockInteractionRange(double blockInteractionRange) {
        this.blockInteractionRange = blockInteractionRange;
    }

    public void setDefaultBlockInteractionRange(boolean creative) {
        this.blockInteractionRange = creative ? 5.0D : 4.5D;
    }

    @Override
    public void onFirstTransaction(boolean relative, boolean hasPos, double relX, double relY, double relZ, CultPlayer player) {
        // Player ignores this
    }

    @Override
    public void onFirstTransaction(boolean relative, boolean hasPos, double relX, double relY, double relZ,
                                   @Nullable Float yaw, @Nullable Float pitch, CultPlayer player) {
        // Player ignores this
    }

    @Override
    public void onBundleTransaction(boolean relative, boolean hasPos, double relX, double relY, double relZ, CultPlayer player) {
        // Player ignores this
    }

    @Override
    public void onBundleTransaction(boolean relative, boolean hasPos, double relX, double relY, double relZ,
                                    @Nullable Float yaw, @Nullable Float pitch, CultPlayer player) {
        // Player ignores this
    }

    @Override
    public void onBundleFirstTransaction(boolean relative, boolean hasPos, double relX, double relY, double relZ, CultPlayer player) {
        // Player ignores this
    }

    @Override
    public void onBundleFirstTransaction(boolean relative, boolean hasPos, double relX, double relY, double relZ,
                                         @Nullable Float yaw, @Nullable Float pitch, CultPlayer player) {
        // Player ignores this
    }

    @Override
    public void onBundledPositionSyncTransaction(double x, double y, double z, CultPlayer player) {
        // Player ignores this
    }

    @Override
    public void onBundledPositionSyncTransaction(double x, double y, double z,
                                                 @Nullable Float yaw, @Nullable Float pitch,
                                                 CultPlayer player) {
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
    public SimpleCollisionBox getPossibleMovementCollisionBoxes() {
        return player.boundingBox.copy();
    }

    @Override
    public List<SimpleCollisionBox> getPossibleMovementCollisionBoxCandidates() {
        return Collections.singletonList(player.boundingBox.copy());
    }
}
