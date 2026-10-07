package ac.cult.cultac.codec;

import ac.cult.shaded.vialib.api.data.MappingData;
import ac.cult.shaded.vialib.api.minecraft.codec.CodecContext;
import ac.cult.shaded.vialib.api.minecraft.item.data.*;
import ac.cult.shaded.vialib.api.type.types.misc.HolderSetType;
import ac.cult.shaded.vialib.codec.nbt.NbtOps;
import ac.cult.shaded.vialib.nbt.tag.*;
import java.util.ArrayList;

/** Adventure predicates retain holder tags, state ranges, NBT and component predicates. */
final class PredicateValues {
    private PredicateValues() {}

    /** The persistent state map cannot represent the stream's ordered, possibly repeated matchers. */
    static CompoundTag layout(AdventureModePredicate value, CodecContext context) {
        var states = new CompoundTag();
        var exactComponents = new CompoundTag();
        for (int index = 0; index < value.predicates().length; index++) {
            var matchers = value.predicates()[index].dataMatchers();
            if (matchers != null && matchers.exactPredicates().length != 0) {
                var components = new ArrayList<CompoundTag>();
                for (var component : matchers.exactPredicates()) {
                    var entry = new CompoundTag();
                    entry.putString(
                            "type",
                            ac.cult.shaded.vialib.util.Key.namespaced(
                                    component.key().identifier()));
                    var encoding = ComponentValues.component(component, context);
                    entry.put("value", encoding);
                    var details = ComponentValues.valueLayout(component, encoding, context);
                    if (!details.isEmpty()) entry.put("layout", details);
                    components.add(entry);
                }
                exactComponents.put(Integer.toString(index), new ListTag<>(components));
            }
            var properties = value.predicates()[index].propertyMatchers();
            if (properties == null) continue;
            var ordered = new ArrayList<CompoundTag>();
            for (var matcher : properties) {
                var entry = new CompoundTag();
                entry.putString("name", matcher.name());
                var exact = matcher.matcher().left();
                if (exact != null) entry.putString("value", exact);
                else {
                    var range = new CompoundTag();
                    var bounds = matcher.matcher().right();
                    if (bounds.minValue() != null) range.putString("min", bounds.minValue());
                    if (bounds.maxValue() != null) range.putString("max", bounds.maxValue());
                    entry.put("value", range);
                }
                ordered.add(entry);
            }
            states.put(Integer.toString(index), new ListTag<>(ordered));
        }
        var layout = new CompoundTag();
        if (!states.isEmpty()) layout.put("states", states);
        if (!exactComponents.isEmpty()) layout.put("exact", exactComponents);
        return layout;
    }

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
                            ac.cult.shaded.vialib.util.Key.namespaced(data.key().identifier()),
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
