package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.behavior.BehaviorRegistry;
import ac.cult.blocksim.behavior.BlockBehavior;
import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.BlockEntityData;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.BlockRaycast;
import ac.cult.blocksim.engine.EntityCollisionContext;
import ac.cult.blocksim.engine.MovingPistonShape;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.engine.SimWorldView;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.blocksim.entity.PaintingSize;
import ac.cult.blocksim.interaction.BlockHit;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import com.google.gson.JsonElement;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

/** Read-only shape view of the compensated world, with the existing BlockGetter semantics. */
public final class ClientBlockGeometry {
    private static final class Rules {
        static final DataTables DATA = DataTables.defaults();
        static final BehaviorRegistry BEHAVIORS = new BehaviorRegistry(DATA);
        static final ac.cult.blocksim.engine.Direction[] DIRECTIONS = ac.cult.blocksim.engine.Direction.values();
        static final boolean[] STATE_ONLY_COLLISION = stateOnlyCollision();

        private static boolean[] stateOnlyCollision() {
            var blocks = DATA.registry().blocks();
            var result = new boolean[blocks.size()];
            // Resolve dispatch once, including overrides of either overload. This is
            // fixed registry metadata; no world or actor result is retained.
            var classes = new IdentityHashMap<Class<?>, Boolean>();
            for (int index = 0; index < blocks.size(); index++) {
                var type = BEHAVIORS.apply(blocks.get(index).defaultState()).getClass();
                result[index] = classes.computeIfAbsent(type, ClientBlockGeometry::inheritsStateOnlyCollision);
            }
            return result;
        }
    }

    private final SimLevel level;
    private final CompensatedWorld world;

    public ClientBlockGeometry(CompensatedWorld world) {
        this.world = world;
        level = new SimLevel(new View(world), Rules.DATA.registry(), Rules.BEHAVIORS);
    }

    public VoxelShape collision(int state, ac.cult.cultac.protocol.value.BlockPos pos, EntityCollisionContext context) {
        return collision(state, position(pos), context);
    }

    public VoxelShape collision(int state, BlockPos pos, EntityCollisionContext context) {
        return level.behavior(state).collisionShape(level, state, pos, context);
    }

    /** Both inherited base methods read only the immutable state shape. */
    public VoxelShape staticCollision(int state) {
        return Rules.STATE_ONLY_COLLISION[Rules.DATA.registry().blockIndex(state)]
                ? Rules.DATA.registry().shapes().collision(state)
                : null;
    }

    static boolean inheritsStateOnlyCollision(Class<?> type) {
        try {
            var base = BlockBehavior.class;
            return type.getMethod("collisionShape", SimLevel.class, int.class, BlockPos.class)
                                    .getDeclaringClass()
                            == base
                    && type.getMethod(
                                            "collisionShape",
                                            SimLevel.class,
                                            int.class,
                                            BlockPos.class,
                                            EntityCollisionContext.class)
                                    .getDeclaringClass()
                            == base;
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Missing block collision dispatch", exception);
        }
    }

    public VoxelShape collision(int state, ac.cult.cultac.protocol.value.BlockPos pos) {
        // PistonMovingBlockEntity queries both moved collision and shove geometry with
        // CollisionContext.empty(), using that client's block implementation.
        return ClientBlockShapes.versionedMovementShape(
                world.player, state, level.behavior(state).collisionShape(level, state, position(pos)));
    }

    public VoxelShape movingPiston(
            int movedState,
            ac.cult.cultac.protocol.value.BlockPos pos,
            ac.cult.cultac.protocol.value.Direction facing,
            boolean extending,
            boolean source,
            float progress) {
        return MovingPistonShape.collision(
                movedState,
                Rules.DIRECTIONS[facing.ordinal()],
                extending,
                source,
                progress,
                null,
                state -> collision(state, pos));
    }

    public VoxelShape outline(int state, BlockPos pos, EntityCollisionContext context) {
        return level.behavior(state).outlineShape(level, state, pos, context);
    }

    public boolean isFaceSturdy(
            int state, ac.cult.cultac.protocol.value.BlockPos pos, ac.cult.cultac.protocol.value.Direction face) {
        return level.isFaceSturdy(state, position(pos), Rules.DIRECTIONS[face.ordinal()]);
    }

    public VoxelShape interaction(int state, ac.cult.cultac.protocol.value.BlockPos pos) {
        return level.behavior(state).interactionShape(level, state, position(pos));
    }

    public BlockHit clipOutline(
            ac.cult.cultac.utils.math.Vec3 from,
            ac.cult.cultac.utils.math.Vec3 to,
            CultPlayer player,
            IntPredicate pickFluid) {
        return clip(
                from,
                to,
                (state, pos) -> ClientBlockShapes.visualShape(player, state, pos.x(), pos.y(), pos.z()),
                pickFluid);
    }

    /** {@code BlockGetter#clip} with {@code ClipContext.Fluid.NONE} and {@code blocks} as the block shape. */
    public BlockHit clipBlocks(
            ac.cult.cultac.utils.math.Vec3 from, ac.cult.cultac.utils.math.Vec3 to, BlockRaycast.ShapeQuery blocks) {
        return clip(from, to, blocks, state -> false);
    }

    public BlockHit clipResetting(
            ac.cult.cultac.utils.math.Vec3 from,
            ac.cult.cultac.utils.math.Vec3 to,
            IntPredicate resettingBlocks,
            IntPredicate pickFluid) {
        return clip(from, to, (state, pos) -> resettingBlocks.test(state) ? Shapes.block() : Shapes.empty(), pickFluid);
    }

    private BlockHit clip(
            ac.cult.cultac.utils.math.Vec3 from,
            ac.cult.cultac.utils.math.Vec3 to,
            BlockRaycast.ShapeQuery blocks,
            IntPredicate pickFluid) {
        return BlockRaycast.clip(
                level,
                new ac.cult.blocksim.engine.Vec3(from.x, from.y, from.z),
                new ac.cult.blocksim.engine.Vec3(to.x, to.y, to.z),
                blocks,
                (state, pos) -> pickFluid.test(state)
                        ? ClientFluidQueries.raycastShape(
                                world, new ac.cult.cultac.protocol.value.BlockPos(pos.x(), pos.y(), pos.z()), state)
                        : Shapes.empty());
    }

    private static BlockPos position(ac.cult.cultac.protocol.value.BlockPos pos) {
        return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    private record View(CompensatedWorld world) implements SimWorldView {
        @Override
        public int stateAt(BlockPos pos) {
            return world.getBlockStateIdAt(pos.x(), pos.y(), pos.z());
        }
        // The original BlockGetter reads air for unloaded coordinates; geometry reads keep that contract.
        @Override
        public boolean isLoaded(BlockPos pos) {
            return true;
        }

        @Override
        public boolean isSectionEmpty(BlockPos pos) {
            var chunk = world.getChunk(pos.x() >> 4, pos.z() >> 4);
            if (chunk == null) return true;
            var section = chunk.getSection((pos.y() - world.getMinHeight()) >> 4);
            return section == null || section.isEmpty();
        }

        @Override
        public int minY() {
            return world.getMinHeight();
        }

        @Override
        public int height() {
            return world.getMaxHeight() - world.getMinHeight();
        }

        @Override
        public BlockEntityData blockEntityAt(BlockPos pos) {
            // ShulkerBoxBlock#getShape reads the current client-tick lid progress.
            return world.getClientBlockEntityData(
                    new ac.cult.cultac.protocol.value.BlockPos(pos.x(), pos.y(), pos.z()));
        }

        @Override
        public VoxelShape movingPistonCollisionAt(BlockPos pos) {
            return Shapes.empty();
        }

        @Override
        public boolean isWithinBorder(BlockPos pos) {
            throw new UnsupportedOperationException("Border action on geometry view");
        }

        @Override
        public ac.cult.blocksim.engine.Vec3 borderHit(
                ac.cult.blocksim.engine.Vec3 from, ac.cult.blocksim.engine.Vec3 to) {
            throw new UnsupportedOperationException("Border action on geometry view");
        }

        @Override
        public boolean hasBorderCollision(Box box, ac.cult.blocksim.engine.Vec3 source) {
            throw new UnsupportedOperationException("Border action on geometry view");
        }

        @Override
        public int seaLevel() {
            throw new UnsupportedOperationException("Level action on geometry view");
        }

        @Override
        public String biomeKeyAt(BlockPos pos) {
            throw new UnsupportedOperationException("Level action on geometry view");
        }

        @Override
        public String dimensionKey() {
            throw new UnsupportedOperationException("Level action on geometry view");
        }

        @Override
        public Set<String> recipeInputs(String key) {
            throw new UnsupportedOperationException("Level action on geometry view");
        }

        @Override
        public boolean canSpawn(EntityTypes.Type type) {
            throw new UnsupportedOperationException("Level action on geometry view");
        }

        @Override
        public boolean hasFeature(String key) {
            throw new UnsupportedOperationException("Level action on geometry view");
        }

        @Override
        public List<PaintingSize> placeablePaintings() {
            throw new UnsupportedOperationException("Level action on geometry view");
        }

        @Override
        public PaintingSize paintingSize(JsonElement holder) {
            throw new UnsupportedOperationException("Level action on geometry view");
        }

        @Override
        public boolean hasPickableEntityHit(
                ac.cult.blocksim.engine.Vec3 from, ac.cult.blocksim.engine.Vec3 to, SimPlayer player) {
            throw new UnsupportedOperationException("Entity action on geometry view");
        }

        @Override
        public boolean hasPickableEntityAtEye(SimPlayer player) {
            throw new UnsupportedOperationException("Entity action on geometry view");
        }

        @Override
        public int itemFrameOutputAt(BlockPos pos, ac.cult.blocksim.engine.Direction direction) {
            throw new UnsupportedOperationException("Entity action on geometry view");
        }

        @Override
        public int detectorRailOutputAt(BlockPos pos) {
            throw new UnsupportedOperationException("Entity action on geometry view");
        }

        @Override
        public boolean hasSittingCatAt(BlockPos pos) {
            throw new UnsupportedOperationException("Entity action on geometry view");
        }

        @Override
        public boolean hasMinecartIn(Box box) {
            throw new UnsupportedOperationException("Entity action on geometry view");
        }

        @Override
        public boolean hasEntityIn(Box box) {
            throw new UnsupportedOperationException("Entity action on geometry view");
        }

        @Override
        public boolean hasEntityCollision(Box box, boolean boatSource) {
            throw new UnsupportedOperationException("Entity action on geometry view");
        }

        @Override
        public boolean isUnobstructed(VoxelShape shape) {
            throw new UnsupportedOperationException("Entity action on geometry view");
        }

        @Override
        public boolean hasHangingEntityOverlap(
                Box box, ac.cult.blocksim.engine.Direction direction, String type, boolean allowSameType) {
            throw new UnsupportedOperationException("Entity action on geometry view");
        }

        @Override
        public boolean creakingActiveAt(BlockPos pos) {
            throw new UnsupportedOperationException("Environment action on geometry view");
        }

        @Override
        public boolean waterEvaporatesAt(BlockPos pos) {
            throw new UnsupportedOperationException("Environment action on geometry view");
        }
    }
}
