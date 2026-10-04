package ac.cult.cultac.network.codec;

import ac.cult.cultac.protocol.ProtocolResolutionException;
import ac.cult.cultac.utils.minecraft.IsolatedMinecraft;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.AdventureModePredicate;
import net.minecraft.world.item.ItemStack;

/** Keeps native adventure values while exposing their exact client-visible action view. */
public final class NativeAdventurePredicates {
    // The original native classes expose no predicates accessor. This read-only
    // field is List<BlockPredicate> in all supported hosts; old/new constructors
    // and BlockPredicate package names differ, so do not link either constructor.
    private static final class NativeLayout {
        private static final Field PREDICATES = predicates();

        private static Field predicates() {
            try {
                // The native wire codec reads this base field, including for a
                // valid plugin subclass with an inherited or shadowed field.
                var field = AdventureModePredicate.class.getDeclaredField("predicates");
                if (field.getType() != List.class) throw new NoSuchFieldException("predicates is not a List");
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException failure) {
                throw new ProtocolResolutionException("Native adventure predicate layout is unavailable", failure);
            }
        }
    }

    private NativeAdventurePredicates() {}

    public static JsonObject encodePatch(DataComponentPatch patch, RegistryAccess registries) {
        // split().added() has the same native ABI on all four host families.
        // entrySet()/get(type) on DataComponentPatch do not. Inspect only these
        // positive values; removals and absent entries stay in the real patch.
        var added = patch.split().added();
        boolean emptyBreak = zero(added.get(DataComponents.CAN_BREAK));
        boolean emptyPlace = zero(added.get(DataComponents.CAN_PLACE_ON));
        var encodable = emptyBreak || emptyPlace
                ? patch.forget(type -> type == DataComponents.CAN_BREAK && emptyBreak
                        || type == DataComponents.CAN_PLACE_ON && emptyPlace)
                : patch;
        var result = encodable.isEmpty()
                ? new JsonObject()
                : DataComponentPatch.CODEC
                        .encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), encodable)
                        .getOrThrow()
                        .getAsJsonObject();
        if (emptyBreak) result.add("minecraft:can_break", ModelItemComponents.denyingPredicate());
        if (emptyPlace) result.add("minecraft:can_place_on", ModelItemComponents.denyingPredicate());
        return result;
    }

    /** Retain raw data only when an action returned exactly its directed, normalized view. */
    public static <T> boolean sameActionView(
            DataComponentType<T> type,
            ItemStack original,
            ItemStack predicted,
            RegistryAccess registries,
            IsolatedMinecraft.Binding model) {
        if (type != DataComponents.CAN_BREAK && type != DataComponents.CAN_PLACE_ON
                || original.getItem() != predicted.getItem()) return false;
        var real = original.get(type);
        var value = predicted.get(type);
        if (real == null || value == null) return false; // Missing entries and removals remain real changes.
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        String key =
                ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil.registryKey(BuiltInRegistries.DATA_COMPONENT_TYPE, type);
        var patch = new JsonObject();
        patch.add(key, encodeValue(type, real, ops));
        var roundtrip = JsonParser.parseString(model.toHostComponents(model.toModelComponents(patch.toString())))
                .getAsJsonObject();
        var expected = roundtrip.get(key);
        // The actual native value is already encoded canonically. Only the
        // projected JSON needs decoding to normalize compact/wrapped forms and defaults.
        return expected != null
                && type.codec()
                        .parse(ops, expected)
                        .flatMap(decoded -> type.codec().encodeStart(ops, decoded))
                        .getOrThrow()
                        .equals(encodeValue(type, value, ops));
    }

    private static boolean zero(Object value) {
        if (value == null) return false;
        try {
            return ((List<?>) NativeLayout.PREDICATES.get(value)).isEmpty();
        } catch (IllegalAccessException failure) {
            throw new ProtocolResolutionException("Cannot read native adventure predicates", failure);
        }
    }

    private static <T> JsonElement encodeValue(DataComponentType<T> type, T value, RegistryOps<JsonElement> ops) {
        return zero(value)
                ? ModelItemComponents.denyingPredicate()
                : type.codec().encodeStart(ops, value).getOrThrow();
    }
}
