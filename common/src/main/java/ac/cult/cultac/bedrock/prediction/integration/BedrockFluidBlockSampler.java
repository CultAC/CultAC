package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.player.CultPlayer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BedrockFluidBlockSampler {
    private static final Set<String> WATER_TAG =
            DataTables.defaults().tags().getOrDefault("fluid:minecraft:water", Set.of());
    private static final Set<String> LAVA_TAG =
            DataTables.defaults().tags().getOrDefault("fluid:minecraft:lava", Set.of());

    boolean isFluid(int state) {
        return BlockIds.is(state, BlockIds.WATER) || BlockIds.is(state, BlockIds.LAVA);
    }

    PlacedBlockCollision fluidBlock(CultPlayer player, int x, int y, int z, int blockState, String javaState) {
        StateFacts fluid = DataTables.defaults().registry().facts(blockState);
        if (fluid.fluid().equals("minecraft:empty")) {
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
        return PlacedBlockCollision.sampled(
                new BlockPosition(x, y, z), javaState, blockState, bedrockIdentifier, bedrockState, List.of());
    }

    private static boolean isWater(StateFacts fluid) {
        return WATER_TAG.contains(fluid.fluid())
                || fluid.fluid().equals("minecraft:water")
                || fluid.fluid().equals("minecraft:flowing_water");
    }

    private static boolean isLava(StateFacts fluid) {
        return LAVA_TAG.contains(fluid.fluid())
                || fluid.fluid().equals("minecraft:lava")
                || fluid.fluid().equals("minecraft:flowing_lava");
    }

    private static int liquidDepth(StateFacts fluid) {
        return fluid.has(StateFacts.FLUID_SOURCE)
                ? 0
                : 8 - Math.min(fluid.fluidAmount(), 8) + (fluid.fallingFluid() ? 8 : 0);
    }
}
