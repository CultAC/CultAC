package ac.cult.cultac.bedrock.prediction.simulation.postmove;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class BedrockLiquidClimbOutMovementTest {
    @Test
    public void climbOutProbeTreatsLiquidAsBlockedSpace() {
        BlockCollisionWorld world = new BlockCollisionWorld(List.of(PlacedBlockCollision.manual(
                new BlockPosition(229, 84, -72),
                "minecraft:bubble_column[drag=false]",
                "minecraft:bubble_column",
                Map.of("drag_down", false),
                List.of())));

        boolean applies = BedrockLiquidClimbOutMovement.applies(
                true,
                new Vec3d(229.52525329589844D, 82.9556884765625D, -71.30000305175781D),
                new Vec3d(229.5301055908203D, 83.33866882324219D, -71.30000305175781D),
                new Vec3d(0.0043617003D, 0.3737434D, 0.0D),
                new BedrockCollisionFlags(false, true, false),
                world,
                PlayerDimensionsState.DEFAULT);

        assertFalse(applies);
    }

    // A replayed swim along a cobblestone wall at z -99 in a channel whose top cells hold water levels 6 and 5,
    // surfaces below the lifted box. The client reports no climb out while the lifted box reaches a water cell
    // and climbs out (0.3) on the tick it lies over the dry column at x 293.
    @Test
    public void climbOutWaitsUntilTheLiftedBoxLeavesEveryLiquidCell() {
        BlockCollisionWorld world = channelWorld();
        BedrockCollisionFlags wall = new BedrockCollisionFlags(false, true, false, true, false, false, false, true);

        assertFalse(BedrockLiquidClimbOutMovement.applies(
                true,
                new Vec3d(294.00037D, 83.08671D, -99.7D),
                new Vec3d(293.9D, 83.07437D, -99.7D),
                new Vec3d(-0.080297D, -0.031898D, 0.0D),
                wall,
                world,
                PlayerDimensionsState.DEFAULT));
        assertTrue(BedrockLiquidClimbOutMovement.applies(
                true,
                new Vec3d(293.9D, 83.07437D, -99.7D),
                new Vec3d(293.7864D, 83.0595D, -99.7D),
                new Vec3d(-0.090863D, -0.031898D, 0.0D),
                wall,
                world,
                PlayerDimensionsState.DEFAULT));
    }

    private static BlockCollisionWorld channelWorld() {
        ArrayList<PlacedBlockCollision> blocks = new ArrayList<>();
        for (int x = 293; x <= 295; x++) {
            blocks.add(cobblestone(x, 82, -101));
            blocks.add(cobblestone(x, 82, -99));
            blocks.add(cobblestone(x, 83, -99));
            if (x > 293) {
                blocks.add(cobblestone(x, 83, -101));
            }
        }
        blocks.add(cobblestone(293, 82, -100));
        blocks.add(water(294, 82, 0));
        blocks.add(water(295, 82, 0));
        blocks.add(water(294, 83, 6));
        blocks.add(water(295, 83, 5));
        return new BlockCollisionWorld(blocks);
    }

    private static PlacedBlockCollision cobblestone(int x, int y, int z) {
        return PlacedBlockCollision.manual(
                new BlockPosition(x, y, z),
                "minecraft:cobblestone",
                "minecraft:cobblestone",
                List.of(new WorldCollisionBox(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D)));
    }

    private static PlacedBlockCollision water(int x, int y, int level) {
        return PlacedBlockCollision.manual(
                new BlockPosition(x, y, -100),
                "minecraft:water[level=" + level + "]",
                level == 0 ? "minecraft:water" : "minecraft:flowing_water",
                Map.of("liquid_depth", level),
                List.of());
    }
}
