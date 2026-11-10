package ac.cult.cultac.bedrock.player;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.function.Consumer;

/**
 * The actors the Bedrock client's translator creates with no server entity behind them, as the client has processed
 * them. One that carries the collidable flag is a solid box other actors collide with: Geyser builds its world
 * border from two invisible ones, 10 wide and 25 tall, whose face lies on the border.
 */
public final class BedrockClientActors {
    private final Long2ObjectOpenHashMap<Actor> actors = new Long2ObjectOpenHashMap<>();

    /** The client spawned the actor, or snapped it to a position, in the frame's local coordinates. */
    public void place(long runtimeId, float x, float y, float z, BedrockCoordinateFrame frame) {
        Actor actor = actors.computeIfAbsent(runtimeId, ignored -> new Actor());
        actor.x = x;
        actor.y = y;
        actor.z = z;
        actor.frame = frame;
    }

    /** Actor data the client processed; a null value was not part of it. */
    public void shape(long runtimeId, Float width, Float height, Boolean collidable) {
        Actor actor = actors.get(runtimeId);
        if (actor == null) return;
        if (width != null) actor.width = width;
        if (height != null) actor.height = height;
        if (collidable != null) actor.collidable = collidable;
    }

    public void remove(long runtimeId) {
        actors.remove(runtimeId);
    }

    public void clear() {
        actors.clear();
    }

    /** Passes the box of every collidable actor that touches {@code reach}, in world coordinates. */
    public void colliders(SimpleCollisionBox reach, Consumer<SimpleCollisionBox> out) {
        if (actors.isEmpty()) return;
        for (Actor actor : actors.values()) {
            SimpleCollisionBox box = actor.box();
            if (box != null && box.isCollided(reach)) out.accept(box);
        }
    }

    private static final class Actor {
        private float x;
        private float y;
        private float z;
        private float width;
        private float height;
        private boolean collidable;
        private BedrockCoordinateFrame frame = BedrockCoordinateFrame.IDENTITY;

        /** The client's box: half its width to each side of its position, single precision in its own frame. */
        SimpleCollisionBox box() {
            if (!collidable || !(width > 0.0F) || !(height > 0.0F)) return null;
            float half = width / 2.0F;
            return new SimpleCollisionBox(
                    (double) (x - half) + frame.originX(),
                    y,
                    (double) (z - half) + frame.originZ(),
                    (double) (x + half) + frame.originX(),
                    y + height,
                    (double) (z + half) + frame.originZ());
        }
    }
}
