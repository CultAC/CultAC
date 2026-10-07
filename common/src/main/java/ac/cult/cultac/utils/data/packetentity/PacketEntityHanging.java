package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.HangingBounds;
import ac.cult.blocksim.entity.PaintingSize;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.util.List;

/** Only the received geometry and comparator data needed by block actions. */
public final class PacketEntityHanging extends PacketEntity {
    public final String typeKey;
    public final Direction facing;
    private PaintingSize painting;
    private boolean hasItem, hasMap;
    private int rotation;

    public PacketEntityHanging(CultPlayer player, int id, int type, Vec3 position, int data, String typeKey) {
        super(player, id, type, position.x, position.y, position.z);
        this.typeKey = typeKey;
        facing = Direction.values()[Math.abs(data % 6)];
        if (typeKey.equals("minecraft:painting")) {
            painting = player.getWorldRegistries().initialPainting();
        }
    }

    public void updateActionMetadata(List<EntityMetadata.Entry> entries) {
        var content = WatchableIndexUtil.getIndex(entries, 8);
        if (painting != null) {
            if (content != null && content.value() instanceof PaintingSize size) painting = size;
        } else {
            if (content != null && content.value() instanceof SimItemStack stack) {
                hasItem = !stack.isEmpty();
                hasMap = stack.components().has("minecraft:map_id");
            }
            var turn = WatchableIndexUtil.getIndex(entries, 9);
            if (turn != null && turn.value() instanceof Integer value) rotation = value;
        }
    }

    public int analogOutput() {
        return hasItem ? rotation % 8 + 1 : 0;
    }

    public Box actionBounds() {
        var anchor = new BlockPos((int) Math.floor(desyncClientPos.x), (int) Math.floor(desyncClientPos.y), (int)
                Math.floor(desyncClientPos.z));
        return painting == null
                ? HangingBounds.frame(anchor, facing, hasMap)
                : HangingBounds.painting(anchor, facing, painting);
    }
}
