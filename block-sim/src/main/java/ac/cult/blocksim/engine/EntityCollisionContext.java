package ac.cult.blocksim.engine;

/** Only the entity facts read by client block collision queries. */
public record EntityCollisionContext(double bottom, boolean descending, double fallDistance,
                                     boolean powderSnowWalkable, boolean placement, String heldItem,
                                     boolean alwaysCollideWithFluid, boolean fallingBlock, boolean empty) {
    private static final EntityCollisionContext EMPTY = new EntityCollisionContext(
            -Double.MAX_VALUE, false, 0, false, false, "minecraft:air", false, false, true);

    public EntityCollisionContext(double bottom, boolean descending, double fallDistance,
                                  boolean powderSnowWalkable, boolean placement) {
        this(bottom, descending, fallDistance, powderSnowWalkable, placement, null, false, false, false);
    }

    public static EntityCollisionContext emptyContext() { return EMPTY; }
    public static EntityCollisionContext position(double bottom) {
        return new EntityCollisionContext(bottom, false, 0, false, false);
    }

    public boolean isHoldingItem(String item) { return item.equals(heldItem); }

    // EntityCollisionContext.isAbove compares against the shape's top with this tolerance.
    public boolean isAbove(double shapeTop, BlockPos pos) { return isAbove(shapeTop, pos, false); }
    public boolean isAbove(double shapeTop, BlockPos pos, boolean defaultValue) {
        return empty ? defaultValue : bottom > pos.y() + shapeTop - 1.0E-5F;
    }
}
