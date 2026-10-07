package ac.cult.cultac.utils.anticheat.update;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.collisions.HitboxData;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

public final class BlockBreak {
    public final BlockPos position;
    public final Direction face;
    public final int faceId;
    public final PlayerAction action;
    public final int sequence;
    public final int block;
    private final CultPlayer player;

    @Getter
    private boolean cancelled;

    public BlockBreak(
            CultPlayer player,
            BlockPos position,
            Direction face,
            int faceId,
            PlayerAction action,
            int sequence,
            int block) {
        this.player = player;
        this.position = position;
        this.face = face;
        this.faceId = faceId;
        this.action = action;
        this.sequence = sequence;
        this.block = block;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public SimpleCollisionBox getCombinedBox() {
        CollisionBox placedOn =
                HitboxData.getBlockHitbox(player, block, position.getX(), position.getY(), position.getZ());

        List<SimpleCollisionBox> boxes = new ArrayList<>();
        placedOn.downCast(boxes);

        SimpleCollisionBox combined = new SimpleCollisionBox(position.getX(), position.getY(), position.getZ());
        for (SimpleCollisionBox box : boxes) {
            double minX = Math.max(box.minX, combined.minX);
            double minY = Math.max(box.minY, combined.minY);
            double minZ = Math.max(box.minZ, combined.minZ);
            double maxX = Math.min(box.maxX, combined.maxX);
            double maxY = Math.min(box.maxY, combined.maxY);
            double maxZ = Math.min(box.maxZ, combined.maxZ);
            combined = new SimpleCollisionBox(minX, minY, minZ, maxX, maxY, maxZ);
        }

        return combined;
    }
}
