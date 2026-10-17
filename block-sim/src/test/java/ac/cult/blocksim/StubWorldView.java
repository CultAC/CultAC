package ac.cult.blocksim;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.engine.SimWorldView;
import ac.cult.blocksim.engine.Vec3;

/**
 * Test worlds answer only the queries their scenario exercises; any other query fails the test.
 * Production adapters implement SimWorldView directly, where every query is required.
 */
public interface StubWorldView extends SimWorldView {
    @Override default int seaLevel() { throw new IllegalStateException("Missing compensated sea level"); }
    @Override default String biomeKeyAt(BlockPos pos) { throw new IllegalStateException("Missing compensated biome lookup"); }
    @Override default String dimensionKey() { throw new IllegalStateException("Ignition requires the compensated dimension key"); }
    @Override default java.util.Set<String> recipeInputs(String key) { throw new IllegalStateException("Missing compensated recipe inputs: " + key); }
    @Override default Vec3 borderHit(Vec3 from, Vec3 to) { throw new IllegalStateException("Missing compensated border ray query"); }
    @Override default boolean hasPickableEntityHit(Vec3 from, Vec3 to, SimPlayer player) {
        throw new IllegalStateException("Missing compensated pickable-entity query");
    }
    @Override default int itemFrameOutputAt(BlockPos pos, Direction direction) { throw new IllegalStateException("Missing compensated item-frame query"); }
    @Override default int detectorRailOutputAt(BlockPos pos) { throw new IllegalStateException("Missing compensated minecart query"); }
    @Override default boolean hasSittingCatAt(BlockPos pos) { throw new IllegalStateException("Missing compensated cat query"); }
    @Override default boolean canSpawn(ac.cult.blocksim.entity.EntityTypes.Type type) { throw new IllegalStateException("Missing compensated spawn conditions"); }
    @Override default boolean hasFeature(String key) { throw new IllegalStateException("Missing received feature flags"); }
    @Override default boolean hasMinecartIn(ac.cult.blocksim.data.Box box) { throw new IllegalStateException("Missing compensated minecart occupancy"); }
    @Override default boolean hasEntityIn(ac.cult.blocksim.data.Box box) { throw new IllegalStateException("Missing compensated entity occupancy"); }
    @Override default boolean hasEntityCollision(ac.cult.blocksim.data.Box box, boolean boatSource) {
        throw new IllegalStateException("Missing compensated entity collision query");
    }
    @Override default boolean isUnobstructed(ac.cult.blocksim.engine.shapes.VoxelShape shape) {
        throw new IllegalStateException("Missing compensated building obstruction query");
    }
    @Override default boolean hasPickableEntityAtEye(SimPlayer player) { throw new IllegalStateException("Missing compensated boat eye query"); }
    @Override default boolean hasBorderCollision(ac.cult.blocksim.data.Box box, Vec3 source) { throw new IllegalStateException("Missing compensated entity border query"); }
    @Override default java.util.List<ac.cult.blocksim.entity.PaintingSize> placeablePaintings() { throw new IllegalStateException("Missing received placeable painting variants"); }
    @Override default ac.cult.blocksim.entity.PaintingSize paintingSize(com.google.gson.JsonElement holder) { throw new IllegalStateException("Missing received painting variant"); }
    @Override default boolean hasHangingEntityOverlap(ac.cult.blocksim.data.Box box, Direction direction, String type, boolean allowSameType) {
        throw new IllegalStateException("Missing compensated hanging-entity query");
    }
    @Override default ac.cult.blocksim.engine.shapes.VoxelShape movingPistonCollisionAt(BlockPos pos) {
        throw new IllegalStateException("Missing compensated moving-piston collision query");
    }
}
