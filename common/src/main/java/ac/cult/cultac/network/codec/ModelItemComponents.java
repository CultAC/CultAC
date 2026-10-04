package ac.cult.cultac.network.codec;

import ac.cult.cultac.protocol.ProtocolResolutionException;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelIdMappings;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** Explicit component changes on the same directed path as the action's item and block IDs. */
public final class ModelItemComponents {
    private ModelItemComponents() {}

    public static String project(String json, ProtocolVersion source, ProtocolVersion client, ProtocolVersion target) {
        if (json == null) return null;
        // Both pinned 26.3->26.2 item handlers call downgradeData, which removes
        // BLOCK_TRANSFORMER. Preserve every 26.3 add/remove/inline value otherwise.
        boolean losesTransformer = source.protocol() == 777 && client.protocol() < 777
                || client.protocol() == 777 && target.protocol() < 777;
        boolean legacyTool = (source.protocol() < 770 || client.protocol() < 770) && target.protocol() >= 770;
        var patch = JsonParser.parseString(json).getAsJsonObject();
        projectHolders(patch, source, client, target);
        if (losesTransformer)
            for (String key : java.util.List.of(
                    "minecraft:block_transformer", "!minecraft:block_transformer",
                    "block_transformer", "!block_transformer")) patch.remove(key);
        // The exact old TOOL wire codec omits this field and reads it as true.
        // Explicit removal stays a removal; all rules/speed/damage fields survive.
        if (legacyTool)
            for (String key : java.util.List.of("minecraft:tool", "tool"))
                if (patch.has(key)) patch.getAsJsonObject(key).addProperty("can_destroy_blocks_in_creative", true);
        if (patch.isEmpty()) return null;
        String projected = patch.toString();
        return projected.equals(json) ? json : projected;
    }

    private static void projectHolders(
            JsonObject patch, ProtocolVersion source, ProtocolVersion client, ProtocolVersion target) {
        for (String key : java.util.List.of("minecraft:tool", "tool"))
            if (patch.has(key)) {
                var tool = patch.getAsJsonObject(key);
                if (tool.has("rules"))
                    for (var rule : tool.getAsJsonArray("rules")) {
                        var fields = rule.getAsJsonObject();
                        if (fields.has("blocks"))
                            fields.add(
                                    "blocks", holders(fields.get("blocks"), "minecraft:block", source, client, target));
                    }
            }
        for (String key : java.util.List.of("minecraft:equippable", "equippable"))
            if (patch.has(key)) {
                var equippable = patch.getAsJsonObject(key);
                if (equippable.has("allowed_entities"))
                    equippable.add(
                            "allowed_entities",
                            holders(
                                    equippable.get("allowed_entities"),
                                    "minecraft:entity_type",
                                    source,
                                    client,
                                    target));
            }
        for (String key :
                java.util.List.of("minecraft:can_break", "can_break", "minecraft:can_place_on", "can_place_on"))
            if (patch.has(key)) {
                var value = patch.get(key);
                // Older native codecs wrap predicates; modern compactListCodec accepts
                // either one predicate or an ordered list. Only direct block holders
                // are rewritten, just as AdventureModePredicate -> BlockPredicate in Via.
                boolean wrapped =
                        value.isJsonObject() && value.getAsJsonObject().has("predicates");
                if (wrapped) value = value.getAsJsonObject().getAsJsonArray("predicates");
                if (value.isJsonArray()) {
                    var predicates = value.getAsJsonArray();
                    // The wire codec permits an empty predicate OR, which always
                    // returns false. Native JSON codecs require a nonempty list; one
                    // predicate with an empty direct blocks set has the same false
                    // result for every block, without weakening an action predicate.
                    boolean emptyOr = predicates.isEmpty();
                    if (emptyOr) predicates.add(denyingPredicate());
                    for (var predicate : predicates) predicate(predicate.getAsJsonObject(), source, client, target);
                    if (target.protocol() >= 770 && (wrapped || emptyOr))
                        patch.add(key, predicates.size() == 1 ? predicates.get(0) : predicates);
                    else if (target.protocol() < 770 && !wrapped) {
                        var legacy = new JsonObject();
                        legacy.add("predicates", predicates);
                        // The legacy tooltip flag defaults to true, matching Via's
                        // constructor when converting the modern predicate type.
                        patch.add(key, legacy);
                    }
                } else predicate(value.getAsJsonObject(), source, client, target);
            }
    }

    private static void predicate(
            JsonObject predicate, ProtocolVersion source, ProtocolVersion client, ProtocolVersion target) {
        // No blocks field is an unrestricted predicate, distinct from an empty
        // direct holder set. Keep state/NBT constraints and predicate order intact.
        if (predicate.has("blocks"))
            predicate.add("blocks", holders(predicate.get("blocks"), "minecraft:block", source, client, target));
    }

    static JsonObject denyingPredicate() {
        var result = new JsonObject();
        result.add("blocks", new JsonArray());
        return result;
    }

    private static JsonElement holders(
            JsonElement original,
            String registry,
            ProtocolVersion source,
            ProtocolVersion client,
            ProtocolVersion target) {
        // HolderSetImpl.Tag's rewrite is identity, independently of tag packet rewrites.
        if (original.isJsonPrimitive() && original.getAsString().startsWith("#")) return original;
        var result = new JsonArray();
        if (original.isJsonArray())
            for (var member : original.getAsJsonArray())
                member(result, member.getAsString(), registry, source, client, target);
        else member(result, original.getAsString(), registry, source, client, target);
        // Retain the original compact singleton representation when representable;
        // a removed singleton becomes the exact empty direct holder list.
        return original.isJsonArray() || result.size() != 1 ? result : result.get(0);
    }

    private static void member(
            JsonArray result,
            String original,
            String registry,
            ProtocolVersion source,
            ProtocolVersion client,
            ProtocolVersion target) {
        var sourceTable = ModelRegistryData.load(source).registry(registry);
        int sourceId = sourceTable.id(original.contains(":") ? original : "minecraft:" + original);
        if (sourceId < 0) throw new ProtocolResolutionException("Unknown " + registry + " holder " + original);
        int visible = map(ModelIdMappings.project(source, client), registry, sourceId);
        if (visible < 0) return; // Via filters a removed member before subsequent edges.
        int model = map(ModelIdMappings.project(client, target), registry, visible);
        if (model >= 0)
            result.add(new JsonPrimitive(
                    ModelRegistryData.load(target).registry(registry).name(model)));
    }

    private static int map(ModelIdMappings mappings, String registry, int id) {
        return registry.equals("minecraft:block") ? mappings.block(id) : mappings.entity(id);
    }
}
