package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.geometry.BlockPosition;
import ac.grim.grimac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

final class BedrockLiquidFlowResolver {
    private static final double MIN_FLOW_VECTOR_LENGTH = 1.0E-4D;

    private BedrockLiquidFlowResolver() {
    }

    static BedrockLiquidFlowVector flowVector(
        PlacedBlockCollision block,
        BedrockLiquidKind liquidKind,
        Map<BlockPosition, PlacedBlockCollision> byPosition
    ) {
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
        BedrockLiquidFlowVector positiveX = horizontalFlowDelta(position, 1, 0, currentDepth, liquidKind, byPosition);
        BedrockLiquidFlowVector negativeX = horizontalFlowDelta(position, -1, 0, currentDepth, liquidKind, byPosition);
        BedrockLiquidFlowVector positiveZ = horizontalFlowDelta(position, 0, 1, currentDepth, liquidKind, byPosition);
        BedrockLiquidFlowVector negativeZ = horizontalFlowDelta(position, 0, -1, currentDepth, liquidKind, byPosition);
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
        Map<BlockPosition, PlacedBlockCollision> byPosition
    ) {
        BlockPosition neighborPosition = new BlockPosition(position.x() + dx, position.y(), position.z() + dz);
        OptionalInt neighborDepth = neighborDepth(neighborPosition, liquidKind, byPosition);
        if (neighborDepth.isPresent()) {
            int delta = BedrockLiquidGeometry.normalizedLiquidDepth(neighborDepth.getAsInt()) - currentDepth;
            return new BedrockLiquidFlowVector(dx * delta, 0.0D, dz * delta);
        }
        if (blocksMotion(byPosition.get(neighborPosition))) {
            return BedrockLiquidFlowVector.NONE;
        }
        BlockPosition belowNeighborPosition = new BlockPosition(
            neighborPosition.x(),
            neighborPosition.y() - 1,
            neighborPosition.z()
        );
        OptionalInt belowDepth = neighborDepth(belowNeighborPosition, liquidKind, byPosition);
        if (belowDepth.isEmpty()) {
            return BedrockLiquidFlowVector.NONE;
        }
        int delta = BedrockLiquidGeometry.normalizedLiquidDepth(belowDepth.getAsInt()) + 8 - currentDepth;
        return new BedrockLiquidFlowVector(dx * delta, 0.0D, dz * delta);
    }

    private static boolean hasFallingLiquidSide(
        BlockPosition position,
        Map<BlockPosition, PlacedBlockCollision> byPosition
    ) {
        return hasSolidFallingLiquidNeighbor(position, 1, 0, byPosition)
            || hasSolidFallingLiquidNeighbor(position, -1, 0, byPosition)
            || hasSolidFallingLiquidNeighbor(position, 0, 1, byPosition)
            || hasSolidFallingLiquidNeighbor(position, 0, -1, byPosition);
    }

    private static boolean hasSolidFallingLiquidNeighbor(
        BlockPosition position,
        int dx,
        int dz,
        Map<BlockPosition, PlacedBlockCollision> byPosition
    ) {
        BlockPosition neighborPosition = new BlockPosition(position.x() + dx, position.y(), position.z() + dz);
        if (isSolid(byPosition.get(neighborPosition))) {
            return true;
        }
        BlockPosition aboveNeighborPosition = new BlockPosition(
            neighborPosition.x(),
            neighborPosition.y() + 1,
            neighborPosition.z()
        );
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
        return block != null && !block.collisionBoxes().isEmpty();
    }

    private static boolean isSolid(PlacedBlockCollision block) {
        return blocksMotion(block);
    }

    private static OptionalInt neighborDepth(
        BlockPosition position,
        BedrockLiquidKind liquidKind,
        Map<BlockPosition, PlacedBlockCollision> byPosition
    ) {
        PlacedBlockCollision neighbor = byPosition.get(position);
        if (neighbor == null || BedrockLiquidGeometry.liquidKind(neighbor) != liquidKind) {
            return OptionalInt.empty();
        }
        return BedrockLiquidGeometry.liquidDepth(neighbor);
    }

}
