package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.ClientClocks;
import ac.cult.blocksim.environment.ClientWorldDefaults;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder.RegistryEntry;
import ac.cult.cultac.utils.latency.ClientWorldRegistries;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReceivedWorldRegistriesTest {
    private static ClientWorldRegistries.Data defaults() {
        var definitions = new HashMap<String, List<ClientWorldRegistries.Definition>>();
        var tags = new HashMap<String, Map<String, List<String>>>();
        for (String registry : ClientWorldRegistries.REGISTRIES) {
            var values = ClientWorldDefaults.defaults();
            definitions.put(
                    registry,
                    values.entries(registry).entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(entry -> new ClientWorldRegistries.Definition(entry.getKey(), entry.getValue()))
                            .toList());
            tags.put(registry, values.tags(registry));
        }
        return new ClientWorldRegistries.Data(definitions, tags);
    }

    private static Map<String, List<RegistryEntry>> knownPacks(ClientWorldRegistries.Data data) {
        var entries = new HashMap<String, List<RegistryEntry>>();
        data.registries()
                .forEach((registry, values) -> entries.put(
                        registry,
                        values.stream()
                                .map(value -> new RegistryEntry(value.key(), null))
                                .toList()));
        return entries;
    }

    private static int id(ClientWorldRegistries.Data data, String registry, String name) {
        var entries = data.registries().get(registry);
        return java.util.stream.IntStream.range(0, entries.size())
                .filter(index -> entries.get(index).key().equals(name))
                .findFirst()
                .orElseThrow();
    }

    // Fixture framing only; production registry entries retain their original wire bytes.
    private static byte[] wire(NbtValue value) {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            var output = new java.io.DataOutputStream(bytes);
            output.writeByte(type(value));
            write(output, value);
            return bytes.toByteArray();
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static int type(NbtValue value) {
        if (value instanceof NbtValue.Numeric number) return number.kind().ordinal() + 1;
        if (value instanceof NbtValue.Text) return 8;
        if (value instanceof NbtValue.Sequence) return 9;
        if (value instanceof NbtValue.Compound) return 10;
        return switch (((NbtValue.PrimitiveArray) value).kind()) {
            case BYTE_ARRAY -> 7;
            case INT_ARRAY -> 11;
            case LONG_ARRAY -> 12;
            default -> throw new AssertionError();
        };
    }

    private static void write(java.io.DataOutputStream output, NbtValue value) throws java.io.IOException {
        if (value instanceof NbtValue.Numeric number) {
            switch (number.kind()) {
                case BYTE -> output.writeByte(number.value().byteValue());
                case SHORT -> output.writeShort(number.value().shortValue());
                case INT -> output.writeInt(number.value().intValue());
                case LONG -> output.writeLong(number.value().longValue());
                case FLOAT -> output.writeFloat(number.value().floatValue());
                case DOUBLE -> output.writeDouble(number.value().doubleValue());
                default -> throw new AssertionError();
            }
        } else if (value instanceof NbtValue.Text text) output.writeUTF(text.value());
        else if (value instanceof NbtValue.Compound compound) {
            for (var entry : compound.values().entrySet()) {
                output.writeByte(type(entry.getValue()));
                output.writeUTF(entry.getKey());
                write(output, entry.getValue());
            }
            output.writeByte(0);
        } else if (value instanceof NbtValue.Sequence list) {
            output.writeByte(list.values().isEmpty() ? 0 : type(list.values().getFirst()));
            output.writeInt(list.values().size());
            for (var element : list.values()) write(output, element);
        } else {
            var array = (NbtValue.PrimitiveArray) value;
            output.writeInt(array.values().size());
            for (long number : array.values())
                switch (array.kind()) {
                    case BYTE_ARRAY -> output.writeByte((byte) number);
                    case INT_ARRAY -> output.writeInt((int) number);
                    case LONG_ARRAY -> output.writeLong(number);
                    default -> throw new AssertionError();
                }
        }
    }

    @Test
    void sharedInitialFactsRemainIsolatedWhenOneConnectionReloadsTimelineTags() {
        var model = ClientWorldRegistries.modelDefaults();
        var first = new ClientWorldRegistries(model);
        var second = new ClientWorldRegistries(model);
        var clocks = new ClientClocks();
        clocks.handleUpdates(1, Map.of("minecraft:overworld", new ClientClocks.State(18000, 0, 0)));
        int plains = id(model, ClientWorldRegistries.BIOMES, "minecraft:plains");
        first.applyTags(
                ClientWorldRegistries.TIMELINES, new RegistryTags.Payload(Map.of("minecraft:in_overworld", List.of())));
        assertFalse(first.dimension("minecraft:overworld")
                .environment()
                .get()
                .create(clocks)
                .at(plains)
                .creakingActive());
        assertTrue(second.dimension("minecraft:overworld")
                .environment()
                .get()
                .create(clocks)
                .at(plains)
                .creakingActive());
        assertFalse(model.tags()
                .get(ClientWorldRegistries.TIMELINES)
                .get("minecraft:in_overworld")
                .isEmpty());
        assertSame(model, ClientWorldRegistries.modelDefaults());
    }

    @Test
    void receivedIdsAndKnownPackReferencesRemainIndependentOfDefaultOrdering() {
        var initial = defaults();
        var worlds = new ClientWorldRegistries(initial);
        var entries = knownPacks(initial);
        entries.put(
                ClientWorldRegistries.DIMENSIONS,
                List.of(
                        new RegistryEntry("minecraft:the_nether", null),
                        new RegistryEntry("minecraft:overworld", null)));
        worlds.finish(entries, ProtocolVersion.V26_3, Map.of(), true);
        assertEquals("minecraft:the_nether", worlds.dimension(0).dimension().key());
        assertTrue(worlds.dimension(0).dimension().hasFastLava());
        assertEquals(-64, worlds.dimension(1).dimension().minY());
        var published = worlds.snapshot().registries();
        worlds.finish(entries, ProtocolVersion.V26_3, Map.of(), false);
        assertSame(published, worlds.snapshot().registries());
        assertThrows(UnsupportedOperationException.class, () -> published.clear());
    }

    @Test
    void pendingRespawnReadsCurrentBiomesAndItsOriginalTimelineGeneration() {
        var initial = defaults();
        var worlds = new ClientWorldRegistries(initial);
        var clocks = new ClientClocks();
        clocks.handleUpdates(1, Map.of("minecraft:overworld", new ClientClocks.State(18000, 0, 0)));
        int plains = id(initial, ClientWorldRegistries.BIOMES, "minecraft:plains");
        var pending = worlds.dimension("minecraft:overworld");
        assertTrue(pending.environment().get().create(clocks).at(plains).creakingActive());
        worlds.applyTags(
                ClientWorldRegistries.TIMELINES, new RegistryTags.Payload(Map.of("minecraft:in_overworld", List.of())));
        assertFalse(pending.environment().get().create(clocks).at(plains).creakingActive());

        var received = knownPacks(initial);
        var biome = ClientWorldDefaults.defaults().resolve(ClientWorldRegistries.BIOMES, "minecraft:plains");
        var fields = new HashMap<>(biome.data().values());
        fields.put(
                "attributes",
                new NbtValue.Compound(Map.of(
                        "minecraft:gameplay/water_evaporates", new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte) 1))));
        received.put(
                ClientWorldRegistries.BIOMES,
                List.of(new RegistryEntry("test:wet_biome", wire(new NbtValue.Compound(fields)))));
        var members = initial.tags().get(ClientWorldRegistries.TIMELINES).get("minecraft:in_overworld").stream()
                .map(name -> id(initial, ClientWorldRegistries.TIMELINES, name))
                .toList();
        worlds.finish(
                received,
                ProtocolVersion.V26_3,
                Map.of(
                        ClientWorldRegistries.TIMELINES,
                        new RegistryTags.Payload(Map.of("minecraft:in_overworld", members))),
                true);
        var oldEnvironment = pending.environment().get().create(clocks);
        assertTrue(oldEnvironment.at(0).waterEvaporates());
        assertFalse(oldEnvironment.at(0).creakingActive());
        assertTrue(worlds.dimension("minecraft:overworld")
                .environment()
                .get()
                .create(clocks)
                .at(0)
                .creakingActive());
    }

    @Test
    void unknownDimensionFieldsRemainOpaqueAndDoNotChangeTheModernFastLavaAttribute() {
        var initial = defaults();
        var received = knownPacks(initial);
        var fields = new HashMap<>(ClientWorldDefaults.defaults()
                .resolve(ClientWorldRegistries.DIMENSIONS, "minecraft:overworld")
                .data()
                .values());
        fields.put("ultrawarm", new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte) 1));
        fields.put(
                "plugin_payload",
                new NbtValue.PrimitiveArray(NbtValue.Kind.LONG_ARRAY, List.of(Long.MIN_VALUE, 1L << 54)));
        var raw = new NbtValue.Compound(fields);
        received.put(ClientWorldRegistries.DIMENSIONS, List.of(new RegistryEntry("test:custom_dimension", wire(raw))));
        var worlds = new ClientWorldRegistries(initial);
        worlds.finish(received, ProtocolVersion.V26_3, Map.of(), true);
        assertEquals(raw, worlds.dimension(0).dimension().data());
        assertFalse(worlds.dimension(0).dimension().hasFastLava());
    }
}
