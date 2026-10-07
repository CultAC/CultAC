package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Set;

public final class LeavesBehavior extends BlockBehavior {
    private final Set<String> preventsDecay;
    private final int propertyTemplate;
    public LeavesBehavior(Set<String> preventsDecay, int propertyTemplate) { this.preventsDecay = Set.copyOf(preventsDecay); this.propertyTemplate = propertyTemplate; }

    private int optionalDistance(SimLevel level, int state) {
        if (preventsDecay.contains(level.registry().block(state).key())) return 0;
        return level.registry().hasSameProperty(state, propertyTemplate, "distance") ? number(level, state, "distance") : -1;
    }

    private int distance(SimLevel level, int state) { int distance = optionalDistance(level, state); return distance < 0 ? 7 : distance; }

    private int updateDistance(SimLevel level, int state, BlockPos pos) {
        int distance = 7;
        for (Direction direction : Direction.values()) {
            distance = Math.min(distance, distance(level, level.stateAt(pos.relative(direction))) + 1);
            if (distance == 1) break;
        }
        return level.registry().with(state, "distance", Integer.toString(distance));
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        var level = context.level(); int state = level.registry().with(block.defaultState(), "persistent", "true");
        state = level.registry().with(state, "waterlogged", Boolean.toString(level.fluidAt(context.clickedPos()).is("minecraft:water")));
        return updateDistance(level, state, context.clickedPos());
    }

    @Override
    public VoxelShape supportShape(SimLevel level, int state, BlockPos pos) { return Shapes.empty(); }
}
