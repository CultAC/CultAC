package ac.cult.cultac.utils.data;

import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@AllArgsConstructor
@Getter
@Setter
public class BlockPrediction {
    List<BlockPos> forBlockUpdate;
    BlockPos blockPosition;
    int originalBlockId;
    int predictedBlockId;
    Vec3 playerPosition;
    int sequence;

    public BlockPrediction(
            List<BlockPos> forBlockUpdate,
            BlockPos blockPosition,
            int originalBlockId,
            int predictedBlockId,
            Vec3 playerPosition) {
        this(forBlockUpdate, blockPosition, originalBlockId, predictedBlockId, playerPosition, 0);
    }
}
