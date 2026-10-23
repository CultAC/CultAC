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

    /** The entry's wire bytes are {@code length} bytes of the decoded buffer from its absolute index {@code offset}. */
    record MetadataValue(int index, String kind, Object value, int offset, int length) {}

    /** Builds the consumer's owned NBT tree directly, without a serialized transport copy. */
    interface NbtFactory<T> {
        T read(byte[] networkNbt);

        T number(int type, Number value);

        T text(String value);

        T array(int type, List<Long> values);

        T sequence(List<T> values);

        T compound(Map<String, T> values);
    }

    record DecodedItem<T>(
            int id,
            int count,
            T components,
            T componentLayouts,
            Map<String, ComponentEncoding> componentEncodings,
            ComponentEncoding wirePatch) {
        public DecodedItem {
            componentEncodings = Map.copyOf(componentEncodings);
        }
    }

    private static <T> DecodedItem<T> decoded(ItemValue item, NbtFactory<T> factory) {
        byte[] components = item.components(), layouts = item.componentLayouts();
        return new DecodedItem<>(
                item.id(),
                item.count(),
                components == null ? null : factory.read(components),
                layouts == null ? null : factory.read(layouts),
                item.componentEncodings(),
                item.wirePatch());
    }

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

    /** Writes a changed component from its portable persistent encoding, using the target schema. */
    void writeComponent(ProtocolVersion version, ByteBuf output, String component, byte[] value, Registries registries);

    ItemValue item(ProtocolVersion version, ByteBuf input, boolean creative, Registries registries);

    default <T> DecodedItem<T> item(
            ProtocolVersion version, ByteBuf input, boolean creative, Registries registries, NbtFactory<T> factory) {
        return decoded(item(version, input, creative, registries), factory);
    }

    ItemValue itemCost(ProtocolVersion version, ByteBuf input, boolean optional, Registries registries);

    default <T> DecodedItem<T> itemCost(
            ProtocolVersion version, ByteBuf input, boolean optional, Registries registries, NbtFactory<T> factory) {
        return decoded(itemCost(version, input, optional, registries), factory);
    }

    ItemValue hashedItem(ProtocolVersion version, ByteBuf input);

    List<MetadataValue> metadata(ProtocolVersion version, ByteBuf input, Registries registries);

    default <T> List<MetadataValue> metadata(
            ProtocolVersion version, ByteBuf input, Registries registries, NbtFactory<T> factory) {
        return metadata(version, input, registries).stream()
                .map(value -> value.kind().equals("item")
                        ? new MetadataValue(
                                value.index(),
                                value.kind(),
                                decoded((ItemValue) value.value(), factory),
                                value.offset(),
                                value.length())
                        : value)
                .toList();
    }

    RegistryValues registry(ByteBuf input);

    /** What players write in {@code version}'s values, for taking it out of a packet before it leaves the server. */
    PlayerTextCodec playerText(ProtocolVersion version);

    int floatSerializer(ProtocolVersion version);

    @Override
    void close();
}
