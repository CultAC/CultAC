package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.HangingBounds;
import ac.cult.blocksim.entity.PaintingSize;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.util.List;

/** Only the received geometry and comparator data needed by the entity's box and block actions. */
public final class PacketEntityHanging extends PacketEntity {
    public final String typeKey;
    public Direction facing;
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

    public void updateActionMetadata(List<EntityMetadata.Entry> entries, ProtocolVersion version) {
        int index = WatchableIndexUtil.hangingData(version);
        if (version.atLeast(ProtocolVersion.V1_21_6)
                && WatchableIndexUtil.getIndex(entries, index - 1) instanceof EntityMetadata.Entry turned
                && turned.value() instanceof ac.cult.cultac.protocol.value.Direction direction) {
            facing = Direction.values()[direction.get3DDataValue()];
        }
        var content = WatchableIndexUtil.getIndex(entries, index);
        if (painting != null) {
            if (content != null && content.value() instanceof PaintingSize size) painting = size;
        } else {
            if (content != null && content.value() instanceof SimItemStack stack) {
                hasItem = !stack.isEmpty();
                hasMap = stack.components().has("minecraft:map_id");
            }
            var turn = WatchableIndexUtil.getIndex(entries, index + 1);
            if (turn != null && turn.value() instanceof Integer value) rotation = value;
        }
    }

    public int analogOutput() {
        return hasItem ? rotation % 8 + 1 : 0;
    }

    public Box actionBounds() {
        return actionBounds(desyncClientPos.x, desyncClientPos.y, desyncClientPos.z);
    }

    /**
     * HangingEntity#recalculateBoundingBox: the box calculateBoundingBox gives at the block containing the position,
     * grown as far as {@code box} spans beyond one position box.
     */
    @Override
    public SimpleCollisionBox toBoundingBox(CultPlayer player, SimpleCollisionBox box) {
        if (player.isBedrockMovement()) return box;
        double width = BoundingBoxSize.getWidth(player, this), height = BoundingBoxSize.getHeight(player, this);
        double growX = (box.maxX - box.minX - width) / 2, growY = (box.maxY - box.minY - height) / 2;
        double growZ = (box.maxZ - box.minZ - width) / 2;
        Box bounds =
                actionBounds((box.minX + box.maxX) / 2, (box.minY + box.maxY - height) / 2, (box.minZ + box.maxZ) / 2);
        return box.set(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())
                .expand(growX, growY, growZ);
    }

    private Box actionBounds(double x, double y, double z) {
        var anchor = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        return painting == null
                ? HangingBounds.frame(anchor, facing, hasMap)
                : HangingBounds.painting(anchor, facing, painting);
    }
}
