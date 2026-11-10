package ac.cult.cultac.utils.collisions;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.bedrock.player.BedrockPistonBlocks;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.ArrayList;
import java.util.List;

/**
 * The collision of a moving block for a Bedrock player: the shape of the block its piston carries, taken at the cell
 * of the piston's last progress and at the cell of its progress, as the one box around both. A moving block that
 * carries nothing yet is a cube.
 */
public final class BedrockMovingBlockShape {
    private BedrockMovingBlockShape() {}

    public static List<WorldCollisionBox> boxes(CultPlayer player, BlockPosition position) {
        BedrockPistonBlocks.Carried carried = player.bedrockState.pistonBlocks.carried(
                new BlockPos(position.x(), position.y(), position.z()), player.compensatedWorld::getBlockStateIdAt);
        if (carried == null
                || carried.state() == BedrockPistonBlocks.NO_BLOCK
                || BlockIds.is(carried.state(), BlockIds.MOVING_PISTON)) {
            return List.of(new WorldCollisionBox(
                    position.x(), position.y(), position.z(), position.x() + 1.0D, position.y() + 1.0D,
                    position.z() + 1.0D));
        }
        List<SimpleCollisionBox> parts = new ArrayList<>();
        addShape(player, carried.state(), carried.last(), parts);
        if (!carried.current().equals(carried.last())) addShape(player, carried.state(), carried.current(), parts);
        if (parts.isEmpty()) return List.of();
        SimpleCollisionBox first = parts.getFirst();
        double minX = first.minX, minY = first.minY, minZ = first.minZ;
        double maxX = first.maxX, maxY = first.maxY, maxZ = first.maxZ;
        for (SimpleCollisionBox part : parts) {
            minX = Math.min(minX, part.minX);
            minY = Math.min(minY, part.minY);
            minZ = Math.min(minZ, part.minZ);
            maxX = Math.max(maxX, part.maxX);
            maxY = Math.max(maxY, part.maxY);
            maxZ = Math.max(maxZ, part.maxZ);
        }
        return List.of(new WorldCollisionBox(minX, minY, minZ, maxX, maxY, maxZ));
    }

    private static void addShape(CultPlayer player, int state, BlockPos cell, List<SimpleCollisionBox> parts) {
        ClientBlockShapes.movement(player, state, cell.getX(), cell.getY(), cell.getZ())
                .downCast(parts);
    }
}
