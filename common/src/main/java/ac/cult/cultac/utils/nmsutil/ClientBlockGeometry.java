package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.behavior.BehaviorRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.BlockEntityData;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.BlockRaycast;
import ac.cult.blocksim.engine.EntityCollisionContext;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimWorldView;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.blocksim.interaction.BlockHit;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import java.util.function.IntPredicate;

/** Read-only shape view of the compensated world, with the existing BlockGetter semantics. */
public final class ClientBlockGeometry {
    private static final class Rules {
        static final DataTables DATA = DataTables.defaults();
        static final BehaviorRegistry BEHAVIORS = new BehaviorRegistry(DATA);
    }

    private final SimLevel level;
    private final CompensatedWorld world;

    public ClientBlockGeometry(CompensatedWorld world) {
        this.world = world;
        level = new SimLevel(new View(world), Rules.DATA.registry(), Rules.BEHAVIORS);
    }

    public VoxelShape collision(int state, ac.cult.cultac.protocol.value.BlockPos pos, EntityCollisionContext context) {
        return level.behavior(state).collisionShape(level, state, position(pos), context);
    }

    public VoxelShape collision(int state, ac.cult.cultac.protocol.value.BlockPos pos) {
        return level.behavior(state).collisionShape(level, state, position(pos));
    }

    public VoxelShape movingPiston(
            int movedState,
            ac.cult.cultac.protocol.value.BlockPos pos,
            ac.cult.cultac.protocol.value.Direction facing,
            boolean extending,
            boolean source,
            float progress) {
        return ac.cult.blocksim.engine.MovingPistonShape.collision(
                movedState,
                ac.cult.blocksim.engine.Direction.values()[facing.ordinal()],
                extending,
                source,
                progress,
                null,
                state -> collision(state, pos));
    }

    public boolean collisionFull(int state, ac.cult.cultac.protocol.value.BlockPos pos) {
        var facts = Rules.DATA.registry().facts(state);
        return (facts.dynamicBits() & ac.cult.blocksim.data.StateFacts.DYNAMIC_COLLISION) == 0
                ? facts.has(ac.cult.blocksim.data.StateFacts.FULL_COLLISION)
                : level.collisionShapeFullBlock(state, position(pos));
    }

    public VoxelShape outline(int state, ac.cult.cultac.protocol.value.BlockPos pos, EntityCollisionContext context) {
        return level.behavior(state).outlineShape(level, state, position(pos), context);
    }

    public VoxelShape support(int state, ac.cult.cultac.protocol.value.BlockPos pos) {
        return level.behavior(state).supportShape(level, state, position(pos));
    }

    public boolean isFaceSturdy(
            int state, ac.cult.cultac.protocol.value.BlockPos pos, ac.cult.cultac.protocol.value.Direction face) {
        return level.isFaceSturdy(state, position(pos), ac.cult.blocksim.engine.Direction.values()[face.ordinal()]);
    }

    public VoxelShape interaction(int state, ac.cult.cultac.protocol.value.BlockPos pos) {
        return level.behavior(state).interactionShape(level, state, position(pos));
    }

    public BlockHit clipOutline(
            ac.cult.cultac.utils.math.Vec3 from,
            ac.cult.cultac.utils.math.Vec3 to,
            EntityCollisionContext context,
            IntPredicate pickFluid) {
        return clip(
                from, to, (state, pos) -> level.behavior(state).outlineShape(level, state, pos, context), pickFluid);
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
        // Ordinary shape queries have no block entities. Piston/open-shulker geometry is supplied separately.
        @Override
        public BlockEntityData blockEntityAt(BlockPos pos) {
            return null;
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
        public boolean creakingActiveAt(BlockPos pos) {
            throw new UnsupportedOperationException("Environment action on geometry view");
        }

        @Override
        public boolean waterEvaporatesAt(BlockPos pos) {
            throw new UnsupportedOperationException("Environment action on geometry view");
        }
    }
}
