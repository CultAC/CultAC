package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;

public class PacketEntitySizeable extends PacketEntity {
    public int size = 4; // To support entity metadata being sent after spawn, assume max size of vanilla slime

    public PacketEntitySizeable(CultPlayer player, int entityId, int type, double x, double y, double z) {
        super(player, entityId, type, x, y, z);
    }
}
