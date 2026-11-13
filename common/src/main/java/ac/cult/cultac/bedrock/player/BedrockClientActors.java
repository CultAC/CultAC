package ac.cult.cultac.bedrock.player;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.function.Consumer;

/**
 * The actors the Bedrock client's translator creates with no server entity behind them, as the client has processed
 * them. One that carries the collidable flag is a solid box other actors collide with: Geyser builds its world
 * border from two invisible ones, 10 wide and 25 tall, whose face lies on the border once they have reached it.
 *
 * <p>The client spawns an actor at its position and lerps it to each later one over three of its ticks, so the box
 * a move meets is the one the last tick's step left.
 */
public final class BedrockClientActors {
    private static final int LERP_STEPS = 3;

    // Spawn order, which is the order colliders are passed in.
    private final Long2ObjectLinkedOpenHashMap<Actor> actors = new Long2ObjectLinkedOpenHashMap<>();

    /** The client spawned the actor at a position in the frame's local coordinates. */
    public void place(long runtimeId, float x, float y, float z, BedrockCoordinateFrame frame) {
        Actor actor = actors.computeIfAbsent(runtimeId, ignored -> new Actor());
        actor.x = actor.targetX = actor.placedX = x;
        actor.y = actor.targetY = actor.placedY = y;
        actor.z = actor.targetZ = actor.placedZ = z;
        actor.frame = frame;
        actor.stepsLeft = 0;
    }

    /** The client handled a move of the actor: it lerps there from where it is, restarting the count. */
    public void move(long runtimeId, float x, float y, float z, BedrockCoordinateFrame frame) {
        Actor actor = actors.get(runtimeId);
        if (actor == null) return;
        if (!frame.equals(actor.frame)) {
            actor.x = frame.localX((double) actor.x + actor.frame.originX());
            actor.z = frame.localZ((double) actor.z + actor.frame.originZ());
            actor.frame = frame;
        }
        actor.targetX = x;
        actor.targetY = y;
        actor.targetZ = z;
        actor.stepsLeft = LERP_STEPS;
    }

    /** The lerp step of a client tick, which the client takes after the player's move. */
    public void tick() {
        if (actors.isEmpty()) return;
        for (Actor actor : actors.values()) {
            if (actor.stepsLeft == 0) continue;
            actor.x = PacketEntity.bedrockLerp(actor.stepsLeft, actor.x, actor.targetX);
            actor.y = PacketEntity.bedrockLerp(actor.stepsLeft, actor.y, actor.targetY);
            actor.z = PacketEntity.bedrockLerp(actor.stepsLeft, actor.z, actor.targetZ);
            actor.stepsLeft--;
        }
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

    /** A collidable actor's id, box, and where it was spawned, last sent to and is shown, in world coordinates. */
    public interface Collider {
        void accept(
                long runtimeId,
                SimpleCollisionBox box,
                ac.cult.cultac.utils.math.Vec3 placed,
                ac.cult.cultac.utils.math.Vec3 sent,
                ac.cult.cultac.utils.math.Vec3 shown,
                int stepsLeft);
    }

    /** Passes every collidable actor that touches {@code reach}, with the position it was sent to and is shown at. */
    public void colliders(SimpleCollisionBox reach, Collider out) {
        if (actors.isEmpty()) return;
        for (var entry : actors.long2ObjectEntrySet()) {
            Actor actor = entry.getValue();
            SimpleCollisionBox box = actor.box();
            if (box == null || !box.isCollided(reach)) continue;
            int ox = actor.frame.originX(), oz = actor.frame.originZ();
            out.accept(
                    entry.getLongKey(),
                    box,
                    new ac.cult.cultac.utils.math.Vec3(
                            (double) actor.placedX + ox, actor.placedY, (double) actor.placedZ + oz),
                    new ac.cult.cultac.utils.math.Vec3(
                            (double) actor.targetX + ox, actor.targetY, (double) actor.targetZ + oz),
                    new ac.cult.cultac.utils.math.Vec3((double) actor.x + ox, actor.y, (double) actor.z + oz),
                    actor.stepsLeft);
        }
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
        private float targetX;
        private float targetY;
        private float targetZ;
        // Where the client spawned it, the first position of its history.
        private float placedX;
        private float placedY;
        private float placedZ;
        private int stepsLeft;
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
