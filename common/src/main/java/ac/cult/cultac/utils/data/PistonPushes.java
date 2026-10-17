package ac.cult.cultac.utils.data;

import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.Set;
import lombok.Data;

@Data
public class PistonPushes {
    SimpleCollisionBox push;
    SimpleCollisionBox pistonPush;
    SimpleCollisionBox shulkerPush;
    Set<Direction> slimeBlockLaunches;

    public PistonPushes(SimpleCollisionBox push, Set<Direction> slimeBlockLaunches) {
        this(push, push.copy(), new SimpleCollisionBox(), slimeBlockLaunches);
    }

    public PistonPushes(
            SimpleCollisionBox push,
            SimpleCollisionBox pistonPush,
            SimpleCollisionBox shulkerPush,
            Set<Direction> slimeBlockLaunches) {
        this.push = push;
        this.pistonPush = pistonPush;
        this.shulkerPush = shulkerPush;
        this.slimeBlockLaunches = slimeBlockLaunches;
    }
}
