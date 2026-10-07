package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.*;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.*;
import java.util.List;
import java.util.Set;

public final class CopperGolemStatueBehavior extends BlockBehavior {
    private static final List<String> POSES = List.of("standing", "sitting", "running", "star");
    private final Set<String> axes;
    private final Set<String> statues;
    public CopperGolemStatueBehavior(Set<String> axes, Set<String> statues) { this.axes = Set.copyOf(axes); this.statues = Set.copyOf(statues); }
    @Override
    public boolean shouldKeepBlockEntity(ac.cult.blocksim.data.BlockRegistry registry, int state, int oldState) {
        return statues.contains(registry.block(oldState).key());
    }
    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        var registry = context.level().registry();
        int state = registry.with(block.defaultState(), "facing", context.horizontalDirection().opposite().name().toLowerCase(java.util.Locale.ROOT));
        return registry.with(state, "waterlogged", Boolean.toString(context.level().fluidAt(context.clickedPos()).is("minecraft:water")));
    }
    @Override
    public SimInteraction useItemOn(int state, UseContext context) {
        if (isFamily(context.level(), state, "WeatheringCopperGolemStatueBlock")) {
            var entity = context.level().blockEntityAt(context.clickedPos());
            if (entity == null || !entity.type().equals("minecraft:copper_golem_statue") || context.stack().is("minecraft:honeycomb")) return SimInteraction.PASS;
        }
        if (axes.contains(context.stack().itemKey())) {
            if (isFamily(context.level(), state, "WeatheringCopperGolemStatueBlock")
                && context.level().registry().block(state).bindings().get("CopperGolemStatueBlock.weatheringState").equals("UNAFFECTED")) {
                context.level().setBlock(context.clickedPos(), context.level().fluidAt(context.clickedPos()).createLegacyBlock(), 3);
                return SimInteraction.SUCCESS;
            }
            return SimInteraction.PASS;
        }
        String next = POSES.get((POSES.indexOf(context.level().registry().value(state, "copper_golem_pose")) + 1) % POSES.size());
        context.level().setBlock(context.clickedPos(), context.level().registry().with(state, "copper_golem_pose", next), 3);
        return SimInteraction.SUCCESS;
    }
}
