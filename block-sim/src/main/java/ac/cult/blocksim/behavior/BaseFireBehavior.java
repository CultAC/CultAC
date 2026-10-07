package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Set;

public class BaseFireBehavior extends BlockBehavior {
    private final Set<String> soulFireBases;
    public BaseFireBehavior(Set<String> soulFireBases) { this.soulFireBases = Set.copyOf(soulFireBases); }

    protected boolean soulFireBase(SimLevel level, int state) { return soulFireBases.contains(level.registry().block(state).key()); }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) { return fireState(context.level(), context.clickedPos()); }

    public int fireState(SimLevel level, BlockPos pos) {
        if (soulFireBase(level, level.stateAt(pos.relative(Direction.DOWN)))) return level.registry().block("minecraft:soul_fire").defaultState();
        var fire = level.registry().block("minecraft:fire");
        return ((FireBehavior) level.behavior(fire.defaultState())).stateForPlacement(level, fire.defaultState(), pos);
    }
}
