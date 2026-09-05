package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.world.BubbleColumnLayer;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.Map;
import java.util.Optional;

record BedrockBubbleColumnFluidContact(BubbleColumnLayer layer, boolean dragDown) {
    private static final long CURRENT_STATE_ACTIVE_FROM_TICK = 0L;

    static Optional<BedrockBubbleColumnFluidContact> from(
        PlacedBlockCollision block,
        WorldCollisionBox actorBox,
        Map<BlockPosition, PlacedBlockCollision> byPosition
    ) {
        if (!isBubbleColumn(block)) {
            return Optional.empty();
        }
        if (!BedrockLiquidGeometry.fullBlockBox(block.position()).intersects(actorBox)) {
            return Optional.empty();
        }
        boolean dragDown = dragDown(block);
        return Optional.of(new BedrockBubbleColumnFluidContact(
            bubbleLayer(block.position(), byPosition, dragDown),
            dragDown
        ));
    }

    private static BubbleColumnLayer bubbleLayer(
        BlockPosition position,
        Map<BlockPosition, PlacedBlockCollision> byPosition,
        boolean dragDown
    ) {
        BlockPosition above = new BlockPosition(position.x(), position.y() + 1, position.z());
        PlacedBlockCollision aboveBlock = byPosition.get(above);
        return aboveBlock == null || isAirBlock(aboveBlock)
            ? BubbleColumnLayer.above(position.y(), dragDown, CURRENT_STATE_ACTIVE_FROM_TICK, Long.MAX_VALUE)
            : BubbleColumnLayer.inside(position.y(), dragDown, CURRENT_STATE_ACTIVE_FROM_TICK, Long.MAX_VALUE);
    }

    private static boolean isAirBlock(PlacedBlockCollision block) {
        return "minecraft:air".equals(block.bedrockIdentifier());
    }

    private static boolean isBubbleColumn(PlacedBlockCollision block) {
        return "minecraft:bubble_column".equals(block.bedrockIdentifier());
    }

    private static boolean dragDown(PlacedBlockCollision block) {
        Object dragDown = block.bedrockState().get("drag_down");
        if (dragDown != null) {
            return BedrockBlockStateProperties.bedrockBoolean(dragDown, "drag_down");
        }
        return block.javaStateProperties().drag();
    }
}
