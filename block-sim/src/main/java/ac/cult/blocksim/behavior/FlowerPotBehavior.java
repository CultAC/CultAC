package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.*;
import ac.cult.blocksim.interaction.*;
import java.util.HashMap;
import java.util.Map;

public final class FlowerPotBehavior extends BlockBehavior {
    private final ItemRegistry items;
    private final Map<String, Integer> pots = new HashMap<>();
    public FlowerPotBehavior(DataTables data) {
        this(data, new ItemRegistry(data));
    }
    public FlowerPotBehavior(DataTables data, ItemRegistry items) {
        this.items = items;
        for (var block : data.registry().blocks()) {
            String plant = block.bindings().get("FlowerPotBlock.potted");
            if (plant != null) pots.put(plant, block.defaultState());
        }
    }
    private String plant(int state, UseContext context) { return context.level().registry().block(state).bindings().get("FlowerPotBlock.potted"); }
    /** MCP 26.3 FlowerPotBlock.updateShape; canSurvive inherits BlockBehaviour's true default. */
    @Override public int updateShape(ac.cult.blocksim.engine.SimLevel level, int state, ac.cult.blocksim.engine.BlockPos pos,
                                     ac.cult.blocksim.engine.Direction direction, ac.cult.blocksim.engine.BlockPos neighborPos, int neighborState) {
        return direction == ac.cult.blocksim.engine.Direction.DOWN && !canSurvive(level, state, pos)
            ? level.registry().block("minecraft:air").defaultState() : state;
    }
    @Override public SimInteraction useItemOn(int state, UseContext context) {
        Integer pot = pots.get(context.stack().isEmpty() ? null : context.stack().definition().block());
        if (pot == null || context.level().registry().block(pot).key().equals("minecraft:air")) return SimInteraction.TRY_WITH_EMPTY_HAND;
        if (!plant(state, context).equals("minecraft:air")) return SimInteraction.CONSUME;
        context.level().setBlock(context.clickedPos(), pot, 3);
        context.stack().consume(1, context.player());
        return SimInteraction.SUCCESS;
    }
    @Override public SimInteraction useWithoutItem(int state, UseContext context) {
        String plant = plant(state, context);
        if (plant.equals("minecraft:air")) return SimInteraction.CONSUME;
        String item = context.level().registry().block(plant).bindings().get("asItem");
        context.player().inventory().add(items.stack(item, 1));
        context.level().setBlock(context.clickedPos(), context.level().registry().block("minecraft:flower_pot").defaultState(), 3);
        return SimInteraction.SUCCESS;
    }
}
