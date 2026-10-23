package ac.cult.cultac.utils.data;

import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.math.Vector3dm;
import lombok.Getter;
import lombok.ToString;

@Getter
@ToString
public class HitData {
    BlockPos position;
    Vector3dm blockHitLocation;
    int state;
    Direction closestDirection;

    public HitData(BlockPos position, Vector3dm blockHitLocation, Direction closestDirection, int state) {
        this.position = position;
        this.blockHitLocation = blockHitLocation;
        this.closestDirection = closestDirection;
        this.state = state;
    }
}
