package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.blockplace.ClientBlockActions;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;

public class BlockBreakSpeed {

    public static double getBlockDamage(CultPlayer player, BlockPos position) {
        return getBlockDamage(player, player.compensatedWorld.getBlockStateIdAt(position));
    }

    public static double getBlockDamage(CultPlayer player, int block) {
        return getBlockDamage(player, player.getInventory().getHeldItem(), block);
    }

    public static boolean couldInstantlyBreakBlock(CultPlayer player, int block) {
        for (int slot = 0; slot < 9; slot++) {
            SimItemStack stack =
                    player.getInventory().inventory.getInventoryStorage().getItem(Inventory.HOTBAR_OFFSET + slot);
            if (getBlockDamage(player, stack, block) >= 1.0D) return true;
        }
        return false;
    }

    /** BlockBehaviour#getDestroyProgress with the bundled simulator's Player#getDestroySpeed. */
    public static double getBlockDamage(CultPlayer player, SimItemStack tool, int block) {
        if (player.gamemode == GameMode.CREATIVE) {
            if (tool != null && ItemUtil.isSword(tool.getItem())) {
                return 0;
            }
            return 1;
        }

        var binding = ClientBlockActions.binding(player);
        return binding.simulator()
                .miningSpeed()
                .progress(
                        tool,
                        binding.states().toModel(block),
                        ClientMiningState.of(player),
                        ClientMiningState.fatigueForm(player, binding));
    }
}
