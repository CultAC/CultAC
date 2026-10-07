package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.blocksim.interaction.PlacementContext;

public final class ScaffoldingBehavior extends BlockBehavior {
    @Override
    public VoxelShape collisionShape(SimLevel level, int state, BlockPos pos, ac.cult.blocksim.engine.EntityCollisionContext context) {
        if (context.placement()) return Shapes.empty();
        if (context.isAbove(1, pos, true) && !context.descending()) {
            // The generated empty-context collision is the stable top and four posts.
            int stable = BlockProps.BOTTOM.with(state, false);
            return super.collisionShape(level, stable, pos);
        }
        return BlockProps.STABILITY_DISTANCE.value(state) != 0 && BlockProps.BOTTOM.booleanValue(state) && context.isAbove(0, pos, true)
            ? Shapes.create(0, 0, 0, 1, 2.0 / 16, 1) : Shapes.empty();
    }
    @Override
    public VoxelShape outlineShape(SimLevel level, int state, BlockPos pos, ac.cult.blocksim.engine.EntityCollisionContext context) {
        return context.isHoldingItem("minecraft:scaffolding")
            ? Shapes.block() : super.outlineShape(level, state, pos, context);
    }
    public static int distance(SimLevel level, BlockPos pos) {
        var below = pos.relative(Direction.DOWN); int state = level.stateAt(below), distance = 7;
        var block = level.registry().block("minecraft:scaffolding");
        if (level.registry().block(state) == block) distance = number(level, state, "distance");
        else if (level.isFaceSturdy(state, below, Direction.UP)) return 0;
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            int neighbor = level.stateAt(pos.relative(direction));
            if (level.registry().block(neighbor) == block) {
                distance = Math.min(distance, number(level, neighbor, "distance") + 1);
                if (distance == 1) break;
            }
        }
        return distance;
    }

    private boolean isBottom(BlockDefinition block, SimLevel level, BlockPos pos, int distance) {
        return distance > 0 && level.registry().block(level.stateAt(pos.relative(Direction.DOWN))) != block;
    }

    @Override
    public boolean canBeReplaced(SimLevel level, int state, PlacementContext context) { return context.stack().is(level.registry().block(state).bindings().get("asItem")); }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        var level = context.level(); var pos = context.clickedPos(); int distance = distance(level, pos);
        int state = level.registry().with(block.defaultState(), "waterlogged", Boolean.toString(level.fluidAt(pos).is("minecraft:water")));
        state = level.registry().with(state, "distance", Integer.toString(distance));
        return level.registry().with(state, "bottom", Boolean.toString(isBottom(block, level, pos, distance)));
    }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) { return distance(level, pos) < 7; }

}
