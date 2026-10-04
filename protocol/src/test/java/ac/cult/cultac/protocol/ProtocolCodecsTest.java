package ac.cult.cultac.protocol;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.Unpooled;
import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProtocolCodecsTest {
    @TempDir
    Path directory;

    @Test
    void tagRenamesRetainTheLastSuppliedValueAndItsPosition() {
        var tags = new LinkedHashMap<String, List<String>>();
        tags.put("minecraft:dry_vegetation_may_place_on", List.of("minecraft:stone"));
        tags.put("cult:marker", List.of("minecraft:dirt"));
        tags.put("minecraft:dead_bush_may_place_on", List.of("minecraft:sand"));
        var projected = ProtocolCodecs.projectTags(
                "minecraft:block", tags, ProtocolVersion.V1_21_4, ProtocolVersion.V1_21_5, ProtocolVersion.V1_21_5);
        assertEquals(List.of("minecraft:sand"), projected.get("minecraft:dry_vegetation_may_place_on"));
        assertFalse(projected.containsKey("minecraft:dead_bush_may_place_on"));
        assertEquals(
                List.of("cult:marker", "minecraft:dry_vegetation_may_place_on"),
                projected.keySet().stream().limit(2).toList());
        assertEquals(List.of("minecraft:stone"), tags.get("minecraft:dry_vegetation_may_place_on"));
        assertThrows(UnsupportedOperationException.class, () -> projected.put("cult:new", List.of()));
    }

    @Test
    void vendorTagsAreAddedButNeverOverwriteSuppliedEmptyTags() {
        var version = ProtocolVersion.V1_21_4;
        var target = ProtocolVersion.V1_21_5;
        var added = ProtocolCodecs.projectTags("minecraft:block", Map.of(), version, version, target);
        assertEquals(
                List.of("minecraft:bamboo_sapling", "minecraft:bamboo"), added.get("minecraft:sword_instantly_mines"));
        var supplied = ProtocolCodecs.projectTags(
                "minecraft:block", Map.of("minecraft:sword_instantly_mines", List.of()), version, version, target);
        assertEquals(List.of(), supplied.get("minecraft:sword_instantly_mines"));
    }

    @Test
    void clientDowngradesRemoveUnsupportedTagMembersBeforeReturningToTheHostModel() {
        var tags = Map.of("cult:flowers", List.of("minecraft:golden_dandelion", "minecraft:grass_block"));
        var projected = ProtocolCodecs.projectTags(
                "minecraft:block", tags, ProtocolVersion.V26_3, ProtocolVersion.V1_21_11, ProtocolVersion.V26_3);
        assertEquals(List.of("minecraft:grass_block"), projected.get("cult:flowers"));
        var entities = ProtocolCodecs.projectTags(
                "minecraft:entity_type",
                Map.of("cult:entities", List.of("minecraft:copper_golem")),
                ProtocolVersion.V26_3,
                ProtocolVersion.V1_21_3,
                ProtocolVersion.V26_3);
        assertEquals(List.of("minecraft:frog"), entities.get("cult:entities"));
        assertThrows(
                ProtocolResolutionException.class,
                () -> ProtocolCodecs.projectTags(
                        "minecraft:block",
                        Map.of("cult:invalid", List.of("cult:unknown")),
                        ProtocolVersion.V26_3,
                        ProtocolVersion.V1_21_11,
                        ProtocolVersion.V26_3));
    }

    @Test
    void everyHostClientModelCombinationProducesValidOrderedTagMembers() {
        var decoder = ProtocolCodecs.decoder();
        for (var source : ProtocolVersion.values())
            for (var client : ProtocolVersion.values())
                for (var target : ProtocolVersion.values())
                    for (String registry :
                            List.of("minecraft:block", "minecraft:item", "minecraft:entity_type", "minecraft:fluid")) {
                        var original = ModelRegistryData.load(source).registry(registry);
                        var destination = ModelRegistryData.load(target).registry(registry);
                        var members = List.of(original.name(0), original.name(original.size() - 1), original.name(0));
                        var projected = decoder.tags(registry, Map.of("cult:ordered", members), source, client, target);
                        assertTrue(projected.containsKey("cult:ordered"));
                        for (var values : projected.values())
                            for (String member : values) assertTrue(destination.id(member) >= 0, member);
                        if (registry.equals("minecraft:fluid")) assertEquals(members, projected.get("cult:ordered"));
                    }
    }

    @Test
    void sharedTagHandlersDoNotMixConcurrentPlayerSnapshots() throws Exception {
        var decoder = ProtocolCodecs.decoder();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (String member : List.of("minecraft:stone", "minecraft:dirt"))
                futures.add(executor.submit(() -> {
                    for (int attempt = 0; attempt < 100; attempt++) {
                        var projected = decoder.tags(
                                "minecraft:block",
                                Map.of("cult:private", List.of(member)),
                                ProtocolVersion.V26_3,
                                ProtocolVersion.V1_21_3,
                                ProtocolVersion.V26_3);
                        assertEquals(List.of(member), projected.get("cult:private"));
                    }
                }));
            for (var future : futures) future.get();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sharedLibraryClosesAndReopensWithoutRetainingTheOldClassloader() {
        var old = ProtocolCodecs.decoder();
        ProtocolCodecs.close();
        assertThrows(IllegalStateException.class, () -> old.mappings(ProtocolVersion.V26_3, ProtocolVersion.V26_3));
        var reopened = ProtocolCodecs.decoder();
        assertNotSame(old, reopened);
        assertNotSame(old.getClass().getClassLoader(), reopened.getClass().getClassLoader());
        assertEquals(
                0,
                reopened.mappings(ProtocolVersion.V26_3, ProtocolVersion.V26_3).blockState(0));
    }

    @Test
    void statelessCodecsReadEveryObservedSchemaWithoutPlatformInjection() throws Exception {
        String marker = System.getProperty("ViaVersion");
        try {
            var codecs = ProtocolCodecs.decoder();
            var names = new ac.cult.cultac.protocol.WireValueDecoder.Registries() {
                public String name(String registry, int id) {
                    throw new AssertionError("Empty items need no registry");
                }

                public int id(String registry, String name) {
                    throw new AssertionError();
                }
            };
            for (var version : ProtocolVersion.values()) {
                var input = Unpooled.buffer();
                try {
                    Wire.writeVarInt(input, 0);
                    var value = codecs.item(version, input, false, names);
                    assertEquals(0, value.count());
                    assertFalse(input.isReadable());
                    input.clear().writeByte(9);
                    Wire.writeVarInt(input, codecs.floatSerializer(version));
                    input.writeFloat(1.25f).writeByte(255);
                    var metadata = codecs.metadata(version, input, names);
                    assertEquals(1, metadata.size());
                    assertEquals(9, metadata.get(0).index());
                    assertEquals(1.25f, metadata.get(0).value());
                    assertEquals(6, metadata.get(0).bytes().length);
                    assertFalse(input.isReadable());
                } finally {
                    input.release();
                }
            }
        } finally {
            ProtocolCodecs.close();
        }
        assertEquals(marker, System.getProperty("ViaVersion"));
    }

    @Test
    void privateLoaderIgnoresParentMappingResourcesAndSharesTheProtocolSpi() throws Exception {
        URL jar = getClass().getResource("/runtime/protocol-codecs.jar");
        assertNotNull(jar);
        // URLClassLoader accepts the extracted file, not the enclosing engine resource URL.
        Path extracted = directory.resolve("codecs.jar");
        try (var input = jar.openStream()) {
            java.nio.file.Files.copy(input, extracted);
        }
        URL poison = new URL("file:/unrelated-proxy-mappings/");
        var parent = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public URL getResource(String name) {
                return name.startsWith("assets/viaversion/") ? poison : super.getResource(name);
            }
        };
        try (var loader = new ProtocolCodecs.CodecLoader(extracted.toUri().toURL(), parent)) {
            String resource = "assets/viaversion/data/mappings-26.2to26.3.nbt";
            assertNotNull(loader.getResource(resource));
            assertNotEquals(poison, loader.getResource(resource));
            assertEquals(
                    1, java.util.Collections.list(loader.getResources(resource)).size());
            assertSame(
                    ac.cult.cultac.protocol.WireValueDecoder.class,
                    loader.loadClass(ac.cult.cultac.protocol.WireValueDecoder.class.getName()));
            assertSame(
                    loader,
                    loader.loadClass("com.viaversion.viaversion.api.Via").getClassLoader());
        }
    }
}
