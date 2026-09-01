package ac.grim.grimac.utils.data;

import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class StuckEdgeData {
    boolean north, south, east, west, zeroHorizBug;

    public boolean isOnEdge() {
        return north || south || east || west;
    }
}
