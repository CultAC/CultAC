package ac.cult.blocksim.interaction;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.engine.Vec3;

public final class PlacementContext extends UseContext {
    private final BlockPos relativePos;
    private boolean replaceClicked = true;

    public PlacementContext(UseContext context) {
        super(context.level(), context.player(), context.hand(), context.stack(), context.hit(), context.usedItem());
        relativePos = context.hit().pos().relative(context.hit().face());
        int clickedState = level().stateAt(context.hit().pos());
        replaceClicked = level().behavior(clickedState).canBeReplaced(level(), clickedState, this);
    }
    public PlacementContext(SimLevel level, SimPlayer player, Hand hand, SimItemStack stack, BlockHit hit) {
        super(level, player, hand, stack, hit);
        relativePos = hit.pos().relative(hit.face());
        int clickedState = level.stateAt(hit.pos());
        replaceClicked = level.behavior(clickedState).canBeReplaced(level, clickedState, this);
    }
    public PlacementContext at(BlockPos pos, Direction direction) {
        return new PlacementContext(new UseContext(level(), player(), hand(), stack(), new BlockHit(pos, direction,
            new Vec3(pos.x() + 0.5 + direction.x() * 0.5, pos.y() + 0.5 + direction.y() * 0.5, pos.z() + 0.5 + direction.z() * 0.5), false), usedItem()));
    }
    @Override
    public BlockPos clickedPos() { return replaceClicked ? super.clickedPos() : relativePos; }
    public boolean canPlace() {
        if (replaceClicked) return true;
        int state = level().stateAt(clickedPos());
        return level().behavior(state).canBeReplaced(level(), state, this);
    }
    public boolean replacingClicked() { return replaceClicked; }
    public Direction nearestLookingDirection() { return lookingDirections()[0]; }
    public Direction nearestLookingVerticalDirection() { return player().state().pitch() < 0.0F ? Direction.UP : Direction.DOWN; }
    private Direction[] lookingDirections() { return Direction.orderedByNearest(player().state().yaw(), player().state().pitch()); }
    public Direction[] nearestLookingDirections() {
        Direction[] directions = lookingDirections();
        if (replaceClicked) return directions;
        Direction opposite = clickedFace().opposite();
        int index = 0;
        while (index < directions.length && directions[index] != opposite) index++;
        if (index > 0) {
            System.arraycopy(directions, 0, directions, 1, index);
            directions[0] = opposite;
        }
        return directions;
    }
}
