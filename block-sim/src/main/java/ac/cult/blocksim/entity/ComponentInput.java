package ac.cult.blocksim.entity;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.nbt.NbtValue;
import java.util.HashMap;
import java.util.Map;

final class ComponentInput {
    final Components components;
    final Map<String, NbtValue> fields;
    final Map<String, com.google.gson.JsonElement> internalFields = new HashMap<>();
    ComponentInput(Components components, NbtValue.Compound saved) {
        this.components = components; this.fields = new HashMap<>(saved.values());
    }
    com.google.gson.JsonElement get(String key) { return components.get(key); }
    NbtValue nbt(String key) { return components.encodedNbt(key); }
}
