package ac.cult.cultac.utils.blockplace;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.minecraft.ModelDimensions;
import ac.cult.cultac.vanilla.VanillaBootstrap;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.Test;

class StandaloneModelTest {
    private static VanillaBootstrap model;

    @org.junit.jupiter.api.BeforeAll
    static void startModel() {
        model = VanillaBootstrap.open();
    }

    @org.junit.jupiter.api.AfterAll
    static void stopModel() {
        if (model != null) model.close();
    }

    @Test
    void emptyNativeAdventurePredicatesKeepTheirDenyingBehaviorThroughThePersistentCodec() {
        var empty = new net.minecraft.world.item.AdventureModePredicate(java.util.List.of());
        var patch = net.minecraft.core.component.DataComponentPatch.builder()
                .set(net.minecraft.core.component.DataComponents.CAN_BREAK, empty)
                .remove(net.minecraft.core.component.DataComponents.CAN_PLACE_ON)
                .set(net.minecraft.core.component.DataComponents.DAMAGE, 7)
                .build();
        var registries = model.registries();
        var json = ac.cult.cultac.network.codec.NativeAdventurePredicates.encodePatch(patch, registries);
        var normalized = net.minecraft.core.component.DataComponentPatch.CODEC
                .parse(RegistryOps.create(JsonOps.INSTANCE, registries), json)
                .getOrThrow()
                .split();
        assertTrue(normalized.removed().contains(net.minecraft.core.component.DataComponents.CAN_PLACE_ON));
        assertEquals(7, normalized.added().get(net.minecraft.core.component.DataComponents.DAMAGE));
        var level = (net.minecraft.world.level.LevelReader) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {net.minecraft.world.level.LevelReader.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getBlockState")) return Blocks.STONE.defaultBlockState();
                    throw new AssertionError("Unexpected world query " + method.getName());
                });
        var block = new net.minecraft.world.level.block.state.pattern.BlockInWorld(
                level, net.minecraft.core.BlockPos.ZERO, true);
        assertFalse(empty.test(block));
        assertFalse(normalized
                .added()
                .get(net.minecraft.core.component.DataComponents.CAN_BREAK)
                .test(block));
    }

    @Test
    void legacyDimensionsDecodeWithVanillaDefaultsAndKeepReceivedGeometryAndLavaRules() {
        // Independent legacy registry payload; not produced by the converter under test.
        String payload = """
                {"has_skylight":false,"has_ceiling":true,"coordinate_scale":8.0,
                 "min_y":-48,"height":256,"logical_height":128,
                 "infiniburn":"#minecraft:infiniburn_overworld","ambient_light":0.2,
                 "monster_spawn_light_level":7,"monster_spawn_block_light_limit":0,
                 "fixed_time":6000,"ultrawarm":1,"piglin_safe":1,"has_raids":0,
                 "respawn_anchor_works":1,"natural":false,"bed_works":false}
                """;
        var registries = model.newConnection().registries().access();
        for (int protocol = 768; protocol <= 773; protocol++) {
            String projected = ModelDimensions.project(
                    "cult:custom", payload, ProtocolVersion.of(protocol), ProtocolVersion.V26_3);
            var json = JsonParser.parseString(projected).getAsJsonObject();
            var dimension = DimensionType.NETWORK_CODEC
                    .parse(RegistryOps.create(JsonOps.INSTANCE, registries), json)
                    .getOrThrow();
            assertEquals(-48, dimension.minY());
            assertEquals(256, dimension.height());
            assertEquals(128, dimension.logicalHeight());
            assertEquals(8.0, dimension.coordinateScale());
            assertFalse(dimension.hasSkyLight());
            assertTrue(dimension.hasCeiling());
            assertTrue(dimension.hasFixedTime());
            assertTrue(dimension.attributes().applyModifier(EnvironmentAttributes.FAST_LAVA, false));
            assertEquals(DimensionType.Skybox.OVERWORLD, dimension.skybox());
            assertEquals(0, dimension.timelines().size());
            assertTrue(dimension.defaultClock().isEmpty());
            assertTrue(
                    json.getAsJsonObject("attributes").keySet().stream().allMatch(key -> key.startsWith("gameplay/")));
        }
    }

    @Test
    void modernModifierConversionKeepsReceivedAttributesAndCustomClock() {
        String payload = """
                {"attributes":{"visual/fog_color":{"modifier":"overlay","argument":"#123456"},
                 "gameplay/fast_lava":true},
                 "timelines":["cult:custom"],"default_clock":"cult:custom"}
                """;
        var projected = JsonParser.parseString(
                        ModelDimensions.project("cult:custom", payload, ProtocolVersion.V26_3, ProtocolVersion.V26_2))
                .getAsJsonObject();
        var fog = projected.getAsJsonObject("attributes").getAsJsonObject("visual/fog_color");
        assertEquals("override", fog.get("modifier").getAsString());
        assertEquals("#123456", fog.get("argument").getAsString());
        assertTrue(projected
                .getAsJsonObject("attributes")
                .get("gameplay/fast_lava")
                .getAsBoolean());
        assertEquals("cult:custom", projected.getAsJsonArray("timelines").get(0).getAsString());
        assertEquals("cult:custom", projected.get("default_clock").getAsString());
    }

    @Test
    void observedWireValuesReachNativeRecordsForEverySupportedVersion(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        ac.cult.cultac.codec.ObservedValueAssertions.verify(model, directory);
    }

    @Test
    void everyObservedComponentFixtureLoadsThroughTheVanillaCodec() throws Exception {
        var state = model.newConnection();
        var tagName = net.minecraft.resources.Identifier.parse("cult:allowed");
        state.execute(() -> {
            state.appendTags(java.util.Map.of(
                    net.minecraft.core.registries.Registries.BLOCK,
                    new net.minecraft.tags.TagNetworkSerialization.NetworkPayload(java.util.Map.of(
                            tagName,
                            it.unimi.dsi.fastutil.ints.IntList.of(
                                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(Blocks.STONE)),
                            net.minecraft.resources.Identifier.parse("cult:mineable"),
                            it.unimi.dsi.fastutil.ints.IntList.of(
                                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(Blocks.STONE)))),
                    net.minecraft.core.registries.Registries.ENTITY_TYPE,
                    new net.minecraft.tags.TagNetworkSerialization.NetworkPayload(java.util.Map.of(
                            net.minecraft.resources.Identifier.parse("cult:equippable"),
                            it.unimi.dsi.fastutil.ints.IntList.of(
                                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getId(
                                            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                                    .get(net.minecraft.resources.Identifier.parse("minecraft:pig"))
                                                    .orElseThrow()
                                                    .value()))))));
            state.finishOlder();
        });
        try (var paths = java.nio.file.Files.walk(java.nio.file.Path.of(System.getProperty("wireValueFixtures")))) {
            var fixtures =
                    paths.filter(path -> path.toString().endsWith(".nbt")).toList();
            assertTrue(fixtures.size() >= 200, "All supported wire schemas must supply fixtures");
            for (var fixture : fixtures) {
                byte[] bytes = java.nio.file.Files.readAllBytes(fixture);
                state.execute(() -> {
                    var input = io.netty.buffer.Unpooled.wrappedBuffer(bytes);
                    try {
                        var nbt = new net.minecraft.network.FriendlyByteBuf(input).readNbt();
                        var result = assertDoesNotThrow(
                                () -> ac.cult.cultac.network.codec.ObservedComponents.decode(state.registries(), nbt),
                                fixture.toString());
                        assertEquals(1, result.size(), fixture.toString());
                        if (fixture.getFileName().toString().equals("bundle_contents.nbt")) {
                            var child = result.split()
                                    .added()
                                    .get(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS)
                                    .items()
                                    .getFirst();
                            assertEquals(2, child.count());
                            assertSame(
                                    net.minecraft.world.item.Items.IRON_CHAIN,
                                    child.item().value());
                            assertNotNull(child.get(net.minecraft.core.component.DataComponents.CREATIVE_SLOT_LOCK));
                            assertEquals(
                                    net.minecraft.world.item.component.MapPostProcessing.SCALE,
                                    child.get(net.minecraft.core.component.DataComponents.MAP_POST_PROCESSING));
                            assertNotNull(child.get(net.minecraft.core.component.DataComponents.CAN_BREAK));
                            assertTrue(child.components()
                                    .split()
                                    .removed()
                                    .contains(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE));
                        }
                    } finally {
                        input.release();
                    }
                });
            }
        }
    }

    @Test
    void vanillaModelPlacesBlocksWithoutBukkitOrServer() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.bukkit.Bukkit"));
        try (var runtime = ac.cult.placement.PlacementRuntime.openVanilla(
                java.nio.file.Path.of(System.getProperty("placementRuntimeJar")))) {
            model.newConnection().execute(() -> {
                assertTrue(model.registries().registries().count() > 20);
                var inventory = new java.util.ArrayList<>(
                        java.util.Collections.nCopies(43, ac.cult.placement.api.InteractionEngine.Stack.EMPTY));
                inventory.set(0, ac.cult.placement.api.InteractionEngine.Stack.vanilla("minecraft:dirt", 3));
                var actor = new ac.cult.placement.api.InteractionEngine.Actor(
                        .5, 64, -2, 0, 30, "STANDING", "SURVIVAL", false, 20, false, 0, inventory, false, 1, 4.5);
                var world = new ac.cult.placement.api.PlacementEngine.World() {
                    public int stateAt(int x, int y, int z) {
                        return net.minecraft.world.level.block.Block.getId(
                                (y <= 63 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
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
                };
                var client = new ac.cult.cultac.utils.latency.ClientComponentRegistries();
                var context =
                        new ac.cult.cultac.utils.minecraft.MinecraftRegistries(model::registries, model::resources);
                var result = runtime.interact(new ac.cult.placement.api.InteractionEngine.Request(
                        ac.cult.placement.api.InteractionEngine.Operation.USE_ON,
                        world,
                        actor,
                        "MAIN_HAND",
                        new ac.cult.placement.api.PlacementEngine.Pos(0, 63, 0),
                        "UP",
                        .5,
                        64,
                        .5,
                        false,
                        "minecraft:overworld",
                        null));
                assertTrue(result.consumes());
                assertEquals(
                        java.util.List.of(new ac.cult.placement.api.PlacementEngine.Write(
                                new ac.cult.placement.api.PlacementEngine.Pos(0, 64, 0),
                                net.minecraft.world.level.block.Block.getId(Blocks.DIRT.defaultBlockState()))),
                        result.writes());
                assertEquals(2, result.inventory().get(0).count());
                assertEquals(3, inventory.get(0).count());
                var supportTag = net.minecraft.resources.Identifier.parse("minecraft:supports_vegetation");
                var customTag = net.minecraft.resources.Identifier.parse("cult:test");
                int stone = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(Blocks.STONE);
                client.appendTags(new ac.cult.cultac.network.packet.RegistryTags(java.util.Map.of(
                        net.minecraft.core.registries.Registries.BLOCK,
                        new net.minecraft.tags.TagNetworkSerialization.NetworkPayload(java.util.Map.of(
                                supportTag,
                                it.unimi.dsi.fastutil.ints.IntList.of(stone),
                                customTag,
                                it.unimi.dsi.fastutil.ints.IntList.of(stone))))));
                var receivedTags = client.geometryTags(context);
                assertEquals(
                        java.util.List.of("minecraft:stone"),
                        receivedTags.blocks().get("cult:test"));
                assertSame(receivedTags, client.geometryTags(context));
                var taggedWorld = new ac.cult.placement.api.PlacementEngine.World() {
                    public int stateAt(int x, int y, int z) {
                        return world.stateAt(x, y, z);
                    }

                    public int minY() {
                        return world.minY();
                    }

                    public int height() {
                        return world.height();
                    }

                    public boolean loaded(int x, int z) {
                        return true;
                    }

                    public ac.cult.placement.api.GeometryTags tags() {
                        return client.geometryTags(context);
                    }
                };
                var flower = new ac.cult.placement.api.PlacementEngine.Request(
                        taggedWorld,
                        "minecraft:dandelion",
                        2,
                        java.util.Map.of(),
                        new ac.cult.placement.api.PlacementEngine.Pos(0, 63, 0),
                        "UP",
                        .5,
                        64,
                        .5,
                        false,
                        .5,
                        64,
                        -2,
                        0,
                        30,
                        false,
                        false);
                assertTrue(runtime.place(flower).consumes(), "Received numeric tag IDs must control native placement");
                client.appendTags(new ac.cult.cultac.network.packet.RegistryTags(java.util.Map.of(
                        net.minecraft.core.registries.Registries.BLOCK,
                        net.minecraft.tags.TagNetworkSerialization.NetworkPayload.EMPTY)));
                assertFalse(runtime.place(flower).consumes(), "An empty received payload must clear custom support");

                // A shovel's default block transformer (a vanilla data registry entry) makes a path.
                var shovelInventory = new java.util.ArrayList<>(
                        java.util.Collections.nCopies(43, ac.cult.placement.api.InteractionEngine.Stack.EMPTY));
                shovelInventory.set(
                        0, ac.cult.placement.api.InteractionEngine.Stack.vanilla("minecraft:iron_shovel", 1));
                var shovelActor = new ac.cult.placement.api.InteractionEngine.Actor(
                        .5, 64, -2, 0, 30, "STANDING", "SURVIVAL", false, 20, false, 0, shovelInventory, false, 1, 4.5);
                var grass = new ac.cult.placement.api.PlacementEngine.World() {
                    public int stateAt(int x, int y, int z) {
                        return net.minecraft.world.level.block.Block.getId(
                                (x == 0 && y == 64 && z == 0 ? Blocks.GRASS_BLOCK : y == 63 ? Blocks.STONE : Blocks.AIR)
                                        .defaultBlockState());
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
                };
                var path = runtime.interact(new ac.cult.placement.api.InteractionEngine.Request(
                        ac.cult.placement.api.InteractionEngine.Operation.USE_ON,
                        grass,
                        shovelActor,
                        "MAIN_HAND",
                        new ac.cult.placement.api.PlacementEngine.Pos(0, 64, 0),
                        "UP",
                        .5,
                        65,
                        .5,
                        false,
                        "minecraft:overworld",
                        null));
                assertTrue(path.consumes());
                assertEquals(
                        java.util.List.of(new ac.cult.placement.api.PlacementEngine.Write(
                                new ac.cult.placement.api.PlacementEngine.Pos(0, 64, 0),
                                net.minecraft.world.level.block.Block.getId(Blocks.DIRT_PATH.defaultBlockState()))),
                        path.writes());
            });
        }
    }

    @Test
    void playTagUpdatesWaitForTheirBoundaryAndCanClearTags() {
        var state = model.newConnection();
        state.execute(() -> {
            var tag = net.minecraft.tags.BlockTags.CLIMBABLE;
            Runnable apply = state.preparePlayTags(java.util.Map.of(
                    net.minecraft.core.registries.Registries.BLOCK,
                    net.minecraft.tags.TagNetworkSerialization.NetworkPayload.EMPTY));
            assertTrue(Blocks.LADDER.defaultBlockState().is(tag));
            apply.run();
            assertFalse(Blocks.LADDER.defaultBlockState().is(tag));
        });
        model.newConnection()
                .execute(
                        () -> assertTrue(Blocks.LADDER.defaultBlockState().is(net.minecraft.tags.BlockTags.CLIMBABLE)));
    }

    @Test
    void equivalentSessionsDoNotRebuildNativeHolderTags() throws Exception {
        var first = model.newConnection();
        var second = model.newConnection();
        var holder = net.minecraft.core.registries.BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.LADDER);
        var tagsField = net.minecraft.core.Holder.Reference.class.getDeclaredField("tags");
        tagsField.setAccessible(true);
        first.execute(first::finish);
        Object initialTags = tagsField.get(holder);
        second.execute(second::finish);
        assertSame(initialTags, tagsField.get(holder), "Identical configuration must reuse installed tag bindings");
        for (int i = 0; i < 100; i++) {
            first.execute(
                    () -> assertTrue(Blocks.LADDER.defaultBlockState().is(net.minecraft.tags.BlockTags.CLIMBABLE)));
            second.execute(
                    () -> assertTrue(Blocks.LADDER.defaultBlockState().is(net.minecraft.tags.BlockTags.CLIMBABLE)));
        }
        assertSame(initialTags, tagsField.get(holder), "Switching players must not rebuild identical holder tag sets");
    }

    @Test
    void connectionTagsAreIsolatedAcrossThreadsAndNestedCalls() throws Exception {
        var first = model.newConnection();
        var second = model.newConnection();
        var key = net.minecraft.core.registries.Registries.BLOCK;
        var tag = net.minecraft.tags.BlockTags.CLIMBABLE;
        first.execute(() -> {
            first.appendTags(java.util.Map.of(
                    key,
                    new net.minecraft.tags.TagNetworkSerialization.NetworkPayload(java.util.Map.of(
                            tag.location(),
                            it.unimi.dsi.fastutil.ints.IntList.of(
                                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(Blocks.STONE))))));
            first.finish();
        });
        second.execute(() -> {
            second.appendTags(java.util.Map.of(
                    key,
                    new net.minecraft.tags.TagNetworkSerialization.NetworkPayload(java.util.Map.of(
                            tag.location(),
                            it.unimi.dsi.fastutil.ints.IntList.of(
                                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(Blocks.DIRT))))));
            second.finish();
        });
        first.execute(() -> {
            assertTrue(Blocks.STONE.defaultBlockState().is(tag));
            assertFalse(Blocks.DIRT.defaultBlockState().is(tag));
            assertThrows(
                    IllegalStateException.class,
                    () -> second.execute(() -> {
                        assertFalse(Blocks.STONE.defaultBlockState().is(tag));
                        assertTrue(Blocks.DIRT.defaultBlockState().is(tag));
                        throw new IllegalStateException("nested task failed");
                    }));
            assertTrue(Blocks.STONE.defaultBlockState().is(tag));
            assertFalse(Blocks.DIRT.defaultBlockState().is(tag));
        });
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> {
                for (int i = 0; i < 100; i++)
                    first.execute(() -> {
                        assertTrue(Blocks.STONE.defaultBlockState().is(tag));
                        assertFalse(Blocks.DIRT.defaultBlockState().is(tag));
                    });
            });
            var b = workers.submit(() -> {
                for (int i = 0; i < 100; i++)
                    second.execute(() -> {
                        assertTrue(Blocks.DIRT.defaultBlockState().is(tag));
                        assertFalse(Blocks.STONE.defaultBlockState().is(tag));
                    });
            });
            a.get();
            b.get();
        }
        // New sessions start with vanilla bindings, even after another backend changed tags.
        model.newConnection().execute(() -> {
            assertFalse(Blocks.STONE.defaultBlockState().is(tag));
            assertFalse(Blocks.DIRT.defaultBlockState().is(tag));
            assertTrue(Blocks.LADDER.defaultBlockState().is(tag));
        });
        assertNull(ac.cult.cultac.vanilla.VanillaContext.current());
        assertTrue(Blocks.LADDER.defaultBlockState().is(tag));
        assertFalse(Blocks.STONE.defaultBlockState().is(tag));
    }
}
