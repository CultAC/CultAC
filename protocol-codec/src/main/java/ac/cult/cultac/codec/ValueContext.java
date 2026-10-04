package ac.cult.cultac.codec;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.data.ModelIdMappings;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.minecraft.codec.CodecContext;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.util.Key;

/** Borrowed read-only registry snapshot, scoped to one decode. */
final class ValueContext implements CodecContext, CodecContext.RegistryAccess {
    private final WireValueDecoder.Registries names;
    private final ac.cult.cultac.protocol.ProtocolVersion version;
    private final ModelIdMappings mappings;

    ValueContext(
            WireValueDecoder.Registries names,
            ac.cult.cultac.protocol.ProtocolVersion version,
            ModelIdMappings mappings) {
        this.names = names;
        this.version = version;
        this.mappings = mappings;
    }

    ac.cult.cultac.protocol.ProtocolVersion version() {
        return version;
    }

    @Override
    public RegistryAccess registryAccess() {
        return this;
    }

    @Override
    public boolean mapped() {
        return false;
    }

    @Override
    public boolean isSupported(StructuredDataKey<?> key) {
        return WireValueDecoder.COMPONENTS.contains(Key.stripMinecraftNamespace(key.identifier()));
    }

    @Override
    public UserConnection connection() {
        throw new UnsupportedOperationException("Stateless value codec");
    }

    @Override
    public com.viaversion.viaversion.api.protocol.Protocol<?, ?, ?, ?> protocol() {
        throw new UnsupportedOperationException("Stateless value codec");
    }

    @Override
    public Key item(int id) {
        return registryKey("item", id);
    }

    @Override
    public Key attributeModifier(int id) {
        return registryKey("attribute", id);
    }

    @Override
    public Key dataComponentType(int id) {
        return registryKey("data_component_type", id);
    }

    @Override
    public Key entity(int id) {
        return registryKey("entity_type", id);
    }

    @Override
    public Key blockEntity(int id) {
        return registryKey("block_entity_type", id);
    }

    @Override
    public Key sound(int id) {
        return registryKey("sound_event", id);
    }

    private static String registry(MappingData.MappingType type) {
        return switch (type) {
            case ITEM -> "item";
            case BLOCK -> "block";
            case SOUND -> "sound_event";
            case ENTITY_TYPE -> "entity_type";
        };
    }

    @Override
    public Key key(MappingData.MappingType type, int id) {
        return registryKey(registry(type), id);
    }

    @Override
    public int id(MappingData.MappingType type, String name) {
        return names.id("minecraft:" + registry(type), Key.namespaced(name));
    }

    @Override
    public Key registryKey(String registry, int id) {
        return Key.of(modelName(Key.namespaced(registry), names.name(Key.namespaced(registry), id)));
    }

    String modelName(String registry, String name) {
        var source = ModelRegistryData.load(version);
        var model = ModelRegistryData.load(ProtocolVersion.V26_3);
        return switch (registry) {
            case "minecraft:item", "minecraft:block", "minecraft:entity_type" -> {
                int id = source.registry(registry).id(name);
                int mapped = switch (registry) {
                    case "minecraft:item" -> mappings.item(id);
                    case "minecraft:block" -> mappings.block(id);
                    default -> mappings.entity(id);
                };
                yield model.registry(registry).name(mapped);
            }
            case "minecraft:sound_event" ->
                model.registry(registry)
                        .name(mappings.sound(source.registry(registry).id(name)));
            default -> name; // Dynamic holders already use received registry order and model names.
        };
    }

    @Override
    public RegistryAccess withMapped(boolean mapped) {
        return this;
    }
}
