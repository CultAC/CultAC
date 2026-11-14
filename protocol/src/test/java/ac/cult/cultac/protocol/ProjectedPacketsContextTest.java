package ac.cult.cultac.protocol;

import static ac.cult.cultac.protocol.ConnectionPhase.CONFIGURATION;
import static ac.cult.cultac.protocol.PacketDirection.CLIENTBOUND;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.Packets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Translated reads and writes hand the adapter the context, and write the bytes, that a fresh context gives. */
class ProjectedPacketsContextTest {
    record ModelValue(int id) implements ClientboundPacket {}

    private static final PacketType<ModelValue> REGISTRY =
            writable("clientbound.registry_data", "minecraft:registry_data");
    // A second family under the same wire name, so both writes share one wire ID.
    private static final PacketType<ModelValue> RENAMED =
            writable("clientbound.registry_copy", "minecraft:registry_data");
    private static final PacketType<ModelValue> ABSENT = writable("clientbound.absent", "minecraft:absent_packet");
    private static final ProtocolRuntime MODEL = model();

    private static PacketType<ModelValue> writable(String key, String name) {
        return new PacketType<>(
                key,
                ModelValue.class,
                CLIENTBOUND,
                Set.of(CONFIGURATION),
                List.of(name),
                ProtocolVersion.V26_3,
                new WritablePacketCodec<>() {
                    public boolean requiresModelValues() {
                        return true;
                    }

                    public ModelValue read(ByteBuf input, ProtocolContext context) {
                        return new ModelValue(Wire.readVarInt(input));
                    }

                    public void write(ByteBuf output, ProtocolContext context, ModelValue value) {
                        Wire.writeVarInt(output, value.id());
                    }
                });
    }

    private static ProtocolRuntime model() {
        var catalog = new ArrayList<>(Packets.all());
        catalog.add(REGISTRY);
        return ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3), catalog);
    }

    private static final class Recorder implements PacketValueAdapter {
        final List<ProtocolContext> contexts = new ArrayList<>();
        final List<CodecState> states;

        Recorder(List<CodecState> states) {
            this.states = states;
        }

        @Override
        public Object read(ByteBuf input, ProtocolContext context) {
            contexts.add(context);
            input.skipBytes(input.readableBytes());
            return defaultRecord(context.type().recordClass());
        }

        @Override
        public Object readForObservation(ByteBuf input, ProtocolContext context) {
            return read(input, context);
        }

        @Override
        public void write(ByteBuf output, ProtocolContext context, Object packet) {
            contexts.add(context);
            output.writeCharSequence(context.wireName(), StandardCharsets.UTF_8);
            output.writeByte(context.phase().ordinal());
            output.writeByte(states.indexOf(context.state()));
            output.writeInt(context.commandInputLimit());
            output.writeInt(context.variant());
            output.writeCharSequence(context.type().toString(), StandardCharsets.UTF_8);
            Wire.writeVarInt(output, ((ModelValue) packet).id());
        }
    }

    private static CodecState state() {
        return new CodecState() {
            public <T> T require(Class<T> type) {
                return CodecState.EMPTY.require(type);
            }
        };
    }

    @Test
    void contextsMatchFreshOnesAcrossStatesPhasesAndFamilies() {
        var states = List.of(CodecState.EMPTY, state(), state());
        var recorder = new Recorder(states);
        var wire = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V1_21_11));
        int reads = 0, writes = 0;
        try (var packets = new ProjectedPackets(wire, MODEL, () -> recorder)) {
            for (int round = 0; round < 3; round++) {
                for (CodecState state : List.of(states.get(1), states.get(0), states.get(2), states.get(1))) {
                    for (var phase : ConnectionPhase.values()) {
                        for (var direction : PacketDirection.values()) {
                            int ids = wire.data().packets(phase, direction).size();
                            for (int id = 0; id < ids; id++) {
                                var type = packets.type(phase, direction, id);
                                int before = recorder.contexts.size();
                                ByteBuf input = Unpooled.buffer();
                                Wire.writeVarInt(input, id);
                                input.writeZero(16);
                                try {
                                    if ((id & 1) == 0) packets.readOne(phase, direction, input, state, t -> true);
                                    else packets.observe(phase, direction, input, state, t -> true);
                                } catch (RuntimeException ignored) {
                                    // Native codecs reject zero payloads; only adapter contexts matter here.
                                } finally {
                                    input.release();
                                }
                                if (recorder.contexts.size() == before) continue;
                                reads++;
                                assertFresh(
                                        new ProtocolContext(
                                                type,
                                                wire.data(),
                                                phase,
                                                wire.data()
                                                        .packets(phase, direction)
                                                        .name(id),
                                                32767,
                                                0,
                                                state),
                                        recorder.contexts.getLast());
                            }
                        }
                    }
                    for (var type : List.of(REGISTRY, RENAMED, REGISTRY)) {
                        writes++;
                        assertEncodesAsFresh(packets, wire, recorder, type, state, round * 10 + writes);
                    }
                }
            }
        }
        assertTrue(reads >= 36, "translated reads " + reads);
        assertTrue(writes > 10);
    }

    @Test
    void failuresMatchFreshContexts() {
        var recorder = new Recorder(List.of(CodecState.EMPTY));
        var wire = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V1_21_11));
        try (var packets = new ProjectedPackets(wire, MODEL, () -> recorder)) {
            var absent = assertThrows(
                    UnsupportedOnVersionException.class,
                    () -> packets.encode(
                            CONFIGURATION,
                            ABSENT,
                            new ModelValue(1),
                            UnpooledByteBufAllocator.DEFAULT,
                            CodecState.EMPTY));
            assertEquals("Unavailable packet " + ABSENT, absent.getMessage());
            packets.encode(
                            CONFIGURATION,
                            REGISTRY,
                            new ModelValue(1),
                            UnpooledByteBufAllocator.DEFAULT,
                            CodecState.EMPTY)
                    .frames()
                    .forEach(ByteBuf::release);
            assertThrows(
                    NullPointerException.class,
                    () -> packets.encode(
                            CONFIGURATION, REGISTRY, new ModelValue(1), UnpooledByteBufAllocator.DEFAULT, null));
            int unknown = wire.data().packets(CONFIGURATION, CLIENTBOUND).size();
            ByteBuf input = Unpooled.buffer();
            Wire.writeVarInt(input, unknown);
            try {
                var expected = assertThrows(
                        MalformedPacketException.class,
                        () -> wire.data().packets(CONFIGURATION, CLIENTBOUND).name(unknown));
                var actual = assertThrows(
                        MalformedPacketException.class,
                        () -> packets.readOne(CONFIGURATION, CLIENTBOUND, input, CodecState.EMPTY, t -> true));
                assertEquals(expected.getMessage(), actual.getMessage());
            } finally {
                input.release();
            }
            int id = wire.data().packets(CONFIGURATION, CLIENTBOUND).id("minecraft:registry_data");
            ByteBuf registry = Unpooled.buffer();
            Wire.writeVarInt(registry, id);
            try {
                packets.readOne(CONFIGURATION, CLIENTBOUND, registry, CodecState.EMPTY, t -> true);
                assertThrows(
                        NullPointerException.class,
                        () -> packets.readOne(CONFIGURATION, CLIENTBOUND, registry, null, t -> true));
            } finally {
                registry.release();
            }
        }
    }

    private static void assertEncodesAsFresh(
            ProjectedPackets packets,
            ProtocolRuntime wire,
            Recorder recorder,
            PacketType<ModelValue> type,
            CodecState state,
            int value) {
        var encoded =
                packets.encode(CONFIGURATION, type, new ModelValue(value), UnpooledByteBufAllocator.DEFAULT, state);
        ByteBuf actual = encoded.frames().getFirst();
        String name = type.wireNames().stream()
                .filter(n ->
                        wire.data().packets(CONFIGURATION, type.direction()).id(n) >= 0)
                .findFirst()
                .orElseThrow();
        var fresh = new ProtocolContext(type, wire.data(), CONFIGURATION, name, 32767, 0, state);
        ByteBuf expected = Unpooled.buffer();
        try {
            assertFresh(fresh, recorder.contexts.getLast());
            Wire.writeVarInt(
                    expected,
                    wire.data().packets(CONFIGURATION, type.direction()).id(name));
            recorder.write(expected, fresh, new ModelValue(value));
            assertArrayEquals(ByteBufUtil.getBytes(expected), ByteBufUtil.getBytes(actual));
        } finally {
            expected.release();
            encoded.frames().forEach(ByteBuf::release);
        }
    }

    private static void assertFresh(ProtocolContext expected, ProtocolContext actual) {
        assertEquals(expected, actual);
        assertSame(expected.type(), actual.type());
        assertSame(expected.data(), actual.data());
        assertSame(expected.phase(), actual.phase());
        assertSame(expected.wireName(), actual.wireName());
        assertSame(expected.state(), actual.state());
        assertEquals(expected.commandInputLimit(), actual.commandInputLimit());
        assertEquals(expected.variant(), actual.variant());
    }

    /** A record of {@code type} built from zero, false and null components. */
    private static Object defaultRecord(Class<?> type) {
        var components = type.getRecordComponents();
        var types = new Class<?>[components.length];
        var arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            arguments[i] = types[i].isPrimitive() ? Array.get(Array.newInstance(types[i], 1), 0) : null;
        }
        try {
            var constructor = type.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return constructor.newInstance(arguments);
        } catch (ReflectiveOperationException e) {
            throw new UnsupportedOperationException(type.getName(), e);
        }
    }
}
