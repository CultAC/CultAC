package ac.cult.placement;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.placement.api.InteractionEngine;
import ac.cult.placement.api.PlacementEngine;
import ac.cult.runtime.RuntimeModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class VanillaInteractionTest {
    @ParameterizedTest
    @EnumSource(RuntimeModel.class)
    void shovelWritesFollowTheOriginalClientSideGuardAndModernTransformerPatch(RuntimeModel model) throws Exception {
        var jar = Path.of(System.getProperty("placementRuntimeJar"));
        try (var runtime = PlacementRuntime.openVanilla(jar, jar.getParent().resolve("runtime"), model)) {
            int grass = state(runtime, "minecraft:grass_block", Map.of("snowy", "false"));
            var base = world(grass, Map.of());
            var floor = new PlacementEngine.World() {
                public int stateAt(int x, int y, int z) {
                    return base.stateAt(x, y, z);
                }

                public int minY() {
                    return base.minY();
                }

                public int height() {
                    return base.height();
                }

                public boolean loaded(int x, int z) {
                    return true;
                }

                public ac.cult.placement.api.GeometryTags tags() {
                    return new ac.cult.placement.api.GeometryTags(
                            Map.of(
                                    "minecraft:turns_into_dirt_path", List.of("minecraft:grass_block"),
                                    "minecraft:air",
                                            List.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air")),
                            Map.of(),
                            Map.of());
                }
            };
            var clicked = new PlacementEngine.Pos(0, 63, 0);
            var shovel = runtime.interact(request(
                    InteractionEngine.Operation.USE_ON,
                    floor,
                    InteractionEngine.Stack.vanilla("minecraft:iron_shovel", 1),
                    clicked,
                    20));
            assertTrue(shovel.consumes());
            // 1.21.11 ShovelItem guards setBlock with !isClientSide. 26.3's
            // stack-selected BlockTransformer writes the client's dirt_path directly.
            assertEquals(
                    model == RuntimeModel.JAVA_26_3 ? 1 : 0, shovel.writes().size());
            if (model == RuntimeModel.JAVA_26_3) {
                assertEquals(
                        "minecraft:dirt_path",
                        runtime.state(shovel.writes().getFirst().state()).block());
                var removed = runtime.interact(request(
                        InteractionEngine.Operation.USE_ON,
                        floor,
                        new InteractionEngine.Stack(
                                "minecraft:iron_shovel", 1, "{\"!minecraft:block_transformer\":{}}"),
                        clicked,
                        20));
                assertFalse(removed.consumes());
                assertTrue(removed.writes().isEmpty());
                var supplied = runtime.interact(request(
                        InteractionEngine.Operation.USE_ON,
                        floor,
                        new InteractionEngine.Stack(
                                "minecraft:stick", 1, "{\"minecraft:block_transformer\":\"minecraft:shovel\"}"),
                        clicked,
                        20));
                assertTrue(supplied.consumes());
                assertEquals(1, supplied.writes().size());
                assertEquals(
                        "minecraft:dirt_path",
                        runtime.state(supplied.writes().getFirst().state()).block());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(RuntimeModel.class)
    void acquiredModelKeepsOriginalBedAndVegetationClientSemantics(RuntimeModel model) throws Exception {
        var jar = Path.of(System.getProperty("placementRuntimeJar"));
        try (var runtime = PlacementRuntime.openVanilla(jar, jar.getParent().resolve("runtime"), model)) {
            int stone = state(runtime, "minecraft:stone", Map.of());
            var clicked = new PlacementEngine.Pos(0, 63, 0);
            var bed = runtime.interact(request(
                    InteractionEngine.Operation.USE_ON,
                    world(stone, Map.of()),
                    InteractionEngine.Stack.vanilla("minecraft:white_bed", 1),
                    clicked,
                    20));
            assertTrue(bed.consumes());
            // Original 1.21.11 BedBlock#setPlacedBy guards the head write with
            // !isClientSide; original 26.3 AbstractBedBlock writes it on either side.
            assertEquals(
                    model == RuntimeModel.JAVA_1_21_11 ? 1 : 2, bed.writes().size());
            assertEquals(
                    "foot",
                    runtime.state(bed.writes().getFirst().state()).properties().get("part"));
            if (model == RuntimeModel.JAVA_26_3)
                assertEquals(
                        "head",
                        runtime.state(bed.writes().getLast().state())
                                .properties()
                                .get("part"));

            // Membership received by the real 1.21.11 client on Paper 26.3:
            // grass is absent from dirt and present in supports_vegetation.
            var tags = new ac.cult.placement.api.GeometryTags(
                    Map.of(
                            "minecraft:dirt",
                                    List.of("minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt"),
                            "minecraft:supports_vegetation", List.of("minecraft:grass_block")),
                    Map.of(),
                    Map.of());
            int grass = state(runtime, "minecraft:grass_block", Map.of("snowy", "false"));
            var floor = world(grass, Map.of());
            var received = new PlacementEngine.World() {
                public int stateAt(int x, int y, int z) {
                    return floor.stateAt(x, y, z);
                }

                public int minY() {
                    return floor.minY();
                }

                public int height() {
                    return floor.height();
                }

                public boolean loaded(int x, int z) {
                    return true;
                }

                public ac.cult.placement.api.GeometryTags tags() {
                    return tags;
                }
            };
            var sunflower = runtime.interact(request(
                    InteractionEngine.Operation.USE_ON,
                    received,
                    InteractionEngine.Stack.vanilla("minecraft:sunflower", 1),
                    clicked,
                    20));
            assertEquals(model == RuntimeModel.JAVA_26_3, sunflower.consumes());
            assertEquals(
                    model == RuntimeModel.JAVA_26_3 ? 2 : 0, sunflower.writes().size());
            assertEquals(model == RuntimeModel.JAVA_26_3, sunflower.inventory().containsKey(0));
        }
    }

    @ParameterizedTest
    @EnumSource(RuntimeModel.class)
    void ordinaryPlacementCakeAndBreakingUseVanillaWithoutHostMinecraft(RuntimeModel model) throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("net.minecraft.world.level.Level"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.bukkit.Bukkit"));
        var jar = Path.of(System.getProperty("placementRuntimeJar"));
        try (var runtime = PlacementRuntime.openVanilla(jar, jar.getParent().resolve("runtime"), model)) {
            assertEquals(model, runtime.model());
            assertEquals(model == RuntimeModel.JAVA_26_3 ? 35723 : 29671, runtime.stateCount());
            if (model == RuntimeModel.JAVA_1_21_11) assertFalse(runtime.narrowed());
            int stone = state(runtime, "minecraft:stone", Map.of());
            int dirt = state(runtime, "minecraft:dirt", Map.of());
            int cake = state(runtime, "minecraft:cake", Map.of("bites", "0"));
            var floor = world(stone, Map.of());
            var place = runtime.interact(request(
                    InteractionEngine.Operation.USE_ON,
                    floor,
                    InteractionEngine.Stack.vanilla("minecraft:dirt", 3),
                    new PlacementEngine.Pos(0, 63, 0),
                    20));
            assertTrue(place.consumes());
            assertEquals(List.of(new PlacementEngine.Write(new PlacementEngine.Pos(0, 64, 0), dirt)), place.writes());
            assertEquals(2, place.inventory().get(0).count());
            assertEquals(0, floor.stateAt(0, 64, 0));
            var food = runtime.interact(request(
                    InteractionEngine.Operation.USE_ON,
                    world(stone, Map.of(new PlacementEngine.Pos(0, 64, 0), cake)),
                    InteractionEngine.Stack.EMPTY,
                    new PlacementEngine.Pos(0, 64, 0),
                    12));
            assertTrue(food.consumes());
            assertEquals(14, food.food());
            assertEquals(
                    state(runtime, "minecraft:cake", Map.of("bites", "1")),
                    food.writes().getFirst().state());
            var broken = runtime.interact(request(
                    InteractionEngine.Operation.BREAK,
                    world(stone, Map.of(new PlacementEngine.Pos(0, 64, 0), dirt)),
                    InteractionEngine.Stack.EMPTY,
                    new PlacementEngine.Pos(0, 64, 0),
                    20));
            assertTrue(broken.success());
            assertEquals(List.of(new PlacementEngine.Write(new PlacementEngine.Pos(0, 64, 0), 0)), broken.writes());

            var restricted = new InteractionEngine.Stack(
                    "minecraft:dirt", 3, "{\"minecraft:can_place_on\":{\"blocks\":\"#cult:allowed\"}}");
            var ordinary = request(
                    InteractionEngine.Operation.USE_ON, floor, restricted, new PlacementEngine.Pos(0, 63, 0), 20);
            var base = ordinary.actor();
            var adventure = new InteractionEngine.Actor(
                    base.x(),
                    base.y(),
                    base.z(),
                    base.yaw(),
                    base.pitch(),
                    base.pose(),
                    "ADVENTURE",
                    false,
                    20,
                    false,
                    0,
                    base.inventory(),
                    false,
                    1,
                    4.5);
            var clientTags = new java.util.concurrent.atomic.AtomicReference<ac.cult.placement.api.GeometryTags>();
            var allowed = new ac.cult.placement.api.GeometryTags(
                    Map.of("cult:allowed", List.of("minecraft:stone")), Map.of(), Map.of());
            var denied = new ac.cult.placement.api.GeometryTags(
                    Map.of("cult:allowed", List.of("minecraft:dirt")), Map.of(), Map.of());
            var permitted = new InteractionEngine.Request(
                    ordinary.operation(),
                    new PlacementEngine.World() {
                        public int stateAt(int x, int y, int z) {
                            return floor.stateAt(x, y, z);
                        }

                        public int minY() {
                            return floor.minY();
                        }

                        public int height() {
                            return floor.height();
                        }

                        public boolean loaded(int x, int z) {
                            return floor.loaded(x, z);
                        }

                        public ac.cult.placement.api.GeometryTags tags() {
                            return clientTags.get();
                        }
                    },
                    adventure,
                    ordinary.hand(),
                    ordinary.clicked(),
                    ordinary.face(),
                    ordinary.hitX(),
                    ordinary.hitY(),
                    ordinary.hitZ(),
                    false,
                    "minecraft:overworld",
                    null);
            clientTags.set(allowed);
            assertTrue(
                    runtime.interact(permitted).consumes(), "A custom tag must govern the native adventure predicate");
            clientTags.set(denied);
            assertFalse(
                    runtime.interact(permitted).consumes(), "Newly installed membership must replace the previous one");
            clientTags.set(allowed);
            assertTrue(runtime.interact(permitted).consumes());

            var infinite = new InteractionEngine.Actor(
                    base.x(),
                    base.y(),
                    base.z(),
                    base.yaw(),
                    base.pitch(),
                    base.pose(),
                    "SURVIVAL",
                    false,
                    20,
                    false,
                    0,
                    request(
                                    InteractionEngine.Operation.USE_ON,
                                    floor,
                                    InteractionEngine.Stack.vanilla("minecraft:dirt", 3),
                                    ordinary.clicked(),
                                    20)
                            .actor()
                            .inventory(),
                    false,
                    1,
                    4.5,
                    true);
            var creativeAbility = runtime.interact(new InteractionEngine.Request(
                    ordinary.operation(),
                    floor,
                    infinite,
                    ordinary.hand(),
                    ordinary.clicked(),
                    ordinary.face(),
                    ordinary.hitX(),
                    ordinary.hitY(),
                    ordinary.hitZ(),
                    false,
                    "minecraft:overworld",
                    null));
            assertTrue(creativeAbility.consumes());
            assertFalse(
                    creativeAbility.inventory().containsKey(0),
                    "The received instabuild ability preserves the stack in survival");
        }
    }

    static int state(PlacementRuntime runtime, String block, Map<String, String> properties) {
        for (int id = 0; id < runtime.stateCount(); id++) {
            var state = runtime.state(id);
            if (state.block().equals(block) && state.properties().entrySet().containsAll(properties.entrySet()))
                return id;
        }
        throw new AssertionError("Missing state " + block + properties);
    }

    static PlacementEngine.World world(int floor, Map<PlacementEngine.Pos, Integer> cells) {
        return new PlacementEngine.World() {
            public int stateAt(int x, int y, int z) {
                return cells.getOrDefault(new PlacementEngine.Pos(x, y, z), y == 63 ? floor : 0);
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
        };
    }

    static InteractionEngine.Request request(
            InteractionEngine.Operation operation,
            PlacementEngine.World world,
            InteractionEngine.Stack item,
            PlacementEngine.Pos clicked,
            int food) {
        var inventory = new ArrayList<>(java.util.Collections.nCopies(41, InteractionEngine.Stack.EMPTY));
        inventory.set(0, item);
        var actor = new InteractionEngine.Actor(
                0.5, 64, -2, 0, 35, "STANDING", "SURVIVAL", false, food, false, 0, inventory, false, 1, 4.5);
        return new InteractionEngine.Request(
                operation,
                world,
                actor,
                "MAIN_HAND",
                clicked,
                "UP",
                clicked.x() + 0.5,
                clicked.y() + 1,
                clicked.z() + 0.5,
                false,
                "minecraft:overworld",
                null);
    }
}
