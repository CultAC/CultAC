package ac.cult.cultac.codec;

import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.*;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.shaded.vialib.ViaManagerImpl;
import ac.cult.shaded.vialib.api.data.MappingData;
import ac.cult.shaded.vialib.api.minecraft.RegistryType;
import ac.cult.shaded.vialib.api.minecraft.item.Item;
import ac.cult.shaded.vialib.api.protocol.ProtocolPathEntry;
import ac.cult.shaded.vialib.api.type.Type;
import ac.cult.shaded.vialib.api.type.Types;
import ac.cult.shaded.vialib.api.type.types.version.*;
import ac.cult.shaded.vialib.protocol.packet.PacketWrapperImpl;
import ac.cult.shaded.vialib.rewriter.TagRewriter;
import ac.cult.shaded.vialib.util.Key;
import io.netty.buffer.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** One-time type initialization, then concurrent reads without private player connections. */
public final class PrivateCodecService implements WireValueDecoder {
    private final ViaManagerImpl manager;

    private record Versions(ProtocolVersion source, ProtocolVersion target) {}

    private final Map<Versions, ModelIdMappings> mappings = new ConcurrentHashMap<>();
    private final Map<ProtocolVersion, PlayerTextCodec> playerText = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public PrivateCodecService() {
        manager = ModernProtocols.open();
    }

    /** Via's cached protocol path, ordered from backend toward client for clientbound values. */
    private List<ProtocolPathEntry> path(ProtocolVersion source, ProtocolVersion target) {
        if (source == target) return List.of();
        var path = manager.getProtocolManager()
                .getProtocolPath(
                        ac.cult.shaded.vialib.api.protocol.version.ProtocolVersion.getProtocol(target.protocol()),
                        ac.cult.shaded.vialib.api.protocol.version.ProtocolVersion.getProtocol(source.protocol()));
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
        } catch (ac.cult.shaded.vialib.exception.InformativeException failure) {
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
    public boolean canWriteComponent(String component) {
        checkOpen();
        return ComponentWrites.supports(component);
    }

    @Override
    public void writeComponent(
            ProtocolVersion version, ByteBuf output, String component, byte[] value, Registries registries) {
        checkOpen();
        int id = ModelRegistryData.load(version)
                .registry("minecraft:data_component_type")
                .id(component);
        var key = id < 0 ? null : types(version).structuredData().key(id);
        if (key == null) throw new ProtocolResolutionException("Unknown component in " + version + ": " + component);
        var input = Unpooled.wrappedBuffer(value);
        try {
            var tag = Types.TAG.read(input);
            if (input.isReadable()) throw new ProtocolResolutionException("Unread component NBT: " + component);
            ComponentWrites.write(output, key, tag, version, registries);
        } finally {
            input.release();
        }
    }

    private record ItemInput(Item item, byte[] patch) {}

    @Override
    public ItemValue item(ProtocolVersion version, ByteBuf input, boolean creative, Registries registries) {
        var decoded = readItem(version, input, creative);
        return item(decoded.item(), registries, version, decoded.patch());
    }

    @Override
    public <T> DecodedItem<T> item(
            ProtocolVersion version, ByteBuf input, boolean creative, Registries registries, NbtFactory<T> factory) {
        var decoded = readItem(version, input, creative);
        return item(decoded.item(), registries, version, decoded.patch(), factory);
    }

    private ItemInput readItem(ProtocolVersion version, ByteBuf input, boolean creative) {
        checkOpen();
        var type = types(version);
        boolean lengthPrefixed = creative && version.atLeast(ProtocolVersion.V1_21_5);
        // The structure budget runs on a view: an oversized item must never reach the readers below.
        ItemStructureGuard.scanItemStack(type, version, input.duplicate(), lengthPrefixed);
        int start = input.readerIndex();
        // 1.21.5 introduced the length-prefixed creative component codec.
        Item item = (lengthPrefixed ? type.lengthPrefixedItem() : type.item()).read(input);
        // Keep the creative count domain used by the inventory view. The backend
        // validates persistent fields of unused components on the original frame.
        if (creative && item != null && !item.isEmpty() && item.amount() > 99)
            throw new MalformedPacketException("Creative item count exceeds 99");
        if (creative || item == null || item.isEmpty()) return new ItemInput(item, null);
        // Trusted stacks frame count and item ID separately from the component patch.
        // Slot corrections can change the count and resend this patch untouched.
        var header = input.duplicate();
        header.readerIndex(start);
        Types.VAR_INT.readPrimitive(header);
        Types.VAR_INT.readPrimitive(header);
        return new ItemInput(
                item, ByteBufUtil.getBytes(input, header.readerIndex(), input.readerIndex() - header.readerIndex()));
    }

    private ItemValue item(Item item, Registries registries, ProtocolVersion version) {
        return item(item, registries, version, null);
    }

    private ItemValue item(Item item, Registries registries, ProtocolVersion version, byte[] wirePatch) {
        if (item == null || item.isEmpty()) return new ItemValue(0, 0, null);
        var context = new ValueContext(registries, version, mappings(version, ProtocolVersion.V26_3));
        var patch = ComponentValues.patch(item, context);
        var layouts = ComponentValues.layouts(item, patch, context);
        ByteBuf output = Unpooled.buffer();
        try {
            Types.COMPOUND_TAG.write(output, patch);
            byte[] components = ByteBufUtil.getBytes(output);
            byte[] layoutBytes = null;
            if (!layouts.isEmpty()) {
                output.clear();
                Types.COMPOUND_TAG.write(output, layouts);
                layoutBytes = ByteBufUtil.getBytes(output);
            }
            // Via's component writers preserve fields that the persistent NBT codec omits.
            var encodings = new java.util.HashMap<String, ComponentEncoding>();
            for (var data : item.dataContainer().data().values()) {
                if (data.isEmpty()) continue;
                encodings.put(
                        ac.cult.shaded.vialib.util.Key.namespaced(data.key().identifier()),
                        ComponentValues.wireEncoding(data, context));
            }
            return new ItemValue(
                    item.identifier(),
                    item.amount(),
                    components,
                    layoutBytes,
                    encodings,
                    wirePatch == null
                            ? null
                            : new ComponentEncoding(version.protocol(), wirePatch, context.references()));
        } finally {
            output.release();
        }
    }

    private <T> DecodedItem<T> item(
            Item item, Registries registries, ProtocolVersion version, byte[] wirePatch, NbtFactory<T> factory) {
        if (item == null || item.isEmpty()) return new DecodedItem<>(0, 0, null, null, Map.of(), null);
        var context = new ValueContext(registries, version, mappings(version, ProtocolVersion.V26_3));
        var patch = ComponentValues.patch(item, context);
        var layouts = ComponentValues.layouts(item, patch, context);
        var encodings = new HashMap<String, ComponentEncoding>();
        for (var data : item.dataContainer().data().values()) {
            if (!data.isEmpty())
                encodings.put(Key.namespaced(data.key().identifier()), ComponentValues.wireEncoding(data, context));
        }
        return new DecodedItem<>(
                item.identifier(),
                item.amount(),
                PortableNbt.read(patch, factory),
                layouts.isEmpty() ? null : PortableNbt.read(layouts, factory),
                encodings,
                wirePatch == null ? null : new ComponentEncoding(version.protocol(), wirePatch, context.references()));
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
        ItemStructureGuard.scanItemCost(types, version, input.duplicate(), optional);
        return item((optional ? types.optionalItemCost() : types.itemCost()).read(input), registries, version);
    }

    @Override
    public <T> DecodedItem<T> itemCost(
            ProtocolVersion version, ByteBuf input, boolean optional, Registries registries, NbtFactory<T> factory) {
        checkOpen();
        var types = types(version);
        ItemStructureGuard.scanItemCost(types, version, input.duplicate(), optional);
        return item(
                (optional ? types.optionalItemCost() : types.itemCost()).read(input),
                registries,
                version,
                null,
                factory);
    }

    record MetadataTypes(int floatingPoint, int pose, int optionalInt, int painting, int particle, int particles) {}

    static MetadataTypes metadataTypes(VersionedTypesHolder types) {
        return switch (types.entityDataTypes()) {
            case ac.cult.shaded.vialib.api.minecraft.entitydata.types.EntityDataTypes1_21_2 values ->
                new MetadataTypes(
                        values.floatType.typeId(),
                        values.poseType.typeId(),
                        values.optionalVarIntType.typeId(),
                        values.paintingVariantType.typeId(),
                        values.particleType.typeId(),
                        values.particlesType.typeId());
            case ac.cult.shaded.vialib.api.minecraft.entitydata.types.EntityDataTypes1_21_5 values ->
                new MetadataTypes(
                        values.floatType.typeId(),
                        values.poseType.typeId(),
                        values.optionalVarIntType.typeId(),
                        values.paintingVariantType.typeId(),
                        values.particleType.typeId(),
                        values.particlesType.typeId());
            case ac.cult.shaded.vialib.api.minecraft.entitydata.types.EntityDataTypes1_21_9 values ->
                new MetadataTypes(
                        values.floatType.typeId(),
                        values.poseType.typeId(),
                        values.optionalVarIntType.typeId(),
                        values.paintingVariantType.typeId(),
                        values.particleType.typeId(),
                        values.particlesType.typeId());
            case ac.cult.shaded.vialib.api.minecraft.entitydata.types.EntityDataTypes1_21_11 values ->
                new MetadataTypes(
                        values.floatType.typeId(),
                        values.poseType.typeId(),
                        values.optionalVarIntType.typeId(),
                        values.paintingVariantType.typeId(),
                        values.particleType.typeId(),
                        values.particlesType.typeId());
            case ac.cult.shaded.vialib.api.minecraft.entitydata.types.EntityDataTypes26_1 values ->
                new MetadataTypes(
                        values.floatType.typeId(),
                        values.poseType.typeId(),
                        values.optionalVarIntType.typeId(),
                        values.paintingVariantType.typeId(),
                        values.particleType.typeId(),
                        values.particlesType.typeId());
            case ac.cult.shaded.vialib.api.minecraft.entitydata.types.EntityDataTypes26_3 values ->
                new MetadataTypes(
                        values.floatType.typeId(),
                        values.poseType.typeId(),
                        values.optionalVarIntType.typeId(),
                        values.paintingVariantType.typeId(),
                        values.particleType.typeId(),
                        values.particlesType.typeId());
            default -> throw new IllegalStateException("Unsupported entity metadata schema");
        };
    }

    @Override
    public int floatSerializer(ProtocolVersion version) {
        return metadataTypes(types(version)).floatingPoint();
    }

    @Override
    public List<MetadataValue> metadata(ProtocolVersion version, ByteBuf input, Registries registries) {
        return metadata(version, input, registries, null);
    }

    @Override
    public <T> List<MetadataValue> metadata(
            ProtocolVersion version, ByteBuf input, Registries registries, NbtFactory<T> factory) {
        checkOpen();
        var types = types(version);
        var serializers = metadataTypes(types);
        int pose = serializers.pose(), optional = serializers.optionalInt();
        int painting = serializers.painting();
        int particle = serializers.particle(), particles = serializers.particles();
        var result = new ArrayList<MetadataValue>();
        for (int index; (index = input.readUnsignedByte()) != 255; ) {
            int start = input.readerIndex() - 1;
            int id = Types.VAR_INT.readPrimitive(input);
            var values = types.entityDataTypes().values();
            if (id < 0 || id >= values.length) throw new MalformedPacketException("Unknown metadata serializer " + id);
            String kind = "unused";
            if (id == 0 || id == 1 || id == 3 || id == 8) kind = "value";
            else if (id == pose) kind = "pose";
            else if (id == optional) kind = "optional_int";
            else if (id == 7) kind = "item";
            else if (id == 12) kind = "direction";
            else if (id == painting) kind = "painting_variant";
            else if (id == 11) kind = "optional_position";
            else if (id == particle) kind = "particle";
            else if (id == particles) kind = "particles";
            Object value = null;
            if (kind.equals("unused")) {
                skipUnusedMetadata(input, values[id].type());
            } else if (kind.equals("particle")) {
                skipParticle(version, types, input);
            } else if (kind.equals("particles")) {
                skipParticles(version, types, input);
            } else if (kind.equals("painting_variant")) {
                // Via's PaintingVariant.TYPE1_21_2 framing is shared by all supported versions.
                int reference = Types.VAR_INT.readPrimitive(input) - 1;
                if (reference != -1) value = registries.name("minecraft:painting_variant", reference);
                else {
                    value = new int[] {Types.VAR_INT.readPrimitive(input), Types.VAR_INT.readPrimitive(input)};
                    skipUnusedMetadata(input, Types.STRING); // Asset ID does not affect the bounds.
                    skipUnusedMetadata(input, Types.TRUSTED_OPTIONAL_TAG); // Title.
                    skipUnusedMetadata(input, Types.TRUSTED_OPTIONAL_TAG); // Author.
                }
            } else {
                if (kind.equals("item")) ItemStructureGuard.scanItemStack(types, version, input.duplicate(), false);
                value = values[id].type().read(input);
                if (kind.equals("item"))
                    value = factory == null
                            ? item((Item) value, registries, version)
                            : item((Item) value, registries, version, null, factory);
                else if (kind.equals("optional_position") && value != null) {
                    var position = (ac.cult.shaded.vialib.api.minecraft.BlockPosition) value;
                    value = new int[] {position.x(), position.y(), position.z()};
                }
            }
            result.add(new MetadataValue(
                    index, kind, value, ByteBufUtil.getBytes(input, start, input.readerIndex() - start)));
        }
        return List.copyOf(result);
    }

    /** Metadata batches may hold this many particles; vanilla sends a handful. */
    private static final int MAX_METADATA_PARTICLES = 1024;

    private static final Map<ProtocolVersion, Integer> ITEM_PARTICLES = new ConcurrentHashMap<>();

    static int itemParticle(ProtocolVersion version) {
        return ITEM_PARTICLES.computeIfAbsent(
                version,
                value -> ModelRegistryData.load(value)
                        .registry("minecraft:particle_type")
                        .id("minecraft:item"));
    }

    /**
     * The item particle embeds a full stack, so it is consumed by the structure budget instead
     * of the particle reader. Every other particle consumes bounded arguments through that reader.
     */
    private static void skipParticle(ProtocolVersion version, VersionedTypesHolder types, ByteBuf input) {
        var probe = input.duplicate();
        if (Wire.readVarInt(probe) == itemParticle(version)) {
            Wire.readVarInt(input); // Consume the type the probe matched.
            ItemStructureGuard.scanItemStack(types, version, input, false);
        } else {
            types.particle().read(input);
        }
    }

    private static void skipParticles(ProtocolVersion version, VersionedTypesHolder types, ByteBuf input) {
        int count = Wire.readVarInt(input);
        if (count < 0) throw new MalformedPacketException("Negative metadata particle count");
        if (count > MAX_METADATA_PARTICLES)
            throw new PacketResourceLimitException(
                    "Metadata particle count " + count + " exceeds " + MAX_METADATA_PARTICLES);
        for (int index = 0; index < count; index++) skipParticle(version, types, input);
    }

    /** Only traverse unused fields; retain their bytes for the health/hand metadata rewrites. */
    private static void skipUnusedMetadata(ByteBuf input, Type<?> type) {
        if (type == Types.VAR_INT) Types.VAR_INT.readPrimitive(input);
        else if (type == Types.VAR_LONG) Types.VAR_LONG.readPrimitive(input);
        else if (type == Types.STRING) input.skipBytes(Wire.readLength(input, Wire.MAX_STRING_LENGTH * 3));
        else if (type == Types.ROTATIONS || type == Types.VECTOR3F) input.skipBytes(12);
        else if (type == Types.QUATERNION) input.skipBytes(16);
        else if (type == Types.BLOCK_POSITION1_14) input.skipBytes(8);
        else if (type == Types.OPTIONAL_UUID) {
            if (input.readBoolean()) input.skipBytes(16);
        } else if (type == Types.OPTIONAL_GLOBAL_POSITION) {
            if (input.readBoolean()) {
                skipUnusedMetadata(input, Types.STRING); // Dimension identifier.
                input.skipBytes(8);
            }
        } else if (type == Types.VILLAGER_DATA) {
            Types.VAR_INT.readPrimitive(input);
            Types.VAR_INT.readPrimitive(input);
            Types.VAR_INT.readPrimitive(input);
        } else if (type == Types.TRUSTED_TAG || type == Types.COMPOUND_TAG)
            ac.cult.cultac.protocol.wire.NbtSkipper.skip(input, 512);
        else if (type == Types.TRUSTED_OPTIONAL_TAG) {
            if (input.readBoolean()) ac.cult.cultac.protocol.wire.NbtSkipper.skip(input, 512);
        } else type.read(input); // Via supplies boundaries for particle arguments and other complex values.
    }

    @Override
    public PlayerTextCodec playerText(ProtocolVersion version) {
        checkOpen();
        return playerText.computeIfAbsent(version, PlayerTextValues::new);
    }

    @Override
    public RegistryValues registry(ByteBuf input) {
        checkOpen();
        return ac.cult.cultac.protocol.wire.RegistryValueCodec.read(input);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            // ModernProtocols caches only read-only mappings/types for the plugin lifetime.
            // Its workers are stopped after initialization; each facade owns its own cache.
            mappings.clear();
        }
    }
}
