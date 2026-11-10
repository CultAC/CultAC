package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.debug.DebugText;
import lombok.Data;

/** The Bedrock engine's piston and shulker push envelope for one packet: each box holds every shove it allows. */
@Data
public class PistonPushes implements DebugText.Recordable {
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

    @Override
    public void appendTo(DebugText.Builder sb) {
        sb.append("PistonPushes(push=").append(push);
        sb.append(", pistonPush=").append(pistonPush);
        sb.append(", shulkerPush=").append(shulkerPush);
        sb.append(')');
    }

    @Override
    public String toString() {
        return DebugText.print(this);
    }

    private static SimpleCollisionBox empty() {
        return new SimpleCollisionBox();
    }
}
