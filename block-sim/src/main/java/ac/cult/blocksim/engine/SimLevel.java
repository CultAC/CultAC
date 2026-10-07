package ac.cult.blocksim.engine;

import ac.cult.blocksim.behavior.BlockBehavior;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.interaction.BlockWrite;
import ac.cult.blocksim.interaction.WriteCondition;
import ac.cult.blocksim.engine.shapes.SupportType;
import ac.cult.blocksim.engine.shapes.BooleanOp;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

/** One action's client world overlay; no server callbacks or scheduled ticks exist here. */
public final class SimLevel {
    // ClientLevel constructor: maxChainedNeighborUpdates=1,000,000. The setBlock
    // depth limit is separately 512 (LevelWriter/BlockStateBase), not this cap.
    public static final int CLIENT_CHAIN_LIMIT = 1_000_000;
    private static final Direction[] SHAPE_ORDER = {
        Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.DOWN, Direction.UP
    };
    private final SimWorldView world;
    private final BlockRegistry registry;
    private final IntFunction<BlockBehavior> behaviors;
    private final ShapeUpdater updater;
    private final Map<BlockPos, StatePossibilities> overlay = new HashMap<>();
    private final Map<BlockPos, BlockEntityData> blockEntities = new HashMap<>();
    private final java.util.Set<BlockPos> changedBlockEntities = new java.util.HashSet<>();
    private final List<BlockWrite> writes = new ArrayList<>();
    private final LinkedHashMap<BlockPos, StatePossibilities> retainedStates = new LinkedHashMap<>();
    private final Map<BlockPos, StatePossibilities> pendingRandomStates = new HashMap<>();
    private final IntSupplier knownRandomPlantAges;
    private boolean predicting = true;

    public SimLevel(SimWorldView world, BlockRegistry registry, IntFunction<BlockBehavior> behaviors) {
        this(world, registry, behaviors, CLIENT_CHAIN_LIMIT);
    }
    public boolean isRecipeInput(String key, SimItemStack stack) { return world.recipeInputs(key).contains(stack.itemKey()); }
    public SimLevel(SimWorldView world, BlockRegistry registry, IntFunction<BlockBehavior> behaviors, int chainLimit) {
        this(world, registry, behaviors, chainLimit, null);
    }
    /** A supplied independent RNG is for reproducible oracle fixtures; production declares the vanilla value set. */
    public SimLevel(SimWorldView world, BlockRegistry registry, IntFunction<BlockBehavior> behaviors, int chainLimit, IntSupplier knownRandomPlantAges) {
        this.world = world;
        this.registry = registry;
        this.behaviors = behaviors;
        this.updater = new ShapeUpdater(this, chainLimit);
        this.knownRandomPlantAges = knownRandomPlantAges;
    }
    public BlockRegistry registry() { return registry; }
    public int minY() { return world.minY(); }
    public int seaLevel() { return world.seaLevel(); }
    public String biomeKeyAt(BlockPos pos) { return world.biomeKeyAt(pos); }
    public String dimensionKey() { return world.dimensionKey(); }
    public int maxY() { return world.minY() + world.height() - 1; }
    public boolean isWithinBorder(BlockPos pos) { return world.isWithinBorder(pos); }
    public boolean creakingActiveAt(BlockPos pos) { return world.creakingActiveAt(pos); }
    public boolean waterEvaporatesAt(BlockPos pos) { return world.waterEvaporatesAt(pos); }
    public int rawBrightnessAt(BlockPos pos) { return world.rawBrightnessAt(pos); }
    public Vec3 borderHit(Vec3 from, Vec3 to) { return world.borderHit(from, to); }
    public boolean hasPickableEntityHit(Vec3 from, Vec3 to, SimPlayer player) { return world.hasPickableEntityHit(from, to, player); }
    public int itemFrameOutputAt(BlockPos pos, Direction direction) { return world.itemFrameOutputAt(pos, direction); }
    public int detectorRailOutputAt(BlockPos pos) { return world.detectorRailOutputAt(pos); }
    public boolean hasSittingCatAt(BlockPos pos) { return world.hasSittingCatAt(pos); }
    public boolean canSpawn(ac.cult.blocksim.entity.EntityTypes.Type type) { return world.canSpawn(type); }
    public boolean hasFeature(String key) { return world.hasFeature(key); }
    public boolean hasMinecartIn(ac.cult.blocksim.data.Box box) { return world.hasMinecartIn(box); }
    public boolean hasEntityIn(ac.cult.blocksim.data.Box box) { return world.hasEntityIn(box); }
    public boolean isUnobstructed(VoxelShape shape) { return shape.isEmpty() || world.isUnobstructed(shape); }
    public boolean hasBorderCollision(ac.cult.blocksim.data.Box box, Vec3 source) { return world.hasBorderCollision(box, source); }
    public java.util.List<ac.cult.blocksim.entity.PaintingSize> placeablePaintings() { return world.placeablePaintings(); }
    public ac.cult.blocksim.entity.PaintingSize paintingSize(com.google.gson.JsonElement holder) { return world.paintingSize(holder); }
    public boolean hasHangingEntityOverlap(ac.cult.blocksim.data.Box box, Direction direction, String type, boolean allowSameType) {
        return world.hasHangingEntityOverlap(box, direction, type, allowSameType);
    }
    public boolean noCollision(ac.cult.blocksim.data.Box box) {
        return !BlockCollisions.hasCollision(this, box) && !world.hasEntityCollision(box, false);
    }
    public boolean hasPickableEntityAtEye(SimPlayer player) { return world.hasPickableEntityAtEye(player); }
    public boolean noBoatCollision(ac.cult.blocksim.data.Box box, Vec3 source) {
        var context = new EntityCollisionContext(box.minY(), false, 0, false, false);
        return !BlockCollisions.hasCollision(this, box, context) && !world.hasEntityCollision(box, true)
            && !world.hasBorderCollision(box, source);
    }
    public boolean isLoaded(BlockPos pos) { return world.isLoaded(pos); }
    public ac.cult.blocksim.engine.shapes.VoxelShape movingPistonCollisionAt(BlockPos pos) { return world.movingPistonCollisionAt(pos); }
    public BlockEntityData blockEntityAt(BlockPos pos) {
        if (!inValidBounds(pos)) return null;
        requireReadable(pos);
        return changedBlockEntities.contains(pos) ? blockEntities.get(pos) : world.blockEntityAt(pos);
    }
    /** Component/field mutations are action-local and do not add block writes. */
    public void blockEntityAt(BlockPos pos, BlockEntityData changed) {
        BlockEntityData previous = blockEntityAt(pos);
        if (previous == null || changed == null || !previous.type().equals(changed.type())) throw new IllegalArgumentException("Invalid block-entity field mutation at " + pos);
        changedBlockEntities.add(pos); blockEntities.put(pos, changed);
    }
    public Map<BlockPos, BlockEntityData> blockEntityChanges() {
        var result = new HashMap<BlockPos, BlockEntityData>();
        changedBlockEntities.forEach(pos -> result.put(pos, blockEntities.get(pos)));
        return java.util.Collections.unmodifiableMap(result);
    }
    public boolean collisionShapeFullBlock(int state, BlockPos pos) {
        return !Shapes.joinIsNotEmpty(Shapes.block(), behavior(state).collisionShape(this, state, pos), BooleanOp.NOT_SAME);
    }
    public BlockBehavior behavior(int state) { return behaviors.apply(state); }
    public ShapeUpdater shapeUpdater() { return updater; }
    public SimFluidState fluidAt(BlockPos pos) { return SimFluidState.of(registry.facts(stateAt(pos))); }
    public int updateFromNeighborShapes(int state, BlockPos pos) {
        for (Direction face : SHAPE_ORDER) {
            var neighbor = pos.relative(face);
            state = behavior(state).updateShape(this, state, pos, face, neighbor, stateAt(neighbor));
        }
        return state;
    }
    public boolean isRedstoneConductor(int state, BlockPos pos) {
        return behavior(state).isRedstoneConductor(this, state, pos);
    }
    public boolean isFaceSturdy(int state, BlockPos pos, Direction direction) {
        return isFaceSturdy(state, pos, direction, SupportType.FULL);
    }
    public boolean isFaceSturdy(int state, BlockPos pos, Direction direction, SupportType type) {
        StateFacts facts = registry.facts(state);
        return (facts.dynamicBits() & StateFacts.DYNAMIC_STURDY) == 0
            ? (facts.sturdyBits() & 1 << (direction.ordinal() * 3 + type.ordinal())) != 0
            : type.supports(behavior(state).supportShape(this, state, pos), direction);
    }
    public int stateAt(BlockPos pos) {
        if (!inValidBounds(pos)) return registry.block("minecraft:void_air").defaultState();
        requireReadable(pos);
        StatePossibilities state = overlay.get(pos);
        return state == null ? world.stateAt(pos) : state.state();
    }
    public StatePossibilities statePossibilitiesAt(BlockPos pos) {
        if (!inValidBounds(pos)) return StatePossibilities.exact(registry.block("minecraft:void_air").defaultState());
        requireReadable(pos);
        StatePossibilities state = overlay.get(pos);
        return state == null ? world.statePossibilitiesAt(pos) : state;
    }

    public int initialPlantAge(BlockPos pos, int state) {
        int age = knownRandomPlantAges == null ? 0 : knownRandomPlantAges.getAsInt();
        if (age < 0 || age >= 25) throw new IllegalArgumentException("Growing plant initial age outside 0..24");
        int result = registry.with(state, "age", Integer.toString(age));
        if (knownRandomPlantAges == null) pendingRandomStates.put(pos, new StatePossibilities(result, java.util.UUID.randomUUID()));
        return result;
    }
    /** A placement candidate can have a declared random age before it is written. */
    public StatePossibilities preparedStatePossibilities(BlockPos pos, int state) {
        StatePossibilities pending = pendingRandomStates.get(pos);
        return pending != null && registry.sameBlock(pending.state(), state) && registry.hasProperty(state, "age")
            && registry.value(pending.state(), "age").equals(registry.value(state, "age"))
            ? pending.carryTo(registry, state) : StatePossibilities.exact(state);
    }
    public List<BlockWrite> writes() { return List.copyOf(writes); }
    public Map<BlockPos, Integer> retainedStates() {
        var result = new LinkedHashMap<BlockPos, Integer>();
        retainedStates.forEach((pos, state) -> result.put(pos, state.state()));
        return java.util.Collections.unmodifiableMap(result);
    }
    public Map<BlockPos, StatePossibilities> retainedPossibilities() { return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(retainedStates)); }
    public void predicting(boolean value) { predicting = value; }

    public boolean setBlock(BlockPos pos, int state, int flags) { return setBlock(pos, state, flags, 512); }

    public boolean destroyBlock(BlockPos pos, int limit) {
        if (registry.facts(stateAt(pos)).has(StateFacts.AIR)) return false;
        // Particles and game events do not affect this contract. Block.dropResources
        // is guarded by ServerLevel; the client never inserts drop entities/items.
        SimFluidState fluid = fluidAt(pos);
        return setBlock(pos, fluid.createLegacyBlock(), 3, limit);
    }

    public boolean setBlock(BlockPos pos, int state, int flags, int limit) {
        // ClientLevel captures before super.setBlock and retains only AFTER its cascades.
        StatePossibilities oldState = statePossibilitiesAt(pos);
        boolean success = setBlockInLevel(pos, state, flags, limit, false);
        if (success && predicting) retainedStates.putIfAbsent(pos, oldState);
        return success;
    }

    private boolean setBlockInLevel(BlockPos pos, int state, int flags, int limit, boolean knownPlantAge) {
        if (!inValidBounds(pos)) return false;
        requireReadable(pos);
        // ClientChunkCache returns EmptyLevelChunk for absent chunks; its setBlockState returns null.
        if (!world.isLoaded(pos)) return false;
        registry.facts(state); // Reject an input from another wire palette.
        int oldState = stateAt(pos);
        // A non-air old cell proves its section is not empty, so no section scan
        // is needed for ordinary breaks or bucket pickups.
        if (registry.facts(state).has(StateFacts.AIR) && registry.facts(oldState).has(StateFacts.AIR) && sectionEmpty(pos)) return false;
        StatePossibilities oldPossibilities = statePossibilitiesAt(pos);
        if (oldState == state && (!knownPlantAge || oldPossibilities.exact())) return false;
        WriteCondition condition = null;
        if (knownPlantAge && !oldPossibilities.exact() && registry.sameBlock(oldState, state)
            && registry.with(oldState, "age", registry.value(state, "age")) == state) {
            int explicitAge = Integer.parseInt(registry.value(state, "age"));
            if (explicitAge < 25) condition = new WriteCondition(oldPossibilities.randomPlantAge(), explicitAge);
        }
        StatePossibilities pending = pendingRandomStates.remove(pos);
        StatePossibilities newPossibilities = knownPlantAge ? StatePossibilities.exact(state) : pending != null && registry.sameBlock(pending.state(), state)
            && registry.hasProperty(state, "age") && registry.value(pending.state(), "age").equals(registry.value(state, "age"))
            ? pending.carryTo(registry, state) : oldPossibilities.carryTo(registry, state);
        boolean blockChanged = !registry.sameBlock(oldState, state);
        BlockEntityData entity = BlockEntityPrototypes.create(registry.block(state), state);
        BlockEntityData previousEntity = entity != null && (!blockChanged || behavior(state).shouldKeepBlockEntity(registry, state, oldState)) ? blockEntityAt(pos) : null;
        overlay.put(pos, newPossibilities);
        if (blockChanged || entity != null && (previousEntity == null || !previousEntity.type().equals(entity.type()))) {
            changedBlockEntities.add(pos);
            if (previousEntity != null && previousEntity.type().equals(entity.type())) entity = previousEntity;
            if (entity == null) blockEntities.remove(pos); else blockEntities.put(pos, entity);
        }
        writes.add(new BlockWrite(pos, oldPossibilities, newPossibilities, condition));
        int afterRootWrite = writes.size();
        // LevelChunk's onPlace and affectNeighborsAfterRemoval are server-only.
        // Level.updateNeighborsAt/neighborChanged and POI callbacks are client no-ops.
        if ((flags & 16) == 0 && limit > 0) {
            int neighborFlags = flags & ~33;
            behavior(oldState).updateIndirectShapes(this, oldState, pos, neighborFlags, limit - 1);
            updateNeighborShapes(state, pos, neighborFlags, limit - 1);
            behavior(state).updateIndirectShapes(this, state, pos, neighborFlags, limit - 1);
        }
        // Initial growing-plant age changes no shape, support or client neighbor
        // predicate. This verifies the conditional branch leaves no further writes.
        if (condition != null && writes.size() != afterRootWrite) throw new IllegalStateException("An initial plant age override changed neighboring state");
        return true;
    }

    /** BlockItemStateProperties supplies a known AGE after the random placement write. */
    public void setBlockFromItemProperties(BlockPos pos, int state, boolean knownPlantAge) {
        StatePossibilities old = statePossibilitiesAt(pos);
        if (knownPlantAge && !old.exact() && !retainedStates.containsKey(pos)) {
            throw new IllegalStateException("A conditional age override requires the preceding prediction write");
        }
        if (setBlockInLevel(pos, state, 2, 512, knownPlantAge) && predicting) retainedStates.putIfAbsent(pos, old);
    }

    private void updateNeighborShapes(int state, BlockPos pos, int flags, int limit) {
        for (Direction direction : SHAPE_ORDER) {
            updater.shapeUpdate(direction.opposite(), state, pos.relative(direction), pos, flags, limit);
        }
    }

    void updateOrDestroy(int oldState, int newState, BlockPos pos, int flags, int limit) {
        if (newState != oldState && !registry.facts(newState).has(StateFacts.AIR)) {
            // The air/destroyBlock branch is guarded by !isClientSide in 26.3.
            setBlock(pos, newState, flags & ~32, limit);
        }
    }

    private boolean sectionEmpty(BlockPos pos) {
        int sx = pos.x() >> 4, sy = pos.y() >> 4, sz = pos.z() >> 4;
        boolean touched = overlay.keySet().stream().anyMatch(p -> (p.x() >> 4) == sx && (p.y() >> 4) == sy && (p.z() >> 4) == sz);
        if (!touched) return world.isSectionEmpty(pos);
        for (int x = sx << 4; x < (sx << 4) + 16; x++) for (int y = sy << 4; y < (sy << 4) + 16; y++) {
            for (int z = sz << 4; z < (sz << 4) + 16; z++) {
                if (!registry.facts(stateAt(new BlockPos(x, y, z))).has(StateFacts.AIR)) return false;
            }
        }
        return true;
    }
    private void requireReadable(BlockPos pos) { if (!world.isReadable(pos)) throw new UnloadedWorldException(pos); }

    // Level.isInValidBounds -> SectionPos.blockToSectionCoord -> ChunkPos.isValid.
    // ChunkPyramid's safety margin is generated from the pinned version.
    private boolean inValidBounds(BlockPos pos) {
        return pos.y() >= world.minY() && (long) pos.y() < (long) world.minY() + world.height()
            && Math.max(Math.abs(pos.x() >> 4), Math.abs(pos.z() >> 4)) <= registry.maxValidChunkCoordinate();
    }

    /** The facade converts this to the contract's UNLOADED decline, discarding the overlay. */
    public static final class UnloadedWorldException extends RuntimeException {
        public UnloadedWorldException(BlockPos pos) { super("Unloaded simulator input at " + pos); }
    }
}
