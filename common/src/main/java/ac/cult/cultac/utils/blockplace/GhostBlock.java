package ac.cult.cultac.utils.blockplace;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.protocol.value.BlockPos;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@RequiredArgsConstructor
@Getter
public class GhostBlock {

    private final BlockPos position;
    private final SimItemStack itemUsed;
    private final boolean placeTypeBlock;
    /** Client block prediction sequence of the newest action that clicked this position. */
    @Setter
    private int sequence;

    public boolean isPlaceTypeBlock() {
        return placeTypeBlock;
    }
}
