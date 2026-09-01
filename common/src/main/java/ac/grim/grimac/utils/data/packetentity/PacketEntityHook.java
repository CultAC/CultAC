package ac.grim.grimac.utils.data.packetentity;

import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.world.entity.EntityType;

public class PacketEntityHook extends PacketEntity{
    public int owner;
    public int attached = -1;

    public PacketEntityHook(GrimPlayer player, int entityId, EntityType type, double x, double y, double z, int owner) { super(player, entityId, type, x, y, z);
        this.owner = owner;
    }
}
