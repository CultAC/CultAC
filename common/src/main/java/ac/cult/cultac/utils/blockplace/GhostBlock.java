package ac.cult.cultac.utils.blockplace;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.protocol.value.BlockPos;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class GhostBlock {

    private final BlockPos position;
    private final SimItemStack itemUsed;
    private final boolean placeTypeBlock;

    public boolean isPlaceTypeBlock() {
        return placeTypeBlock;
    }
}
