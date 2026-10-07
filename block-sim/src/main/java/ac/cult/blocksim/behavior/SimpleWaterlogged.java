package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.SimFluidState;
import ac.cult.blocksim.engine.SimLevel;

/** Default interface methods inherited by the generated SimpleWaterloggedBlock bindings. */
final class SimpleWaterlogged {
    private SimpleWaterlogged() { }

    static boolean canPlaceLiquid(String fluidType) { return fluidType.equals("minecraft:water"); }

    static boolean placeLiquid(SimLevel level, int state, SimFluidState fluid) {
        // setBlockAndUpdate and scheduling are entirely guarded by !isClientSide.
        return !BlockBehavior.bool(level, state, "waterlogged") && fluid.is("minecraft:water");
    }

    static String pickupBlock(SimLevel level, int state, BlockPos pos) {
        if (!BlockBehavior.bool(level, state, "waterlogged")) return null;
        level.setBlock(pos, level.registry().with(state, "waterlogged", "false"), 3);
        // Vanilla checks the original state after the write, not the current state.
        if (!level.behavior(state).canSurvive(level, state, pos)) level.destroyBlock(pos, 512);
        return "minecraft:water_bucket";
    }
}
