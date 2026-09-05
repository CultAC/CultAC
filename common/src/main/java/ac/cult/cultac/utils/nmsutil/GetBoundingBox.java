package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import net.minecraft.world.entity.EntityDimensions;

public class GetBoundingBox {
    public static SimpleCollisionBox getCollisionBoxForPlayer(CultPlayer player, double centerX, double centerY, double centerZ) {
        if (player.compensatedEntities != null && player.compensatedEntities.getSelf().getRiding() != null) {
            return getPacketEntityBoundingBox(player, centerX, centerY, centerZ, player.compensatedEntities.getSelf().getRiding());
        }

        return getPlayerBoundingBox(player, centerX, centerY, centerZ);
    }

    public static SimpleCollisionBox getPacketEntityBoundingBox(CultPlayer player, double centerX, double minY, double centerZ, PacketEntity entity) {
        float width = BoundingBoxSize.getWidth(player, entity);
        float height = BoundingBoxSize.getHeight(player, entity);

        return getBoundingBoxFromPosAndSize(centerX, minY, centerZ, width, height);
    }

    // Size regular: 0.6 width 1.8 height
    // Size shifting on 1.14+ (19w12a): 0.6 width 1.5 height
    // Size while gliding/swimming: 0.6 width 0.6 height
    // Size while sleeping: 0.2 width 0.2 height
    public static SimpleCollisionBox getPlayerBoundingBox(CultPlayer player, double centerX, double minY, double centerZ) {
        float width = player.pose.width * player.getScale();
        float height = player.getBukkitHeight();

        return getBoundingBoxFromPosAndSize(centerX, minY, centerZ, width, height);
    }

    public static SimpleCollisionBox getBoundingBoxFromPosAndSize(double centerX, double minY, double centerZ, float width, float height) {
        return fromAABB(EntityDimensions.fixed(width, height).makeBoundingBox(centerX, minY, centerZ));
    }

    private static SimpleCollisionBox fromAABB(net.minecraft.world.phys.AABB box) {
        return new SimpleCollisionBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, false);
    }
}
