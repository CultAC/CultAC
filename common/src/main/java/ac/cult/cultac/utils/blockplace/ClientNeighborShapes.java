package ac.cult.cultac.utils.blockplace;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseCoralPlantTypeBlock;
import net.minecraft.world.level.block.BaseCoralWallFanBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CoralBlock;
import net.minecraft.world.level.block.ConcretePowderBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RedstoneSide;

/** Client shape updates without fork-specific server tick scheduling. */
final class ClientNeighborShapes {
    private ClientNeighborShapes() {
    }

    static void updateIndirect(BlockState state, Level level, BlockPos pos, int flags, int limit) {
        if (state.getBlock() != Blocks.REDSTONE_WIRE) {
            state.updateIndirectNeighbourShapes(level, pos, flags, limit);
            return;
        }
        // Vanilla RedStoneWireBlock.updateIndirectNeighbourShapes. Paper replaces
        // getBlockState with a final live-chunk accessor which, on Folia, also reads
        // region-owned tree-capture data. Every read here must use our overlay.
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(direction);
            var connection = switch (direction) {
                case NORTH -> BlockStateProperties.NORTH_REDSTONE;
                case SOUTH -> BlockStateProperties.SOUTH_REDSTONE;
                case WEST -> BlockStateProperties.WEST_REDSTONE;
                case EAST -> BlockStateProperties.EAST_REDSTONE;
                default -> throw new IllegalStateException("Non-horizontal wire direction");
            };
            if (state.getValue(connection) == RedstoneSide.NONE
                    || level.getBlockState(side).getBlock() == state.getBlock()) {
                continue;
            }
            for (Direction vertical : new Direction[]{Direction.DOWN, Direction.UP}) {
                BlockPos wire = side.relative(vertical);
                if (level.getBlockState(wire).getBlock() == state.getBlock()) {
                    BlockPos neighbor = wire.relative(direction.getOpposite());
                    level.neighborShapeChanged(direction.getOpposite(), wire, neighbor,
                            level.getBlockState(neighbor), flags, limit);
                }
            }
        }
    }

    static boolean update(Level level, BlockPos pos, Direction direction, int flags, int limit) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        if (block instanceof ConcretePowderBlock) {
            Block.updateOrDestroy(state, NmsClientInteraction.concretePowderNeighborShape(state, level, pos),
                    level, pos, flags, limit);
            return true;
        }
        // Vanilla updateShape returns the same state; only server scheduled ticks
        // can grow cactus, kill solid coral, flow fluids, or pulse observers.
        if (block instanceof CactusBlock || block instanceof CoralBlock
                || block instanceof LiquidBlock || block instanceof ObserverBlock) {
            return true;
        }
        if (block instanceof BaseCoralPlantTypeBlock) {
            // BaseCoralPlantTypeBlock/CoralPlantBlock/CoralFanBlock and
            // BaseCoralWallFanBlock/CoralWallFanBlock retain client support loss.
            // Their water/death tick scheduling has no client state effect.
            Direction support = block instanceof BaseCoralWallFanBlock
                    ? state.getValue(BaseCoralWallFanBlock.FACING).getOpposite() : Direction.DOWN;
            if (direction == support && !state.canSurvive(level, pos)) {
                Block.updateOrDestroy(state, Blocks.AIR.defaultBlockState(), level, pos, flags, limit);
            }
            return true;
        }
        return false;
    }
}
