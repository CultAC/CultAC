package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.player.CultPlayer;

public class PacketEntitySizeable extends PacketEntity {
    public int size;

    public PacketEntitySizeable(CultPlayer player, int entityId, int type, double x, double y, double z) {
        super(player, entityId, type, x, y, z);
        // ID_SIZE defaults to 0 (Phantom) or 1 (AbstractCubeMob); Bedrock sizing assumes 4 until size data arrives.
        // The spawn box is built before this is set.
        if (player.isBedrockMovement()) size = 4;
        else size = type == EntityTypeIds.PHANTOM ? 0 : 1;
        refreshDimensions(player);
    }
}
