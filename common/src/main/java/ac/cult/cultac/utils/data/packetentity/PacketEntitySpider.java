package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;

/** Spider.onClimbable reads its synchronized flag instead of querying blocks. */
public final class PacketEntitySpider extends PacketEntity {
    public boolean actionClimbing;

    public PacketEntitySpider(CultPlayer player, int id, int type, Vec3 position) {
        super(player, id, type, position.x, position.y, position.z);
    }
}
