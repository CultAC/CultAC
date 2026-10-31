package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.player.CultPlayer;

public class PacketEntityRideable extends PacketEntity {

    public boolean hasSaddle = false;
    public final RideableBoostState boost = new RideableBoostState();

    /** Pig#getRiddenSpeed and Strider#getRiddenSpeed read movementSpeed's value as a double. */
    public double movementSpeedAttribute = 0.1D;

    public final ClientMovementSpeed movementSpeed;
    public float flyingSpeedAttribute = 0.1f;

    public PacketEntityRideable(CultPlayer player, int entityId, int type, double x, double y, double z) {
        super(player, entityId, type, x, y, z);
        if (type == EntityTypeIds.PIG) {
            // MCP-Reborn Pig#createAttributes defines MOVEMENT_SPEED as 0.25.
            movementSpeedAttribute = 0.25f;
        } else if (type == EntityTypeIds.STRIDER) {
            // MCP-Reborn Strider#createAttributes defines MOVEMENT_SPEED as 0.175.
            movementSpeedAttribute = player.isBedrockMovement() ? 0.175D : (double) 0.175F;
        }
        movementSpeed = new ClientMovementSpeed(movementSpeedAttribute);
        flyingSpeedAttribute = (float) movementSpeedAttribute;
    }

    @Override
    public ClientMovementSpeed riddenMovementSpeed() {
        return movementSpeed;
    }

    @Override
    public void refreshRiddenMovementSpeed() {
        movementSpeedAttribute = movementSpeed.value();
    }
}
