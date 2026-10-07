package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.math.Vec3;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class StuckSpeedData {
    Vec3 stuckSpeedMultiplier;
    Vec3 unknownStuckSpeedMultiplier;
}
