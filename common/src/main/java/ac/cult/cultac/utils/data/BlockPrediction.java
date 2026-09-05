package ac.cult.cultac.utils.data;

import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@AllArgsConstructor
@Getter
@Setter
public class BlockPrediction {
    List<BlockPos> forBlockUpdate;
    BlockPos blockPosition;
    int originalBlockId;
    int predictedBlockId;
    Vec3 playerPosition;
}
