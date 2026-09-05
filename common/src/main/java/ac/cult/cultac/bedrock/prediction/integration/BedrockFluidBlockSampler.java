package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.player.CultPlayer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;

final class BedrockFluidBlockSampler {
    boolean isFluid(BlockState state) {
        return state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.LAVA;
    }

    PlacedBlockCollision fluidBlock(
            CultPlayer player,
            int x,
            int y,
            int z,
            BlockState blockState,
            String javaState
    ) {
        net.minecraft.world.level.material.FluidState fluid = blockState.getFluidState();
        if (fluid == null || fluid.isEmpty()) {
            return null;
        }
        boolean water = isWater(fluid);
        boolean lava = isLava(fluid);
        if (!water && !lava) {
            return null;
        }
        int depth = liquidDepth(fluid);
        String bedrockIdentifier = water
                ? depth == 0 ? "minecraft:water" : "minecraft:flowing_water"
                : depth == 0 ? "minecraft:lava" : "minecraft:flowing_lava";
        Map<String, Object> bedrockState = new HashMap<>();
        bedrockState.put("liquid_depth", depth);
        Vec3 flow = fluid.getFlow(player.compensatedWorld, new net.minecraft.core.BlockPos(x, y, z));
        if (flow.lengthSqr() >= 1.0E-5D) {
            bedrockState.put("flow_x", flow.x);
            bedrockState.put("flow_y", flow.y);
            bedrockState.put("flow_z", flow.z);
        }
        return PlacedBlockCollision.sampled(
                new BlockPosition(x, y, z),
                javaState,
                blockState,
                bedrockIdentifier,
                bedrockState,
                List.of());
    }

    private static boolean isWater(net.minecraft.world.level.material.FluidState fluid) {
        return fluid.is(FluidTags.WATER)
                || isFluidType(fluid.getType(), Fluids.WATER, Fluids.FLOWING_WATER);
    }

    private static boolean isLava(net.minecraft.world.level.material.FluidState fluid) {
        return fluid.is(FluidTags.LAVA)
                || isFluidType(fluid.getType(), Fluids.LAVA, Fluids.FLOWING_LAVA);
    }

    private static boolean isFluidType(Fluid actual, Fluid source, Fluid flowing) {
        return actual == source || actual == flowing;
    }

    private static int liquidDepth(net.minecraft.world.level.material.FluidState fluid) {

        return fluid.isSource() ? 0 : Math.max(1, Math.min(8, 8 - fluid.getAmount()));
    }
}
