package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.BubbleColumnLayer;
import ac.cult.cultac.bedrock.prediction.world.BubbleColumnLayerType;
import ac.cult.cultac.bedrock.prediction.world.BubbleColumnState;
import ac.cult.cultac.bedrock.prediction.world.FluidCurrentState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class BedrockBlockFluidResolver {
    private static final double LIQUID_CURRENT_PUSH_PER_TICK = 0.014D;
    private static final double LAVA_CURRENT_PUSH_PER_TICK = 0.0035000001080334187D;

    private BedrockBlockFluidResolver() {
    }

    static BedrockFluidResolution resolve(BlockCollisionWorld blockWorld, WorldCollisionBox actorBox) {
        if (blockWorld.isEmpty()) {
            return BedrockFluidResolution.NONE;
        }
        Map<BlockPosition, PlacedBlockCollision> byPosition = blockWorld.blocksByPosition();
        Map<BlockPosition, PlacedBlockCollision> liquidByPosition = blockWorld.liquidBlocksByPosition();

        WorldCollisionBox waterBox = BedrockLiquidGeometry.liquidActorBox(actorBox, BedrockLiquidKind.WATER);
        WorldCollisionBox lavaBox = BedrockLiquidGeometry.liquidActorBox(actorBox, BedrockLiquidKind.LAVA);
        boolean waterContact = false;
        boolean lavaContact = false;
        boolean upwardBubbleContact = false;
        boolean downwardBubbleContact = false;
        double waterFlowX = 0.0D;
        double waterFlowY = 0.0D;
        double waterFlowZ = 0.0D;
        double lavaFlowX = 0.0D;
        double lavaFlowY = 0.0D;
        double lavaFlowZ = 0.0D;
        List<BubbleColumnLayer> bubbleLayers = new ArrayList<>();
        Set<BlockPosition> bubbleLayerPositions = new HashSet<>();

        int minX = (int) Math.floor(actorBox.minX());
        int maxX = (int) Math.ceil(actorBox.maxX()) - 1;
        int minY = (int) Math.floor(actorBox.minY());
        int maxY = (int) Math.ceil(actorBox.maxY()) - 1;
        int minZ = (int) Math.floor(actorBox.minZ());
        int maxZ = (int) Math.ceil(actorBox.maxZ()) - 1;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockPosition position = new BlockPosition(x, y, z);
                    PlacedBlockCollision block = byPosition.get(position);
                    if (block != null) {
                        // Bubble columns have both a block entry and a water-fluid entry.
                        // The block owns drag direction; the fluid entry owns water flow.
                        Optional<BedrockBubbleColumnFluidContact> bubbleContact =
                                BedrockBubbleColumnFluidContact.from(block, actorBox, byPosition);
                        if (bubbleContact.isPresent() && bubbleLayerPositions.add(position)) {
                            BedrockBubbleColumnFluidContact contact = bubbleContact.get();
                            if (contact.layer().type() == BubbleColumnLayerType.INSIDE) {
                                waterContact = true;
                            }
                            if (contact.dragDown()) {
                                downwardBubbleContact = true;
                            } else {
                                upwardBubbleContact = true;
                            }
                            bubbleLayers.add(contact.layer());
                        }
                    }
                    PlacedBlockCollision liquidBlock = liquidByPosition.get(position);
                    if (liquidBlock != null) {
                        if (BedrockLiquidGeometry.isWaterBlock(liquidBlock)
                                && BedrockLiquidGeometry.intersectsBlock(position, waterBox)) {
                            waterContact = true;
                            BedrockLiquidFlowVector flow = BedrockLiquidFlowResolver.flowVector(
                                    liquidBlock, BedrockLiquidKind.WATER, byPosition);
                            waterFlowX += flow.x();
                            waterFlowY += flow.y();
                            waterFlowZ += flow.z();
                        } else if (BedrockLiquidGeometry.isLavaBlock(liquidBlock)
                                && BedrockLiquidGeometry.intersectsBlock(position, lavaBox)) {
                            lavaContact = true;
                            BedrockLiquidFlowVector flow = BedrockLiquidFlowResolver.flowVector(
                                    liquidBlock, BedrockLiquidKind.LAVA, byPosition);
                            lavaFlowX += flow.x();
                            lavaFlowY += flow.y();
                            lavaFlowZ += flow.z();
                        }
                    }
                    if (block != null
                            && block != liquidBlock
                            && isWaterloggedBlock(block)
                            && BedrockLiquidGeometry.intersectsBlock(position, waterBox)) {
                        waterContact = true;
                    }
                }
            }
        }

        if (!waterContact && !lavaContact && !upwardBubbleContact && !downwardBubbleContact) {
            return BedrockFluidResolution.NONE;
        }

        Medium liquidMovementMedium = liquidMovementMedium(waterContact, lavaContact);
        double currentX = liquidMovementMedium == Medium.LAVA ? lavaFlowX : waterFlowX;
        double currentY = liquidMovementMedium == Medium.LAVA ? lavaFlowY : waterFlowY;
        double currentZ = liquidMovementMedium == Medium.LAVA ? lavaFlowZ : waterFlowZ;
        boolean currentPositiveX = currentX > 0.0D;
        boolean currentNegativeX = currentX < 0.0D;
        boolean currentPositiveZ = currentZ > 0.0D;
        boolean currentNegativeZ = currentZ < 0.0D;
        boolean liquidCurrent = currentPositiveX || currentNegativeX || currentPositiveZ || currentNegativeZ || currentY != 0.0D;
        FluidCurrentState currentState = liquidCurrent
            ? fluidCurrentStateFor(liquidMovementMedium, currentX, currentY, currentZ)
            : FluidCurrentState.NONE;
        bubbleLayers.sort(Comparator.comparingInt(BubbleColumnLayer::blockY));
        FluidState fluidState = new FluidState(
            liquidCurrent,
            currentPositiveX,
            currentNegativeX,
            currentPositiveZ,
            currentNegativeZ,
            upwardBubbleContact,
            downwardBubbleContact,
            currentState,
            bubbleLayers.isEmpty() ? BubbleColumnState.NONE : bubbleColumnState(bubbleLayers, actorBox.maxY() - actorBox.minY()),
            false,
            1.0D
        );
        return new BedrockFluidResolution(
            waterContact ? Medium.WATER : Medium.LAVA,
            fluidState,
            waterContact,
            lavaContact,
            liquidMovementMedium
        );
    }

    private static Medium liquidMovementMedium(boolean waterContact, boolean lavaContact) {
        if (lavaContact) {
            return Medium.LAVA;
        }
        return waterContact ? Medium.WATER : Medium.AIR;
    }

    private static FluidCurrentState fluidCurrentStateFor(
        Medium liquidMovementMedium,
        double directionX,
        double directionY,
        double directionZ
    ) {
        double push = liquidMovementMedium == Medium.LAVA
            ? LAVA_CURRENT_PUSH_PER_TICK
            : LIQUID_CURRENT_PUSH_PER_TICK;
        return new FluidCurrentState(
            push,
            Double.POSITIVE_INFINITY,
            Double.POSITIVE_INFINITY,
            Double.POSITIVE_INFINITY,
            directionX,
            directionY,
            directionZ
        );
    }

    private static BubbleColumnState bubbleColumnState(List<BubbleColumnLayer> bubbleLayers, double playerHeight) {
        int minY = bubbleLayers.stream().mapToInt(BubbleColumnLayer::blockY).min().orElse(0);
        int maxY = bubbleLayers.stream().mapToInt(BubbleColumnLayer::blockY).max().orElse(-1);
        return new BubbleColumnState(
            minY,
            maxY,
            maxY,
            maxY + 1.0D,
            playerHeight,
            bubbleLayers
        );
    }

    private static boolean isWaterloggedBlock(PlacedBlockCollision block) {
        return block.javaStateProperties().waterlogged();
    }

}
