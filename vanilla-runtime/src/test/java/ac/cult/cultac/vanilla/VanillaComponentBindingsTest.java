package ac.cult.cultac.vanilla;

import static org.junit.jupiter.api.Assertions.*;

import com.google.common.collect.Interners;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.junit.jupiter.api.Test;

class VanillaComponentBindingsTest {
    private static final ResourceKey<Registry<String>> REGISTRY =
            ResourceKey.createRegistryKey(Identifier.parse("test:values"));
    private static final ResourceKey<String> ENTRY = ResourceKey.create(REGISTRY, Identifier.parse("test:entry"));
    private static final DataComponentType<Integer> VALUE = DataComponentType.<Integer>builder()
            .persistent(com.mojang.serialization.Codec.INT)
            .build();

    @Test
    void equivalentDefaultsShareSnapshotsWithoutChangingBoundComponents() {
        var holder = Holder.Reference.createStandAlone(new HolderOwner<String>() {}, ENTRY);
        var defaults = components(1);
        holder.bindComponents(defaults);
        var shared = Interners.<VanillaComponentBindings<?>>newWeakInterner();
        var first = shared.intern(binding(holder, 3));
        var equivalent = shared.intern(binding(holder, 3));
        var changed = shared.intern(binding(holder, 4));
        assertSame(first, equivalent);
        assertNotSame(first, changed);
        assertEquals(3, first.contents().get(holder).get(VALUE));
        assertEquals(4, changed.contents().get(holder).get(VALUE));
        assertSame(first.contents(), equivalent.contents());
        assertSame(defaults, holder.components());
    }

    @Test
    void equalKeysAndValuesDoNotMergeHoldersFromDifferentRegistries() {
        var first = Holder.Reference.createStandAlone(new HolderOwner<String>() {}, ENTRY);
        var second = Holder.Reference.createStandAlone(new HolderOwner<String>() {}, ENTRY);
        var shared = Interners.<VanillaComponentBindings<?>>newWeakInterner();
        var a = shared.intern(binding(first, 3));
        var b = shared.intern(binding(second, 3));
        assertNotSame(a, b);
        assertFalse(a.contents().containsKey(second));
        assertFalse(b.contents().containsKey(first));
        assertFalse(first.areComponentsBound());
        assertFalse(second.areComponentsBound());
        var changed = binding(first, 7);
        assertEquals(7, changed.contents().get(first).get(VALUE));
        assertEquals(3, a.contents().get(first).get(VALUE));
        assertEquals(3, b.contents().get(second).get(VALUE));
    }

    private static VanillaComponentBindings<String> binding(Holder.Reference<String> holder, int value) {
        return binding(Map.of(holder, components(value)));
    }

    private static DataComponentMap components(int value) {
        return DataComponentMap.builder().set(VALUE, value).build();
    }

    private static VanillaComponentBindings<String> binding(Map<Holder.Reference<String>, DataComponentMap> entries) {
        return new VanillaComponentBindings<>(new DataComponentInitializers.PendingComponents<>() {
            @Override
            public ResourceKey<? extends Registry<? extends String>> key() {
                return REGISTRY;
            }

            @Override
            public void forEach(BiConsumer<Holder.Reference<String>, DataComponentMap> output) {
                entries.forEach(output);
            }

            @Override
            public void apply() {
                throw new AssertionError("A component snapshot must not bind shared holders");
            }
        });
    }
}
