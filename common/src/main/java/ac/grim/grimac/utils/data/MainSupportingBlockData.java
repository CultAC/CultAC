package ac.grim.grimac.utils.data;

import net.minecraft.core.BlockPos;
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
