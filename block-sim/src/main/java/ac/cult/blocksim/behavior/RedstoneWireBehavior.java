package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.interaction.PlacementContext;
import ac.cult.blocksim.interaction.UseContext;
import ac.cult.blocksim.interaction.SimInteraction;
import java.util.Locale;

/** Client shape connections only. Power evaluators and neighborChanged are server-only. */
public final class RedstoneWireBehavior extends BlockBehavior {
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static String property(Direction direction) { return direction.name().toLowerCase(Locale.ROOT); }
    private static boolean connected(SimLevel level, int state, Direction direction) {
        return !level.registry().value(state, property(direction)).equals("none");
    }
    private static int side(SimLevel level, int state, Direction direction, String side) {
        return level.registry().with(state, property(direction), side);
    }
    private static int defaultState(SimLevel level) { return level.registry().block("minecraft:redstone_wire").defaultState(); }
    private static int crossState(SimLevel level) {
        int state = defaultState(level);
        for (Direction direction : HORIZONTAL) state = side(level, state, direction, "side");
        return state;
    }
    private static boolean isWire(SimLevel level, int state) { return level.registry().sameBlock(state, defaultState(level)); }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        return connectionState(context.level(), crossState(context.level()), context.clickedPos());
    }

    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) {
        if (!context.player().state().mayBuild()) return SimInteraction.PASS;
        SimLevel level = context.level();
        if (isCross(level, state) || isDot(level, state)) {
            int next = isCross(level, state) ? defaultState(level) : crossState(level);
            next = level.registry().with(next, "power", level.registry().value(state, "power"));
            next = connectionState(level, next, context.clickedPos());
            if (next != state) {
                level.setBlock(context.clickedPos(), next, 3);
                // updatesOnShapeChange calls only updateNeighborsAtExceptFromFacing,
                // whose Level implementation is a client no-op.
                return SimInteraction.SUCCESS;
            }
        }
        return SimInteraction.PASS;
    }

    public int connectionState(SimLevel level, int state, BlockPos pos) {
        boolean wasDot = isDot(level, state);
        state = missingConnections(level, level.registry().with(defaultState(level), "power", level.registry().value(state, "power")), pos);
        if (wasDot && isDot(level, state)) return state;
        boolean north = connected(level, state, Direction.NORTH), south = connected(level, state, Direction.SOUTH);
        boolean east = connected(level, state, Direction.EAST), west = connected(level, state, Direction.WEST);
        boolean northSouthEmpty = !north && !south, eastWestEmpty = !east && !west;
        if (!west && northSouthEmpty) state = side(level, state, Direction.WEST, "side");
        if (!east && northSouthEmpty) state = side(level, state, Direction.EAST, "side");
        if (!north && eastWestEmpty) state = side(level, state, Direction.NORTH, "side");
        if (!south && eastWestEmpty) state = side(level, state, Direction.SOUTH, "side");
        return state;
    }

    private int missingConnections(SimLevel level, int state, BlockPos pos) {
        boolean canConnectUp = !level.isRedstoneConductor(level.stateAt(pos.relative(Direction.UP)), pos);
        for (Direction direction : HORIZONTAL) {
            if (!connected(level, state, direction)) state = side(level, state, direction, connectingSide(level, pos, direction, canConnectUp));
        }
        return state;
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        if (direction == Direction.DOWN) return canSurviveOn(level, neighborPos, neighborState) ? state : level.registry().block("minecraft:air").defaultState();
        if (direction == Direction.UP) return connectionState(level, state, pos);
        String side = connectingSide(level, pos, direction);
        if (!side.equals("none") == connected(level, state, direction) && !isCross(level, state)) return side(level, state, direction, side);
        int cross = level.registry().with(crossState(level), "power", level.registry().value(state, "power"));
        return connectionState(level, side(level, cross, direction, side), pos);
    }

    private static boolean isCross(SimLevel level, int state) {
        return connected(level, state, Direction.NORTH) && connected(level, state, Direction.SOUTH)
            && connected(level, state, Direction.EAST) && connected(level, state, Direction.WEST);
    }

    private static boolean isDot(SimLevel level, int state) {
        return !connected(level, state, Direction.NORTH) && !connected(level, state, Direction.SOUTH)
            && !connected(level, state, Direction.EAST) && !connected(level, state, Direction.WEST);
    }

    @Override
    public void updateIndirectShapes(SimLevel level, int state, BlockPos pos, int flags, int limit) {
        for (Direction direction : HORIZONTAL) {
            BlockPos relative = pos.relative(direction);
            if (connected(level, state, direction) && !isWire(level, level.stateAt(relative))) {
                BlockPos below = relative.relative(Direction.DOWN);
                if (isWire(level, level.stateAt(below))) {
                    BlockPos neighbor = below.relative(direction.opposite());
                    level.shapeUpdater().shapeUpdate(direction.opposite(), level.stateAt(neighbor), below, neighbor, flags, limit);
                }
                BlockPos above = relative.relative(Direction.UP);
                if (isWire(level, level.stateAt(above))) {
                    BlockPos neighbor = above.relative(direction.opposite());
                    level.shapeUpdater().shapeUpdate(direction.opposite(), level.stateAt(neighbor), above, neighbor, flags, limit);
                }
            }
        }
    }

    private String connectingSide(SimLevel level, BlockPos pos, Direction direction) {
        return connectingSide(level, pos, direction, !level.isRedstoneConductor(level.stateAt(pos.relative(Direction.UP)), pos));
    }

    private String connectingSide(SimLevel level, BlockPos pos, Direction direction, boolean canConnectUp) {
        BlockPos relative = pos.relative(direction);
        int relativeState = level.stateAt(relative);
        if (canConnectUp) {
            boolean trapDoor = level.registry().block(relativeState).bindings().get("classHierarchy").contains("net.minecraft.world.level.block.TrapDoorBlock,");
            boolean placeableAbove = trapDoor || canSurviveOn(level, relative, relativeState);
            if (placeableAbove && shouldConnectTo(level, relative.relative(Direction.UP))) {
                return level.isFaceSturdy(relativeState, relative, direction.opposite()) ? "up" : "side";
            }
        }
        return !shouldConnectTo(relativeState, level, relative, direction)
            && (level.isRedstoneConductor(relativeState, relative) || !shouldConnectTo(level, relative.relative(Direction.DOWN))) ? "none" : "side";
    }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        BlockPos below = pos.relative(Direction.DOWN);
        return canSurviveOn(level, below, level.stateAt(below));
    }

    private boolean canSurviveOn(SimLevel level, BlockPos pos, int state) {
        return level.isFaceSturdy(state, pos, Direction.UP) || level.registry().block(state).key().equals("minecraft:hopper");
    }

    @Override
    public int directSignal(SimLevel level, int state, BlockPos pos, Direction direction) {
        // shouldSignal is toggled only inside server power evaluation (getBlockSignal).
        return signal(level, state, pos, direction);
    }

    @Override
    public int signal(SimLevel level, int state, BlockPos pos, Direction direction) {
        if (direction == Direction.DOWN) return 0;
        int power = ownSignal(level, state, pos);
        if (power == 0) return 0;
        return direction != Direction.UP && !connected(level, connectionState(level, state, pos), direction.opposite()) ? 0 : power;
    }

    @Override
    public int ownSignal(SimLevel level, int state, BlockPos pos) { return number(level, state, "power"); }

    @Override
    public boolean wireConnectsTo(SimLevel level, int state, BlockPos pos, Direction direction) { return true; }

    private static boolean shouldConnectTo(SimLevel level, BlockPos pos) {
        return shouldConnectTo(level.stateAt(pos), level, pos, null);
    }

    private static boolean shouldConnectTo(int state, SimLevel level, BlockPos pos, Direction direction) {
        return level.behavior(state).wireConnectsTo(level, state, pos, direction);
    }
}
