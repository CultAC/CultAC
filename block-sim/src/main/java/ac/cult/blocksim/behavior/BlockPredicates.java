package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.HolderSets;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.shapes.Shapes;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToIntFunction;

/** Deterministic client block predicates over the same compensated action overlay as state providers. */
final class BlockPredicates {
    private final DataTables data;
    private final ToIntFunction<JsonObject> state;

    BlockPredicates(DataTables data, ToIntFunction<JsonObject> state) {
        this.data = data;
        this.state = state;
    }

    boolean test(SimLevel level, BlockPos origin, JsonObject predicate) {
        var pos = origin;
        if (predicate.has("offset")) {
            var offset = predicate.getAsJsonArray("offset");
            pos = new BlockPos(origin.x() + offset.get(0).getAsInt(), origin.y() + offset.get(1).getAsInt(), origin.z() + offset.get(2).getAsInt());
        }
        return switch (HolderSets.identifier(predicate.get("type").getAsString())) {
            case "minecraft:true" -> true;
            case "minecraft:not" -> !test(level, origin, predicate.getAsJsonObject("predicate"));
            case "minecraft:all_of", "minecraft:any_of" -> {
                boolean all = HolderSets.identifier(predicate.get("type").getAsString()).equals("minecraft:all_of"), result = all;
                for (var value : predicate.getAsJsonArray("predicates")) {
                    result = test(level, origin, value.getAsJsonObject());
                    if (result != all) break;
                }
                yield result;
            }
            case "minecraft:matching_blocks" -> HolderSets.contains(data, "block", predicate.get("blocks"), data.registry().block(level.stateAt(pos)).key());
            case "minecraft:matching_block_tag" -> data.tags().getOrDefault("block:" + HolderSets.identifier(predicate.get("tag").getAsString()), Set.of())
                    .contains(data.registry().block(level.stateAt(pos)).key());
            case "minecraft:matching_fluids" -> HolderSets.contains(data, "fluid", predicate.get("fluids"), level.fluidAt(pos).type());
            case "minecraft:matching_biomes" -> HolderSets.contains(data, "worldgen/biome", predicate.get("biomes"), level.biomeKeyAt(origin));
            case "minecraft:replaceable" -> data.registry().facts(level.stateAt(pos)).has(StateFacts.REPLACEABLE);
            case "minecraft:solid" -> data.registry().facts(level.stateAt(pos)).has(StateFacts.SOLID);
            case "minecraft:has_sturdy_face" -> level.isFaceSturdy(level.stateAt(pos), pos, Direction.valueOf(predicate.get("direction").getAsString().toUpperCase(Locale.ROOT)));
            case "minecraft:would_survive" -> {
                int candidate = state.applyAsInt(predicate.getAsJsonObject("state"));
                yield level.behavior(candidate).canSurvive(level, candidate, pos);
            }
            case "minecraft:inside_world_bounds" -> pos.y() >= level.minY() && pos.y() <= level.maxY();
            case "minecraft:height_range" -> origin.y() >= anchor(level, predicate.getAsJsonObject("min_inclusive"))
                    && origin.y() <= anchor(level, predicate.getAsJsonObject("max_inclusive"));
            // UnobstructedPredicate.test deliberately uses the original position:
            // its serialized offset is not applied by the pinned client implementation.
            case "minecraft:unobstructed" -> level.isUnobstructed(Shapes.block().move(origin.x(), origin.y(), origin.z()));
            case "minecraft:volume_match" -> volume(level, origin, predicate);
            default -> throw new IllegalArgumentException("Unsupported block predicate " + predicate.get("type"));
        };
    }

    private boolean volume(SimLevel level, BlockPos origin, JsonObject predicate) {
        var min = predicate.getAsJsonArray("min");
        var max = predicate.getAsJsonArray("max");
        for (int x = min.get(0).getAsInt(); x <= max.get(0).getAsInt(); x++)
            for (int z = min.get(2).getAsInt(); z <= max.get(2).getAsInt(); z++)
                for (int y = min.get(1).getAsInt(); y <= max.get(1).getAsInt(); y++)
                    if (!test(level, new BlockPos(origin.x() + x, origin.y() + y, origin.z() + z), predicate.getAsJsonObject("match"))) return false;
        return true;
    }

    private static int anchor(SimLevel level, JsonObject anchor) {
        if (anchor.has("absolute")) return anchor.get("absolute").getAsInt();
        if (anchor.has("above_bottom")) return level.minY() + anchor.get("above_bottom").getAsInt();
        if (anchor.has("below_top")) return level.maxY() - anchor.get("below_top").getAsInt();
        if (anchor.has("relative_to_sea_level")) return level.seaLevel() + anchor.get("relative_to_sea_level").getAsInt();
        throw new IllegalArgumentException("Unsupported vertical anchor " + anchor);
    }
}
