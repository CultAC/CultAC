package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;

/**
 * The block the head point is in, apart from any water that block holds. A waterlogged block, kelp or seagrass is
 * {@link #OTHER} there, though the head is in its water.
 */
public enum BedrockHeadBlock {
    AIR,
    /** Water, lava or a bubble column, whatever its surface height. */
    LIQUID,
    OTHER;

    static BedrockHeadBlock at(BlockCollisionWorld blockWorld, double x, double y, double z) {
        return blockWorld
                .blockAt(new BlockPosition((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)))
                .map(block -> BedrockLiquidGeometry.liquidKind(block) == BedrockLiquidKind.NONE ? OTHER : LIQUID)
                .orElse(AIR);
    }
}
