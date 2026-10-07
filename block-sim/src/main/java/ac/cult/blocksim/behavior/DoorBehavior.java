package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.Signals;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.interaction.PlacementContext;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;

public final class DoorBehavior extends BlockBehavior {
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        SimLevel level = context.level(); BlockPos pos = context.clickedPos(); BlockPos above = pos.relative(Direction.UP);
        int aboveState = level.stateAt(above);
        if (pos.y() >= level.maxY() || !level.behavior(aboveState).canBeReplaced(level, aboveState, context)) return -1;
        var signals = new Signals(level);
        boolean powered = signals.hasNeighborSignal(pos) || signals.hasNeighborSignal(above);
        int state = level.registry().with(block.defaultState(), "facing", context.horizontalDirection().name().toLowerCase(java.util.Locale.ROOT));
        state = level.registry().with(state, "hinge", hinge(context));
        state = level.registry().with(state, "powered", Boolean.toString(powered));
        state = level.registry().with(state, "open", Boolean.toString(powered));
        return level.registry().with(state, "half", "lower");
    }

    private String hinge(PlacementContext context) {
        SimLevel level = context.level(); BlockPos pos = context.clickedPos(); Direction facing = context.horizontalDirection();
        BlockPos above = pos.relative(Direction.UP);
        BlockPos leftPos = pos.relative(facing.counterClockwise()), rightPos = pos.relative(facing.clockwise());
        BlockPos leftAbove = above.relative(facing.counterClockwise()), rightAbove = above.relative(facing.clockwise());
        int left = level.stateAt(leftPos), right = level.stateAt(rightPos);
        int balance = (level.collisionShapeFullBlock(left, leftPos) ? -1 : 0)
            + (level.collisionShapeFullBlock(level.stateAt(leftAbove), leftAbove) ? -1 : 0)
            + (level.collisionShapeFullBlock(right, rightPos) ? 1 : 0)
            + (level.collisionShapeFullBlock(level.stateAt(rightAbove), rightAbove) ? 1 : 0);
        boolean doorLeft = isFamily(level, left, "DoorBlock") && level.registry().value(left, "half").equals("lower");
        boolean doorRight = isFamily(level, right, "DoorBlock") && level.registry().value(right, "half").equals("lower");
        if ((!doorLeft || doorRight) && balance <= 0) {
            if ((!doorRight || doorLeft) && balance >= 0) {
                double clickX = context.clickLocation().x() - pos.x(), clickZ = context.clickLocation().z() - pos.z();
                return (facing.x() >= 0 || !(clickZ < 0.5)) && (facing.x() <= 0 || !(clickZ > 0.5))
                    && (facing.z() >= 0 || !(clickX > 0.5)) && (facing.z() <= 0 || !(clickX < 0.5)) ? "left" : "right";
            }
            return "left";
        }
        return "right";
    }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        BlockPos below = pos.relative(Direction.DOWN); int belowState = level.stateAt(below);
        return level.registry().value(state, "half").equals("lower")
            ? level.isFaceSturdy(belowState, below, Direction.UP) : level.registry().sameBlock(state, belowState);
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        String half = level.registry().value(state, "half");
        if (direction.horizontal() || half.equals("lower") != (direction == Direction.UP)) {
            return half.equals("lower") && direction == Direction.DOWN && !canSurvive(level, state, pos)
                ? level.registry().block("minecraft:air").defaultState() : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
        }
        return isFamily(level, neighborState, "DoorBlock") && !level.registry().value(neighborState, "half").equals(half)
            ? level.registry().with(neighborState, "half", half) : level.registry().block("minecraft:air").defaultState();
    }

    @Override
    public void setPlacedBy(SimLevel level, int state, BlockPos pos, SimPlayer player, SimItemStack stack) {
        level.setBlock(pos.relative(Direction.UP), level.registry().with(state, "half", "upper"), 3);
    }

    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) {
        SimLevel level = context.level();
        if (!level.registry().block(state).bindings().get("DoorBlock.type").endsWith(":canOpenByHand=true")) return SimInteraction.PASS;
        level.setBlock(context.clickedPos(), level.registry().with(state, "open", Boolean.toString(!bool(level, state, "open"))), 10);
        // Native sound and game-event calls do not change this contract.
        return SimInteraction.SUCCESS;
    }
}
