package ac.cult.blocksim.interaction;

import ac.cult.blocksim.behavior.ItemBehaviorRegistry;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimPlayer;

/** MultiPlayerGameMode.destroyBlock. Tick progress and packet acknowledgement are
 * separate from this ordered, action-local world mutation. */
public final class BreakDriver {
    private final ItemBehaviorRegistry items;
    private final AdventureRuleMatcher adventure;

    public BreakDriver(ItemBehaviorRegistry items, AdventureRuleMatcher adventure) {
        this.items = java.util.Objects.requireNonNull(items);
        this.adventure = java.util.Objects.requireNonNull(adventure);
    }

    public boolean restricted(SimLevel level, SimPlayer player, BlockPos pos) {
        return switch (player.state().gameMode()) {
            case SURVIVAL, CREATIVE -> false;
            case SPECTATOR -> true;
            case ADVENTURE -> !player.state().mayBuild() && (player.hand(Hand.MAIN_HAND).isEmpty()
                || !adventure.test(player.hand(Hand.MAIN_HAND), "minecraft:can_break", level, pos));
        };
    }

    public boolean destroyBlock(SimLevel level, SimPlayer player, BlockPos pos) {
        if (restricted(level, player, pos)) return false;
        int oldState = level.stateAt(pos);
        if (!items.canDestroyBlock(player.hand(Hand.MAIN_HAND), player)) return false;
        var block = level.registry().block(oldState);
        if (block.bindings().get("classInterfaces").contains("net.minecraft.world.level.block.GameMasterBlock") && !player.state().gameMaster()) return false;
        if (level.registry().facts(oldState).has(StateFacts.AIR)) return false;
        var behavior = level.behavior(oldState);
        behavior.playerWillDestroy(level, oldState, pos, player);
        boolean changed = level.setBlock(pos, level.fluidAt(pos).createLegacyBlock(), 11);
        if (changed) behavior.destroy(level, oldState, pos);
        return changed;
    }
}
