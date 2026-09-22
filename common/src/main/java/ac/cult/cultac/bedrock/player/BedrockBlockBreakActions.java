package ac.cult.cultac.bedrock.player;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.blockplace.NmsBlockBreakResolver;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.GameMasterBlock;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.bukkit.GameMode;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;

public final class BedrockBlockBreakActions {
    private BlockPos breaking;

    public void clear() {
        breaking = null;
    }

    public void apply(CultPlayer player, BedrockCoordinateFrame coordinates, List<PlayerBlockActionData> actions) {
        for (var action : actions) {
            if (action.getAction() == org.cloudburstmc.protocol.bedrock.data.PlayerActionType.DROP_ITEM) {
                player.getInventory().dropHeldItem(false);
                continue;
            }
            var local = action.getBlockPosition();
            if (action.getAction() == null || local == null) continue;
            Vec3 world = coordinates.toWorld(new Vec3(local.getX(), local.getY(), local.getZ()));
            BlockPos pos = BlockPos.containing(world);
            if (action.getAction() == org.cloudburstmc.protocol.bedrock.data.PlayerActionType.ABORT_BREAK) {
                breaking = null;
                continue;
            }
            if (action.getFace() < 0 || action.getFace() > 5) continue;
            switch (action.getAction()) {
                case START_BREAK, BLOCK_CONTINUE_DESTROY -> {
                    breaking = pos;
                    if (canDestroy(player, pos) && BlockBreakSpeed.getBlockDamage(player, pos) >= 1.0) {
                        NmsBlockBreakResolver.applyBlockBreak(player, pos);
                        breaking = null;
                    }
                }
                case BLOCK_PREDICT_DESTROY -> {
                    if (!pos.equals(breaking)) continue;
                    breaking = null;
                    if (!canDestroy(player, pos)) continue;
                    NmsBlockBreakResolver.applyBlockBreak(player, pos);
                }
                default -> { }
            }
        }
    }

    private static boolean canDestroy(CultPlayer player, BlockPos pos) {
        var state = player.compensatedWorld.getBlockStateAt(pos);
        return player.gamemode != GameMode.SPECTATOR && !state.isAir()
                && !(state.getBlock() instanceof LiquidBlock)
                && (!(state.getBlock() instanceof GameMasterBlock) || player.canUseGameMasterBlocks())
                && (player.gamemode == GameMode.CREATIVE
                    ? BlockBreakSpeed.getBlockDamage(player, pos) >= 1.0
                    : state.getDestroySpeed(player.compensatedWorld, pos) >= 0);
    }
}
