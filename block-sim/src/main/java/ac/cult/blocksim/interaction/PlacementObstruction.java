package ac.cult.blocksim.interaction;

/** The existing entity/player obstruction check is supplied by the platform adapter. */
@FunctionalInterface
public interface PlacementObstruction {
    /** CollisionContext#empty, CollisionContext#of the player, or the 1.21.5+ CollisionContext#placementContext. */
    enum CollisionContext { EMPTY, ENTITY, PLACEMENT }
    boolean isUnobstructed(PlacementContext context, int state, CollisionContext collisionContext);
}
