package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.util.List;

/** ChestBlock uses the synced sitting-pose bit, rather than the server's orderedToSit field. */
public final class PacketEntityCat extends PacketEntity {
    public boolean sitting;

    public PacketEntityCat(CultPlayer player, int id, int type, Vec3 position) {
        super(player, id, type, position.x, position.y, position.z);
    }

    public void updateActionMetadata(List<EntityMetadata.Entry> entries, ProtocolVersion version) {
        var flags = WatchableIndexUtil.getIndex(entries, WatchableIndexUtil.tameableFlags(version));
        if (flags != null && flags.value() instanceof Byte value) sitting = (value & 1) != 0;
    }
}
