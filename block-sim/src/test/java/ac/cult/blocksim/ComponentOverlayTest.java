package ac.cult.blocksim;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.BundleContents;
import ac.cult.blocksim.data.ComponentPatch;
import ac.cult.blocksim.data.ComponentWireEncoding;
import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.data.nbt.NbtValue;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ComponentOverlayTest {
    private static Object field(Components value, String name) throws Exception {
        var field = Components.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }

    @SuppressWarnings("unchecked")
    private static Components materialized(Components prototype, Components added, Set<String> removed)
            throws Exception {
        // Independent reference: the four-map algorithm used before this refactor.
        var values = new HashMap<>((Map<String, JsonElement>) field(prototype, "values"));
        var nbt = new HashMap<>((Map<String, NbtValue>) field(prototype, "encodedNbt"));
        var layouts = new HashMap<>((Map<String, NbtValue.Compound>) field(prototype, "layouts"));
        var wire = new HashMap<>((Map<String, ComponentWireEncoding>) field(prototype, "wireEncodings"));
        for (String key : removed) {
            values.remove(key); nbt.remove(key); layouts.remove(key); wire.remove(key);
        }
        for (String key : added.keys()) {
            values.put(key, added.get(key));
            nbt.remove(key); layouts.remove(key); wire.remove(key);
            if (added.hasEncodedNbt(key)) nbt.put(key, added.encodedNbt(key));
            if (added.layout(key) != null) layouts.put(key, added.layout(key));
            if (added.wireEncoding(key) != null) wire.put(key, added.wireEncoding(key));
        }
        var bundle = added.has(BundleContents.COMPONENT) ? added.bundle()
                : removed.contains(BundleContents.COMPONENT) ? null : prototype.bundle();
        var constructor = Components.class.getDeclaredConstructor(
                Map.class, Map.class, Map.class, BundleContents.class, Map.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(values, nbt, layouts, bundle, wire, false);
    }

    private static void equivalent(Components expected, Components actual) throws Exception {
        assertEquals(expected, actual);
        assertEquals(actual, expected);
        assertEquals(expected.hashCode(), actual.hashCode());
        assertEquals(expected.keys(), actual.keys());
        assertEquals(JsonParser.parseString(expected.json()), JsonParser.parseString(actual.json()));
        assertSame(expected.bundle(), actual.bundle());
        for (String name : List.of("values", "encodedNbt", "layouts", "wireEncodings"))
            assertEquals(field(expected, name), field(actual, name), name);
        for (String key : expected.keys()) {
            assertEquals(expected.get(key), actual.get(key), key);
            assertTrue(expected.sameValue(key, actual), key);
            assertTrue(actual.sameValue(key, expected), key);
            assertEquals(expected.hasEncodedNbt(key), actual.hasEncodedNbt(key), key);
            if (expected.hasEncodedNbt(key)) assertEquals(expected.encodedNbt(key), actual.encodedNbt(key), key);
            else assertThrows(IllegalStateException.class, () -> actual.encodedNbt(key), key);
            assertEquals(expected.layout(key), actual.layout(key), key);
            assertSame(expected.wireEncoding(key), actual.wireEncoding(key), key);
        }
    }

    @Test
    void everyGeneratedPrototypeMatchesTheMaterializingAlgorithm() throws Exception {
        var data = DataTables.defaults();
        var items = new ItemRegistry(data);
        var patch = Components.parse("{\"minecraft:damage\":3,\"test:nested\":{\"values\":[1,2]}}")
                .with("minecraft:damage", JsonParser.parseString("3"), new NbtValue.Numeric(NbtValue.Kind.INT, 3));
        for (var item : data.items()) {
            var prototype = items.defaults(item.key());
            var removed = Set.of("minecraft:unbreakable", "minecraft:damage", "test:absent");
            var expected = materialized(prototype, patch, removed);
            var actual = new ComponentPatch(patch, removed).applyToCapturedPrototype(prototype);
            equivalent(expected, actual);
            assertEquals(ComponentPatch.between(prototype, expected),
                    new ComponentPatch(patch, removed).sanitized(prototype));
            assertSame(ComponentPatch.EMPTY, new ComponentPatch(prototype, prototype.keys()).sanitized(prototype));
            equivalent(expected.subset(Set.of("minecraft:damage", "test:nested")),
                    actual.subset(Set.of("minecraft:damage", "test:nested")));
            actual.get("test:nested").getAsJsonObject().addProperty("values", 99);
            assertEquals(patch.get("test:nested"), actual.get("test:nested"));
        }
    }

    @Test
    void overridesHideAllOldMetadataAndSnapshotMutableRemovalSets() throws Exception {
        var layout = new NbtValue.Compound(Map.of("test:layout", new NbtValue.Numeric(NbtValue.Kind.INT, 1)));
        var original = new Components(
                Map.of("test:first", JsonParser.parseString("1"), "test:second", JsonParser.parseString("2")),
                Map.of("test:first", new NbtValue.Numeric(NbtValue.Kind.BYTE, 1),
                        "test:second", new NbtValue.Numeric(NbtValue.Kind.INT, 2)),
                Map.of("test:first", layout, "test:second", layout))
                .withWireEncodings(Map.of("test:first", new ComponentWireEncoding(777, new byte[] {1}, Map.of())));
        var added = Components.parse("{\"test:first\":1,\"test:third\":3}");
        var removed = new HashSet<>(Set.of("test:second", "test:third"));
        var expected = materialized(original, added, removed);
        var actual = new ComponentPatch(added, removed).applyToCapturedPrototype(original);
        removed.clear(); removed.add("test:first");
        equivalent(expected, actual);
        assertEquals(ComponentPatch.between(original, expected),
                new ComponentPatch(added, Set.of("test:second", "test:third")).sanitized(original));
        assertNull(actual.layout("test:first"));
        assertNull(actual.wireEncoding("test:first"));
        assertFalse(actual.hasEncodedNbt("test:first"));
        assertTrue(actual.has("test:third")); // An addition wins over a removal of the same key.
        assertThrows(UnsupportedOperationException.class, () -> actual.keys().clear());
        assertThrows(UnsupportedOperationException.class, () -> actual.keys().remove("test:absent"));
        assertThrows(UnsupportedOperationException.class, () -> actual.keys().removeIf(key -> false));
    }

    @Test
    void repeatedEditsKeepLookupDepthBoundedAndOldSnapshotsIndependent() throws Exception {
        var actual = Components.parse("{\"test:seed\":{\"nested\":1}}");
        var expected = actual;
        var first = actual;
        for (int i = 0; i < 1000; i++) {
            var patch = Components.parse("{\"test:value\":" + i + "}");
            var removed = i % 3 == 0 ? Set.of("test:seed") : Set.<String>of();
            expected = materialized(expected, patch, removed);
            actual = new ComponentPatch(patch, removed).applyToCapturedPrototype(actual);
            equivalent(expected, actual);
            assertBounded(actual);
        }
        assertEquals(JsonParser.parseString("{\"nested\":1}"), first.get("test:seed"));
    }

    private static void assertBounded(Components value) throws Exception {
        for (String name : List.of("values", "encodedNbt", "layouts", "wireEncodings")) {
            var map = field(value, name);
            if (!map.getClass().getSimpleName().equals("OverlayMap")) continue;
            for (String parent : List.of("prototype", "added")) {
                var field = map.getClass().getDeclaredField(parent);
                field.setAccessible(true);
                assertNotEquals("OverlayMap", field.get(map).getClass().getSimpleName());
            }
        }
    }

    @Test
    void generalOverlaysReleaseOverriddenAndRemovedPayloads() throws Exception {
        var original = Components.parse("{\"test:removed\":{\"large\":[1,2,3]},\"test:replaced\":{\"old\":true}}");
        var added = Components.parse("{\"test:replaced\":1}");
        var ordinary = original.overlay(added, Set.of("test:removed"));
        equivalent(materialized(original, added, Set.of("test:removed")), ordinary);
        for (String name : List.of("values", "encodedNbt", "layouts", "wireEncodings"))
            assertNotEquals("OverlayMap", field(ordinary, name).getClass().getSimpleName(), name);
    }
}
