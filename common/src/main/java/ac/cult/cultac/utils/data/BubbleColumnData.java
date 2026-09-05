package ac.cult.cultac.utils.data;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BubbleColumnData {
    int upAir, up, downAir, down;

    public boolean hasBubbleColumn() {
        return upAir > 0 || up > 0 || downAir > 0 || down > 0;
    }
}
