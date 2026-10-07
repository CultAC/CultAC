package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.player.CultPlayer;

public class PacketEntityRideable extends PacketEntity {

    public boolean hasSaddle = false;
    public final RideableBoostState boost = new RideableBoostState();

    public double movementSpeedAttribute = 0.1D;
    public float flyingSpeedAttribute = 0.1f;

    public PacketEntityRideable(CultPlayer player, int entityId, int type, double x, double y, double z) {
        super(player, entityId, type, x, y, z);
        if (type == EntityTypeIds.PIG) {
            // MCP-Reborn Pig#createAttributes defines MOVEMENT_SPEED as 0.25.
            movementSpeedAttribute = 0.25f;
        } else if (type == EntityTypeIds.STRIDER) {
            // MCP-Reborn Strider#createAttributes defines MOVEMENT_SPEED as 0.175.
            movementSpeedAttribute = 0.175D;
        }
        flyingSpeedAttribute = (float) movementSpeedAttribute;
    }
}
