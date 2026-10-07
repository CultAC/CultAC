package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.shapes.SupportType;
import java.util.Locale;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.interaction.PlacementContext;

public final class MultifaceBehavior extends BlockBehavior {
    private static boolean hasAnyVacantFace(SimLevel level, int state) {
        for (Direction direction : Direction.values()) if (!hasFace(level, state, direction)) return true;
        return false;
    }

    @Override
    public boolean canBeReplaced(SimLevel level, int state, PlacementContext context) {
        return !context.stack().is(level.registry().block(state).bindings().get("asItem")) || hasAnyVacantFace(level, state);
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        var level = context.level(); var pos = context.clickedPos(); int old = level.stateAt(pos);
        for (Direction direction : context.nearestLookingDirections()) {
            int placed = placementState(block, level, old, pos, direction);
            if (placed >= 0) return placed;
        }
        return -1;
    }

    private boolean isValidStateForPlacement(BlockDefinition block, SimLevel level, int old, BlockPos pos, Direction direction) {
        return level.registry().hasProperty(block.defaultState(), direction.name().toLowerCase(Locale.ROOT))
            && (level.registry().block(old) != block || !hasFace(level, old, direction)) && canAttachTo(level, pos, direction);
    }

    private int placementState(BlockDefinition block, SimLevel level, int old, BlockPos pos, Direction direction) {
        if (!isValidStateForPlacement(block, level, old, pos, direction)) return -1;
        int placed = level.registry().block(old) == block ? old : level.fluidAt(pos).isSourceOfType("minecraft:water")
            ? level.registry().with(block.defaultState(), "waterlogged", "true") : block.defaultState();
        return level.registry().with(placed, direction.name().toLowerCase(Locale.ROOT), "true");
    }

    private static boolean hasAnyFace(SimLevel level, int state) {
        for (Direction direction : Direction.values()) if (hasFace(level, state, direction)) return true;
        return false;
    }

    private static int removeFace(SimLevel level, int state, Direction direction) {
        int result = level.registry().with(state, direction.name().toLowerCase(Locale.ROOT), "false");
        return hasAnyFace(level, result) ? result : level.registry().block("minecraft:air").defaultState();
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        // The native scheduled-water-tick call is a no-op in ClientLevel.
        if (!hasAnyFace(level, state)) return level.registry().block("minecraft:air").defaultState();
        return hasFace(level, state, direction) && !canAttachTo(level, direction, neighborPos, neighborState)
            ? removeFace(level, state, direction) : state;
    }
    private static boolean hasFace(SimLevel level, int state, Direction direction) {
        String property = direction.name().toLowerCase(Locale.ROOT);
        return level.registry().hasProperty(state, property) && bool(level, state, property);
    }

    /** GlowLichenBlock.isValidBonemealTarget and MultifaceSpreader's three attachment candidates. */
    boolean canSpread(SimLevel level, int state, BlockPos pos) {
        for (Direction from : Direction.values()) for (Direction toward : Direction.values()) {
            if (from.axisName().equals(toward.axisName()) || !hasFace(level, state, from) || hasFace(level, state, toward)) continue;
            if (canSpreadInto(level, state, pos, toward)
                    || canSpreadInto(level, state, pos.relative(toward), from)
                    || canSpreadInto(level, state, pos.relative(toward).relative(from), toward.opposite())) return true;
        }
        return false;
    }

    private static boolean canSpreadInto(SimLevel level, int source, BlockPos pos, Direction face) {
        int state = level.stateAt(pos);
        boolean same = level.registry().sameBlock(source, state);
        if (!(same || level.registry().facts(state).has(ac.cult.blocksim.data.StateFacts.AIR)
                || level.registry().block(state).key().equals("minecraft:water") && level.fluidAt(pos).isSourceOfType("minecraft:water"))) return false;
        return (!same || !hasFace(level, state, face)) && canAttachTo(level, pos, face);
    }

    private static boolean canAttachTo(SimLevel level, BlockPos pos, Direction direction) {
        BlockPos neighborPos = pos.relative(direction);
        return canAttachTo(level, direction, neighborPos, level.stateAt(neighborPos));
    }

    static boolean canAttachTo(SimLevel level, Direction direction, BlockPos neighborPos, int neighborState) {
        BlockBehavior behavior = level.behavior(neighborState);
        return SupportType.FULL.supports(behavior.supportShape(level, neighborState, neighborPos), direction.opposite())
            || SupportType.FULL.supports(behavior.collisionShape(level, neighborState, neighborPos), direction.opposite());
    }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        boolean hasAtLeastOneFace = false;
        for (Direction direction : Direction.values()) {
            if (hasFace(level, state, direction)) {
                if (!canAttachTo(level, pos, direction)) return false;
                hasAtLeastOneFace = true;
            }
        }
        return hasAtLeastOneFace;
    }
}
