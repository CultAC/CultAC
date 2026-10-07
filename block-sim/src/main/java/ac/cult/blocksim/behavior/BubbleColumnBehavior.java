package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import java.util.Set;

/** BubbleColumnBlock.canSurvive; fluid pickup still follows the shared bucket mechanic. */
public final class BubbleColumnBehavior extends BlockPickupBehavior {
    private final Set<String> up, down;
    public BubbleColumnBehavior(DataTables data) {
        super("minecraft:water_bucket", false);
        up = data.tags().get("block:minecraft:enables_bubble_column_push_up");
        down = data.tags().get("block:minecraft:enables_bubble_column_drag_down");
    }
    @Override public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        String below = level.registry().block(level.stateAt(pos.relative(Direction.DOWN))).key();
        return below.equals("minecraft:bubble_column") || up.contains(below) || down.contains(below);
    }
}
