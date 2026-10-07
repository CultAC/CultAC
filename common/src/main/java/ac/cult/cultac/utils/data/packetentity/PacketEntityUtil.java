package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;

public class PacketEntityUtil {

    public static boolean isRideable(int type) {
        return EntityTypeUtil.isBoat(type)
                || EntityTypeUtil.isHorseFamily(type)
                || type == EntityTypeIds.PIG
                || type == EntityTypeIds.STRIDER
                || EntityTypeUtil.isHappyGhast(type)
                || EntityTypeUtil.isNautilusFamily(type);
    }
}
