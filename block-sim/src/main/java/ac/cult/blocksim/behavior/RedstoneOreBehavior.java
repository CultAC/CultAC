package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.*;

/** RedStoneOreBlock: attack lights the ore on the client; use emits only particles. */
public final class RedstoneOreBehavior extends BlockBehavior {
    @Override public void attack(SimLevel level, int state, BlockPos pos, SimPlayer player) {
        if (!bool(level, state, "lit")) level.setBlock(pos, level.registry().with(state, "lit", "true"), 3);
    }
    @Override public SimInteraction useItemOn(int state, UseContext context) {
        boolean blockItem = context.usedItem().bindings().get("classHierarchy").contains("net.minecraft.world.item.BlockItem");
        return blockItem && new PlacementContext(context).canPlace() ? SimInteraction.PASS : SimInteraction.SUCCESS;
    }
}
