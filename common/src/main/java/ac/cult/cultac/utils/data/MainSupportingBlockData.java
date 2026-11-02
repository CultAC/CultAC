package ac.cult.cultac.utils.data;

import ac.cult.cultac.protocol.value.BlockPos;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.jetbrains.annotations.Nullable;

/**
 * Entity#mainSupportingBlockPos with the ground and Entity#onGroundNoBlocks that Entity#checkSupportingBlock wrote
 * beside it.
 */
@Data
@AllArgsConstructor
public class MainSupportingBlockData {
    @Nullable
    BlockPos blockPos;

    boolean onGround;

    /**
     * Entity#onGroundNoBlocks. A move's support search sets it exactly when it found no block; only
     * Entity#setOnGround(true), which has no movement to repeat the search with, can keep an earlier block beside it.
     */
    boolean noBlocks;

    public MainSupportingBlockData(@Nullable BlockPos blockPos, boolean onGround) {
        this(blockPos, onGround, blockPos == null && onGround);
    }

    public boolean lastOnGroundAndNoBlock() {
        return noBlocks;
    }
}
