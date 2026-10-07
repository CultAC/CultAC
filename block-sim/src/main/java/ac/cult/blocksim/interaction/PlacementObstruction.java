package ac.cult.blocksim.interaction;

/** The existing entity/player obstruction check is supplied by the platform adapter. */
@FunctionalInterface
public interface PlacementObstruction {
    enum CollisionContext { EMPTY, PLACEMENT }
    boolean isUnobstructed(PlacementContext context, int state, CollisionContext collisionContext);
}
