package ac.cult.cultac.bedrock.player;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.cultac.bedrock.prediction.api.BedrockBlockShapeContext.PistonProgress;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * The blocks a piston carries for a Bedrock player, from the block entity data its translator sends.
 *
 * <p>A moving block names the block it carries, its piston and whether that piston is expanding. A piston arm holds
 * the piston's state, progress and last progress. Its data sets the state only: the progress is the client's own,
 * stepped once per tick before it moves, and a stroke that ends settles the state by itself. The moving block
 * collides as the carried block at the two cells those progresses put it in ({@link #carried}). Neither block entity
 * shoves anyone.
 */
public final class BedrockPistonBlocks {
    /** The carried state of a moving block whose data names no block the world can hold. */
    public static final int NO_BLOCK = -1;

    private static final int RETRACTED = 0;
    private static final int EXPANDING = 1;
    private static final int EXPANDED = 2;
    private static final int RETRACTING = 3;
    private static final float STEP = 0.5F;

    private final Long2ObjectMap<Moving> movingBlocks = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<Arm> arms = new Long2ObjectOpenHashMap<>();

    /** The data of the moving block at {@code position}; {@code piston} is null where it names none. */
    public void movingBlock(BlockPos position, int carriedState, BlockPos piston, boolean expanding) {
        movingBlocks.put(position.asLong(), new Moving(position, carriedState, piston, expanding));
    }

    /**
     * The data of the piston arm at {@code position}. An arm first heard of rests where the piston was before this
     * state: out when it is expanded or starts to retract, in otherwise.
     */
    public void pistonArm(BlockPos position, int state) {
        Arm arm = arms.get(position.asLong());
        if (arm == null) {
            float progress = state == EXPANDED || state == RETRACTING ? 1.0F : 0.0F;
            arms.put(position.asLong(), new Arm(position, state, progress, progress));
        } else {
            arm.state = state;
        }
    }

    /** One client tick, before its move. A position that no longer holds the block has lost its data. */
    public void tick(ToIntFunction<BlockPos> blockStateAt) {
        if (!movingBlocks.isEmpty()) {
            movingBlocks
                    .values()
                    .removeIf(moving -> !BlockIds.is(blockStateAt.applyAsInt(moving.position), BlockIds.MOVING_PISTON));
        }
        if (arms.isEmpty()) return;
        var iterator = arms.values().iterator();
        while (iterator.hasNext()) {
            Arm arm = iterator.next();
            if (armDirection(blockStateAt.applyAsInt(arm.position)) == null) {
                iterator.remove();
                continue;
            }
            arm.lastProgress = arm.progress;
            switch (arm.state) {
                case EXPANDING -> {
                    arm.progress = Math.min(arm.progress + STEP, 1.0F);
                    if (arm.progress >= 1.0F) arm.state = EXPANDED;
                }
                case RETRACTING -> {
                    arm.progress = Math.max(arm.progress - STEP, 0.0F);
                    if (arm.progress <= 0.0F) arm.state = RETRACTED;
                }
                case EXPANDED -> arm.progress = 1.0F;
                case RETRACTED -> arm.progress = 0.0F;
                default -> {}
            }
        }
    }

    /**
     * The block the moving block at {@code position} carries and the two cells it collides at, or null where the
     * client has no data for it. Both cells are its own without a piston that has an arm.
     */
    public Carried carried(BlockPos position, ToIntFunction<BlockPos> blockStateAt) {
        Moving moving = movingBlocks.get(position.asLong());
        if (moving == null) return null;
        Arm arm = moving.piston == null ? null : arms.get(moving.piston.asLong());
        Direction direction = arm == null ? null : armDirection(blockStateAt.applyAsInt(moving.piston));
        if (direction == null) return new Carried(moving.carriedState, position, position);
        return new Carried(
                moving.carriedState,
                cell(position, direction, arm.lastProgress, moving.expanding),
                cell(position, direction, arm.progress, moving.expanding));
    }

    /**
     * How far out the piston at {@code piston} has pushed its arm, or {@link PistonProgress#NO_PISTON} where no
     * piston is. A piston without data has its arm out.
     */
    public float progress(BlockPos piston, ToIntFunction<BlockPos> blockStateAt) {
        if (armDirection(blockStateAt.applyAsInt(piston)) == null) return PistonProgress.NO_PISTON;
        Arm arm = arms.get(piston.asLong());
        return arm == null ? 1.0F : arm.progress;
    }

    /**
     * The cell a moving block at {@code position} is in at {@code progress}: the block of its position moved along
     * the arm by the progress, from one cell back while the piston expands.
     */
    public static BlockPos cell(BlockPos position, Direction arm, float progress, boolean expanding) {
        float along = expanding ? progress - 1.0F : progress;
        return new BlockPos(
                (int) Math.floor(position.getX() + arm.getModX() * along),
                (int) Math.floor(position.getY() + arm.getModY() * along),
                (int) Math.floor(position.getZ() + arm.getModZ() * along));
    }

    /** Every position with moving block data. */
    public void forEachMovingBlock(Consumer<BlockPos> action) {
        for (Moving moving : movingBlocks.values()) action.accept(moving.position);
    }

    public void clear() {
        movingBlocks.clear();
        arms.clear();
    }

    /** The way the arm of the piston {@code state} points, or null for any other block. */
    private static Direction armDirection(int state) {
        if (!BlockIds.is(state, BlockIds.PISTON) && !BlockIds.is(state, BlockIds.STICKY_PISTON)) return null;
        return Direction.from3DDataValue(BlockProps.FACING.value(state));
    }

    /**
     * @param state the carried block, or {@link #NO_BLOCK}
     * @param last the cell at the piston's last progress
     * @param current the cell at its progress
     */
    public record Carried(int state, BlockPos last, BlockPos current) {}

    private record Moving(BlockPos position, int carriedState, BlockPos piston, boolean expanding) {}

    private static final class Arm {
        private final BlockPos position;
        private int state;
        private float progress;
        private float lastProgress;

        private Arm(BlockPos position, int state, float progress, float lastProgress) {
            this.position = position;
            this.state = state;
            this.progress = progress;
            this.lastProgress = lastProgress;
        }
    }
}
