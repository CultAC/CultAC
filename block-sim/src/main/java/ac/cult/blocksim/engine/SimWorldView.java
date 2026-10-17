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
    int seaLevel();
    /** BiomeManager.getBiome, including its zoom seed, received palettes and unloaded-chunk fallback. */
    String biomeKeyAt(BlockPos pos);
    /** The current client world-border test, including a border transition at this tick. */
    boolean isWithinBorder(BlockPos pos);
    /** The received dimension key, independent of its dimension-type attributes. */
    String dimensionKey();
    /** Recipe property sets are replaced by the received update-recipes packet. */
    java.util.Set<String> recipeInputs(String key);
    /** Resolved from the client's synced dimension/biome/timeline attributes at this tick. */
    boolean creakingActiveAt(BlockPos pos);
    /** WATER_EVAPORATES at this position, resolved from received dimension, biome and timeline data. */
    boolean waterEvaporatesAt(BlockPos pos);
    /** Null means the client has no block entity at this position. */
    BlockEntityData blockEntityAt(BlockPos pos);
    /** CollisionGetter.clipIncludingBorder: null if this segment does not hit the received border. */
    Vec3 borderHit(Vec3 from, Vec3 to);
    /** ProjectileUtil view query uses exact pickable-entity boxes, with zero expansion margin. */
    boolean hasPickableEntityHit(Vec3 from, Vec3 to, SimPlayer player);
    /** The unique frame facing direction, or Integer.MIN_VALUE when there is no unique frame. */
    int itemFrameOutputAt(BlockPos pos, Direction direction);
    int detectorRailOutputAt(BlockPos pos);
    boolean hasSittingCatAt(BlockPos pos);
    /** EntityType.canSpawn reads received feature flags and difficulty. */
    boolean canSpawn(ac.cult.blocksim.entity.EntityTypes.Type type);
    boolean hasFeature(String key);
    boolean hasMinecartIn(ac.cult.blocksim.data.Box box);
    /**
     * Level.getEntities(null, box): EntityGetter filters with EntitySelector.NO_SPECTATORS, so every
     * non-spectator entity counts, including the local player and non-collidable entities.
     */
    boolean hasEntityIn(ac.cult.blocksim.data.Box box);
    /** EntityGetter.getEntityCollisions: a null source tests collidable entities; a boat also tests pushable entities. */
    boolean hasEntityCollision(ac.cult.blocksim.data.Box box, boolean boatSource);
    /** EntityGetter.isUnobstructed(null, shape), used by block placement. */
    boolean isUnobstructed(ac.cult.blocksim.engine.shapes.VoxelShape shape);
    /** BoatItem checks eye containment within pickable entities in the expanded view box. */
    boolean hasPickableEntityAtEye(SimPlayer player);
    boolean hasBorderCollision(ac.cult.blocksim.data.Box box, Vec3 source);
    java.util.List<ac.cult.blocksim.entity.PaintingSize> placeablePaintings();
    ac.cult.blocksim.entity.PaintingSize paintingSize(com.google.gson.JsonElement holder);
    /** Frames allow overlapping types in different directions; paintings also reject their own type. */
    boolean hasHangingEntityOverlap(ac.cult.blocksim.data.Box box, Direction direction, String type, boolean allowSameType);
    /** MovingPistonBlock delegates to the client-visible piston block entity. Local coordinates. */
    ac.cult.blocksim.engine.shapes.VoxelShape movingPistonCollisionAt(BlockPos pos);
}
