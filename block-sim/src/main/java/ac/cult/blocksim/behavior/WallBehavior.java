package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.shapes.BooleanOp;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Locale;
import java.util.Map;

public final class WallBehavior extends BlockBehavior {
    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    // WallBlock's TEST_SHAPE_POST and rotateHorizontal(TEST_SHAPES_WALL), in block units.
    private static final VoxelShape TEST_POST = Shapes.create(7.0 / 16, 0, 7.0 / 16, 9.0 / 16, 1, 9.0 / 16);
    private static final Map<Direction, VoxelShape> TEST_SIDES = Map.of(
        Direction.NORTH, Shapes.create(7.0 / 16, 0, 0, 9.0 / 16, 1, 9.0 / 16),
        Direction.EAST, Shapes.create(7.0 / 16, 0, 7.0 / 16, 1, 1, 9.0 / 16),
        Direction.SOUTH, Shapes.create(7.0 / 16, 0, 7.0 / 16, 9.0 / 16, 1, 1),
        Direction.WEST, Shapes.create(0, 0, 7.0 / 16, 9.0 / 16, 1, 9.0 / 16));
    private final ConnectionRules rules;
    public WallBehavior(ConnectionRules rules) { this.rules = rules; }
    private static String property(Direction direction) { return direction.name().toLowerCase(Locale.ROOT); }

    private boolean connectsTo(SimLevel level, int state, boolean faceSolid, Direction direction) {
        boolean gate = isFamily(level, state, "FenceGateBlock") && FenceGateBehavior.connectsToDirection(level, state, direction);
        return rules.walls.contains(level.registry().block(state).key()) || !rules.exception(level, state) && faceSolid || isFamily(level, state, "IronBarsBlock") || gate;
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        SimLevel level = context.level(); BlockPos pos = context.clickedPos(); boolean[] connected = new boolean[4];
        for (int i = 0; i < SIDES.length; i++) {
            Direction direction = SIDES[i]; BlockPos neighbor = pos.relative(direction); int state = level.stateAt(neighbor);
            connected[i] = connectsTo(level, state, level.isFaceSturdy(state, neighbor, direction.opposite()), direction.opposite());
        }
        int result = level.registry().with(block.defaultState(), "waterlogged", Boolean.toString(level.fluidAt(pos).is("minecraft:water")));
        BlockPos above = pos.relative(Direction.UP);
        return updateWall(level, result, above, level.stateAt(above), connected);
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        // Native fluid scheduling is a client no-op.
        if (direction == Direction.DOWN) return super.updateShape(level, state, pos, direction, neighborPos, neighborState);
        return direction == Direction.UP ? topUpdate(level, state, neighborPos, neighborState) : sideUpdate(level, pos, state, neighborPos, neighborState, direction);
    }

    private static boolean isConnected(SimLevel level, int state, Direction side) { return !level.registry().value(state, property(side)).equals("none"); }

    private static boolean isCovered(VoxelShape aboveShape, VoxelShape testShape) {
        return !Shapes.joinIsNotEmpty(testShape, aboveShape, BooleanOp.ONLY_FIRST);
    }

    private int topUpdate(SimLevel level, int state, BlockPos topPos, int topState) {
        boolean[] connected = new boolean[4];
        for (int i = 0; i < SIDES.length; i++) connected[i] = isConnected(level, state, SIDES[i]);
        return updateWall(level, state, topPos, topState, connected);
    }

    private int sideUpdate(SimLevel level, BlockPos pos, int state, BlockPos neighborPos, int neighborState, Direction direction) {
        boolean[] connected = new boolean[4];
        for (int i = 0; i < SIDES.length; i++) connected[i] = direction == SIDES[i]
            ? connectsTo(level, neighborState, level.isFaceSturdy(neighborState, neighborPos, direction.opposite()), direction.opposite()) : isConnected(level, state, SIDES[i]);
        BlockPos above = pos.relative(Direction.UP);
        return updateWall(level, state, above, level.stateAt(above), connected);
    }

    private int updateWall(SimLevel level, int state, BlockPos topPos, int topState, boolean[] connected) {
        VoxelShape aboveShape = level.behavior(topState).collisionShape(level, topState, topPos).face(Direction.DOWN);
        int sidesUpdated = updateSides(level, state, connected, aboveShape);
        return level.registry().with(sidesUpdated, "up", Boolean.toString(shouldRaisePost(level, sidesUpdated, topState, aboveShape)));
    }

    private boolean shouldRaisePost(SimLevel level, int state, int topState, VoxelShape aboveShape) {
        if (isFamily(level, topState, "WallBlock") && bool(level, topState, "up")) return true;
        String north = level.registry().value(state, "north"), south = level.registry().value(state, "south");
        String east = level.registry().value(state, "east"), west = level.registry().value(state, "west");
        boolean northNone = north.equals("none"), southNone = south.equals("none"), eastNone = east.equals("none"), westNone = west.equals("none");
        if (northNone && southNone && eastNone && westNone || northNone != southNone || westNone != eastNone) return true;
        boolean high = north.equals("tall") && south.equals("tall") || east.equals("tall") && west.equals("tall");
        return !high && (rules.wallPostOverride.contains(level.registry().block(topState).key()) || isCovered(aboveShape, TEST_POST));
    }

    private int updateSides(SimLevel level, int state, boolean[] connected, VoxelShape aboveShape) {
        for (int i = 0; i < SIDES.length; i++) state = level.registry().with(state, property(SIDES[i]), makeWallState(connected[i], aboveShape, TEST_SIDES.get(SIDES[i])));
        return state;
    }

    private String makeWallState(boolean connected, VoxelShape aboveShape, VoxelShape testShape) {
        return connected ? isCovered(aboveShape, testShape) ? "tall" : "low" : "none";
    }
}
