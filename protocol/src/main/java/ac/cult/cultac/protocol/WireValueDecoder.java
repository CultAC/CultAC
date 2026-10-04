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

    Set<String> COMPONENTS = Set.of(
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
            "max_stack_size");

    /** Names in received registry order; a missing name is an unsupported value, never a guessed ID. */
    interface Registries {
        String name(String registry, int id);

        int id(String registry, String name);
    }

    record ItemValue(int id, int count, byte[] components) {}

    record MetadataValue(int index, String kind, Object value, byte[] bytes) {}

    record RegistryEntry(String name, byte[] data) {}

    record RegistryValues(String registry, List<RegistryEntry> entries) {
        public RegistryValues {
            entries = List.copyOf(entries);
        }
    }

    ItemValue item(ProtocolVersion version, ByteBuf input, boolean creative, Registries registries);

    ItemValue itemCost(ProtocolVersion version, ByteBuf input, boolean optional, Registries registries);

    ItemValue hashedItem(ProtocolVersion version, ByteBuf input);

    List<MetadataValue> metadata(ProtocolVersion version, ByteBuf input, Registries registries);
    /** Consume only the section payload and return model-format sections; the caller owns packet framing. */
    byte[] sections(
            ProtocolVersion version,
            ByteBuf input,
            int count,
            int blockBits,
            int biomeBits,
            int modelBiomeBits,
            java.util.function.IntUnaryOperator biomeIds);

    RegistryValues registry(ByteBuf input);

    int floatSerializer(ProtocolVersion version);

    @Override
    void close();
}
