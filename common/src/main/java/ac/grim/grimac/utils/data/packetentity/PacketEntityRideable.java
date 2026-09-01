package ac.grim.grimac.utils.data.packetentity;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.nmsutil.EntityTypesCompat;
import net.minecraft.world.entity.EntityType;

public class PacketEntityRideable extends PacketEntity {

    public boolean hasSaddle = false;
    public int boostTimeMax = 0;
    public int currentBoostTime = 0;

    public float movementSpeedAttribute = 0.1f;
    public float flyingSpeedAttribute = 0.1f;

    public PacketEntityRideable(GrimPlayer player, int entityId, EntityType type, double x, double y, double z) { super(player, entityId, type, x, y, z);
        if (type == EntityTypesCompat.PIG) {
            // MCP-Reborn Pig#createAttributes defines MOVEMENT_SPEED as 0.25.
            movementSpeedAttribute = 0.25f;
        } else if (type == EntityTypesCompat.STRIDER) {
            // MCP-Reborn Strider#createAttributes defines MOVEMENT_SPEED as 0.175.
            movementSpeedAttribute = 0.175f;
        }
        flyingSpeedAttribute = movementSpeedAttribute;
    }
}
