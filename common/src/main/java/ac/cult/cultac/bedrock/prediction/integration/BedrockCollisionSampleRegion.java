package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;

/** The part of the world one tick's block collision sample covers. */
record BedrockCollisionSampleRegion(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    private static final double CARRIED_MOVE_MARGIN = 2.0D;
    private static final double MAX_MOVE_LENGTH = 16.0D;
    // A shape can leave its own block (a fence is 1.5 tall), so a fetch needs the blocks around it too.
    private static final double SHAPE_REACH = 1.0D;

    /** Covers a move at the speed the actor carried into the tick, before any impulse of this tick. */
    static BedrockCollisionSampleRegion around(SimpleCollisionBox actorBox, double carriedSpeed) {
        double reach = Double.isFinite(carriedSpeed)
                ? Math.max(CARRIED_MOVE_MARGIN, Math.min(MAX_MOVE_LENGTH, carriedSpeed + CARRIED_MOVE_MARGIN))
                : CARRIED_MOVE_MARGIN;
        return new BedrockCollisionSampleRegion(
                actorBox.minX - reach,
                actorBox.minY - reach,
                actorBox.minZ - reach,
                actorBox.maxX + reach,
                actorBox.maxY + reach,
                actorBox.maxZ + reach);
    }

    /** Whether every shape a move with this fetch box can touch is in the sample. */
    boolean holds(WorldCollisionBox fetched) {
        return fetched.minX() - SHAPE_REACH >= minX
                && fetched.minY() - SHAPE_REACH >= minY
                && fetched.minZ() - SHAPE_REACH >= minZ
                && fetched.maxX() + SHAPE_REACH <= maxX
                && fetched.maxY() + SHAPE_REACH <= maxY
                && fetched.maxZ() + SHAPE_REACH <= maxZ;
    }

    BedrockCollisionSampleRegion including(WorldCollisionBox fetched) {
        return new BedrockCollisionSampleRegion(
                Math.min(minX, fetched.minX() - SHAPE_REACH),
                Math.min(minY, fetched.minY() - SHAPE_REACH),
                Math.min(minZ, fetched.minZ() - SHAPE_REACH),
                Math.max(maxX, fetched.maxX() + SHAPE_REACH),
                Math.max(maxY, fetched.maxY() + SHAPE_REACH),
                Math.max(maxZ, fetched.maxZ() + SHAPE_REACH));
    }

    SimpleCollisionBox toQueryBox() {
        return new SimpleCollisionBox(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
