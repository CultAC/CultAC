package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;

/** Received CAN_MOVE only affects the client action's pushable predicate. */
public final class PacketEntityCreaking extends PacketEntity {
    public boolean actionCanMove = true;

    public PacketEntityCreaking(CultPlayer player, int id, int type, Vec3 position) {
        super(player, id, type, position.x, position.y, position.z);
    }
}
