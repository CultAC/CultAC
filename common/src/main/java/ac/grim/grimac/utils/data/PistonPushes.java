package ac.grim.grimac.utils.data;

import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import org.bukkit.block.BlockFace;
import lombok.Data;

import java.util.Set;

@Data
public class PistonPushes {
    SimpleCollisionBox push;
    SimpleCollisionBox pistonPush;
    SimpleCollisionBox shulkerPush;
    Set<BlockFace> slimeBlockLaunches;

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
