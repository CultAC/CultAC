package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.BlockTags;
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
        var result = ac.cult.blocksim.engine.FluidQueries.flow(
                new FlowView(player), new ac.cult.blocksim.engine.BlockPos(pos.getX(), pos.getY(), pos.getZ()), state);
        return new Vec3(result.x(), result.y(), result.z());
    }

    private record FlowView(CultPlayer player) implements ac.cult.blocksim.engine.FluidQueries.View {
        private BlockPos position(ac.cult.blocksim.engine.BlockPos pos) {
            return new BlockPos(pos.x(), pos.y(), pos.z());
        }

        @Override
        public ac.cult.blocksim.engine.SimFluidState fluidAt(ac.cult.blocksim.engine.BlockPos pos) {
            return ClientFluidQueries.modelFluid(player.compensatedWorld.getBlockStateIdAt(pos.x(), pos.y(), pos.z()));
        }

        @Override
        public boolean blocksFlow(ac.cult.blocksim.engine.BlockPos pos) {
            return BlockTags.BLOCKS_FLUID_FLOW.test(
                    player.compensatedWorld.getBlockStateIdAt(pos.x(), pos.y(), pos.z()));
        }

        @Override
        public boolean isIce(ac.cult.blocksim.engine.BlockPos pos) {
            return BlockFamilies.ICE.test(player.compensatedWorld.getBlockStateIdAt(pos.x(), pos.y(), pos.z()));
        }

        @Override
        public boolean isFaceSturdy(ac.cult.blocksim.engine.BlockPos pos, ac.cult.blocksim.engine.Direction face) {
            var position = position(pos);
            return player.compensatedWorld
                    .geometry()
                    .isFaceSturdy(
                            player.compensatedWorld.getBlockStateIdAt(position),
                            position,
                            Direction.values()[face.ordinal()]);
        }
    }
}
