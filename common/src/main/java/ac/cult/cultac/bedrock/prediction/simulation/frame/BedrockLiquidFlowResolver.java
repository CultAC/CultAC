package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.world.BedrockBlockMaterials;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

final class BedrockLiquidFlowResolver {
    private static final double MIN_FLOW_VECTOR_LENGTH = 1.0E-4D;

    private BedrockLiquidFlowResolver() {}

    static BedrockLiquidFlowVector flowVector(
            PlacedBlockCollision block,
            BedrockLiquidKind liquidKind,
            Map<BlockPosition, PlacedBlockCollision> byPosition,
            Map<BlockPosition, PlacedBlockCollision> liquidByPosition) {
        Optional<BedrockLiquidFlowVector> explicitFlow = explicitFlowVector(block);
        if (explicitFlow.isPresent()) {
            return explicitFlow.get();
        }
        OptionalInt depth = BedrockLiquidGeometry.liquidDepth(block);
        if (depth.isEmpty()) {
            return BedrockLiquidFlowVector.NONE;
        }
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        int rawCurrentDepth = depth.getAsInt();
        int currentDepth = BedrockLiquidGeometry.normalizedLiquidDepth(rawCurrentDepth);
        BlockPosition position = block.position();
        BedrockLiquidFlowVector positiveX = horizontalFlowDelta(
                position, 1, 0, currentDepth, liquidKind, byPosition, liquidByPosition);
        BedrockLiquidFlowVector negativeX = horizontalFlowDelta(
                position, -1, 0, currentDepth, liquidKind, byPosition, liquidByPosition);
        BedrockLiquidFlowVector positiveZ = horizontalFlowDelta(
                position, 0, 1, currentDepth, liquidKind, byPosition, liquidByPosition);
        BedrockLiquidFlowVector negativeZ = horizontalFlowDelta(
                position, 0, -1, currentDepth, liquidKind, byPosition, liquidByPosition);
        x += positiveX.x() + negativeX.x() + positiveZ.x() + negativeZ.x();
        z += positiveX.z() + negativeX.z() + positiveZ.z() + negativeZ.z();
        if (rawCurrentDepth > 7 && hasFallingLiquidSide(position, byPosition)) {
            BedrockLiquidFlowVector horizontal = normalizedFlowVector(x, 0.0D, z);
            x = horizontal.x();
            y = horizontal.y() - 6.0D;
            z = horizontal.z();
        }
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length < MIN_FLOW_VECTOR_LENGTH) {
            return BedrockLiquidFlowVector.NONE;
        }
        return new BedrockLiquidFlowVector(x / length, y / length, z / length);
    }

    private static Optional<BedrockLiquidFlowVector> explicitFlowVector(PlacedBlockCollision block) {
        Object flowX = block.bedrockState().get("flow_x");
        Object flowY = block.bedrockState().get("flow_y");
        Object flowZ = block.bedrockState().get("flow_z");
        if (flowX == null && flowY == null && flowZ == null) {
            return Optional.empty();
        }
        double x = BedrockBlockStateProperties.bedrockDoubleOrZero(flowX, "flow_x");
        double y = BedrockBlockStateProperties.bedrockDoubleOrZero(flowY, "flow_y");
        double z = BedrockBlockStateProperties.bedrockDoubleOrZero(flowZ, "flow_z");
        BedrockLiquidFlowVector normalized = normalizedFlowVector(x, y, z);
        return normalized == BedrockLiquidFlowVector.NONE
                ? Optional.of(BedrockLiquidFlowVector.NONE)
                : Optional.of(normalized);
    }

    private static BedrockLiquidFlowVector horizontalFlowDelta(
            BlockPosition position,
            int dx,
            int dz,
            int currentDepth,
            BedrockLiquidKind liquidKind,
            Map<BlockPosition, PlacedBlockCollision> byPosition,
            Map<BlockPosition, PlacedBlockCollision> liquidByPosition) {
        BlockPosition neighborPosition = new BlockPosition(position.x() + dx, position.y(), position.z() + dz);
        OptionalInt neighborDepth = neighborDepth(neighborPosition, liquidKind, liquidByPosition);
        // A liquid neighbour behind a closed face shapes the flow as if it held no liquid.
        if (neighborDepth.isPresent()
                && BedrockLiquidFaces.passes(byPosition, neighborPosition, -dx, -dz)
                && BedrockLiquidFaces.passes(byPosition, position, dx, dz)) {
            int delta = BedrockLiquidGeometry.normalizedLiquidDepth(neighborDepth.getAsInt()) - currentDepth;
            return new BedrockLiquidFlowVector(dx * delta, 0.0D, dz * delta);
        }
        if (blocksMotion(byPosition.get(neighborPosition))) {
            return BedrockLiquidFlowVector.NONE;
        }
        BlockPosition belowNeighborPosition =
                new BlockPosition(neighborPosition.x(), neighborPosition.y() - 1, neighborPosition.z());
        OptionalInt belowDepth = neighborDepth(belowNeighborPosition, liquidKind, liquidByPosition);
        if (belowDepth.isEmpty()) {
            return BedrockLiquidFlowVector.NONE;
        }
        int delta = BedrockLiquidGeometry.normalizedLiquidDepth(belowDepth.getAsInt()) + 8 - currentDepth;
        return new BedrockLiquidFlowVector(dx * delta, 0.0D, dz * delta);
    }

    private static boolean hasFallingLiquidSide(
            BlockPosition position, Map<BlockPosition, PlacedBlockCollision> byPosition) {
        return hasSolidFallingLiquidNeighbor(position, 1, 0, byPosition)
                || hasSolidFallingLiquidNeighbor(position, -1, 0, byPosition)
                || hasSolidFallingLiquidNeighbor(position, 0, 1, byPosition)
                || hasSolidFallingLiquidNeighbor(position, 0, -1, byPosition);
    }

    private static boolean hasSolidFallingLiquidNeighbor(
            BlockPosition position, int dx, int dz, Map<BlockPosition, PlacedBlockCollision> byPosition) {
        BlockPosition neighborPosition = new BlockPosition(position.x() + dx, position.y(), position.z() + dz);
        if (isSolid(byPosition.get(neighborPosition))) {
            return true;
        }
        BlockPosition aboveNeighborPosition =
                new BlockPosition(neighborPosition.x(), neighborPosition.y() + 1, neighborPosition.z());
        return isSolid(byPosition.get(aboveNeighborPosition));
    }

    private static BedrockLiquidFlowVector normalizedFlowVector(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length < MIN_FLOW_VECTOR_LENGTH) {
            return BedrockLiquidFlowVector.NONE;
        }
        return new BedrockLiquidFlowVector(x / length, y / length, z / length);
    }

    private static boolean blocksMotion(PlacedBlockCollision block) {
        return BedrockBlockMaterials.bundled().blocksMotion(block);
    }

    private static boolean isSolid(PlacedBlockCollision block) {
        return BedrockBlockMaterials.bundled().isSolid(block);
    }

    // A waterlogged block, kelp or a bubble column samples as its block plus a separate source-water entry at the
    // same position; only the liquid index holds that water, so a neighbour's depth must come from there.
    private static OptionalInt neighborDepth(
            BlockPosition position,
            BedrockLiquidKind liquidKind,
            Map<BlockPosition, PlacedBlockCollision> liquidByPosition) {
        PlacedBlockCollision neighbor = liquidByPosition.get(position);
        if (neighbor == null || BedrockLiquidGeometry.liquidKind(neighbor) != liquidKind) {
            return OptionalInt.empty();
        }
        return BedrockLiquidGeometry.liquidDepth(neighbor);
    }
}
