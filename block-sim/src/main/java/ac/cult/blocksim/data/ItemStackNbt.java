package ac.cult.blocksim.data;

import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.SimItemStack;
import java.util.HashMap;
import java.util.Map;

/** ItemStack.MAP_CODEC output, using component encodings supplied at the input boundary. */
public final class ItemStackNbt {
    private ItemStackNbt() { }
    public static NbtValue.Compound encode(SimItemStack stack) {
        if (stack.isEmpty()) throw new IllegalArgumentException("Empty ItemStack is not allowed by CODEC");
        var result = new HashMap<String, NbtValue>();
        result.put("id", new NbtValue.Text(stack.itemKey()));
        result.put("count", new NbtValue.Numeric(NbtValue.Kind.INT, stack.count()));
        var patch = stack.patch();
        if (!patch.added().keys().isEmpty() || !patch.removed().isEmpty()) {
            var components = new HashMap<>(patch.added().encodedNbt().values());
            patch.removed().forEach(key -> components.put("!" + key, new NbtValue.Compound(Map.of())));
            result.put("components", new NbtValue.Compound(components));
        }
        return new NbtValue.Compound(result);
    }
}
