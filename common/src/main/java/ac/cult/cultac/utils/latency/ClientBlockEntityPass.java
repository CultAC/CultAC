package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.engine.BlockEntityData;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/**
 * One client Level#tickBlockEntities over the block entities that move entities, in Level#blockEntityTickers order,
 * with Level#getGameTime during it. Each {@link Piston} is a PistonMovingBlockEntity whose tick calls
 * moveCollidedEntities and moveStuckEntities, held as it was before that tick advanced its progress; each
 * {@link ShulkerBox} is one ShulkerBoxBlockEntity#moveCollidedEntities call. ClientLevel#tickEntities runs before
 * it, and its {@link ShulkerPeek} moves lead the pass in their tick order. Each {@link Lid} is a
 * ShulkerBoxBlockEntity#updateAnimation that changes the lid ShulkerBoxBlock#getShape reads, opening or closing, and
 * each {@link Final} a PistonMovingBlockEntity#tick that replaces its MOVING_PISTON with the moved block.
 */
public record ClientBlockEntityPass(List<Mover> movers, List<Lid> lids, List<Final> finals, long gameTime) {
    public static final ClientBlockEntityPass EMPTY = new ClientBlockEntityPass(List.of(), 0L);

    public ClientBlockEntityPass {
        movers = List.copyOf(movers);
        lids = List.copyOf(lids);
        finals = List.copyOf(finals);
    }

    public ClientBlockEntityPass(List<Mover> movers, List<Lid> lids, long gameTime) {
        this(movers, lids, List.of(), gameTime);
    }

    public ClientBlockEntityPass(List<Mover> movers, long gameTime) {
        this(movers, List.of(), gameTime);
    }

    /** Whether no mover moves an entity; the pass may still change {@link #lids}. */
    public boolean isEmpty() {
        return movers.isEmpty();
    }

    /** This pass after the ClientLevel#tickEntities moves {@code entityMoves}. */
    public ClientBlockEntityPass after(List<ShulkerPeek> entityMoves) {
        if (entityMoves.isEmpty()) return this;
        List<Mover> all = new ArrayList<>(entityMoves.size() + movers.size());
        all.addAll(entityMoves);
        all.addAll(movers);
        return new ClientBlockEntityPass(all, lids, finals, gameTime);
    }

    /**
     * One ShulkerBoxBlockEntity#updateAnimation that changes the block entity at {@code pos} from {@code before} to
     * {@code after}. It runs ahead of the pass's {@code beforeMover}th piston or shulker box mover, counting from 0
     * and skipping the leading {@link ShulkerPeek} moves; an opening box's update runs right before its own
     * moveCollidedEntities.
     */
    public record Lid(BlockPos pos, BlockEntityData before, BlockEntityData after, int beforeMover) {}

    /**
     * The client-only PistonMovingBlockEntity#tick past its fifth death tick at {@code pos}: it sets {@code finalState}
     * over {@code movingState}, the MOVING_PISTON its block entity's fields collided as at progress 1. It runs ahead of
     * the {@code beforeMover}th piston or shulker box mover, counted as for a {@link Lid}.
     */
    public record Final(
            BlockPos pos,
            int movingState,
            int movedState,
            Direction direction,
            boolean extending,
            boolean source,
            int finalState,
            int beforeMover) {}

    /** Whether some mover's tick can reach an entity whose box lies within {@code box}. */
    public boolean mayReach(SimpleCollisionBox box) {
        for (Mover mover : movers) if (mover.reach().isIntersected(box)) return true;
        return false;
    }

    public sealed interface Mover permits Piston, ShulkerBox, ShulkerPeek {
        /** Bounds every entity box this mover's tick can query. */
        SimpleCollisionBox reach();

        /**
         * Whether {@code box} meets {@link #reach()} under AABB#intersects, the test Level#getEntities applies to each
         * query area inside it. False means this mover's tick neither lists nor moves an entity with that box.
         */
        default boolean reaches(SimpleCollisionBox box) {
            SimpleCollisionBox reach = reach();
            return box.maxX > reach.minX
                    && box.minX < reach.maxX
                    && box.maxY > reach.minY
                    && box.minY < reach.maxY
                    && box.maxZ > reach.minZ
                    && box.minZ < reach.maxZ;
        }
    }

    /** PistonMovingBlockEntity fields at {@code pos} before its tick: its progress is below 1, so the tick moves. */
    public record Piston(
            BlockPos pos, int movedState, Direction direction, boolean extending, boolean source, float progress)
            implements Mover {

        /** PistonMovingBlockEntity#getMovementDirection. */
        public Direction movementDirection() {
            return extending ? direction : direction.getOppositeFace();
        }

        /** PistonMovingBlockEntity#tick's progress + 0.5F, the progress moveCollidedEntities moves towards. */
        public float newProgress() {
            return progress + 0.5F;
        }

        /** The progress PistonMovingBlockEntity#tick stores after moving entities. */
        public float storedProgress() {
            float next = newProgress();
            return next >= 1.0F ? 1.0F : next;
        }

        /** PistonMovingBlockEntity#getExtendedProgress of the current progress. */
        public float extendedProgress() {
            return extending ? progress - 1.0F : 1.0F - progress;
        }

        /** PistonMovingBlockEntity#getCollisionRelatedBlockState. */
        public int collisionRelatedState() {
            if (!extending && source && BlockFamilies.PISTON_BASE.test(movedState)) {
                return BlockProps.FACING.with(
                        BlockProps.PISTON_TYPE.with(
                                BlockProps.SHORT.with(BlockIds.PISTON_HEAD.defaultState(), progress > 0.25F),
                                BlockIds.is(movedState, BlockIds.STICKY_PISTON) ? 1 : 0),
                        BlockProps.FACING.value(movedState));
            }
            return movedState;
        }

        @Override
        public SimpleCollisionBox reach() {
            // The block is drawn at most one block from pos. Its shape rises at most 0.5 above its block (a fence or
            // wall) and a long piston arm reaches 0.25 past it, and the movement area adds at most 0.5 more: the
            // moveCollidedEntities query stays within pos -1..+2 sideways and -1..+2.5 upward. The honey sticky box
            // tops out at 1.500001 on a sideways move, and fixEntityWithinPistonBase reads the block itself.
            return new SimpleCollisionBox(pos).expand(1.5D);
        }

        /** {@link #reach()}'s test without allocating it: a pass replays every piston for each of its actors. */
        @Override
        public boolean reaches(SimpleCollisionBox box) {
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            return box.maxX > x - 1.5D
                    && box.minX < x + 2.5D
                    && box.maxY > y - 1.5D
                    && box.minY < y + 2.5D
                    && box.maxZ > z - 1.5D
                    && box.minZ < z + 2.5D;
        }
    }

    /** One ShulkerBoxBlockEntity#moveCollidedEntities call: every entity box intersecting {@code area} moves. */
    public record ShulkerBox(SimpleCollisionBox area, Vec3 movement) implements Mover {
        @Override
        public SimpleCollisionBox reach() {
            return area;
        }
    }

    /**
     * One Shulker#onPeekAmountChange, at {@code tickOrder} in ClientLevel#tickEntities: every entity box intersecting
     * {@code area}, the part the growing peek opened, moves by {@code movement} with MoverType.SHULKER.
     */
    public record ShulkerPeek(SimpleCollisionBox area, Vec3 movement, long tickOrder) implements Mover {
        @Override
        public SimpleCollisionBox reach() {
            return area;
        }
    }
}
