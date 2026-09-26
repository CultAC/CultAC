package ac.cult.cultac.utils.blockplace;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public final class GrowingPlantPlacementTest {
    private static final BlockPos PLACED = new BlockPos(0, 64, 0);

    @BeforeClass
    public static void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    public void bothVinesPredictSupportedPlacementAndInventoryConsumption() {
        for (boolean upward : new boolean[]{false, true}) {
            var access = new World();
            access.blocks.put(upward ? PLACED.below() : PLACED.above(), Blocks.STONE.defaultBlockState());
            var result = NmsBlockPlaceResolver.simulatePlace(access, snapshot(upward));
            assertTrue(result.getResyncReason(), result.isSuccess());
            assertTrue(result.isConsumeInventory());
            assertEquals(PLACED, result.getPrimaryPlacedPosition());
            var head = result.getChangedBlocks().stream().filter(b -> b.position().equals(PLACED)).findFirst().orElseThrow().state();
            assertSame(upward ? Blocks.TWISTING_VINES : Blocks.WEEPING_VINES, head.getBlock());
            assertTrue(head.getValue(GrowingPlantHeadBlock.AGE) >= 0);
            assertTrue(head.getValue(GrowingPlantHeadBlock.AGE) < 25);
        }
    }

    @Test
    public void unsupportedVinesStillRejectPlacement() {
        for (boolean upward : new boolean[]{false, true}) {
            var result = NmsBlockPlaceResolver.simulatePlace(new World(), snapshot(upward));
            assertFalse(result.isSuccess());
            assertTrue(result.getChangedBlocks().isEmpty());
            assertFalse(result.isConsumeInventory());
        }
    }

    @Test
    public void extendingVinesConvertsPreviousHeadToBody() {
        for (boolean upward : new boolean[]{false, true}) {
            var access = new World();
            Block head = upward ? Blocks.TWISTING_VINES : Blocks.WEEPING_VINES;
            Block body = upward ? Blocks.TWISTING_VINES_PLANT : Blocks.WEEPING_VINES_PLANT;
            BlockPos previous = upward ? PLACED.below() : PLACED.above();
            access.blocks.put(previous, head.defaultBlockState().setValue(GrowingPlantHeadBlock.AGE, 12));
            access.blocks.put(upward ? previous.below() : previous.above(), Blocks.STONE.defaultBlockState());
            var result = NmsBlockPlaceResolver.simulatePlace(access, snapshot(upward));
            assertTrue(result.getResyncReason(), result.isSuccess());
            assertTrue(result.getChangedBlocks().stream().anyMatch(b -> b.position().equals(previous) && b.state().is(body)));
            assertTrue(result.getChangedBlocks().stream().anyMatch(b -> b.position().equals(PLACED) && b.state().is(head)));
        }
    }

    @Test
    public void randomInitialAgesHaveIdenticalVineGeometryAndClimbability() {
        var access = new World();
        var level = PlacementWorldFactory.create(access, snapshot(true)).level();
        for (Block block : new Block[]{Blocks.WEEPING_VINES, Blocks.TWISTING_VINES}) {
            var base = block.defaultBlockState();
            for (int age = 0; age < 25; age++) {
                var state = base.setValue(GrowingPlantHeadBlock.AGE, age);
                assertSame(base.getShape(level, PLACED), state.getShape(level, PLACED));
                assertSame(base.getCollisionShape(level, PLACED), state.getCollisionShape(level, PLACED));
                assertTrue(state.is(BlockTags.CLIMBABLE));
            }
        }
    }

    private static PlacementSnapshot snapshot(boolean upward) {
        Direction face = upward ? Direction.UP : Direction.DOWN;
        BlockPos clicked = PLACED.relative(face.getOpposite());
        double hitY = upward ? 1 : 0;
        Item item = upward ? Items.TWISTING_VINES : Items.WEEPING_VINES;
        return PlacementSnapshot.of(InteractionHand.MAIN_HAND,
                new org.bukkit.inventory.ItemStack(upward ? Material.TWISTING_VINES : Material.WEEPING_VINES, 8),
                new net.minecraft.world.item.ItemStack(item, 8), clicked, PLACED, face,
                new Vec3(clicked.getX() + 0.5, clicked.getY() + hitY, clicked.getZ() + 0.5),
                new Vec3(0.5, hitY, 0.5), false, new Vec3(0.5, 64, -3),
                0, 0, Direction.SOUTH, false, GameMode.SURVIVAL, -64, 320, false);
    }

    private static final class World implements PlacementBlockAccess {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();

        @Override public BlockState getBlockStateAt(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override public FluidState getFluidIfLoaded(BlockPos pos) {
            return getBlockStateAt(pos).getFluidState();
        }

        @Override public boolean isChunkLoaded(int chunkX, int chunkZ) {
            return true;
        }
    }
}
