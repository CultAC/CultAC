package ac.grim.grimac.utils.data.packetentity;

import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.world.entity.EntityType;

public class PacketEntitySizeable extends PacketEntity {
    public int size = 4; // To support entity metadata being sent after spawn, assume max size of vanilla slime

    public PacketEntitySizeable(GrimPlayer player, int entityId, EntityType type, double x, double y, double z) { super(player, entityId, type, x, y, z); }
}
