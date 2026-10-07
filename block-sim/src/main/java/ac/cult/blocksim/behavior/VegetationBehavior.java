package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.*;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.engine.shapes.SupportType;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Set;

/** Shared plant placement and support; each rule names a vanilla support predicate. */
public class VegetationBehavior extends BlockBehavior {
    public enum Kind { NORMAL, CACTUS_FLOWER, SEAGRASS, LILY_PAD, FROGSPAWN, LEAF_LITTER, FLOWER_BED, SEA_PICKLE, MUSHROOM, SMALL_DRIPLEAF }
    private final Set<String> supportTag;
    private final DataTables data;
    private final Kind kind;

    public VegetationBehavior(Set<String> supportTag) {
        this.supportTag = Set.copyOf(supportTag); data = null; kind = Kind.NORMAL;
    }
    public VegetationBehavior(DataTables data, Kind kind) {
        this.data = data; this.kind = kind; supportTag = data.tags().get("block:minecraft:supports_vegetation");
    }

    protected boolean mayPlaceOn(SimLevel level, int state, BlockPos pos) {
        String block = level.registry().block(state).key();
        return switch (kind) {
            case NORMAL, FLOWER_BED -> supportTag.contains(block);
            case CACTUS_FLOWER -> tagged("block", "support_override_cactus_flower", block) || level.isFaceSturdy(state, pos, Direction.UP, SupportType.CENTER);
            case SEAGRASS -> level.isFaceSturdy(state, pos, Direction.UP) && !tagged("block", "cannot_support_seagrass", block);
            case LILY_PAD, FROGSPAWN -> surfaceSupport(level, pos, block, kind == Kind.LILY_PAD ? "supports_lily_pad" : "supports_frogspawn");
            case LEAF_LITTER -> level.isFaceSturdy(state, pos, Direction.UP);
            case SEA_PICKLE -> !level.behavior(state).collisionShape(level, state, pos).face(Direction.UP).isEmpty() || level.isFaceSturdy(state, pos, Direction.UP);
            // MushroomBlock.canSurvive checks light at the plant, above its support.
            case MUSHROOM -> tagged("block", "overrides_mushroom_light_requirement", block)
                || level.rawBrightnessAt(pos.relative(Direction.UP)) < 13 && level.registry().facts(state).has(StateFacts.SOLID_RENDER);
            case SMALL_DRIPLEAF -> tagged("block", "supports_small_dripleaf", block)
                || level.fluidAt(pos.relative(Direction.UP)).isSourceOfType("minecraft:water") && supportTag.contains(block);
        };
    }
    private boolean tagged(String type, String tag, String key) { return data.tags().get(type + ":minecraft:" + tag).contains(key); }
    private boolean surfaceSupport(SimLevel level, BlockPos pos, String block, String tag) {
        return (tagged("fluid", tag, level.fluidAt(pos).type()) || tagged("block", tag, block))
            && level.fluidAt(pos.relative(Direction.UP)).is("minecraft:empty");
    }
    private String segmentProperty() {
        return switch (kind) { case LEAF_LITTER -> "segment_amount"; case FLOWER_BED -> "flower_amount"; case SEA_PICKLE -> "pickles"; default -> null; };
    }

    @Override public boolean canBeReplaced(SimLevel level, int state, PlacementContext context) {
        String segment = segmentProperty();
        return segment != null && SegmentablePlacement.canBeReplaced(level, state, context, segment)
            || super.canBeReplaced(level, state, context);
    }
    @Override public int placementState(BlockDefinition block, PlacementContext context) {
        String segment = segmentProperty();
        if (segment != null && kind != Kind.SEA_PICKLE) return SegmentablePlacement.placementState(block, context, segment);
        var level = context.level(); var fluid = level.fluidAt(context.clickedPos());
        if (kind == Kind.SEAGRASS && !(tagged("fluid", "water", fluid.type()) && fluid.amount() == 8)) return -1;
        if (kind == Kind.SEA_PICKLE) {
            int old = level.stateAt(context.clickedPos());
            return level.registry().block(old) == block
                ? level.registry().with(old, "pickles", Integer.toString(Math.min(4, number(level, old, "pickles") + 1)))
                : level.registry().with(block.defaultState(), "waterlogged", Boolean.toString(fluid.is("minecraft:water")));
        }
        return block.defaultState();
    }

    protected boolean mayPlaceOn(SimLevel level, int plantState, int supportState, BlockPos supportPos) {
        return mayPlaceOn(level, supportState, supportPos);
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return !canSurvive(level, state, pos) ? level.registry().block("minecraft:air").defaultState()
            : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        BlockPos below = pos.relative(Direction.DOWN);
        return mayPlaceOn(level, state, level.stateAt(below), below);
    }
}
