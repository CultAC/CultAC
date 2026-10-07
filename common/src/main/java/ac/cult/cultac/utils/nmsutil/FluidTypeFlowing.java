package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockTags;
import ac.cult.blocksim.engine.SimFluidState;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.ViaClientBlockShapeMappings;
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
        if (!player.isBedrockMovement() && player.getClientVersion().isOlderThan(ClientVersion.V_1_13)) {
            return legacyFlow(player, pos, state);
        }
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

    // Pre-flattening BlockLiquid uses integer metadata differences before normalization.
    private static Vec3 legacyFlow(CultPlayer player, BlockPos pos, SimFluidState state) {
        if (state.isEmpty()) return Vec3.ZERO;
        int level = legacyLevel(state, state);
        Vec3 flow = Vec3.ZERO;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.relative(direction);
            int other = legacyLevel(ClientFluidQueries.fluidAt(player.compensatedWorld, neighbor), state);
            int distance = 0;
            if (other < 0) {
                int block = player.compensatedWorld.getBlockStateIdAt(neighbor);
                if ((BlockIds.is(block, BlockIds.COBWEB)
                        || !ViaClientBlockShapeMappings.legacyMaterialIsSolid(player, block))) {
                    other = legacyLevel(ClientFluidQueries.fluidAt(player.compensatedWorld, neighbor.below()), state);
                    if (other >= 0) distance = other - (level - 8);
                }
            } else distance = other - level;
            flow = flow.add(direction.getStepX() * distance, 0, direction.getStepZ() * distance);
        }
        if (state.falling()) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos neighbor = pos.relative(direction);
                if (legacySolid(player, neighbor, state) || legacySolid(player, neighbor.above(), state)) {
                    flow = flow.normalize().add(0, -6, 0);
                    break;
                }
            }
        }
        return flow.normalize();
    }

    private static int legacyLevel(SimFluidState state, SimFluidState source) {
        if (state.isEmpty() || !state.isSame(source)) return -1;
        return state.falling() ? 0 : 8 - state.amount();
    }

    private static boolean legacySolid(CultPlayer player, BlockPos pos, SimFluidState source) {
        int block = player.compensatedWorld.getBlockStateIdAt(pos);
        if (ClientFluidQueries.fluidAt(player.compensatedWorld, pos).isSame(source) || BlockFamilies.ICE.test(block)) {
            return false;
        }
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_12)) {
            return ViaClientBlockShapeMappings.legacyMaterialIsSolid(player, block);
        }

        // 1.12 BlockLiquid#causesDownwardCurrent excludes stairs and every
        // Block#isExceptBlockForAttachWithPiston entry before testing face shape.
        if (BlockFamilies.STAIR.test(block)
                || BlockFamilies.LEAVES.test(block)
                || BlockFamilies.SHULKER_BOX.test(block)
                || BlockFamilies.TRAP_DOOR.test(block)
                || BlockFamilies.STAINED_GLASS.test(block)
                || BlockIds.is(block, BlockIds.BEACON)
                || BlockIds.is(block, BlockIds.CAULDRON)
                || BlockIds.is(block, BlockIds.GLASS)
                || BlockIds.is(block, BlockIds.GLOWSTONE)
                || BlockIds.is(block, BlockIds.SEA_LANTERN)
                || BlockIds.is(block, BlockIds.PISTON)
                || BlockIds.is(block, BlockIds.STICKY_PISTON)
                || BlockIds.is(block, BlockIds.PISTON_HEAD)) {
            return false;
        }
        // Only horizontal faces are queried here. In 1.12 those are SOLID for
        // full cubes (including double slabs) and soul sand. Snow layers remain
        // UNDEFINED horizontally even at eight layers; their modern box differs.
        return BlockIds.is(block, BlockIds.SOUL_SAND)
                || !BlockFamilies.SNOW_LAYER.test(block)
                        && player.compensatedWorld.geometry().collisionFull(block, pos);
    }
}
