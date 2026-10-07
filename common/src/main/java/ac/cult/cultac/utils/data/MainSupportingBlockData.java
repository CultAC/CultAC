package ac.cult.cultac.utils.data;

import ac.cult.cultac.protocol.value.BlockPos;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.jetbrains.annotations.Nullable;

@Data
@AllArgsConstructor
public class MainSupportingBlockData {
    @Nullable
    BlockPos blockPos;

    boolean onGround;

    public boolean lastOnGroundAndNoBlock() {
        return blockPos == null && onGround;
    }
}
