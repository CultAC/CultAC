package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.*;
import ac.cult.blocksim.data.nbt.NbtJson;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.*;
import com.google.gson.*;
import java.util.Locale;

/** One implementation of the vanilla tool transform list and deterministic state providers. */
public final class BlockTransformerBehavior {
    private final DataTables data;
    private final InteractionRegistries registries;
    private final BlockPredicates predicates;
    public BlockTransformerBehavior(DataTables data, InteractionRegistries registries) {
        this.data = data; this.registries = registries; predicates = new BlockPredicates(data, this::state);
    }
    public SimInteraction useOn(UseContext context, JsonElement transformer) {
        if (context.hand() == Hand.MAIN_HAND && context.player() != null
            && context.player().hand(Hand.OFF_HAND).components().has("minecraft:blocks_attacks") && !context.secondaryUseActive()) return SimInteraction.PASS;
        for (var entry : registries.resolve("block_transformer", transformer).getAsJsonArray()) {
            var rule = entry.getAsJsonObject();
            if (rule.has("disallowed_faces") && rule.getAsJsonArray("disallowed_faces").asList().stream()
                .anyMatch(face -> face.getAsString().equals(context.clickedFace().name().toLowerCase(Locale.ROOT)))) continue;
            int state = provider(context.level(), context.clickedPos(), rule.get("block_state_provider"), true);
            if (state < 0) continue;
            if (!rule.has("update_from_neighbors") || NbtJson.booleanValue(rule.get("update_from_neighbors"))) {
                state = context.level().updateFromNeighborShapes(state, context.clickedPos());
            }
            if (context.stack().isStackable()) context.stack().consume(!rule.has("consume_on_use") || NbtJson.booleanValue(rule.get("consume_on_use")) ? 1 : 0, context.player());
            // ItemStack.hurtAndBreak requires ServerLevel; the client does not change damage.
            context.level().setBlock(context.clickedPos(), state, 11);
            return SimInteraction.SUCCESS;
        }
        return SimInteraction.PASS;
    }

    private int state(JsonElement value) {
        if (value.isJsonPrimitive()) return data.registry().block(HolderSets.identifier(value.getAsString())).defaultState();
        var encoded = value.getAsJsonObject();
        int result = data.registry().block(HolderSets.identifier(encoded.get("id").getAsString())).defaultState();
        if (encoded.has("properties")) for (var property : encoded.getAsJsonObject("properties").entrySet())
            result = data.registry().with(result, property.getKey(), property.getValue().getAsString());
        return result;
    }
    private int provider(SimLevel level, BlockPos pos, JsonElement encoded, boolean optional) {
        var value = registries.resolve("block_state_provider", encoded).getAsJsonObject();
        if (value.has("id")) return state(value);
        return switch (HolderSets.identifier(value.get("type").getAsString())) {
            case "minecraft:simple_state_provider" -> state(value.getAsJsonObject("state"));
            // Rare custom providers allocate only when an action actually uses them.
            case "minecraft:noise", "minecraft:dual_noise" -> new NoiseStateProvider(value, this::state).state(pos);
            case "minecraft:copy_properties" -> data.registry().withPropertiesOf(provider(level, pos, value.get("source"), false), level.stateAt(pos));
            case "minecraft:rule_based" -> {
                int result = -1;
                for (var entry : value.getAsJsonArray("rules")) {
                    var rule = entry.getAsJsonObject();
                    if (predicates.test(level, pos, rule.getAsJsonObject("if_true"))) result = provider(level, pos, rule.get("then"), true);
                    if (result >= 0) break;
                }
                if (result < 0 && value.has("fallback")) result = provider(level, pos, value.get("fallback"), true);
                yield result < 0 && !optional ? level.stateAt(pos) : result;
            }
            default -> throw new IllegalArgumentException("Unsupported state provider " + value.get("type"));
        };
    }
}
