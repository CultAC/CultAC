package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;
import net.minecraft.world.entity.EntityType;

public class PacketEntityHook extends PacketEntity{
    public int owner;
    public int attached = -1;

    public PacketEntityHook(CultPlayer player, int entityId, EntityType type, double x, double y, double z, int owner) { super(player, entityId, type, x, y, z);
        this.owner = owner;
    }
}
