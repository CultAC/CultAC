package ac.cult.blocksim.engine;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.nbt.NbtJson;
import ac.cult.blocksim.data.nbt.NbtValue;
import java.util.HashMap;
import java.util.List;

/** Sparse constructor state for client actions and geometry. */
public final class BlockEntityPrototypes {
    private BlockEntityPrototypes() { }
    public static BlockEntityData create(BlockDefinition block, int state) {
        String type = block.bindings().get("blockEntity.type");
        if (type == null || type.equals("none")) return null;
        var fields = new HashMap<String, NbtValue>();
        switch (type) {
            case "minecraft:piston" -> {
                var tag = (NbtValue.Compound) ac.cult.blocksim.data.nbt.CanonicalSnbt.parse(block.bindings().get("blockEntity.defaultNbt"));
                fields.putAll(tag.values()); fields.remove("components");
            }
            case "minecraft:test_block" -> fields.put("powered", new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte) 0));
            case "minecraft:potent_sulfur" -> fields.put("countdown", new NbtValue.Numeric(NbtValue.Kind.INT, -1));
            case "minecraft:comparator" -> fields.put("OutputSignal", new NbtValue.Numeric(NbtValue.Kind.INT, 0));
            case "minecraft:command_block" -> fields.put("SuccessCount", new NbtValue.Numeric(NbtValue.Kind.INT, 0));
            case "minecraft:sculk_sensor", "minecraft:calibrated_sculk_sensor" ->
                    fields.put("last_vibration_frequency", new NbtValue.Numeric(NbtValue.Kind.INT, 0));
            case "minecraft:lectern" -> {
                for (String key : List.of("Page", "page_count", "has_book_content"))
                    fields.put(key, new NbtValue.Numeric(NbtValue.Kind.INT, 0));
            }
            default -> { }
        }
        int size = Integer.parseInt(block.bindings().getOrDefault("blockEntity.containerSize", "0"));
        // Shelf/bookcase output is server-only; campfires do not read their client inventory.
        if (size > 0 && !List.of("minecraft:shelf", "minecraft:chiseled_bookshelf", "minecraft:campfire").contains(type))
            fields.put("Items", new NbtValue.Sequence(List.of()));
        if (type.equals("minecraft:crafter")) fields.put("disabled_slots", new NbtValue.PrimitiveArray(NbtValue.Kind.INT_ARRAY, List.of()));
        var saved = new NbtValue.Compound(fields);
        var modeled = NbtJson.encode(saved).getAsJsonObject();
        if (type.equals("minecraft:sign") || type.equals("minecraft:hanging_sign"))
            for (String key : List.of("is_waxed", "front_has_commands", "back_has_commands")) modeled.addProperty(key, false);
        if (type.equals("minecraft:jukebox")) modeled.addProperty("song_playing", false);
        if (type.equals("minecraft:shulker_box")) {
            modeled.addProperty("animation_status", "CLOSED");
            modeled.addProperty("progress", 0.0F); modeled.addProperty("progress_old", 0.0F);
        }
        return new BlockEntityData(type, new Components(modeled.asMap()), saved);
    }
}
