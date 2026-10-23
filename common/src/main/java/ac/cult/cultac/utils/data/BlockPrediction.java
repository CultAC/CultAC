package ac.cult.cultac.utils.data;

import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BlockPrediction {
    List<BlockPos> forBlockUpdate;
    BlockPos blockPosition;
    // BlockStatePredictionHandler's retained state, which the ack restores.
    int originalBlockId;
    int predictedBlockId;
    Vec3 playerPosition;
    int sequence;
    // The newest state the server sent here. A chunk resend replaces it without touching the retained state.
    int serverBlockId;

    public BlockPrediction(
            List<BlockPos> forBlockUpdate,
            BlockPos blockPosition,
            int originalBlockId,
            int predictedBlockId,
            Vec3 playerPosition,
            int sequence) {
        this.forBlockUpdate = forBlockUpdate;
        this.blockPosition = blockPosition;
        this.originalBlockId = originalBlockId;
        this.predictedBlockId = predictedBlockId;
        this.playerPosition = playerPosition;
        this.sequence = sequence;
        this.serverBlockId = originalBlockId;
    }

    public BlockPrediction(
            List<BlockPos> forBlockUpdate,
            BlockPos blockPosition,
            int originalBlockId,
            int predictedBlockId,
            Vec3 playerPosition) {
        this(forBlockUpdate, blockPosition, originalBlockId, predictedBlockId, playerPosition, 0);
    }
}
