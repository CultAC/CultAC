package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import lombok.Data;

/** The Bedrock engine's piston and shulker push envelope for one packet: each box holds every shove it allows. */
@Data
public class PistonPushes {
    /** The empty envelope Java movement carries: it replays the client's block-entity passes instead. */
    public static final PistonPushes NONE = new PistonPushes(empty(), empty(), empty());

    final SimpleCollisionBox push;
    final SimpleCollisionBox pistonPush;
    final SimpleCollisionBox shulkerPush;

    public PistonPushes(SimpleCollisionBox push, SimpleCollisionBox pistonPush, SimpleCollisionBox shulkerPush) {
        this.push = push;
        this.pistonPush = pistonPush;
        this.shulkerPush = shulkerPush;
    }

    private static SimpleCollisionBox empty() {
        return new SimpleCollisionBox();
    }
}
