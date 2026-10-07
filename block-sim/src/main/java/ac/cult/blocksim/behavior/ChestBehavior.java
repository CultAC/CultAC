package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;
import java.util.Locale;

public class ChestBehavior extends BlockBehavior {
    protected boolean connectsTo(SimLevel level, int state, int neighborState) { return level.registry().sameBlock(state, neighborState); }

    protected static Direction connectedDirection(SimLevel level, int state) {
        return level.registry().value(state, "type").equals("left") ? facing(level, state).clockwise() : facing(level, state).counterClockwise();
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        // Client fluid tick scheduling has no world effect.
        if (connectsTo(level, state, neighborState) && direction.horizontal()) {
            String neighborType = level.registry().value(neighborState, "type");
            if (level.registry().value(state, "type").equals("single") && !neighborType.equals("single")
                && facing(level, state) == facing(level, neighborState) && connectedDirection(level, neighborState) == direction.opposite()) {
                return level.registry().with(state, "type", neighborType.equals("left") ? "right" : "left");
            }
        } else if (connectedDirection(level, state) == direction) return level.registry().with(state, "type", "single");
        return super.updateShape(level, state, pos, direction, neighborPos, neighborState);
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        SimLevel level = context.level(); BlockPos pos = context.clickedPos(); int base = block.defaultState();
        Direction facing = context.horizontalDirection().opposite(), face = context.clickedFace(); String type = "single";
        if (face.horizontal() && context.secondaryUseActive()) {
            Direction partner = candidateFacing(level, base, pos, face.opposite());
            if (partner != null && !partner.axisName().equals(face.axisName())) {
                facing = partner; type = facing.counterClockwise() == face.opposite() ? "right" : "left";
            }
        }
        if (type.equals("single") && !context.secondaryUseActive()) type = chestType(level, base, pos, facing);
        int state = level.registry().with(base, "facing", facing.name().toLowerCase(Locale.ROOT));
        state = level.registry().with(state, "type", type);
        return level.registry().with(state, "waterlogged", Boolean.toString(level.fluidAt(pos).is("minecraft:water")));
    }

    protected String chestType(SimLevel level, int state, BlockPos pos, Direction facing) {
        if (facing == candidateFacing(level, state, pos, facing.clockwise())) return "left";
        return facing == candidateFacing(level, state, pos, facing.counterClockwise()) ? "right" : "single";
    }

    private Direction candidateFacing(SimLevel level, int state, BlockPos pos, Direction direction) {
        int neighbor = level.stateAt(pos.relative(direction));
        return connectsTo(level, state, neighbor) && level.registry().value(neighbor, "type").equals("single") ? facing(level, neighbor) : null;
    }

    @Override
    public SimInteraction useWithoutItem(int state, UseContext context) {
        // Menu opening, statistics and piglin anger are guarded by ServerLevel.
        return SimInteraction.SUCCESS;
    }
}
