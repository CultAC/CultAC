package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;

/** MCP 26.3 NetherPortalBlock.updateShape: only changes in the portal plane require a complete frame. */
public final class NetherPortalBehavior extends BlockBehavior {
    private final PortalFrame frames;
    public NetherPortalBehavior(DataTables data) {
        frames = new PortalFrame(data.tags().get("block:minecraft:nether_portal_frame"), data.tags().get("block:minecraft:fire"));
    }
    @Override public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        String axis = level.registry().value(state, "axis");
        boolean wrongAxis = !axis.equals(direction.axisName()) && direction.horizontal();
        return !wrongAxis && !level.registry().sameBlock(state, neighborState) && !frames.isComplete(level, pos, axis)
            ? level.registry().block("minecraft:air").defaultState() : state;
    }
}
