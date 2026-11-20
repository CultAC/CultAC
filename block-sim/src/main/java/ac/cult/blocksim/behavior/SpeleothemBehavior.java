package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.Offsets;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Set;

public final class SpeleothemBehavior extends BlockBehavior {
    private final Set<String> speleothems;
    public SpeleothemBehavior(Set<String> speleothems) { this.speleothems = Set.copyOf(speleothems); }
    private static Direction tip(SimLevel level, int state) { return Direction.valueOf(level.registry().value(state, "vertical_direction").toUpperCase(java.util.Locale.ROOT)); }

    private boolean sameDirection(SimLevel level, int state, Direction direction) {
        return speleothems.contains(level.registry().block(state).key()) && tip(level, state) == direction;
    }

    private boolean valid(SimLevel level, int state, BlockPos pos, Direction direction) {
        var behind = pos.relative(direction.opposite()); int support = level.stateAt(behind);
        return level.isFaceSturdy(support, behind, direction) || sameDirection(level, support, direction) && level.registry().sameBlock(state, support);
    }

    private String thickness(SimLevel level, int state, BlockPos pos, Direction direction, boolean merge) {
        int front = level.stateAt(pos.relative(direction));
        if (sameDirection(level, front, direction.opposite()) && level.registry().sameBlock(state, front)) {
            return !merge && !level.registry().value(front, "thickness").equals("tip_merge") ? "tip" : "tip_merge";
        }
        if (!sameDirection(level, front, direction)) return "tip";
        String frontThickness = level.registry().value(front, "thickness");
        if (frontThickness.equals("tip") || frontThickness.equals("tip_merge")) return "frustum";
        return sameDirection(level, level.stateAt(pos.relative(direction.opposite())), direction) ? "middle" : "base";
    }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) { return valid(level, state, pos, tip(level, state)); }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        if (direction.horizontal()) return state;
        Direction tip = tip(level, state);
        // Client tick access is empty: scheduled support loss never destroys the block here.
        if (direction == tip.opposite() && !canSurvive(level, state, pos)) return state;
        return level.registry().with(state, "thickness", thickness(level, state, pos, tip, level.registry().value(state, "thickness").equals("tip_merge")));
    }

    private Direction placementDirection(SimLevel level, int state, BlockPos pos, Direction preferred) {
        if (valid(level, state, pos, preferred)) return preferred;
        return valid(level, state, pos, preferred.opposite()) ? preferred.opposite() : null;
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        SimLevel level = context.level(); var pos = context.clickedPos(); int state = block.defaultState();
        Direction direction = placementDirection(level, state, pos, context.nearestLookingVerticalDirection().opposite());
        if (direction == null) return -1;
        state = level.registry().with(state, "vertical_direction", direction.name().toLowerCase(java.util.Locale.ROOT));
        state = level.registry().with(state, "thickness", thickness(level, state, pos, direction, !context.secondaryUseActive()));
        return level.registry().with(state, "waterlogged", Boolean.toString(level.fluidAt(pos).is("minecraft:water")));
    }

    @Override
    public VoxelShape collisionShape(SimLevel level, int state, BlockPos pos) {
        String thickness = level.registry().value(state, "thickness");
        int width = switch (thickness) { case "tip", "tip_merge" -> 6; case "frustum" -> 8; case "middle" -> 10; case "base" -> 12; default -> throw new IllegalStateException(thickness); };
        double minY = 0, maxY = 1;
        if (thickness.equals("tip")) { if (tip(level, state) == Direction.DOWN) minY = 5.0 / 16; else maxY = 11.0 / 16; }
        var offset = Offsets.offset(level.registry().block(state), pos);
        return Shapes.create((8.0 - width / 2.0) / 16, minY, (8.0 - width / 2.0) / 16,
            (8.0 + width / 2.0) / 16, maxY, (8.0 + width / 2.0) / 16).move(offset.x(), offset.y(), offset.z());
    }

    @Override
    public VoxelShape supportShape(SimLevel level, int state, BlockPos pos) { return collisionShape(level, state, pos); }
}
