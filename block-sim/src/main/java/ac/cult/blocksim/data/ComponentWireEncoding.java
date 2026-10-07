package ac.cult.blocksim.data;

import java.util.Map;
import java.util.function.ToIntBiFunction;

/** Transport metadata, independent of component equality and the simulation's values. */
public final class ComponentWireEncoding {
    private final int protocol;
    private final byte[] bytes;
    private final Map<String, Map<Integer, String>> references;
    private final Map<String, Map<String, ComponentWireEncoding>> items;

    public ComponentWireEncoding(int protocol, byte[] bytes, Map<String, Map<Integer, String>> references) {
        this(protocol, bytes, references, Map.of());
    }

    public ComponentWireEncoding(int protocol, byte[] bytes, Map<String, Map<Integer, String>> references,
                                 Map<String, Map<String, ComponentWireEncoding>> items) {
        this.protocol = protocol;
        this.bytes = bytes.clone();
        var copy = new java.util.HashMap<String, Map<Integer, String>>();
        references.forEach((key, value) -> copy.put(key, Map.copyOf(value)));
        this.references = Map.copyOf(copy);
        var children = new java.util.HashMap<String, Map<String, ComponentWireEncoding>>();
        items.forEach((key, value) -> children.put(key, Map.copyOf(value)));
        this.items = Map.copyOf(children);
    }

    public byte[] bytes() { return bytes.clone(); }
    /** A fresh read-only view lets packet helpers copy directly into their output. */
    public java.nio.ByteBuffer buffer() { return java.nio.ByteBuffer.wrap(bytes).asReadOnlyBuffer(); }
    public Map<String, ComponentWireEncoding> item(String slot) { return items.getOrDefault(slot, Map.of()); }

    /** Raw holder IDs can only be reused while every referenced registry entry still agrees. */
    public boolean matches(int protocol, ToIntBiFunction<String, String> ids) {
        if (this.protocol != protocol) return false;
        for (var registry : references.entrySet())
            for (var entry : registry.getValue().entrySet())
                if (ids.applyAsInt(registry.getKey(), entry.getValue()) != entry.getKey()) return false;
        return true;
    }
}
