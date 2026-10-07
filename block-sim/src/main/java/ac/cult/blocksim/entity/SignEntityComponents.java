package ac.cult.blocksim.entity;

import ac.cult.blocksim.data.nbt.NbtValue;

public final class SignEntityComponents {
    private SignEntityComponents() { }
    static void apply(ComponentInput input) {
        for (String side : java.util.List.of("front", "back")) {
            boolean commands = hasCommands(input.nbt("minecraft:sign_text_" + side));
            String key = side + "_has_commands";
            input.fields.put(key, new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte) (commands ? 1 : 0)));
            input.internalFields.put(key, new com.google.gson.JsonPrimitive(commands));
        }
        boolean waxed = input.get("minecraft:waxed") != null;
        input.fields.put("is_waxed", new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte)(waxed ? 1 : 0)));
        input.internalFields.put("is_waxed", new com.google.gson.JsonPrimitive(waxed));
    }

    /** SignText.hasAnyClickCommands(false) checks each unfiltered line's root style. */
    public static boolean hasCommands(NbtValue text) {
        if (!(text instanceof NbtValue.Compound sign)
                || !(sign.values().get("messages") instanceof NbtValue.Sequence messages)) return false;
        for (var message : messages.values()) {
            // A component list appends siblings to its first component; their styles do not become root styles.
            while (message instanceof NbtValue.Sequence list && !list.values().isEmpty()) message = list.values().get(0);
            if (message instanceof NbtValue.Compound line
                    && line.values().get("click_event") instanceof NbtValue.Compound event
                    && event.values().get("action") instanceof NbtValue.Text action
                    && action.value().equals("run_command")) return true;
        }
        return false;
    }
}
