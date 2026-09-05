package ac.cult.cultac.utils.blockplace;

import ac.cult.cultac.player.CultPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.bukkit.block.data.BlockData;

import java.util.List;

// Netty-thread-only break simulator. This path must never read live world state.
public final class NmsBlockBreakResolver {
    private static final int BREAK_FLAGS = Block.UPDATE_ALL;
    private static final int MAX_UPDATE_DEPTH = 512;

    private NmsBlockBreakResolver() {
    }

    public static boolean applyBlockBreak(CultPlayer player, BlockPos blockPosition) {
        PlacementSnapshot snapshot = PlacementSnapshot.captureBreak(player, blockPosition);
        PlacementResult result = simulateBreak(
                PlacementBlockAccess.fromCompensatedWorld(player.compensatedWorld),
                snapshot,
                blockPosition
        );
        if (!result.isSuccess()) {
            if (player.debugBreaks && result.getResyncReason() != null) {
                player.sendMessage("Resolved break failed: " + result.getResyncReason());
            }
            return false;
        }

        if (player.debugBreaks) {
            logResolvedChangeSet(player, result.getChangedBlocks());
        }

        for (PlacementResult.ChangedBlock changedBlock : result.getChangedBlocks()) {
            BlockData data = ac.cult.cultac.network.protocol.util.SpigotConversionUtil.fromNmsBlockState(changedBlock.state());
            player.compensatedWorld.updateBlock(changedBlock.position(), data);
        }
        return true;
    }

    static PlacementResult simulateBreak(PlacementBlockAccess blockAccess, PlacementSnapshot snapshot, BlockPos blockPosition) {
        PlacementWorldAdapter world = PlacementWorldFactory.create(blockAccess, snapshot);
        BlockPos pos = blockPosition.immutable();
        if (snapshot.isOutsideBuildHeight(pos)) {
            return PlacementResult.failed("outside build height");
        }

        BlockState oldState = world.getBlockState(pos);
        if (!world.setBlock(pos, replacementAfterBreak(oldState), BREAK_FLAGS, MAX_UPDATE_DEPTH)) {
            return PlacementResult.failed("virtual setBlock failed");
        }
        return world.buildResult(pos);
    }

    private static BlockState replacementAfterBreak(BlockState oldState) {
        FluidState fluidState = oldState.getFluidState();
        return fluidState.isSourceOfType(Fluids.WATER) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    private static void logResolvedChangeSet(CultPlayer player, List<PlacementResult.ChangedBlock> changedBlocks) {
        if (changedBlocks.isEmpty()) {
            player.sendMessage("Break resolved: no world changes");
            return;
        }

        int index = 0;
        for (PlacementResult.ChangedBlock changedBlock : changedBlocks) {
            BlockData data = ac.cult.cultac.network.protocol.util.SpigotConversionUtil.fromNmsBlockState(changedBlock.state());
            player.sendMessage("Break resolved[" + index + "] " + data.getAsString(false) + " at " + changedBlock.position());
            index++;
        }
    }
}
