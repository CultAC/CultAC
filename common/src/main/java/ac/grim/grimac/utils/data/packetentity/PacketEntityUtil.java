package ac.grim.grimac.utils.data.packetentity;

import ac.grim.grimac.utils.nmsutil.EntityTypeUtil;
import ac.grim.grimac.utils.nmsutil.EntityTypesCompat;
import net.minecraft.world.entity.EntityType;

public class PacketEntityUtil {

    public static boolean isRideable(EntityType type) {
       return EntityTypeUtil.isBoat(type)
                || EntityTypeUtil.isHorseFamily(type)
                || type == EntityTypesCompat.PIG
                || type == EntityTypesCompat.STRIDER
                || EntityTypeUtil.isHappyGhast(type);
    }

}
