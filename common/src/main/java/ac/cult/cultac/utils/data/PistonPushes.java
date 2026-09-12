package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import org.bukkit.block.BlockFace;
import lombok.Data;

import java.util.Set;

@Data
public class PistonPushes {
    SimpleCollisionBox push;
    SimpleCollisionBox pistonPush;
    SimpleCollisionBox shulkerPush;
    Set<BlockFace> slimeBlockLaunches;
    // The piston snapshot already represents the pass visible to this movement.
    boolean pistonMovementPhased;

    public PistonPushes(SimpleCollisionBox push, Set<BlockFace> slimeBlockLaunches) {
        this(push, push.copy(), new SimpleCollisionBox(), slimeBlockLaunches);
    }

    public PistonPushes(SimpleCollisionBox push, SimpleCollisionBox pistonPush, SimpleCollisionBox shulkerPush, Set<BlockFace> slimeBlockLaunches) {
        this.push = push;
        this.pistonPush = pistonPush;
        this.shulkerPush = shulkerPush;
        this.slimeBlockLaunches = slimeBlockLaunches;
    }
}
