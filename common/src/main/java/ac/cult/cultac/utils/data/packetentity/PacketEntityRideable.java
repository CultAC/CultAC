package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import net.minecraft.world.entity.EntityType;

public class PacketEntityRideable extends PacketEntity {

    public boolean hasSaddle = false;
    public final RideableBoostState boost = new RideableBoostState();

    public double movementSpeedAttribute = 0.1D;
    public float flyingSpeedAttribute = 0.1f;

    public PacketEntityRideable(CultPlayer player, int entityId, EntityType type, double x, double y, double z) { super(player, entityId, type, x, y, z);
        if (type == EntityTypesCompat.PIG) {
            // MCP-Reborn Pig#createAttributes defines MOVEMENT_SPEED as 0.25.
            movementSpeedAttribute = 0.25f;
        } else if (type == EntityTypesCompat.STRIDER) {
            // MCP-Reborn Strider#createAttributes defines MOVEMENT_SPEED as 0.175.
            movementSpeedAttribute = 0.175D;
        }
        flyingSpeedAttribute = (float) movementSpeedAttribute;
    }
}
