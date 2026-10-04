package ac.cult.cultac.utils.blockplace;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.placement.PlacementRuntime;
import ac.cult.placement.api.BlockGeometry;
import ac.cult.placement.api.PlacementEngine;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "placementRuntimeJar", matches = ".+")
class IsolatedGeometryTest {
    private static final BlockPos ROOT = new BlockPos(-17, 64, 39);
    private static final PlacementEngine.Pos POS = new PlacementEngine.Pos(-17, 64, 39);

    @org.junit.jupiter.api.Test
    void allStatesAndContextualShapesMatchVanillaWithoutSharingNativeObjects() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        try (var runtime = PlacementRuntime.openVanilla(Path.of(System.getProperty("placementRuntimeJar")))) {
            var view = new View();
            CollisionContext[] contexts = {
                CollisionContext.empty(),
                context(65, false, new ItemStack(Items.SCAFFOLDING), false),
                context(64, true, new ItemStack(Items.LIGHT), false),
                context(64.875, false, new ItemStack(Items.AIR), true)
            };
            BlockGeometry.Context[] actors = {
                null,
                new BlockGeometry.Context(65, false, "minecraft:scaffolding", false),
                new BlockGeometry.Context(64, true, "minecraft:light", false),
                new BlockGeometry.Context(64.875, false, "minecraft:air", true)
            };
            long comparisons = 0;
            for (int id = 0; id < runtime.stateCount(); id++) {
                view.root = Block.stateById(id);
                var description = runtime.state(id);
                assertEquals(
                        BuiltInRegistries.BLOCK.getKey(view.root.getBlock()).toString(), description.block());
                assertEquals(view.root.isAir(), description.air());
                assertEquals(view.root.canBeReplaced(), description.replaceable());
                assertEquals(view.root.getBlock().getFriction(), description.friction());
                assertEquals(view.root.getBlock().getSpeedFactor(), description.speedFactor());
                assertEquals(view.root.getBlock().getJumpFactor(), description.jumpFactor());
                assertEquals(
                        view.root.getProperties().size(),
                        description.properties().size());
                // Registry/cache-only kinds are context-independent. Context-sensitive
                // shapes are checked with both an empty and compensated entity context.
                for (var kind : BlockGeometry.Shape.values()) {
                    int count = switch (kind) {
                        case COLLISION, OUTLINE, VISUAL -> contexts.length;
                        default -> 1;
                    };
                    for (int i = 0; i < count; i++) {
                        var expected = switch (kind) {
                            case COLLISION -> view.root.getCollisionShape(view, ROOT, contexts[i]);
                            case OUTLINE -> view.root.getShape(view, ROOT, contexts[i]);
                            case VISUAL -> view.root.getVisualShape(view, ROOT, contexts[i]);
                            case INTERACTION -> view.root.getInteractionShape(view, ROOT);
                            case SUPPORT -> view.root.getBlockSupportShape(view, ROOT);
                            case OCCLUSION -> view.root.getOcclusionShape();
                        };
                        assertEquals(
                                boxes(expected),
                                runtime.shape(view, POS, id, kind, actors[i]),
                                view.root + " " + kind + " context=" + i);
                        comparisons++;
                    }
                }
            }
            // Ray/placement replacement states can differ from the compensated world.
            view.root = Blocks.AIR.defaultBlockState();
            int stone = Block.getId(Blocks.STONE.defaultBlockState());
            assertEquals(
                    List.of(new PlacementEngine.Box(0, 0, 0, 1, 1, 1)),
                    runtime.shape(view, POS, stone, BlockGeometry.Shape.COLLISION, actors[1]));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.shape(view, POS, -1, BlockGeometry.Shape.COLLISION, null));
            assertThrows(IllegalArgumentException.class, () -> runtime.state(runtime.stateCount()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.shape(
                            view,
                            POS,
                            stone,
                            BlockGeometry.Shape.COLLISION,
                            new BlockGeometry.Context(65, false, "minecraft:missing", false)));
            assertEquals(stone, runtime.state(stone).id(), "Rejected queries must not poison the runtime");
            System.out.println("Isolated contextual geometry: " + comparisons + " exact shape comparisons");
        }
    }

    private static CollisionContext context(double y, boolean descending, ItemStack held, boolean placement) {
        return new EntityCollisionContext(descending, placement, y, held, false, null) {};
    }

    private static List<PlacementEngine.Box> boxes(VoxelShape shape) {
        return shape.toAabbs().stream()
                .map(b -> new PlacementEngine.Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ))
                .toList();
    }

    private static final class View implements PlacementEngine.World, BlockGetter {
        private BlockState root = Blocks.AIR.defaultBlockState();

        public BlockState getBlockState(BlockPos pos) {
            if (ROOT.equals(pos)) return root;
            return pos.getY() < ROOT.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }

        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        public BlockState getBlockStateIfLoaded(BlockPos pos) {
            return getBlockState(pos);
        }

        public FluidState getFluidIfLoaded(BlockPos pos) {
            return getFluidState(pos);
        }

        public int getMinY() {
            return -64;
        }

        public int getHeight() {
            return 384;
        }

        public int stateAt(int x, int y, int z) {
            return Block.getId(getBlockState(new BlockPos(x, y, z)));
        }

        public int minY() {
            return getMinY();
        }

        public int height() {
            return getHeight();
        }

        public boolean loaded(int x, int z) {
            return true;
        }
    }
}
