package ac.cult.cultac.protocol;

import ac.cult.cultac.protocol.data.ModelIdMappings;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stateless value codecs. No private Via or vanilla objects cross this boundary. */
public interface WireValueDecoder extends AutoCloseable {
    ModelIdMappings mappings(ProtocolVersion source, ProtocolVersion target);

    Map<String, List<String>> tags(
            String registry,
            Map<String, List<String>> tags,
            ProtocolVersion source,
            ProtocolVersion client,
            ProtocolVersion target);

    /** Values projected into the model schema. Other components are retained in their source codec shape. */
    Set<String> PROJECTED_COMPONENTS = Set.of(
            "tool",
            "food",
            "consumable",
            "equippable",
            "glider",
            "enchantments",
            "use_cooldown",
            "damage",
            "max_damage",
            "bundle_contents",
            "can_place_on",
            "can_break",
            "writable_book_content",
            "custom_data",
            "block_state",
            "block_entity_data",
            "entity_data",
            "potion_contents",
            "dyed_color",
            "block_transformer",
            "creative_slot_lock",
            "map_post_processing",
            "map_id",
            "painting_variant",
            "pot_decorations",
            "max_stack_size");

    /** Names in received registry order; a missing name is an unsupported value, never a guessed ID. */
    interface Registries {
        String name(String registry, int id);

        int id(String registry, String name);

        /** Platform-authored values keep registry names while their schema follows the source version. */
        default boolean preserveIdentifiers() {
            return false;
        }
    }

    /** Detached component bytes and the source registry references used by their codec. */
    record ComponentEncoding(
            int protocol,
            byte[] bytes,
            Map<String, Map<Integer, String>> references,
            Map<String, Map<String, ComponentEncoding>> items) {
        public ComponentEncoding {
            bytes = bytes.clone();
            var copy = new java.util.HashMap<String, Map<Integer, String>>();
            references.forEach((key, value) -> copy.put(key, Map.copyOf(value)));
            references = Map.copyOf(copy);
            var children = new java.util.HashMap<String, Map<String, ComponentEncoding>>();
            items.forEach((key, value) -> children.put(key, Map.copyOf(value)));
            items = Map.copyOf(children);
        }

        public ComponentEncoding(int protocol, byte[] bytes, Map<String, Map<Integer, String>> references) {
            this(protocol, bytes, references, Map.of());
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /** Complete patch plus source-defined details that its persistent codec omits. Both own their bytes. */
    record ItemValue(
            int id,
            int count,
            byte[] components,
            byte[] componentLayouts,
            Map<String, ComponentEncoding> componentEncodings,
            ComponentEncoding wirePatch) {
        public ItemValue {
            components = components == null ? null : components.clone();
            componentLayouts = componentLayouts == null ? null : componentLayouts.clone();
            componentEncodings = Map.copyOf(componentEncodings);
        }

        public ItemValue(int id, int count, byte[] components) {
            this(id, count, components, null);
        }

        public ItemValue(int id, int count, byte[] components, byte[] layouts) {
            this(id, count, components, layouts, Map.of());
        }

        public ItemValue(
                int id, int count, byte[] components, byte[] layouts, Map<String, ComponentEncoding> encodings) {
            this(id, count, components, layouts, encodings, null);
        }

        @Override
        public byte[] components() {
            return components == null ? null : components.clone();
        }

        @Override
        public byte[] componentLayouts() {
            return componentLayouts == null ? null : componentLayouts.clone();
        }
    }

    record MetadataValue(int index, String kind, Object value, byte[] bytes) {}

    /** Unnamed network NBT, or null for a known-pack reference. Owns its bytes. */
    record RegistryEntry(String name, byte[] data) {
        public RegistryEntry {
            data = data == null ? null : data.clone();
        }

        @Override
        public byte[] data() {
            return data == null ? null : data.clone();
        }
    }

    record RegistryValues(String registry, List<RegistryEntry> entries) {
        public RegistryValues {
            entries = List.copyOf(entries);
        }
    }

    boolean canWriteComponent(String component);

    /** Writes a changed component from its portable persistent encoding, using the target schema. */
    void writeComponent(ProtocolVersion version, ByteBuf output, String component, byte[] value, Registries registries);

    ItemValue item(ProtocolVersion version, ByteBuf input, boolean creative, Registries registries);

    ItemValue itemCost(ProtocolVersion version, ByteBuf input, boolean optional, Registries registries);

    ItemValue hashedItem(ProtocolVersion version, ByteBuf input);

    List<MetadataValue> metadata(ProtocolVersion version, ByteBuf input, Registries registries);

    RegistryValues registry(ByteBuf input);

    int floatSerializer(ProtocolVersion version);

    @Override
    void close();
}
