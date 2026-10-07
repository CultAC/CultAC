package ac.cult.blocksim.data;

import java.util.HashSet;
import java.util.Set;

/** Component additions/removals; live stacks sanitize against their captured prototype. */
public record ComponentPatch(Components added, Set<String> removed) {
    public static final ComponentPatch EMPTY = new ComponentPatch(Components.EMPTY, Set.of());
    public ComponentPatch { java.util.Objects.requireNonNull(added); removed = Set.copyOf(removed); }

    /** Complete network values from the private wire decoder, without native codec parsing. */
    public static ComponentPatch fromNetwork(byte[] bytes) {
        return fromNetwork(bytes, null);
    }
    public static ComponentPatch fromNetwork(byte[] bytes, byte[] layouts) {
        if (bytes == null) return EMPTY;
        var compound = (ac.cult.blocksim.data.nbt.NbtValue.Compound) ac.cult.blocksim.data.nbt.BinaryNbt.read(bytes);
        var details = layouts == null ? new ac.cult.blocksim.data.nbt.NbtValue.Compound(java.util.Map.of())
                : (ac.cult.blocksim.data.nbt.NbtValue.Compound) ac.cult.blocksim.data.nbt.BinaryNbt.read(layouts);
        return fromNbt(compound, details);
    }
    public static ComponentPatch fromNbt(ac.cult.blocksim.data.nbt.NbtValue.Compound compound) {
        return fromNbt(compound, new ac.cult.blocksim.data.nbt.NbtValue.Compound(java.util.Map.of()));
    }
    public static ComponentPatch fromNbt(ac.cult.blocksim.data.nbt.NbtValue.Compound compound,
                                       ac.cult.blocksim.data.nbt.NbtValue.Compound layouts) {
        var values = new java.util.HashMap<String, com.google.gson.JsonElement>();
        var encoded = new java.util.HashMap<String, ac.cult.blocksim.data.nbt.NbtValue>();
        var removed = new HashSet<String>();
        var details = new java.util.HashMap<String, ac.cult.blocksim.data.nbt.NbtValue.Compound>();
        compound.values().forEach((key, value) -> {
            if (key.startsWith("!")) removed.add(key.substring(1));
            else {
                values.put(key, ac.cult.blocksim.data.nbt.NbtJson.encode(value));
                encoded.put(key, value);
                if (layouts.values().get(key) instanceof ac.cult.blocksim.data.nbt.NbtValue.Compound layout)
                    details.put(key, layout);
            }
        });
        return values.isEmpty() && removed.isEmpty() ? EMPTY
                : new ComponentPatch(new Components(values, encoded, details), removed);
    }

    /** Authored codec values; received values use fromNbt to retain their exact NBT kinds. */
    public static ComponentPatch fromJson(com.google.gson.JsonObject object) {
        var values = new java.util.HashMap<String, com.google.gson.JsonElement>();
        var removed = new HashSet<String>();
        object.entrySet().forEach(entry -> {
            String key = entry.getKey();
            if (key.startsWith("!")) removed.add(HolderSets.identifier(key.substring(1)));
            else values.put(HolderSets.identifier(key), entry.getValue());
        });
        return values.isEmpty() && removed.isEmpty() ? EMPTY : new ComponentPatch(new Components(values), removed);
    }

    public static ComponentPatch between(Components prototype, Components effective) {
        var added = new HashSet<String>(); var removed = new HashSet<String>();
        for (String key : effective.keys()) if (!effective.sameValue(key, prototype)) added.add(key);
        for (String key : prototype.keys()) if (!effective.has(key)) removed.add(key);
        return new ComponentPatch(effective.subset(added), removed);
    }
    public Components apply(Components prototype) { return prototype.overlay(added, removed); }
    public ComponentPatch forget(Set<String> consumed) {
        var kept = new HashSet<>(added.keys()); kept.removeAll(consumed);
        var keptRemoved = new HashSet<>(removed); keptRemoved.removeAll(consumed);
        return new ComponentPatch(added.subset(kept), keptRemoved);
    }
}
