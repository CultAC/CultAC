package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.data.*;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.interaction.*;
import com.google.gson.JsonPrimitive;

public final class ComparatorBehavior extends DiodeBehavior {
    private final AnalogSignals analog;
    public ComparatorBehavior(DataTables data, ItemRegistry items, InteractionRegistries registries) { analog = new AnalogSignals(data, items, registries); }

    /** ComparatorBlock.useWithoutItem and refreshOutputState run immediately on both sides. */
    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) {
        if (!context.player().state().mayBuild()) return SimInteraction.PASS;
        var level = context.level(); var pos = context.clickedPos();
        boolean subtract = !level.registry().value(state, "mode").equals("subtract");
        state = level.registry().with(state, "mode", subtract ? "subtract" : "compare");
        level.setBlock(pos, state, 2);
        if (!level.registry().sameBlock(state, level.stateAt(pos))) return SimInteraction.SUCCESS;
        int input = inputSignal(level, pos, state), side = input == 0 ? 0 : alternateSignal(level, pos, state, false);
        int output = input == 0 || side > input ? 0 : subtract ? input - side : input;
        var entity = level.blockEntityAt(pos);
        int oldOutput = outputSignal(level, pos, state);
        if (entity != null && entity.type().equals("minecraft:comparator")) {
            var fields = entity.data().with("OutputSignal", new JsonPrimitive(output));
            NbtValue.Compound saved = entity.savedData();
            if (saved != null) {
                var values = new java.util.HashMap<>(saved.values());
                values.put("OutputSignal", new NbtValue.Numeric(NbtValue.Kind.INT, output));
                saved = new NbtValue.Compound(values);
            }
            level.blockEntityAt(pos, new BlockEntityData(entity.type(), fields, saved));
        }
        if (oldOutput != output || !subtract) {
            // Re-read after the entity field changes, matching shouldTurnOn's signal queries.
            int powerInput = inputSignal(level, pos, state);
            int powerSide = powerInput == 0 ? 0 : alternateSignal(level, pos, state, false);
            boolean powered = powerInput > 0 && (powerInput > powerSide || powerInput == powerSide && !subtract);
            if (bool(level, state, "powered") != powered) level.setBlock(pos, level.registry().with(state, "powered", Boolean.toString(powered)), 2);
        }
        return SimInteraction.SUCCESS;
    }

    private int inputSignal(SimLevel level, BlockPos pos, int state) {
        Direction direction = facing(level, state);
        BlockPos target = pos.relative(direction);
        int targetState = level.stateAt(target);
        int input = new Signals(level).signal(target, direction);
        if (input < 15 && level.registry().block(targetState).key().equals("minecraft:redstone_wire")) input = Math.max(input, number(level, targetState, "power"));
        if (analog.hasOutput(level, targetState)) return analog.output(level, targetState, target, direction.opposite());
        if (input >= 15 || !level.isRedstoneConductor(targetState, target)) return input;
        target = target.relative(direction); targetState = level.stateAt(target);
        int frame = level.itemFrameOutputAt(target, direction);
        int block = analog.hasOutput(level, targetState) ? analog.output(level, targetState, target, direction.opposite()) : Integer.MIN_VALUE;
        int combined = Math.max(frame, block);
        return combined == Integer.MIN_VALUE ? input : combined;
    }
    @Override
    protected int outputSignal(SimLevel level, BlockPos pos, int state) {
        var entity = level.blockEntityAt(pos);
        return entity != null && entity.type().equals("minecraft:comparator") ? entity.data().integer("OutputSignal", 0) : 0;
    }
    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return direction == Direction.DOWN && !canSurviveOn(level, neighborPos, neighborState) ? level.registry().block("minecraft:air").defaultState()
            : super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }
}
