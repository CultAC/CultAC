package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import java.util.List;

/**
 * Collisions probed once in each world of {@link EntityPositionWorlds}. Each axis holds what any world allows; a move
 * is clipped against one world at a time.
 */
public final class EntityWorldCollisions extends CollideAxisData {
    private final List<CollideAxisData> worlds;
    private final List<List<SimpleCollisionBox>> colliders;

    private EntityWorldCollisions(List<CollideAxisData> worlds, List<List<SimpleCollisionBox>> colliders) {
        super(null, null, null, null, List.of());
        this.worlds = worlds;
        this.colliders = colliders;
        for (int i = 0; i < worlds.size(); i++) {
            CollideAxisData world = worlds.get(i);
            if (world.getX() != null)
                combineX(
                        world.getX().isLikelyCollide(),
                        world.getX().getResult(),
                        world.getX().getSide());
            if (world.getYPos() != null)
                combineYPos(world.getYPos().isLikelyCollide(), world.getYPos().getResult());
            if (world.getYNeg() != null)
                combineYNeg(world.getYNeg().isLikelyCollide(), world.getYNeg().getResult());
            if (world.getZ() != null)
                combineZ(
                        world.getZ().isLikelyCollide(),
                        world.getZ().getResult(),
                        world.getZ().getSide());
        }
    }

    /** The collisions of each world, probed with the entity boxes {@code colliders} holds at the same index. */
    public static EntityWorldCollisions of(List<CollideAxisData> worlds, List<List<SimpleCollisionBox>> colliders) {
        return new EntityWorldCollisions(List.copyOf(worlds), List.copyOf(colliders));
    }

    public List<CollideAxisData> worlds() {
        return worlds;
    }

    /** The entity boxes of each world, at the index of its collisions in {@link #worlds()}. */
    public List<List<SimpleCollisionBox>> colliders() {
        return colliders;
    }
}
