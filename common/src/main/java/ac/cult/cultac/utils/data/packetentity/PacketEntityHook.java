package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;

public class PacketEntityHook extends PacketEntity {
    public int owner;
    public int attached = -1;

    public PacketEntityHook(CultPlayer player, int entityId, int type, double x, double y, double z, int owner) {
        super(player, entityId, type, x, y, z);
        this.owner = owner;
    }
}
