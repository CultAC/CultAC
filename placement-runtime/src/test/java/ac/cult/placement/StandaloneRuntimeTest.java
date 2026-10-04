package ac.cult.placement;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.placement.api.BlockGeometry;
import ac.cult.placement.api.PlacementEngine;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** The host classpath contains only this module, its JDK API, and JUnit. */
@EnabledIfSystemProperty(named = "placementRuntimeJar", matches = ".+")
class StandaloneRuntimeTest {
    @org.junit.jupiter.api.Test
    void geometryAndPlacementWorkWithoutPaperOrMinecraftOnTheHost() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.bukkit.Bukkit"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("net.minecraft.world.level.block.Block"));
        var runtime = PlacementRuntime.openVanilla(Path.of(System.getProperty("placementRuntimeJar")));
        try (runtime) {
            int stone = -1, scaffolding = -1, dirt = -1;
            for (int id = 0; id < runtime.stateCount(); id++) {
                var state = runtime.state(id);
                if (state.block().equals("minecraft:stone")) stone = id;
                if (state.block().equals("minecraft:dirt")) dirt = id;
                if (state.block().equals("minecraft:scaffolding")) scaffolding = id;
            }
            assertTrue(stone >= 0 && scaffolding >= 0 && dirt >= 0);
            int floor = stone;
            var clientTags = new java.util.concurrent.atomic.AtomicReference<ac.cult.placement.api.GeometryTags>();
            var world = new PlacementEngine.World() {
                public int stateAt(int x, int y, int z) {
                    return y == 63 ? floor : 0;
                }

                public int minY() {
                    return -64;
                }

                public int height() {
                    return 384;
                }

                public boolean loaded(int x, int z) {
                    return true;
                }

                public int brightness(int x, int y, int z) {
                    return 15;
                }

                public ac.cult.placement.api.GeometryTags tags() {
                    return clientTags.get();
                }
            };
            var pos = new PlacementEngine.Pos(0, 64, 0);
            var shape = runtime.shape(
                    world,
                    pos,
                    scaffolding,
                    BlockGeometry.Shape.OUTLINE,
                    new BlockGeometry.Context(65, false, "minecraft:scaffolding", false));
            assertEquals(List.of(new PlacementEngine.Box(0, 0, 0, 1, 1, 1)), shape);
            assertThrows(UnsupportedOperationException.class, () -> shape.clear());
            var result = runtime.place(new PlacementEngine.Request(
                    world,
                    "minecraft:dirt",
                    3,
                    Map.of(),
                    new PlacementEngine.Pos(0, 63, 0),
                    "UP",
                    0.5,
                    64,
                    0.5,
                    false,
                    0.5,
                    64,
                    -2,
                    0,
                    45,
                    false,
                    false));
            assertTrue(result.consumes());
            assertEquals(2, result.remaining());
            assertEquals(List.of(new PlacementEngine.Write(pos, dirt)), result.writes());
            assertEquals(0, world.stateAt(0, 64, 0), "Speculation cannot mutate the host's world");
            var flower = new PlacementEngine.Request(
                    world,
                    "minecraft:dandelion",
                    2,
                    Map.of(),
                    new PlacementEngine.Pos(0, 63, 0),
                    "UP",
                    0.5,
                    64,
                    0.5,
                    false,
                    0.5,
                    64,
                    -2,
                    0,
                    45,
                    false,
                    false);
            assertFalse(runtime.place(flower).consumes(), "Vanilla vegetation does not survive on stone");
            clientTags.set(new ac.cult.placement.api.GeometryTags(
                    Map.of("minecraft:supports_vegetation", List.of("minecraft:stone")), Map.of(), Map.of()));
            assertTrue(runtime.place(flower).consumes(), "The received support tag governs vanilla placement");
            var valid = clientTags.get();
            clientTags.set(new ac.cult.placement.api.GeometryTags(
                    Map.of(), Map.of("cult:test", List.of("cult:missing_item")), Map.of()));
            assertThrows(IllegalArgumentException.class, () -> runtime.place(flower));
            clientTags.set(valid);
            assertTrue(runtime.place(flower).consumes(), "Invalid membership must not partially replace active tags");
            clientTags.set(new ac.cult.placement.api.GeometryTags(Map.of(), Map.of(), Map.of()));
            assertFalse(runtime.place(flower).consumes(), "Changing backend bindings must clear previous memberships");
            clientTags.set(new ac.cult.placement.api.GeometryTags(
                    Map.of("minecraft:supports_vegetation", List.of("minecraft:stone")), Map.of(), Map.of()));
            assertTrue(runtime.place(flower).consumes(), "Returning to a previous backend must restore its tags");
            var support = new ac.cult.placement.api.GeometryTags(
                    Map.of("minecraft:supports_vegetation", List.of("minecraft:stone")), Map.of(), Map.of());
            var empty = new ac.cult.placement.api.GeometryTags(Map.of(), Map.of(), Map.of());
            clientTags.set(empty);
            runtime.outline(world, pos);
            assertFalse(runtime.place(flower).consumes(), "A shape query must install that client's tag snapshot");
            clientTags.set(support);
            runtime.outline(world, pos);
            assertTrue(runtime.place(flower).consumes(), "Interleaved clients must restore their own tag snapshot");
        }
        assertThrows(IllegalStateException.class, runtime::stateCount);
        runtime.close();
    }
}
