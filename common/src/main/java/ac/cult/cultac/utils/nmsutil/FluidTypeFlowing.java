package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.BlockTags;
import ac.cult.blocksim.engine.FluidQueries;
import ac.cult.blocksim.engine.SimFluidState;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;

public class FluidTypeFlowing {
    public static Vector3dm getFlow(CultPlayer player, int originalX, int originalY, int originalZ) {
        BlockPos pos = new BlockPos(originalX, originalY, originalZ);
        SimFluidState fluidState = ClientFluidQueries.fluidAt(player.compensatedWorld, pos);
        if (fluidState.isEmpty()) return new Vector3dm();

        Vec3 flow = flow(player, pos, fluidState);
        return new Vector3dm(flow.x, flow.y, flow.z);
    }

    public static Vec3 flow(CultPlayer player, BlockPos pos, SimFluidState state) {
        // The dispatcher model is pinned to 26.3, including for older clients.
        var result = FluidQueries.flow(
                new FlowView(player), new ac.cult.blocksim.engine.BlockPos(pos.getX(), pos.getY(), pos.getZ()), state);
        return new Vec3(result.x(), result.y(), result.z());
    }

    /** {@link #flow} at {@code x, y, z} into {@code out} as x, y, z through a {@link #view}, allocating nothing. */
    public static void flow(FluidQueries.View view, int x, int y, int z, SimFluidState state, double[] out) {
        FluidQueries.flow(view, x, y, z, state, out);
    }

    /** The world queries {@link #flow} reads for {@code player}, reusable across calls. */
    public static FluidQueries.View view(CultPlayer player) {
        return new FlowView(player);
    }

    private record FlowView(CultPlayer player) implements FluidQueries.View {
        @Override
        public SimFluidState fluidAt(int x, int y, int z) {
            return ClientFluidQueries.modelFluid(player.compensatedWorld.getBlockStateIdAt(x, y, z));
        }

        @Override
        public boolean blocksFlow(int x, int y, int z) {
            return BlockTags.BLOCKS_FLUID_FLOW.test(player.compensatedWorld.getBlockStateIdAt(x, y, z));
        }

        @Override
        public boolean isIce(int x, int y, int z) {
            return BlockFamilies.ICE.test(player.compensatedWorld.getBlockStateIdAt(x, y, z));
        }

        @Override
        public boolean isFaceSturdy(int x, int y, int z, ac.cult.blocksim.engine.Direction face) {
            var position = new BlockPos(x, y, z);
            return player.compensatedWorld
                    .geometry()
                    .isFaceSturdy(
                            player.compensatedWorld.getBlockStateIdAt(position),
                            position,
                            Direction.values()[face.ordinal()]);
        }
    }
}
