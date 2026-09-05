package ac.cult.cultac.utils.blockplace;

import org.bukkit.inventory.ItemStack;
import net.minecraft.core.BlockPos;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class GhostBlock {

    private final BlockPos position;
    private final ItemStack itemUsed;
    private final boolean placeTypeBlock;

    public boolean isPlaceTypeBlock() { return placeTypeBlock; }

}
