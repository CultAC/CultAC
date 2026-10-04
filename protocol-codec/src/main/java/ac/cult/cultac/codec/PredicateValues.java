package ac.cult.cultac.codec;

import com.viaversion.nbt.tag.*;
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.minecraft.codec.CodecContext;
import com.viaversion.viaversion.api.minecraft.item.data.*;
import com.viaversion.viaversion.api.type.types.misc.HolderSetType;
import com.viaversion.viaversion.codec.nbt.NbtOps;
import java.util.ArrayList;

/** Adventure predicates retain holder tags, state ranges, NBT and component predicates. */
final class PredicateValues {
    private PredicateValues() {}

    static Tag adventure(AdventureModePredicate value, CodecContext context) {
        var predicates = new ArrayList<CompoundTag>();
        for (var predicate : value.predicates()) {
            var entry = new CompoundTag();
            if (predicate.holderSet() != null)
                entry.put(
                        "blocks",
                        NbtOps.serialize(
                                context, new HolderSetType(MappingData.MappingType.BLOCK), predicate.holderSet()));
            if (predicate.propertyMatchers() != null) {
                var state = new CompoundTag();
                for (var matcher : predicate.propertyMatchers()) {
                    var exact = matcher.matcher().left();
                    if (exact != null) state.putString(matcher.name(), exact);
                    else {
                        var range = new CompoundTag();
                        var bounds = matcher.matcher().right();
                        if (bounds.minValue() != null) range.putString("min", bounds.minValue());
                        if (bounds.maxValue() != null) range.putString("max", bounds.maxValue());
                        state.put(matcher.name(), range);
                    }
                }
                entry.put("state", state);
            }
            if (predicate.tag() != null) entry.put("nbt", predicate.tag());
            var matchers = predicate.dataMatchers();
            if (matchers != null) {
                var exact = new CompoundTag();
                for (var data : matchers.exactPredicates())
                    exact.put(
                            com.viaversion.viaversion.util.Key.namespaced(
                                    data.key().identifier()),
                            ComponentValues.component(data, context));
                if (!exact.isEmpty()) entry.put("components", exact);
                var partial = new CompoundTag();
                for (var data : matchers.predicates()) {
                    // Partial predicate values are already persistent NBT; their type is versioned.
                    String key = data.type().isPredicateType()
                            ? context.registryAccess()
                                    .registryKey(
                                            "data_component_predicate_type",
                                            data.type().id())
                                    .toString()
                            : context.registryAccess()
                                    .dataComponentType(data.type().id())
                                    .toString();
                    partial.put(key, data.predicate());
                }
                if (!partial.isEmpty()) entry.put("predicates", partial);
            }
            predicates.add(entry);
        }
        return new ListTag<>(predicates);
    }
}
