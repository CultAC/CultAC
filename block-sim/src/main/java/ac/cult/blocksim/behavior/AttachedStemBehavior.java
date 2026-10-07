package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;

public final class AttachedStemBehavior extends VegetationBehavior {
    private final DataTables data;
    public AttachedStemBehavior(DataTables data) { super(data.tags().get("block:minecraft:supports_vegetation")); this.data = data; }

    @Override
    protected boolean mayPlaceOn(SimLevel level, int plantState, int supportState, BlockPos supportPos) {
        var bindings = level.registry().block(plantState).bindings();
        return data.tags().get("block:" + bindings.get("AttachedStemBlock.supportBlocks")).contains(level.registry().block(supportState).key());
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        var bindings = level.registry().block(state).bindings();
        if (!level.registry().block(neighborState).key().equals(bindings.get("AttachedStemBlock.fruit")) && direction == facing(level, state)) {
            int stem = level.registry().block(bindings.get("AttachedStemBlock.stem")).defaultState();
            return level.registry().withIfValid(stem, "age", "7");
        }
        return super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }
}
