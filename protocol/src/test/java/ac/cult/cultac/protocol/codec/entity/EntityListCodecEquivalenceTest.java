package ac.cult.cultac.protocol.codec.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * The move, position sync and attribute codecs against their list-building form before this change, on generated
 * packets and on every truncation of them: equal packets with the same list classes at every level, or the same
 * exception.
 */
class EntityListCodecEquivalenceTest {
    private static final ProtocolVersion[] VERSIONS = {
        ProtocolVersion.V1_21_11, ProtocolVersion.V26_2, ProtocolVersion.V26_3
    };

    @Test
    void moveEntityReadsMatchTheListBuildingForm() {
        Random random = new Random(41);
        int[] stepCounts = new int[4];
        for (ProtocolVersion version : VERSIONS) {
            ProtocolData data = ProtocolData.load(version);
            for (int variant = 0; variant < 3; variant++) {
                ProtocolContext context = context(ClientboundPackets.MOVE_ENTITY, data, variant);
                for (int n = 0; n < 400; n++) {
                    ByteBuf bytes = Unpooled.buffer();
                    Wire.writeVarInt(bytes, random.nextInt(1 << 20));
                    boolean position = variant != 2;
                    boolean rotation = variant != 0;
                    if (version.atLeast(ProtocolVersion.V26_3)) {
                        if (position) {
                            int steps = random.nextInt(4) == 0 ? 0 : random.nextInt(5);
                            stepCounts[Math.min(steps, 3)]++;
                            Wire.writeVarInt(bytes, steps << 1 | random.nextInt(2));
                            if (steps == 0) shorts(bytes, random);
                            for (int i = 0; i < steps; i++) {
                                Wire.writeVarInt(bytes, 1 + random.nextInt(3));
                                shorts(bytes, random);
                            }
                        } else bytes.writeBoolean(random.nextBoolean());
                    } else if (position) shorts(bytes, random);
                    if (rotation) bytes.writeByte(random.nextInt(256)).writeByte(random.nextInt(256));
                    if (!version.atLeast(ProtocolVersion.V26_3)) bytes.writeBoolean(random.nextBoolean());
                    assertSameOutcome(
                            bytes, context, new MoveEntityCodec()::read, EntityListCodecEquivalenceTest::baseMove);
                }
            }
        }
        for (int count : stepCounts) assertTrue(count > 0, "every step count is generated");
    }

    @Test
    void positionSyncReadsMatchTheListBuildingForm() {
        Random random = new Random(43);
        for (ProtocolVersion version : VERSIONS) {
            ProtocolContext context = context(ClientboundPackets.ENTITY_POSITION_SYNC, ProtocolData.load(version), 0);
            for (int n = 0; n < 400; n++) {
                ByteBuf bytes = Unpooled.buffer();
                Wire.writeVarInt(bytes, random.nextInt(1 << 20));
                if (version.atLeast(ProtocolVersion.V26_3)) {
                    boolean stepped = random.nextInt(4) != 0;
                    Wire.writeVarInt(bytes, stepped ? 1 : random.nextInt(2) * 2);
                    if (stepped) {
                        int steps = random.nextInt(8) == 0 ? random.nextInt(2) - 1 : 1 + random.nextInt(4);
                        Wire.writeVarInt(bytes, steps);
                        for (int i = 0; i < steps; i++) {
                            Wire.writeVec3(bytes, vec(random));
                            Wire.writeVarInt(bytes, random.nextInt(4));
                        }
                    } else Wire.writeVec3(bytes, vec(random));
                } else {
                    Wire.writeVec3(bytes, vec(random));
                    bytes.writeZero(24);
                }
                bytes.writeFloat(random.nextFloat())
                        .writeFloat(random.nextFloat())
                        .writeBoolean(random.nextBoolean());
                assertSameOutcome(
                        bytes,
                        context,
                        new EntityPositionSyncCodec()::read,
                        EntityListCodecEquivalenceTest::basePositionSync);
            }
        }
    }

    @Test
    void attributeReadsMatchTheListBuildingForm() {
        Random random = new Random(47);
        for (ProtocolVersion version : VERSIONS) {
            ProtocolData data = ProtocolData.load(version);
            int attributeIds = data.registry("minecraft:attribute").size();
            ProtocolContext context = context(ClientboundPackets.UPDATE_ATTRIBUTES, data, 0);
            for (int n = 0; n < 400; n++) {
                ByteBuf bytes = Unpooled.buffer();
                Wire.writeVarInt(bytes, random.nextInt(1 << 20));
                int count = random.nextInt(5);
                Wire.writeVarInt(bytes, count);
                for (int i = 0; i < count; i++) {
                    Wire.writeVarInt(bytes, random.nextInt(attributeIds));
                    bytes.writeDouble(random.nextDouble());
                    int modifiers = random.nextInt(3) == 0 ? 1 + random.nextInt(3) : 0;
                    Wire.writeVarInt(bytes, modifiers);
                    for (int j = 0; j < modifiers; j++) {
                        Wire.writeString(bytes, "cult:m" + random.nextInt(100), 32767);
                        bytes.writeDouble(random.nextDouble());
                        Wire.writeVarInt(bytes, random.nextInt(4));
                    }
                }
                assertSameOutcome(
                        bytes,
                        context,
                        new UpdateAttributesCodec()::read,
                        EntityListCodecEquivalenceTest::baseAttributes);
            }
        }
    }

    static ProtocolContext context(PacketType<?> type, ProtocolData data, int variant) {
        return new ProtocolContext(
                type, data, ConnectionPhase.PLAY, type.wireNames().get(variant), 0, variant);
    }

    private static void shorts(ByteBuf bytes, Random random) {
        for (int i = 0; i < 3; i++) bytes.writeShort(random.nextInt(65536));
    }

    private static Vec3d vec(Random random) {
        return new Vec3d(random.nextDouble() * 64 - 32, random.nextDouble() * 64, random.nextDouble() * 64 - 32);
    }

    /** The whole packet and every truncation of it. */
    private static void assertSameOutcome(
            ByteBuf bytes,
            ProtocolContext context,
            BiFunction<ByteBuf, ProtocolContext, Object> read,
            BiFunction<ByteBuf, ProtocolContext, Object> base) {
        try {
            for (int length = bytes.writerIndex(); length >= 0; length--) {
                String expected = outcome(bytes.retainedSlice(0, length), context, base);
                String actual = outcome(bytes.retainedSlice(0, length), context, read);
                assertEquals(expected, actual, "length " + length + " of " + bytes.writerIndex());
            }
        } finally {
            bytes.release();
        }
    }

    private static String outcome(
            ByteBuf input, ProtocolContext context, BiFunction<ByteBuf, ProtocolContext, Object> read) {
        try {
            Object packet = read.apply(input, context);
            return shape(packet) + " " + packet + " left " + input.readableBytes();
        } catch (RuntimeException e) {
            return e.getClass().getName() + ": " + e.getMessage() + " at " + input.readerIndex();
        } finally {
            input.release();
        }
    }

    /** The list classes inside a packet. */
    private static String shape(Object value) {
        if (value instanceof List<?> list) {
            var shape = new StringBuilder(list.getClass().getName()).append('[');
            for (Object element : list) shape.append(shape(element)).append(',');
            return shape.append(']').toString();
        }
        return switch (value) {
            case ClientboundMoveEntity move -> shape(move.delta());
            case EntityDelta.Stepped stepped -> shape(stepped.steps());
            case ClientboundEntityPositionSync sync -> shape(sync.position());
            case PositionPath.Stepped stepped -> shape(stepped.steps());
            case ClientboundUpdateAttributes attributes -> shape(attributes.attributes());
            case AttributeSnapshot snapshot -> shape(snapshot.modifiers());
            default -> value.getClass().getSimpleName();
        };
    }

    static Object baseMove(ByteBuf input, ProtocolContext context) {
        int entityId = Wire.readVarInt(input);
        boolean position = context.variant() != 2;
        boolean rotation = context.variant() != 0;
        EntityDelta delta = EntityDelta.ZERO;
        boolean ground = false;
        boolean modern = context.version().atLeast(ProtocolVersion.V26_3);
        if (modern) {
            if (position) {
                int properties = Wire.readVarInt(input);
                ground = (properties & 1) != 0;
                delta = baseDelta(input, properties >>> 1);
            } else ground = input.readBoolean();
        } else if (position) delta = baseLinear(input);
        float yaw = rotation ? Wire.readAngle(input) : 0;
        float pitch = rotation ? Wire.readAngle(input) : 0;
        if (!modern) ground = input.readBoolean();
        return new ClientboundMoveEntity(entityId, delta, yaw, pitch, ground, position, rotation);
    }

    private static EntityDelta baseDelta(ByteBuf input, int steps) {
        if (steps == 0) return baseLinear(input);
        if (steps > input.readableBytes() / 7)
            throw new MalformedPacketException("Entity delta step count exceeds remaining bytes: " + steps);
        var result = new ArrayList<EntityDelta.Step>(steps);
        for (int i = 0; i < steps; i++) {
            int ticks = Wire.readVarInt(input);
            result.add(new EntityDelta.Step(input.readShort(), input.readShort(), input.readShort(), ticks));
        }
        return new EntityDelta.Stepped(result);
    }

    private static EntityDelta.Linear baseLinear(ByteBuf input) {
        return new EntityDelta.Linear(input.readShort(), input.readShort(), input.readShort());
    }

    static Object basePositionSync(ByteBuf input, ProtocolContext context) {
        int id = Wire.readVarInt(input);
        PositionPath position;
        if (context.version().atLeast(ProtocolVersion.V26_3)) position = basePath(input);
        else {
            position = new PositionPath.Linear(Wire.readVec3(input));
            input.skipBytes(24);
        }
        return new ClientboundEntityPositionSync(
                id, position, input.readFloat(), input.readFloat(), input.readBoolean());
    }

    private static PositionPath basePath(ByteBuf input) {
        if (Wire.readVarInt(input) != 1) return new PositionPath.Linear(Wire.readVec3(input));
        int count = Wire.readVarInt(input);
        if (count <= 0 || count > input.readableBytes() / 25)
            throw new MalformedPacketException("Invalid position step count " + count);
        var steps = new ArrayList<PositionPath.Step>(count);
        for (int i = 0; i < count; i++) steps.add(new PositionPath.Step(Wire.readVec3(input), Wire.readVarInt(input)));
        return new PositionPath.Stepped(steps);
    }

    static Object baseAttributes(ByteBuf input, ProtocolContext context) {
        int entityId = Wire.readVarInt(input);
        int count = baseCount(input, context.version().atLeast(ProtocolVersion.V26_2) ? 128 : Integer.MAX_VALUE);
        var attributes = new ArrayList<AttributeSnapshot>(count);
        for (int i = 0; i < count; i++) {
            String attribute = context.data().registry("minecraft:attribute").name(Wire.readVarInt(input));
            double base = input.readDouble();
            int modifiersCount = baseCount(input, Integer.MAX_VALUE);
            var modifiers = new ArrayList<AttributeModifier>(modifiersCount);
            for (int j = 0; j < modifiersCount; j++) {
                String id = Wire.readIdentifier(input);
                double amount = input.readDouble();
                var operation = switch (Wire.readVarInt(input)) {
                    case 1 -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
                    case 2 -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
                    default -> AttributeModifier.Operation.ADD_VALUE;
                };
                modifiers.add(new AttributeModifier(id, amount, operation));
            }
            attributes.add(new AttributeSnapshot(attribute, base, modifiers));
        }
        return new ClientboundUpdateAttributes(entityId, attributes);
    }

    private static int baseCount(ByteBuf input, int max) {
        int count = Wire.readVarInt(input);
        if (count < 0 || count > max || count > input.readableBytes() / 10) {
            throw new MalformedPacketException("Invalid attribute list size " + count);
        }
        return count;
    }
}
