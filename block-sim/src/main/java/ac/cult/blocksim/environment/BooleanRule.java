/* Copyright (C) 2025 retrooper and contributors. SPDX-License-Identifier: GPL-3.0-or-later
 * Boolean operations adapted from PacketEvents' world.attributes.modifiers.BooleanModifier. */
package ac.cult.blocksim.environment;

import ac.cult.blocksim.data.nbt.NbtJson;
import com.google.gson.JsonObject;

/** Only the boolean attribute operations consumed by prediction. */
public final class BooleanRule {
    private BooleanRule() { }
    public enum Operation {
        OVERRIDE, AND, NAND, OR, NOR, XOR, XNOR;
        public boolean apply(boolean base, boolean argument) {
            return switch (this) {
                case OVERRIDE -> argument;
                case AND -> argument && base;
                case NAND -> !argument || !base;
                case OR -> argument || base;
                case NOR -> !argument && !base;
                case XOR -> argument ^ base;
                case XNOR -> argument == base;
            };
        }
        public static Operation read(String name) {
            for (var operation : values())
                if (operation.name().toLowerCase(java.util.Locale.ROOT).equals(name)) return operation;
            throw new IllegalArgumentException("Unknown boolean modifier " + name);
        }
    }
    public static boolean apply(JsonObject attributes, String name, boolean base) {
        if (attributes == null) return base;
        var entry = attributes.get(name);
        if (entry == null) entry = attributes.get("minecraft:" + name);
        if (entry == null) return base;
        if (entry.isJsonPrimitive()) return NbtJson.booleanValue(entry);
        var object = entry.getAsJsonObject();
        return Operation.read(object.get("modifier").getAsString()).apply(base, NbtJson.booleanValue(object.get("argument")));
    }
}
