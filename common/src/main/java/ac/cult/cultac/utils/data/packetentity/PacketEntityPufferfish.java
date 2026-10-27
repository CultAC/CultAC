package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.util.List;

/** Pufferfish#getDefaultDimensions scales both dimensions by its client-visible puff state. */
public final class PacketEntityPufferfish extends PacketEntity {
    private int puffState;

    public PacketEntityPufferfish(CultPlayer player, int id, int type, Vec3 position) {
        super(player, id, type, position.x, position.y, position.z);
    }

    public float puffScale() {
        return puffState == 0 ? 0.5F : puffState == 1 ? 0.7F : 1.0F;
    }

    public void updateMetadata(CultPlayer player, List<EntityMetadata.Entry> entries) {
        var state = WatchableIndexUtil.getIndex(entries, 17);
        if (state != null && state.value() instanceof Integer value) {
            puffState = value;
            refreshDimensions(player);
        }
    }
}
