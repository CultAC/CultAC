package ac.cult.blocksim;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.data.ComponentPatch;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.SimInventory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComponentsTest {
    @Test void receivedRegistryGenerationsOwnTheirPayloadsAndReplaceOnlyTheirRegistry() {
        var defaults = ac.cult.blocksim.data.InteractionRegistries.defaults();
        byte[] bytes = ac.cult.blocksim.data.nbt.BinaryNbt.write(new NbtValue.Compound(Map.of(
                "id", new NbtValue.Text("minecraft:stone"))));
        var payloads = new java.util.HashMap<String, byte[]>();
        payloads.put("test:custom", bytes);
        payloads.put("minecraft:soil_beneath_tree", null);
        var first = defaults.withEncodedRegistry("block_state_provider", payloads);
        bytes[0] = 0;
        payloads.clear();
        var holder = new com.google.gson.JsonPrimitive("test:custom");
        var resolved = first.resolve("block_state_provider", holder).getAsJsonObject();
        assertEquals("minecraft:stone", resolved.get("id").getAsString());
        resolved.addProperty("id", "minecraft:dirt");
        assertEquals("minecraft:stone", first.resolve("block_state_provider", holder).getAsJsonObject().get("id").getAsString());
        var known = new com.google.gson.JsonPrimitive("minecraft:soil_beneath_tree");
        assertEquals(defaults.resolve("block_state_provider", known), first.resolve("block_state_provider", known));
        var second = first.withEncodedRegistry("block_state_provider", Map.of());
        assertThrows(IllegalArgumentException.class, () -> second.resolve("block_state_provider", holder));
        assertEquals("minecraft:stone", first.resolve("block_state_provider", holder).getAsJsonObject().get("id").getAsString());
        var axe = new com.google.gson.JsonPrimitive("minecraft:axe");
        assertEquals(defaults.resolve("block_transformer", axe), second.resolve("block_transformer", axe));
    }

    @Test void inventoryKeepsRepeatedExactAdventureComponentsWhenMergingStacks() {
        var items = new ItemRegistry(DataTables.defaults());
        var fields = new NbtValue.Compound(Map.of("components", new NbtValue.Compound(Map.of(
                "minecraft:damage", new NbtValue.Numeric(NbtValue.Kind.INT, 8)))));
        var patch = new NbtValue.Compound(Map.of("minecraft:can_break", new NbtValue.Sequence(java.util.List.of(fields))));
        var exact = new NbtValue.Sequence(java.util.List.of(
                new NbtValue.Compound(Map.of("type", new NbtValue.Text("minecraft:damage"), "value", new NbtValue.Numeric(NbtValue.Kind.INT, 7))),
                new NbtValue.Compound(Map.of("type", new NbtValue.Text("minecraft:damage"), "value", new NbtValue.Numeric(NbtValue.Kind.INT, 8)))));
        var layout = new NbtValue.Compound(Map.of("minecraft:can_break", new NbtValue.Compound(Map.of(
                "exact", new NbtValue.Compound(Map.of("0", exact))))));
        var ordinary = items.stack("minecraft:stone", 32, ComponentPatch.fromNbt(patch));
        var incoming = items.stack("minecraft:stone", 3, ComponentPatch.fromNbt(patch, layout));
        var repeated = incoming.copy();
        var slots = new ArrayList<>(Collections.nCopies(43, items.empty())); slots.set(0, ordinary);
        var inventory = new SimInventory(slots, 0, false, items.empty());
        assertTrue(inventory.add(incoming));
        assertEquals(32, inventory.get(0).count());
        assertEquals(3, inventory.get(1).count());
        assertTrue(inventory.get(1).sameItemSameComponents(repeated));
        assertTrue(inventory.add(repeated));
        assertEquals(6, inventory.get(1).count());
        assertEquals(ordinary.components().encodedNbt(), inventory.get(1).components().encodedNbt());
    }

    @Test void inventoryKeepsAdventureMatcherOrderAndMergesEquivalentCodecForms() {
        var items = new ItemRegistry(DataTables.defaults());
        var fields = new NbtValue.Compound(Map.of("state", new NbtValue.Compound(Map.of("axis", new NbtValue.Text("y")))));
        var patch = new NbtValue.Compound(Map.of("minecraft:can_break", new NbtValue.Sequence(java.util.List.of(fields))));
        var order = new NbtValue.Sequence(java.util.List.of(
                new NbtValue.Compound(Map.of("name", new NbtValue.Text("axis"), "value", new NbtValue.Text("x"))),
                new NbtValue.Compound(Map.of("name", new NbtValue.Text("axis"), "value", new NbtValue.Text("y")))));
        var layout = new NbtValue.Compound(Map.of("minecraft:can_break", new NbtValue.Compound(Map.of(
                "states", new NbtValue.Compound(Map.of("0", order))))));
        var ordinary = items.stack("minecraft:stone", 32, ComponentPatch.fromNbt(patch));
        var incoming = items.stack("minecraft:stone", 3, ComponentPatch.fromNbt(patch, layout));
        var beforeIncoming = incoming.copy();
        var slots = new ArrayList<>(Collections.nCopies(43, items.empty())); slots.set(0, ordinary);
        var inventory = new SimInventory(slots, 0, false, items.empty());
        assertTrue(inventory.add(incoming));
        assertEquals(32, inventory.get(0).count());
        assertEquals(3, inventory.get(1).count());
        assertTrue(inventory.get(1).sameItemSameComponents(beforeIncoming));

        var authored = ComponentPatch.fromJson(JsonParser.parseString("{\"minecraft:can_break\":{\"state\":{\"axis\":\"y\"}}}").getAsJsonObject());
        var equal = items.stack("minecraft:stone", 2, authored);
        var equalComponents = equal.components();
        assertTrue(inventory.add(equal));
        assertEquals(34, inventory.get(0).count());
        assertEquals(ordinary.components().hashCode(), equalComponents.hashCode());
    }
    @Test void inventoryMergesEquivalentTooltipMembershipsWithoutReorderingTheValue() {
        var items = new ItemRegistry(DataTables.defaults());
        var first = new NbtValue.Compound(Map.of("hidden_components", new NbtValue.Sequence(java.util.List.of(
                new NbtValue.Text("minecraft:tool"), new NbtValue.Text("minecraft:food")))));
        var reversed = new NbtValue.Compound(Map.of("hidden_components", new NbtValue.Sequence(java.util.List.of(
                new NbtValue.Text("minecraft:food"), new NbtValue.Text("minecraft:tool")))));
        var existing = items.stack("minecraft:stone", 32); existing.setComponent("minecraft:tooltip_display", first);
        var incoming = items.stack("minecraft:stone", 3); incoming.setComponent("minecraft:tooltip_display", reversed);
        var incomingComponents = incoming.components();
        var slots = new ArrayList<>(Collections.nCopies(43, items.empty())); slots.set(0, existing);
        var inventory = new SimInventory(slots, 0, false, items.empty());
        assertTrue(inventory.add(incoming));
        assertEquals(35, inventory.get(0).count());
        assertTrue(inventory.get(1).isEmpty());
        assertEquals(first, inventory.get(0).components().encodedNbt("minecraft:tooltip_display"));
        assertEquals(existing.components().hashCode(), incomingComponents.hashCode());
    }
    @Test void equalRegistrySnapshotsKeepInventoryMergeIdentityAcrossEncodings() {
        var data = DataTables.defaults(); var base = new ItemRegistry(data).defaults("minecraft:stone");
        var food = new NbtValue.Compound(Map.of("nutrition", new NbtValue.Numeric(NbtValue.Kind.INT, 4),
                "saturation", new NbtValue.Numeric(NbtValue.Kind.FLOAT, 0.1F),
                "can_always_eat", new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte)1)));
        var generated = base.with("minecraft:food", JsonParser.parseString("{\"nutrition\":4,\"saturation\":0.1,\"can_always_eat\":true}"), food);
        var current = new java.util.concurrent.atomic.AtomicReference<>(generated);
        var items = new ItemRegistry(data, key -> key.equals("minecraft:stone") ? current.get() : null);
        var slots = new ArrayList<>(Collections.nCopies(43, items.empty())); slots.set(0, items.stack("minecraft:stone", 32));
        var inventory = new SimInventory(slots, 0, false, items.empty());
        current.set(ComponentPatch.fromNbt(generated.encodedNbt()).added());
        assertTrue(inventory.add(items.stack("minecraft:stone", 3)));
        assertEquals(35, inventory.get(0).count());
        assertTrue(inventory.get(1).isEmpty());
        assertEquals(generated.hashCode(), current.get().hashCode());
        var changed = new java.util.HashMap<>(food.values());
        changed.put("saturation", new NbtValue.Numeric(NbtValue.Kind.FLOAT, Math.nextUp(0.1F)));
        current.set(current.get().with("minecraft:food", ac.cult.blocksim.data.nbt.NbtJson.encode(new NbtValue.Compound(changed)), new NbtValue.Compound(changed)));
        assertTrue(inventory.add(items.stack("minecraft:stone", 2)));
        assertEquals(35, inventory.get(0).count());
        assertEquals(2, inventory.get(1).count());
    }
    @Test void inventoryKeepsDistinctNumericRecordsAndPreservesTheirGetterValues() {
        var items = new ItemRegistry(DataTables.defaults());
        var food = new NbtValue.Compound(Map.of("nutrition", new NbtValue.Numeric(NbtValue.Kind.INT, 4),
                "saturation", new NbtValue.Numeric(NbtValue.Kind.FLOAT, 0.0F)));
        var patch = new NbtValue.Compound(Map.of("minecraft:food", food));
        var details = new NbtValue.Compound(Map.of("minecraft:food", new NbtValue.Compound(Map.of(
                "negative_zero", new NbtValue.Compound(Map.of("saturation", new NbtValue.Numeric(NbtValue.Kind.INT, 5)))))));
        var ordinary = items.stack("minecraft:stone", 32, ComponentPatch.fromNbt(patch));
        var incoming = items.stack("minecraft:stone", 3, ComponentPatch.fromNbt(patch, details));
        var slots = new ArrayList<>(Collections.nCopies(43, items.empty())); slots.set(0, ordinary);
        var inventory = new SimInventory(slots, 0, false, items.empty());
        assertTrue(inventory.add(incoming));
        assertEquals(32, inventory.get(0).count());
        assertEquals(3, inventory.get(1).count());
        assertEquals(Integer.MIN_VALUE, Float.floatToRawIntBits(ac.cult.blocksim.data.ItemComponents.food(inventory.get(1).components()).saturation()));
        assertTrue(inventory.get(1).sameItemSameComponents(inventory.get(1).copy()));
        assertEquals(ordinary.components().encodedNbt(), inventory.get(1).components().encodedNbt());
        var canonical = inventory.get(1).copy(); canonical.setComponent("minecraft:food", food);
        assertTrue(ordinary.sameItemSameComponents(canonical));
    }
    @Test void inventoryMergesPreserveContainerSlotLayouts() throws Exception {
        var items = new ItemRegistry(DataTables.load("26.3"));
        var value = new NbtValue.Compound(Map.of("minecraft:container", new NbtValue.Sequence(java.util.List.of())));
        var layout = new NbtValue.Compound(Map.of("minecraft:container", new NbtValue.Compound(Map.of(
                "slots", new NbtValue.Numeric(NbtValue.Kind.INT, 4)))));
        var ordinary = items.stack("minecraft:stone", 32, ComponentPatch.fromNbt(value));
        var incoming = items.stack("minecraft:stone", 3, ComponentPatch.fromNbt(value, layout));
        var slots = new ArrayList<>(Collections.nCopies(43, items.empty()));
        slots.set(0, ordinary);
        var inventory = new SimInventory(slots, 0, false, items.empty());
        assertTrue(inventory.add(incoming));
        assertEquals(32, inventory.get(0).count());
        assertEquals(3, inventory.get(1).count());
        assertEquals(ordinary.components().encodedNbt(), inventory.get(1).components().encodedNbt());
        assertFalse(ordinary.sameItemSameComponents(inventory.get(1)));
        assertTrue(inventory.get(1).sameItemSameComponents(inventory.get(1).copy()));
        // Replacing this component from its persistent value resets it to that codec's canonical layout.
        var canonical = inventory.get(1).copy();
        canonical.setComponent("minecraft:container", value.values().get("minecraft:container"));
        assertTrue(ordinary.sameItemSameComponents(canonical));
    }
    @Test void everyGeneratedComponentSurvivesThePortableBoundary() throws Exception {
        var data = DataTables.load("26.3"); var items = new ItemRegistry(data);
        for (var item : data.items()) {
            assertEquals(JsonParser.parseString(item.defaultComponentsJson()).getAsJsonObject().get("components"),
                JsonParser.parseString(items.defaults(item.key()).json()), item.key());
        }
    }

    @Test void registryDefaultsExposeNativeComponentsRatherThanTheReportWrapper() throws Exception {
        var items = new ItemRegistry(DataTables.load("26.3"));
        assertEquals(64, items.stack("minecraft:stone", 1).components().integer("minecraft:max_stack_size", -1));
        var honey = items.stack("minecraft:honey_bottle", 1).components();
        assertTrue(honey.has("minecraft:consumable"));
        assertEquals("minecraft:glass_bottle", honey.get("minecraft:use_remainder").getAsJsonObject().get("id").getAsString());
        assertFalse(honey.has("components"));
    }

    @Test void externalMutationsCannotChangeDefaultComponentsOrOtherStacks() throws Exception {
        var items = new ItemRegistry(DataTables.load("26.3"));
        var stack = items.stack("minecraft:oak_slab", 32); var independent = stack.copy();
        var property = JsonParser.parseString("{\"type\":\"top\"}");
        stack.components(stack.components().with("minecraft:block_state", property));
        property.getAsJsonObject().addProperty("type", "double");
        var returned = stack.components().get("minecraft:block_state");
        returned.getAsJsonObject().addProperty("type", "bottom");
        assertEquals("top", stack.components().stringMap("minecraft:block_state").get("type"));
        assertFalse(independent.components().has("minecraft:block_state"));
        assertFalse(items.defaults("minecraft:oak_slab").has("minecraft:block_state"));
        stack.shrink(1);
        assertEquals(31, stack.count());
        assertEquals(32, independent.count());
    }
}
