package ac.cult.blocksim.engine;

/** The proven client-visible world. All state IDs belong to the input version. */
public interface SimWorldView {
    int stateAt(BlockPos pos);
    default StatePossibilities statePossibilitiesAt(BlockPos pos) { return StatePossibilities.exact(stateAt(pos)); }
    boolean isLoaded(BlockPos pos);
    /** A known empty client chunk is readable even though it cannot accept writes. */
    default boolean isReadable(BlockPos pos) { return isLoaded(pos); }
    boolean isSectionEmpty(BlockPos pos);
    int minY();
    int height();
    /** Sent in CommonPlayerSpawnInfo when the client creates this level. */
    default int seaLevel() { throw new IllegalStateException("Missing compensated sea level"); }
    /** BiomeManager.getBiome, including its zoom seed, received palettes and unloaded-chunk fallback. */
    default String biomeKeyAt(BlockPos pos) { throw new IllegalStateException("Missing compensated biome lookup"); }
    /** The current client world-border test, including a border transition at this tick. */
    boolean isWithinBorder(BlockPos pos);
    /** The received dimension key, independent of its dimension-type attributes. */
    default String dimensionKey() { throw new IllegalStateException("Ignition requires the compensated dimension key"); }
    /** Recipe property sets are replaced by the received update-recipes packet. */
    default java.util.Set<String> recipeInputs(String key) { throw new IllegalStateException("Missing compensated recipe inputs: " + key); }
    /** Resolved from the client's synced dimension/biome/timeline attributes at this tick. */
    boolean creakingActiveAt(BlockPos pos);
    /** WATER_EVAPORATES at this position, resolved from received dimension, biome and timeline data. */
    boolean waterEvaporatesAt(BlockPos pos);
    default int rawBrightnessAt(BlockPos pos) { throw new IllegalStateException("Missing compensated light at " + pos); }
    /** Null means the client has no block entity at this position. */
    BlockEntityData blockEntityAt(BlockPos pos);
    /** CollisionGetter.clipIncludingBorder: null if this segment does not hit the received border. */
    default Vec3 borderHit(Vec3 from, Vec3 to) { throw new IllegalStateException("Missing compensated border ray query"); }
    /** ProjectileUtil view query uses exact pickable-entity boxes, with zero expansion margin. */
    default boolean hasPickableEntityHit(Vec3 from, Vec3 to, SimPlayer player) {
        throw new IllegalStateException("Missing compensated pickable-entity query");
    }
    /** The unique frame facing direction, or Integer.MIN_VALUE when there is no unique frame. */
    default int itemFrameOutputAt(BlockPos pos, Direction direction) { throw new IllegalStateException("Missing compensated item-frame query"); }
    default int detectorRailOutputAt(BlockPos pos) { throw new IllegalStateException("Missing compensated minecart query"); }
    default boolean hasSittingCatAt(BlockPos pos) { throw new IllegalStateException("Missing compensated cat query"); }
    /** EntityType.canSpawn reads received feature flags and difficulty. */
    default boolean canSpawn(ac.cult.blocksim.entity.EntityTypes.Type type) { throw new IllegalStateException("Missing compensated spawn conditions"); }
    default boolean hasFeature(String key) { throw new IllegalStateException("Missing received feature flags"); }
    default boolean hasMinecartIn(ac.cult.blocksim.data.Box box) { throw new IllegalStateException("Missing compensated minecart occupancy"); }
    /** Level.getEntities(null, box), including the local player and non-collidable entities. */
    default boolean hasEntityIn(ac.cult.blocksim.data.Box box) { throw new IllegalStateException("Missing compensated entity occupancy"); }
    /** EntityGetter.getEntityCollisions: a null source tests collidable entities; a boat also tests pushable entities. */
    default boolean hasEntityCollision(ac.cult.blocksim.data.Box box, boolean boatSource) {
        throw new IllegalStateException("Missing compensated entity collision query");
    }
    /** EntityGetter.isUnobstructed(null, shape), used by block placement. */
    default boolean isUnobstructed(ac.cult.blocksim.engine.shapes.VoxelShape shape) {
        throw new IllegalStateException("Missing compensated building obstruction query");
    }
    /** BoatItem checks eye containment within pickable entities in the expanded view box. */
    default boolean hasPickableEntityAtEye(SimPlayer player) { throw new IllegalStateException("Missing compensated boat eye query"); }
    default boolean hasBorderCollision(ac.cult.blocksim.data.Box box, Vec3 source) { throw new IllegalStateException("Missing compensated entity border query"); }
    default java.util.List<ac.cult.blocksim.entity.PaintingSize> placeablePaintings() { throw new IllegalStateException("Missing received placeable painting variants"); }
    default ac.cult.blocksim.entity.PaintingSize paintingSize(com.google.gson.JsonElement holder) { throw new IllegalStateException("Missing received painting variant"); }
    /** Frames allow overlapping types in different directions; paintings also reject their own type. */
    default boolean hasHangingEntityOverlap(ac.cult.blocksim.data.Box box, Direction direction, String type, boolean allowSameType) {
        throw new IllegalStateException("Missing compensated hanging-entity query");
    }
    /** MovingPistonBlock delegates to the client-visible piston block entity. Local coordinates. */
    default ac.cult.blocksim.engine.shapes.VoxelShape movingPistonCollisionAt(BlockPos pos) {
        throw new IllegalStateException("Missing compensated moving-piston collision query");
    }
}
