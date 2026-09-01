package ac.grim.grimac.utils.data.packetentity;

import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.world.entity.EntityType;

public class PacketEntityCamel extends PacketEntityHorse {

    public boolean dashing = false;

    public PacketEntityCamel(GrimPlayer player, int entityId, EntityType type, double x, double y, double z, float xRot) { super(player, entityId, type, x, y, z, xRot);
        applyCamelAttributeDefaults();
    }

    private void applyCamelAttributeDefaults() {
        // Vanilla camel attribute defaults.
        this.jumpStrength = 0.42F;
        this.movementSpeedAttribute = 0.09f;
    }



}
