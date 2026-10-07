package ac.cult.cultac.bedrock.bridge;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import java.util.HashMap;
import java.util.List;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.UseEffects;
import org.junit.Test;

public class GeyserItemComponentsTest {
    private static WireValueDecoder.Registries names() {
        var data = ModelRegistryData.load(GeyserItemStacks.version());
        return new WireValueDecoder.Registries() {
            @Override
            public String name(String registry, int id) {
                return data.registry(registry).name(id);
            }

            @Override
            public int id(String registry, String name) {
                return data.registry(registry).id(name);
            }

            @Override
            public boolean preserveIdentifiers() {
                return true;
            }
        };
    }

    private static ItemStack source(String key, int count, DataComponents patch) {
        return new ItemStack(names().id("minecraft:item", key), count, patch);
    }

    @Test
    public void itemTypeAndCountUseGeysersProtocolIds() {
        var captured = GeyserItemStacks.decode(
                source("minecraft:iron_axe", 3, null), names(), ac.cult.cultac.utils.inventory.ItemUtil.modelItems());
        assertEquals("minecraft:iron_axe", captured.itemKey());
        assertEquals(3, captured.count());
        assertTrue(GeyserItemStacks.decode(null, names(), ac.cult.cultac.utils.inventory.ItemUtil.modelItems())
                .isEmpty());
    }

    @Test
    public void componentSchemaPreservesUseEffectsAndRemovals() {
        var patch = new DataComponents(new HashMap<>());
        patch.put(DataComponentTypes.USE_EFFECTS, new UseEffects(true, false, 0.35f));
        patch.put(DataComponentTypes.MAX_STACK_SIZE, null);
        var captured = GeyserItemStacks.decode(
                source("minecraft:stone", 7, patch), names(), ac.cult.cultac.utils.inventory.ItemUtil.modelItems());
        var effects = captured.components().get("minecraft:use_effects").getAsJsonObject();
        assertTrue(ac.cult.blocksim.data.nbt.NbtJson.booleanValue(effects.get("can_sprint")));
        assertFalse(ac.cult.blocksim.data.nbt.NbtJson.booleanValue(effects.get("interact_vibrations")));
        assertEquals(0.35f, effects.get("speed_multiplier").getAsFloat(), 0);
        assertFalse(captured.components().has("minecraft:max_stack_size"));
        assertEquals(7, captured.count());
    }

    @Test
    public void capturedArraysAndNestedValuesAreDetached() {
        int[] values = {4, 8};
        var childPatch = new DataComponents(new HashMap<>());
        childPatch.put(
                DataComponentTypes.CUSTOM_DATA,
                NbtMap.builder().putIntArray("indices", values).build());
        var patch = new DataComponents(new HashMap<>());
        patch.put(DataComponentTypes.BUNDLE_CONTENTS, List.of(source("minecraft:stone", 3, childPatch)));
        var captured = GeyserItemStacks.decode(
                source("minecraft:bundle", 1, patch), names(), ac.cult.cultac.utils.inventory.ItemUtil.modelItems());
        values[0] = 99;
        childPatch.put(DataComponentTypes.CUSTOM_DATA, NbtMap.EMPTY);
        patch.put(DataComponentTypes.BUNDLE_CONTENTS, List.of());
        var child = captured.components().bundle().items().getFirst();
        assertEquals(3, child.count());
        var data = (NbtValue.Compound) child.patch().added().encodedNbt("minecraft:custom_data");
        assertEquals(List.of(4L, 8L), ((NbtValue.PrimitiveArray) data.values().get("indices")).values());
    }
}
