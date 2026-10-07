package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.engine.shapes.SupportType;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.BooleanOp;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Set;

public final class CeilingHangingSignBehavior extends SignBehavior {
    private final Set<String> hangingSigns;
    public CeilingHangingSignBehavior(Set<String> hangingSigns) { this.hangingSigns = Set.copyOf(hangingSigns); }
    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        var above = pos.relative(Direction.UP);
        return level.isFaceSturdy(level.stateAt(above), above, Direction.DOWN, SupportType.CENTER);
    }
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        var level = context.level(); var above = context.clickedPos().relative(Direction.UP); int aboveState = level.stateAt(above);
        Direction direction = Direction.fromYRot(context.rotation());
        boolean attached = Shapes.joinIsNotEmpty(Shapes.block(), level.behavior(aboveState).collisionShape(level, aboveState, above).face(Direction.DOWN), BooleanOp.NOT_SAME) || context.secondaryUseActive();
        if (hangingSigns.contains(level.registry().block(aboveState).key()) && !context.secondaryUseActive()) {
            if (level.registry().hasProperty(aboveState, "facing")) {
                if (facing(level, aboveState).axisName().equals(direction.axisName())) attached = false;
            } else if (level.registry().hasProperty(aboveState, "rotation")) {
                Direction aboveDirection = Rotation16.toDirection(number(level, aboveState, "rotation"));
                if (aboveDirection != null && aboveDirection.axisName().equals(direction.axisName())) attached = false;
            }
        }
        int rotation = attached ? Rotation16.fromDegrees(context.rotation() + 180.0F) : Rotation16.fromDirection(direction.opposite());
        int state = level.registry().with(block.defaultState(), "attached", Boolean.toString(attached));
        state = level.registry().with(state, "rotation", Integer.toString(rotation));
        return level.registry().with(state, "waterlogged", Boolean.toString(level.fluidAt(context.clickedPos()).type().equals("minecraft:water")));
    }
    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return direction == Direction.UP && !canSurvive(level, state, pos) ? level.registry().block("minecraft:air").defaultState()
            : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }
}
