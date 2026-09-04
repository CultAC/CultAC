package ac.grim.grimac.utils.data.packetentity;

import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.world.entity.EntityType;

public class PacketEntityCamel extends PacketEntityHorse {

    public boolean dashing = false;
    public int dashCooldown;
    public boolean lastPredictedInLiquid;

    public PacketEntityCamel(GrimPlayer player, int entityId, EntityType type, double x, double y, double z, float xRot) { super(player, entityId, type, x, y, z, xRot);
        applyCamelAttributeDefaults();
    }

    private void applyCamelAttributeDefaults() {
        // Vanilla camel attribute defaults.
        this.jumpStrength = 0.42F;
        this.movementSpeedAttribute = 0.09f;
        this.stepHeightAttribute = 1.5D;
    }

    public void tickDashCooldown() {
        if (dashing && dashCooldown < 50 && (onGround || lastPredictedInLiquid || getRiding() != null)) {
            dashing = false;
        }
        if (dashCooldown > 0) {
            dashCooldown--;
        }
    }

    public void setDashingFromMetadata(boolean dashing) {
        if (this.dashing != dashing && dashCooldown == 0) {
            dashCooldown = 55;
        }
        this.dashing = dashing;
    }



}
