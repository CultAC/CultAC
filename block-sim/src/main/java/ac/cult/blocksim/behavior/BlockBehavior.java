package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimFluidState;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.interaction.PlacementContext;
import ac.cult.blocksim.interaction.UseContext;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.Offsets;

/** Client-reachable defaults from BlockBehaviour. Families override only their own methods. */
public class BlockBehavior {
    public boolean shouldKeepBlockEntity(ac.cult.blocksim.data.BlockRegistry registry, int state, int oldState) { return false; }
    protected static boolean isFamily(SimLevel level, int state, String family) {
        for (String type : level.registry().block(state).bindings().get("classHierarchy").split(",")) {
            if (type.equals("net.minecraft.world.level.block." + family)) return true;
        }
        return false;
    }

    public void setPlacedBy(SimLevel level, int state, BlockPos pos, SimPlayer player, SimItemStack stack) { }

    /** Block's default hook emits particles/events; it performs no client block write. */
    public void playerWillDestroy(SimLevel level, int state, BlockPos pos, SimPlayer player) { }

    public void destroy(SimLevel level, int state, BlockPos pos) { }

    public void attack(SimLevel level, int state, BlockPos pos, SimPlayer player) { }

    public SimInteraction useItemOn(int state, UseContext context) { return SimInteraction.TRY_WITH_EMPTY_HAND; }

    public SimInteraction useWithoutItem(int state, UseContext context) { return SimInteraction.PASS; }

    public int placementState(BlockDefinition block, PlacementContext context) { return block.defaultState(); }

    public boolean canBeReplaced(SimLevel level, int state, PlacementContext context) {
        return level.registry().facts(state).has(StateFacts.REPLACEABLE)
            && (context.stack().isEmpty() || !context.stack().is(level.registry().block(state).bindings().get("asItem")));
    }
    public boolean canBeReplacedByFluid(SimLevel level, int state, String fluidType) {
        return level.registry().facts(state).has(StateFacts.FLUID_REPLACEABLE);
    }

    protected static boolean bool(SimLevel level, int state, String property) {
        return Boolean.parseBoolean(level.registry().value(state, property));
    }

    protected static int number(SimLevel level, int state, String property) {
        return Integer.parseInt(level.registry().value(state, property));
    }

    protected static Direction facing(SimLevel level, int state) {
        return Direction.valueOf(level.registry().value(state, "facing").toUpperCase(java.util.Locale.ROOT));
    }

    public VoxelShape supportShape(SimLevel level, int state, BlockPos pos) {
        return level.registry().shapes().support(state);
    }

    public VoxelShape collisionShape(SimLevel level, int state, BlockPos pos) {
        return level.registry().shapes().collision(state);
    }
    public VoxelShape collisionShape(SimLevel level, int state, BlockPos pos, ac.cult.blocksim.engine.EntityCollisionContext context) {
        return collisionShape(level, state, pos);
    }

    public VoxelShape outlineShape(SimLevel level, int state, BlockPos pos, SimPlayer player) {
        var context = player == null ? ac.cult.blocksim.engine.EntityCollisionContext.emptyContext()
                : new ac.cult.blocksim.engine.EntityCollisionContext(player.state().position().y(), player.state().secondaryUseActive(),
                        0, false, false, player.hand(ac.cult.blocksim.interaction.Hand.MAIN_HAND).itemKey(), false, false, false);
        return outlineShape(level, state, pos, context);
    }

    public VoxelShape outlineShape(SimLevel level, int state, BlockPos pos, ac.cult.blocksim.engine.EntityCollisionContext context) {
        var shape = level.registry().shapes().outline(state);
        var block = level.registry().block(state);
        if (!Boolean.parseBoolean(block.bindings().get("outlineUsesOffset"))) return shape;
        var offset = Offsets.offset(block, pos);
        return shape.move(offset.x(), offset.y(), offset.z());
    }

    public VoxelShape interactionShape(SimLevel level, int state, BlockPos pos) {
        return level.registry().shapes().interaction(state);
    }

    public boolean isRedstoneConductor(SimLevel level, int state, BlockPos pos) {
        return level.registry().facts(state).has(StateFacts.CONDUCTOR);
    }

    public boolean wireConnectsTo(SimLevel level, int state, BlockPos pos, Direction direction) {
        return level.registry().facts(state).has(StateFacts.SIGNAL_SOURCE) && direction != null;
    }

    private static boolean inheritsWaterlogging(SimLevel level, int state) {
        return level.registry().block(state).bindings().get("classInterfaces").contains("net.minecraft.world.level.block.SimpleWaterloggedBlock");
    }
    public boolean canPlaceLiquid(SimLevel level, int state, BlockPos pos, String fluidType, boolean creativePlayer) {
        return inheritsWaterlogging(level, state) && SimpleWaterlogged.canPlaceLiquid(fluidType);
    }
    public boolean placeLiquid(SimLevel level, int state, BlockPos pos, SimFluidState fluid) {
        return inheritsWaterlogging(level, state) && SimpleWaterlogged.placeLiquid(level, state, fluid);
    }
    /** Null is the empty stack; successful native pickup produces one default bucket item. */
    public String pickupBlock(SimLevel level, int state, BlockPos pos, boolean creativePlayer) {
        return inheritsWaterlogging(level, state) ? SimpleWaterlogged.pickupBlock(level, state, pos) : null;
    }

    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return state;
    }

    public void updateIndirectShapes(SimLevel level, int state, BlockPos pos, int flags, int limit) { }

    public boolean canSurvive(SimLevel level, int state, BlockPos pos) { return true; }

    public int signal(SimLevel level, int state, BlockPos pos, Direction direction) { return ownSignal(level, state, pos); }

    public int ownSignal(SimLevel level, int state, BlockPos pos) { return 0; }

    public int directSignal(SimLevel level, int state, BlockPos pos, Direction direction) { return 0; }
}
