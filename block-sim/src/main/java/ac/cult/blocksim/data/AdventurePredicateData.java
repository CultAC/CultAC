package ac.cult.blocksim.data;

import ac.cult.blocksim.data.nbt.CanonicalSnbt;
import ac.cult.blocksim.data.nbt.NbtJson;
import ac.cult.blocksim.data.nbt.NbtValue;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** State matcher order and typed NBT consumed by the client's adventure BlockInWorld test. */
public final class AdventurePredicateData {
    private AdventurePredicateData() {}

    static boolean isComponent(String key) {
        return key.equals("minecraft:can_break") || key.equals("minecraft:can_place_on");
    }

    public record Property(String name, String exact, String minimum, String maximum) {}
    public record Predicate(JsonObject fields, List<Property> properties, NbtValue nbt) {
        public Predicate {
            fields = fields.deepCopy();
            if (properties != null) properties = List.copyOf(properties);
        }
        @Override public JsonObject fields() { return fields.deepCopy(); }
    }

    public static List<Predicate> read(Components components, String key) {
        return decode(components, key);
    }

    private static List<Predicate> decode(Components components, String key) {
        var value = components.get(key);
        if (value == null) return List.of();
        var values = value.isJsonArray() ? value.getAsJsonArray().asList() : List.of(value);
        NbtValue encoding = components.hasEncodedNbt(key) ? components.encodedNbt(key) : null;
        var encoded = encoding instanceof NbtValue.Sequence sequence ? sequence.values()
                : encoding == null ? List.<NbtValue>of() : List.of(encoding);
        var layout = components.layout(key);
        var states = layout == null ? null : layout.values().get("states");
        var result = new ArrayList<Predicate>();
        for (int index = 0; index < values.size(); index++) {
            var fields = values.get(index).getAsJsonObject();
            var order = states instanceof NbtValue.Compound map ? map.values().get(Integer.toString(index)) : null;
            List<Property> properties = order instanceof NbtValue.Sequence sequence ? ordered(sequence)
                    : fields.has("state") ? properties(fields.getAsJsonObject("state")) : null;
            NbtValue nbt = index < encoded.size() ? ((NbtValue.Compound) encoded.get(index)).values().get("nbt") : null;
            if (nbt instanceof NbtValue.Text text) nbt = CanonicalSnbt.parse(text.value());
            else if (nbt == null && fields.has("nbt")) {
                var literal = fields.get("nbt");
                nbt = literal.isJsonPrimitive() ? CanonicalSnbt.parse(literal.getAsString()) : NbtJson.literal(literal);
            }
            result.add(new Predicate(fields, properties, nbt));
        }
        return List.copyOf(result);
    }

    private static List<Property> properties(JsonObject state) {
        var result = new ArrayList<Property>();
        state.entrySet().forEach(entry -> result.add(property(entry.getKey(), entry.getValue())));
        return List.copyOf(result);
    }

    private static List<Property> ordered(NbtValue.Sequence sequence) {
        var result = new ArrayList<Property>();
        for (var value : sequence.values()) {
            var fields = ((NbtValue.Compound) value).values();
            result.add(property(((NbtValue.Text) fields.get("name")).value(), NbtJson.encode(fields.get("value"))));
        }
        return List.copyOf(result);
    }

    private static Property property(String name, JsonElement value) {
        if (value.isJsonPrimitive()) return new Property(name, value.getAsString(), null, null);
        var range = value.getAsJsonObject();
        return new Property(name, null, range.has("min") ? range.get("min").getAsString() : null,
                range.has("max") ? range.get("max").getAsString() : null);
    }

    /** Compact list/holder syntax and SNBT are codec forms, rather than different predicate values. */
    static Object identity(Components components, String key) {
        var parsed = decode(components, key);
        NbtValue encoding = components.hasEncodedNbt(key) ? components.encodedNbt(key) : null;
        var encoded = encoding instanceof NbtValue.Sequence sequence ? sequence.values()
                : encoding == null ? List.<NbtValue>of() : List.of(encoding);
        var result = new ArrayList<Identity>();
        var layout = components.layout(key);
        var exactLists = layout == null ? null : layout.values().get("exact");
        for (int index = 0; index < parsed.size(); index++) {
            var predicate = parsed.get(index);
            var fields = predicate.fields();
            var block = fields.get("blocks");
            Object holders = null;
            if (block != null) {
                if (block.isJsonPrimitive() && block.getAsString().startsWith("#"))
                    holders = "#" + HolderSets.identifier(block.getAsString().substring(1));
                else holders = (block.isJsonArray() ? block.getAsJsonArray().asList() : List.of(block)).stream()
                        .map(value -> HolderSets.identifier(value.getAsString())).toList();
            }
            // Retain typed component matcher payloads. Empty exact/partial maps are their codec defaults.
            var matchers = new java.util.HashMap<String, Object>();
            var partial = fields.get("predicates");
            if (partial != null && !partial.getAsJsonObject().isEmpty())
                matchers.put("predicates", index < encoded.size()
                        ? ((NbtValue.Compound) encoded.get(index)).values().get("predicates") : partial);
            var exact = new ArrayList<Components>();
            var ordered = exactLists instanceof NbtValue.Compound lists ? lists.values().get(Integer.toString(index)) : null;
            if (ordered instanceof NbtValue.Sequence sequence) {
                for (var entry : sequence.values()) {
                    var tuple = ((NbtValue.Compound) entry).values();
                    String type = ((NbtValue.Text) tuple.get("type")).value();
                    var detail = tuple.get("layout") instanceof NbtValue.Compound data ? data : ComponentLayout.EMPTY;
                    exact.add(ComponentPatch.fromNbt(new NbtValue.Compound(Map.of(type, tuple.get("value"))),
                            new NbtValue.Compound(detail.values().isEmpty() ? Map.of() : Map.of(type, detail))).added());
                }
            } else if (fields.has("components")) {
                var encodedComponents = index < encoded.size() ? ((NbtValue.Compound) encoded.get(index)).values().get("components") : null;
                fields.getAsJsonObject("components").entrySet().forEach(entry -> {
                    String type = HolderSets.identifier(entry.getKey());
                    exact.add(encodedComponents instanceof NbtValue.Compound data
                            ? ComponentPatch.fromNbt(new NbtValue.Compound(Map.of(type, data.values().get(entry.getKey())))).added()
                            : new Components(Map.of(type, entry.getValue())));
                });
            }
            result.add(new Identity(holders, predicate.properties(), predicate.nbt(), List.copyOf(exact), Map.copyOf(matchers)));
        }
        var other = layout == null ? new java.util.HashMap<String, NbtValue>() : new java.util.HashMap<>(layout.values());
        other.remove("states");
        other.remove("exact");
        return new ValueIdentity(List.copyOf(result), Map.copyOf(other));
    }
    private record Identity(Object blocks, List<Property> properties, NbtValue nbt, List<Components> exact, Map<String, Object> matchers) {}
    private record ValueIdentity(List<Identity> predicates, Map<String, NbtValue> layout) {}
}
