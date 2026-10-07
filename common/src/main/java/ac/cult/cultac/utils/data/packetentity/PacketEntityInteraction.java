package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.data.Box;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;

/** Interaction's received width/height determine picking geometry, independent of movement. */
public final class PacketEntityInteraction extends PacketEntity {
    public float actionWidth = 1.0F, actionHeight = 1.0F;

    public PacketEntityInteraction(CultPlayer player, int id, int type, Vec3 position) {
        super(player, id, type, position.x, position.y, position.z);
    }

    public Box actionBounds(SimpleCollisionBox position) {
        // Native AABB comparisons with a NaN bound never intersect the entity search area.
        if (Float.isNaN(actionWidth) || Float.isNaN(actionHeight)) return null;
        double x = position.minX + .5 * (position.maxX - position.minX);
        double z = position.minZ + .5 * (position.maxZ - position.minZ), radius = actionWidth / 2.0F;
        return new Box(
                Math.min(x - radius, x + radius),
                Math.min(position.minY, position.minY + actionHeight),
                Math.min(z - radius, z + radius),
                Math.max(x - radius, x + radius),
                Math.max(position.minY, position.minY + actionHeight),
                Math.max(z - radius, z + radius));
    }
}
