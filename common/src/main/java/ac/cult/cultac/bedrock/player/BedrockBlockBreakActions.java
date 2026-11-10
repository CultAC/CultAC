package ac.cult.cultac.bedrock.player;

import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.checks.impl.visibility.BedrockBreakVisibility;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.blockplace.ClientBlockActions;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import java.util.List;
import java.util.function.Consumer;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;

public final class BedrockBlockBreakActions {
    private BlockPos breaking;

    public void clear() {
        breaking = null;
    }

    /**
     * Applies the block actions of an auth input in order, in its resolved {@code coordinates}, whose move claims the
     * feet position {@code claimed}, or null when none is judged. One BedrockBreakVisibility judges hidden is removed
     * from {@code actions}, so Geyser never translates it, and handed to {@code dropped} with its position in the
     * client's coordinates, for the bridge to restore the block on the client.
     */
    public void apply(
            CultPlayer player,
            BedrockCoordinateFrame coordinates,
            Vec3 claimed,
            List<PlayerBlockActionData> actions,
            Consumer<Vector3i> dropped) {
        var visibility = player.checkManager.getCheck(BedrockBreakVisibility.class);
        for (var iterator = actions.iterator(); iterator.hasNext(); ) {
            var action = iterator.next();
            if (action.getAction() == org.cloudburstmc.protocol.bedrock.data.PlayerActionType.DROP_ITEM) {
                player.getInventory().dropHeldItem(false);
                continue;
            }
            var local = action.getBlockPosition();
            if (action.getAction() == null || local == null) continue;
            Vec3 world = coordinates.toWorld(new Vec3(local.getX(), local.getY(), local.getZ()));
            BlockPos pos = BlockPos.containing(world.x, world.y, world.z);
            if (action.getAction() == org.cloudburstmc.protocol.bedrock.data.PlayerActionType.ABORT_BREAK) {
                breaking = null;
                continue;
            }
            if (action.getFace() < 0 || action.getFace() > 5) continue;
            boolean breaks = switch (action.getAction()) {
                case START_BREAK, BLOCK_CONTINUE_DESTROY, BLOCK_PREDICT_DESTROY -> true;
                default -> false;
            };
            if (breaks && visibility.dropsAction(action.getAction().name(), pos, action.getFace(), claimed)) {
                iterator.remove();
                if (pos.equals(breaking)) breaking = null;
                dropped.accept(local);
                continue;
            }
            switch (action.getAction()) {
                case START_BREAK, BLOCK_CONTINUE_DESTROY -> {
                    breaking = pos;
                    if (canDestroy(player, pos) && BlockBreakSpeed.getBlockDamage(player, pos) >= 1.0) {
                        ClientBlockActions.breakBlock(player, pos);
                        breaking = null;
                    }
                }
                case BLOCK_PREDICT_DESTROY -> {
                    if (!pos.equals(breaking)) continue;
                    breaking = null;
                    if (!canDestroy(player, pos)) continue;
                    ClientBlockActions.breakBlock(player, pos);
                }
                default -> {}
            }
        }
    }

    private static boolean canDestroy(CultPlayer player, BlockPos pos) {
        int state = player.compensatedWorld.getBlockStateIdAt(pos);
        var facts = DataTables.defaults().registry().facts(state);
        return player.gamemode != GameMode.SPECTATOR
                && !facts.has(StateFacts.AIR)
                && !BlockFamilies.LIQUID.test(state)
                && (!BlockFamilies.GAME_MASTER.test(state) || player.canUseGameMasterBlocks())
                && (player.gamemode == GameMode.CREATIVE
                        ? BlockBreakSpeed.getBlockDamage(player, pos) >= 1.0
                        : facts.destroyTime() >= 0);
    }
}
