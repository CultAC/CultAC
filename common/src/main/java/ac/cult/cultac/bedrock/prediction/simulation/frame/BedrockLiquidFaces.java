package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.JavaStateProperties;
import java.util.Map;

/**
 * Whether liquid passes through a block's horizontal face. A liquid neighbour shapes a flow vector only when both the
 * neighbour's face and the flowing block's face between them let liquid through.
 *
 * <p>Cult closes faces only on stairs and shelves. A stair's raised back is closed and its open front passes; a side
 * closes when the stair in front of it, of any kind, faces that side, turning this stair into an inner corner there. A
 * shelf facing north closes its south face and one facing east its north face; shelves facing south or west close no
 * side. Every other block passes liquid on all four sides.
 */
final class BedrockLiquidFaces {
    private static final int NORTH = 2;
    private static final int SOUTH = 3;
    private static final int WEST = 4;
    private static final int EAST = 5;

    private BedrockLiquidFaces() {}

    /** Whether liquid passes the face of the block at {@code position} that points along {@code (dx, dz)}. */
    static boolean passes(Map<BlockPosition, PlacedBlockCollision> byPosition, BlockPosition position, int dx, int dz) {
        PlacedBlockCollision block = byPosition.get(position);
        if (block == null) {
            return true;
        }
        JavaStateProperties properties = block.javaStateProperties();
        if (properties.shelfFacing() != JavaStateProperties.NO_FACING) {
            return !closesShelfFace(properties.shelfFacing(), dx, dz);
        }
        int facing = properties.stairFacing();
        if (facing == JavaStateProperties.NO_FACING) {
            return true;
        }
        int facingX = stepX(facing);
        int facingZ = stepZ(facing);
        if (dx == facingX && dz == facingZ) {
            return false;
        }
        if (dx == -facingX && dz == -facingZ) {
            return true;
        }
        int frontFacing = stairFacing(byPosition.get(
                new BlockPosition(position.x() - facingX, position.y(), position.z() - facingZ)));
        return frontFacing == JavaStateProperties.NO_FACING || stepX(frontFacing) != dx || stepZ(frontFacing) != dz;
    }

    private static boolean closesShelfFace(int facing, int dx, int dz) {
        return switch (facing) {
            case NORTH -> dx == stepX(SOUTH) && dz == stepZ(SOUTH);
            case EAST -> dx == stepX(NORTH) && dz == stepZ(NORTH);
            default -> false;
        };
    }

    private static int stairFacing(PlacedBlockCollision block) {
        return block == null ? JavaStateProperties.NO_FACING : block.javaStateProperties().stairFacing();
    }

    private static int stepX(int facing) {
        return switch (facing) {
            case WEST -> -1;
            case EAST -> 1;
            default -> 0;
        };
    }

    private static int stepZ(int facing) {
        return switch (facing) {
            case NORTH -> -1;
            case SOUTH -> 1;
            default -> 0;
        };
    }
}
