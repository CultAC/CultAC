package ac.cult.cultac.utils.blockplace;

import lombok.Getter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.List;

@Getter
public final class PlacementResult {
    private final boolean success;
    private final List<ChangedBlock> changedBlocks;
    private final BlockPos primaryPlacedPosition;
    private final boolean consumeInventory;
    private final String resyncReason;

    private PlacementResult(boolean success, List<ChangedBlock> changedBlocks, BlockPos primaryPlacedPosition, boolean consumeInventory, String resyncReason) {
        this.success = success;
        this.changedBlocks = changedBlocks;
        this.primaryPlacedPosition = primaryPlacedPosition;
        this.consumeInventory = consumeInventory;
        this.resyncReason = resyncReason;
    }

    public static PlacementResult failed(String resyncReason) {
        return new PlacementResult(false, Collections.emptyList(), null, false, resyncReason);
    }

    public static PlacementResult success(List<ChangedBlock> changedBlocks, BlockPos primaryPlacedPosition, boolean consumeInventory) {
        return new PlacementResult(true, List.copyOf(changedBlocks), primaryPlacedPosition, consumeInventory, null);
    }

    public record ChangedBlock(BlockPos position, BlockState state) {
    }
}
