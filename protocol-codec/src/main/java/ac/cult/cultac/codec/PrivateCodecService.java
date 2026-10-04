package ac.cult.cultac.codec;

import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.*;
import com.viaversion.viaversion.ViaManagerImpl;
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.minecraft.RegistryType;
import com.viaversion.viaversion.api.minecraft.chunks.PaletteType;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.ProtocolPathEntry;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.chunk.*;
import com.viaversion.viaversion.api.type.types.version.*;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.rewriter.TagRewriter;
import com.viaversion.viaversion.util.Key;
import io.netty.buffer.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** One-time type initialization, then concurrent reads without private player connections. */
public final class PrivateCodecService implements WireValueDecoder {
    private final ViaManagerImpl manager;

    private record Versions(ProtocolVersion source, ProtocolVersion target) {}

    private final Map<Versions, ModelIdMappings> mappings = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public PrivateCodecService(Path directory) throws Exception {
        manager = ModernProtocols.open(directory.toFile());
    }

    /** Via's cached protocol path, ordered from backend toward client for clientbound values. */
    private List<ProtocolPathEntry> path(ProtocolVersion source, ProtocolVersion target) {
        if (source == target) return List.of();
        var path = manager.getProtocolManager()
                .getProtocolPath(
                        com.viaversion.viaversion.api.protocol.version.ProtocolVersion.getProtocol(target.protocol()),
                        com.viaversion.viaversion.api.protocol.version.ProtocolVersion.getProtocol(source.protocol()));
        if (path == null) throw new ProtocolResolutionException("Missing Via path " + source + " -> " + target);
        return path.reversed();
    }

    @Override
    public ModelIdMappings mappings(ProtocolVersion source, ProtocolVersion target) {
        checkOpen();
        return mappings.computeIfAbsent(new Versions(source, target), versions -> {
            var original = ModelRegistryData.load(source);
            var destination = ModelRegistryData.load(target);
            var path = path(source, target);
            var ids = new HashMap<String, int[]>();
            for (var key : List.of("blockstates", "blocks", "items", "entities", "sounds")) {
                String registry = switch (key) {
                    case "blocks" -> "minecraft:block";
                    case "items" -> "minecraft:item";
                    case "entities" -> "minecraft:entity_type";
                    default -> "minecraft:sound_event";
                };
                int count = key.equals("blockstates")
                        ? original.blockStates().size()
                        : original.registry(registry).size();
                int targetCount = key.equals("blockstates")
                        ? destination.blockStates().size()
                        : destination.registry(registry).size();
                var values = new int[count];
                for (int id = 0; id < count; id++) {
                    int mapped = id;
                    for (var edge : path) {
                        if (mapped == -1) break; // Removed members never enter the following edge.
                        MappingData data = edge.protocol().getMappingData();
                        if (data == null) continue;
                        mapped = switch (key) {
                            case "blockstates" ->
                                data.getBlockStateMappings() == null ? mapped : data.getNewBlockStateId(mapped);
                            case "blocks" -> data.getBlockMappings() == null ? mapped : data.getNewBlockId(mapped);
                            case "items" -> data.getItemMappings() == null ? mapped : data.getNewItemId(mapped);
                            case "entities" ->
                                data.getEntityMappings() == null
                                        ? mapped
                                        : data.getEntityMappings().getNewId(mapped);
                            default -> data.getSoundMappings() == null ? mapped : data.getNewSoundId(mapped);
                        };
                    }
                    int minimum = key.equals("blocks") || key.equals("entities") ? -1 : 0;
                    if (mapped < minimum || mapped >= targetCount)
                        throw new ProtocolResolutionException("Invalid Via " + key + " mapping " + source + " -> "
                                + target + ": " + id + " -> " + mapped);
                    values[id] = mapped;
                }
                ids.put(key, values);
            }
            return new ModelIdMappings(ids);
        });
    }

    @Override
    public Map<String, List<String>> tags(
            String registry,
            Map<String, List<String>> tags,
            ProtocolVersion source,
            ProtocolVersion client,
            ProtocolVersion target) {
        checkOpen();
        if (!List.of("minecraft:block", "minecraft:item", "minecraft:fluid", "minecraft:entity_type")
                .contains(registry)) throw new IllegalArgumentException("Unsupported action tag registry " + registry);
        if (source == client && client == target) return Collections.unmodifiableMap(new LinkedHashMap<>(tags));
        var type = RegistryType.getByKey(Key.stripMinecraftNamespace(registry));
        var original = ModelRegistryData.load(source).registry(registry);
        var destination = ModelRegistryData.load(target).registry(registry);
        // Only typed tag values enter this wrapper. No connection, packet translation or sends.
        var wrapper = new PacketWrapperImpl(-1, null, null);
        wrapper.write(Types.VAR_INT, tags.size());
        for (var entry : tags.entrySet()) {
            wrapper.write(Types.STRING, Key.namespaced(entry.getKey()));
            int[] ids = entry.getValue().stream()
                    .mapToInt(member -> {
                        int id = original.id(Key.namespaced(member));
                        if (id < 0)
                            throw new ProtocolResolutionException("Unknown " + registry + " tag member " + member);
                        return id;
                    })
                    .toArray();
            wrapper.write(Types.VAR_INT_ARRAY_PRIMITIVE, ids);
        }
        wrapper.resetReader();
        var edges = new ArrayList<>(path(source, client));
        edges.addAll(path(client, target));
        try {
            for (var edge : edges) {
                var rewriter = (TagRewriter<?>) edge.protocol().getTagRewriter();
                if (rewriter == null)
                    throw new ProtocolResolutionException("Missing Via tag rewriter "
                            + edge.protocol().getClass().getName());
                if (rewriter.shouldRemoveRegistry(Key.stripMinecraftNamespace(registry))) {
                    wrapper = new PacketWrapperImpl(-1, null, null);
                    wrapper.write(Types.VAR_INT, 0);
                } else rewriter.handle(wrapper, type);
                wrapper.resetReader();
            }
            var result = new LinkedHashMap<String, List<String>>();
            int size = wrapper.read(Types.VAR_INT);
            for (int index = 0; index < size; index++) {
                String key = Key.namespaced(wrapper.read(Types.STRING));
                var members = new ArrayList<String>();
                for (int id : wrapper.read(Types.VAR_INT_ARRAY_PRIMITIVE)) members.add(destination.name(id));
                // Native tag decoding retains the last occurrence's value and position.
                result.remove(key);
                result.put(key, List.copyOf(members));
            }
            return Collections.unmodifiableMap(result);
        } catch (com.viaversion.viaversion.exception.InformativeException failure) {
            throw new ProtocolResolutionException("Cannot project Via tags for " + registry, failure);
        }
    }

    static VersionedTypesHolder types(ProtocolVersion version) {
        return switch (version) {
            case V1_21_3 -> VersionedTypes.V1_21_2;
            case V1_21_4 -> VersionedTypes.V1_21_4;
            case V1_21_5 -> VersionedTypes.V1_21_5;
            case V1_21_6, V1_21_7 -> VersionedTypes.V1_21_6;
            case V1_21_9 -> VersionedTypes.V1_21_9;
            case V1_21_11 -> VersionedTypes.V1_21_11;
            case V26_1 -> VersionedTypes.V26_1;
            case V26_2 -> VersionedTypes.V26_2;
            case V26_3 -> VersionedTypes.V26_3;
        };
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Value codecs are closed");
    }

    @Override
    public ItemValue item(ProtocolVersion version, ByteBuf input, boolean creative, Registries registries) {
        checkOpen();
        var type = types(version);
        // 1.21.5 introduced the length-prefixed creative component codec.
        Item item = (creative && version.atLeast(ProtocolVersion.V1_21_5) ? type.lengthPrefixedItem() : type.item())
                .read(input);
        return item(item, registries, version);
    }

    private ItemValue item(Item item, Registries registries, ProtocolVersion version) {
        if (item == null || item.isEmpty()) return new ItemValue(0, 0, null);
        var patch = ComponentValues.patch(
                item, new ValueContext(registries, version, mappings(version, ProtocolVersion.V26_3)));
        ByteBuf output = Unpooled.buffer();
        try {
            Types.COMPOUND_TAG.write(output, patch);
            return new ItemValue(item.identifier(), item.amount(), ByteBufUtil.getBytes(output));
        } finally {
            output.release();
        }
    }

    @Override
    public ItemValue hashedItem(ProtocolVersion version, ByteBuf input) {
        checkOpen();
        var item = Types.HASHED_ITEM.read(input);
        // Hashes are not values. This matches the native consumer's item/count view.
        return item.isEmpty() ? new ItemValue(0, 0, null) : new ItemValue(item.identifier(), item.amount(), null);
    }

    @Override
    public ItemValue itemCost(ProtocolVersion version, ByteBuf input, boolean optional, Registries registries) {
        checkOpen();
        var types = types(version);
        return item((optional ? types.optionalItemCost() : types.itemCost()).read(input), registries, version);
    }

    private static int serializer(VersionedTypesHolder types, String name) {
        try {
            var values = types.entityDataTypes();
            return ((com.viaversion.viaversion.api.minecraft.entitydata.EntityDataType)
                            values.getClass().getField(name).get(values))
                    .typeId();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Missing metadata type " + name, failure);
        }
    }

    @Override
    public int floatSerializer(ProtocolVersion version) {
        return serializer(types(version), "floatType");
    }

    @Override
    public List<MetadataValue> metadata(ProtocolVersion version, ByteBuf input, Registries registries) {
        checkOpen();
        var types = types(version);
        int pose = serializer(types, "poseType"), optional = serializer(types, "optionalVarIntType");
        var result = new ArrayList<MetadataValue>();
        for (int index; (index = input.readUnsignedByte()) != 255; ) {
            int start = input.readerIndex() - 1;
            int id = Types.VAR_INT.readPrimitive(input);
            var values = types.entityDataTypes().values();
            if (id < 0 || id >= values.length) throw new MalformedPacketException("Unknown metadata serializer " + id);
            Object value = values[id].type().read(input);
            String kind = "unused";
            if (id == 0 || id == 1 || id == 3 || id == 8) kind = "value";
            else if (id == pose) kind = "pose";
            else if (id == optional) kind = "optional_int";
            else if (id == 7) {
                kind = "item";
                value = item((Item) value, registries, version);
            } else if (id == 12) kind = "direction";
            else if (id == 11) {
                kind = "optional_position";
                if (value != null) {
                    var position = (com.viaversion.viaversion.api.minecraft.BlockPosition) value;
                    value = new int[] {position.x(), position.y(), position.z()};
                }
            }
            if (kind.equals("unused")) value = null;
            result.add(new MetadataValue(
                    index, kind, value, ByteBufUtil.getBytes(input, start, input.readerIndex() - start)));
        }
        return List.copyOf(result);
    }

    @Override
    public byte[] sections(
            ProtocolVersion version,
            ByteBuf input,
            int count,
            int blockBits,
            int biomeBits,
            int modelBiomeBits,
            java.util.function.IntUnaryOperator biomeIds) {
        checkOpen();
        var source = version.atLeast(ProtocolVersion.V26_1)
                ? new ChunkSectionType26_1(blockBits, biomeBits)
                : version.atLeast(ProtocolVersion.V1_21_5)
                        ? new ChunkSectionType1_21_5(blockBits, biomeBits)
                        : new ChunkSectionType1_18(blockBits, biomeBits);
        var data = ModelRegistryData.load(ProtocolVersion.V26_3);
        var target = new ChunkSectionType26_1(bits(data.blockStates().size()), modelBiomeBits);
        var mappings = mappings(version, ProtocolVersion.V26_3);
        ByteBuf output = Unpooled.buffer();
        try {
            for (int section = 0; section < count; section++) {
                var value = source.read(input);
                var palette = value.palette(PaletteType.BLOCKS);
                for (int index = 0; index < palette.size(); index++)
                    palette.setIdByIndex(index, mappings.blockState(palette.idByIndex(index)));
                var biomes = value.palette(PaletteType.BIOMES);
                for (int index = 0; index < biomes.size(); index++)
                    biomes.setIdByIndex(index, biomeIds.applyAsInt(biomes.idByIndex(index)));
                target.write(output, value);
            }
            if (input.isReadable()) throw new MalformedPacketException("Trailing chunk section bytes");
            return ByteBufUtil.getBytes(output);
        } finally {
            output.release();
        }
    }

    private static int bits(int count) {
        return 32 - Integer.numberOfLeadingZeros(count - 1);
    }

    @Override
    public RegistryValues registry(ByteBuf input) {
        checkOpen();
        String registry = Types.STRING.read(input);
        var entries = new ArrayList<RegistryEntry>();
        for (var value : Types.REGISTRY_ENTRY_ARRAY.read(input)) {
            byte[] data = null;
            if (value.tag() != null) {
                ByteBuf output = Unpooled.buffer();
                try {
                    Types.TAG.write(output, value.tag());
                    data = ByteBufUtil.getBytes(output);
                } finally {
                    output.release();
                }
            }
            entries.add(new RegistryEntry(value.key(), data));
        }
        return new RegistryValues(registry, entries);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            manager.destroy();
        }
    }
}
