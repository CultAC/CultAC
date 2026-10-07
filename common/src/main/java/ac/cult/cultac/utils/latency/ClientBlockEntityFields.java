package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.nbt.NbtJson;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.BlockEntityData;
import java.util.HashMap;

/** Only packet fields read by client action, signal or collision prediction. */
final class ClientBlockEntityFields {
    private ClientBlockEntityFields() {}

    static BlockEntityData load(String type, NbtValue.Compound tag, int state) {
        if (type.equals("minecraft:jukebox")) return null;
        var saved = new HashMap<String, NbtValue>();
        switch (type) {
            case "minecraft:comparator" -> saveInteger(saved, tag, "OutputSignal");
            case "minecraft:potent_sulfur" -> saveInteger(saved, tag, "countdown");
            case "minecraft:command_block" -> saveInteger(saved, tag, "SuccessCount");
            case "minecraft:sculk_sensor", "minecraft:calibrated_sculk_sensor" ->
                saveInteger(saved, tag, "last_vibration_frequency");
            case "minecraft:lectern" ->
                saved.putAll(ClientBookFields.lectern(tag).values());
            case "minecraft:sign", "minecraft:hanging_sign" ->
                saved.putAll(ClientSignFields.load(tag).values());
            case "minecraft:test_block" ->
                saved.put("powered", new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte)
                        (bool(tag.values().get("powered")) ? 1 : 0)));
            default -> saved.putAll(ClientContainerFields.load(type, tag).values());
        }
        var nbt = new NbtValue.Compound(saved);
        var fields = NbtJson.encode(nbt).getAsJsonObject();
        if (type.equals("minecraft:sign") || type.equals("minecraft:hanging_sign")) {
            for (String key : java.util.List.of("is_waxed", "front_has_commands", "back_has_commands"))
                fields.addProperty(key, bool(saved.get(key)));
        }
        if (type.equals("minecraft:shulker_box")) {
            fields.addProperty("animation_status", "CLOSED");
            fields.addProperty("progress", 0.0F);
            fields.addProperty("progress_old", 0.0F);
        }
        return new BlockEntityData(type, new Components(fields.asMap()), nbt);
    }

    private static void saveInteger(java.util.Map<String, NbtValue> saved, NbtValue.Compound tag, String field) {
        saved.put(
                field,
                new NbtValue.Numeric(NbtValue.Kind.INT, integer(tag.values().get(field))));
    }

    /** TagValueInput uses NumericTag conversions, including floor for floating-point ints. */
    static int integer(NbtValue value) {
        if (!(value instanceof NbtValue.Numeric number)) return 0;
        if (number.kind() != NbtValue.Kind.FLOAT && number.kind() != NbtValue.Kind.DOUBLE)
            return number.value().intValue();
        return (int) Math.floor(number.value().doubleValue());
    }

    static boolean bool(NbtValue value) {
        return value instanceof NbtValue.Numeric && (byte) integer(value) != 0;
    }
}
