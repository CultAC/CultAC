package ac.cult.cultac.manager.player;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

public final class SmoketestBlockStateComparisonTest {
    @BeforeClass
    public static void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    public void initialPlantAgesMatchButShearsCompletionDoesNot() {
        for (var block : new net.minecraft.world.level.block.Block[]{
                Blocks.WEEPING_VINES, Blocks.TWISTING_VINES, Blocks.KELP, Blocks.CAVE_VINES}) {
            var initial = block.defaultBlockState().setValue(GrowingPlantHeadBlock.AGE, 0);
            var mature = initial.setValue(GrowingPlantHeadBlock.AGE, 25);
            for (int age = 0; age < 25; age++) {
                var randomized = initial.setValue(GrowingPlantHeadBlock.AGE, age);
                assertTrue(SmoketestSnapshotBridge.blockStatesMatch(randomized.toString(), initial));
                assertFalse(SmoketestSnapshotBridge.blockStatesMatch(randomized.toString(), mature));
                assertFalse(SmoketestSnapshotBridge.blockStatesMatch(mature.toString(), randomized));
            }
            assertTrue(SmoketestSnapshotBridge.blockStatesMatch(mature.toString(), mature));
        }
    }

    @Test
    public void blockTypeAndOtherPropertiesRemainExact() {
        var vine = Blocks.CAVE_VINES.defaultBlockState();
        assertFalse(SmoketestSnapshotBridge.blockStatesMatch(
                vine.setValue(CaveVines.BERRIES, true).toString(), vine.setValue(CaveVines.BERRIES, false)));
        assertFalse(SmoketestSnapshotBridge.blockStatesMatch(
                Blocks.WEEPING_VINES.defaultBlockState().toString(), Blocks.TWISTING_VINES.defaultBlockState()));
        assertFalse(SmoketestSnapshotBridge.blockStatesMatch(
                Blocks.WEEPING_VINES_PLANT.defaultBlockState().toString(), Blocks.WEEPING_VINES.defaultBlockState()));
        var wheat = Blocks.WHEAT.defaultBlockState();
        assertFalse(SmoketestSnapshotBridge.blockStatesMatch(wheat.setValue(CropBlock.AGE, 3).toString(), wheat));
        assertFalse(SmoketestSnapshotBridge.blockStatesMatch(Blocks.AIR.defaultBlockState().toString(), vine));
    }

    @Test
    public void invalidAgesDoNotMatch() {
        var vine = Blocks.WEEPING_VINES.defaultBlockState().setValue(GrowingPlantHeadBlock.AGE, 0);
        for (var invalid : new String[]{"-1", "26", "100000000000000000000"}) {
            assertFalse(SmoketestSnapshotBridge.blockStatesMatch(vine.toString().replace("age=0", "age=" + invalid), vine));
        }
    }
}
