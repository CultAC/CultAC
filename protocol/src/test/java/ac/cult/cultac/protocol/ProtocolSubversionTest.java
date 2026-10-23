package ac.cult.cultac.protocol;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundExplode;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSpectatorAction;
import ac.cult.cultac.protocol.testing.CodecFixture;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * Exact boundaries from PacketEvents 2.0 wrappers at 5da85d7ad, checked against
 * the pinned official server codecs (including the six intermediate releases).
 */
class ProtocolSubversionTest {
    @Test
    void vehicleGroundAndPlayerInfoHatMatchTheirIntroduction() {
        for (var version : ProtocolVersion.values()) {
            ByteBuf bytes = Unpooled.buffer();
            try {
                bytes.writeDouble(1)
                        .writeDouble(2)
                        .writeDouble(3)
                        .writeFloat(90)
                        .writeFloat(-20);
                if (version.atLeast(ProtocolVersion.V1_21_4)) bytes.writeBoolean(true);
                var vehicle = read(version, ServerboundPackets.MOVE_VEHICLE, bytes);
                assertEquals(new Vec3d(1, 2, 3), vehicle.position());
                assertEquals(version.atLeast(ProtocolVersion.V1_21_4), vehicle.hasOnGround());
                assertEquals(version.atLeast(ProtocolVersion.V1_21_4), vehicle.onGround());
                if (version == ProtocolVersion.V1_21_3) {
                    bytes.writeByte(1);
                    assertThrows(
                            MalformedPacketException.class,
                            () -> read(version, ServerboundPackets.MOVE_VEHICLE, bytes));
                } else {
                    bytes.writerIndex(bytes.writerIndex() - 1);
                    assertThrows(
                            MalformedPacketException.class,
                            () -> read(version, ServerboundPackets.MOVE_VEHICLE, bytes));
                }

                bytes.clear()
                        .writeByte(version.atLeast(ProtocolVersion.V1_21_4) ? 128 : 0)
                        .writeByte(1);
                Wire.writeUuid(bytes, new java.util.UUID(1, 2));
                if (version.atLeast(ProtocolVersion.V1_21_4)) bytes.writeBoolean(true);
                var info = read(version, ClientboundPackets.PLAYER_INFO_UPDATE, bytes);
                assertEquals(
                        version.atLeast(ProtocolVersion.V1_21_4),
                        info.actions().contains(ClientboundPlayerInfoUpdate.Action.UPDATE_HAT));
                if (version.atLeast(ProtocolVersion.V1_21_4))
                    assertArrayEquals(
                            new byte[] {1},
                            info.entries()
                                    .get(0)
                                    .encodedActions()
                                    .get(ClientboundPlayerInfoUpdate.Action.UPDATE_HAT)
                                    .bytes());
                assertWrittenBytes(version, ClientboundPackets.PLAYER_INFO_UPDATE, info, bytes);
            } finally {
                bytes.release();
            }
        }
    }

    @Test
    void bundleSelectionValidationStartsAtV1_21_5AndSneakCommandsEndAtV1_21_6() {
        for (var version : ProtocolVersion.values()) {
            ByteBuf bytes = Unpooled.buffer();
            try {
                for (int selected : new int[] {Integer.MIN_VALUE, -2, -1, 0, 1, Integer.MAX_VALUE}) {
                    bytes.clear().writeByte(2);
                    Wire.writeVarInt(bytes, selected);
                    if (version.atLeast(ProtocolVersion.V1_21_5) && selected < -1) {
                        assertThrows(
                                MalformedPacketException.class,
                                () -> read(version, ServerboundPackets.SELECT_BUNDLE_ITEM, bytes));
                    } else
                        assertEquals(
                                selected,
                                read(version, ServerboundPackets.SELECT_BUNDLE_ITEM, bytes)
                                        .selectedItemIndex());
                }
                var expected = !version.atLeast(ProtocolVersion.V1_21_6)
                        ? new PlayerCommandAction[] {
                            PlayerCommandAction.PRESS_SHIFT_KEY,
                            PlayerCommandAction.RELEASE_SHIFT_KEY,
                            PlayerCommandAction.STOP_SLEEPING,
                            PlayerCommandAction.START_SPRINTING,
                            PlayerCommandAction.STOP_SPRINTING,
                            PlayerCommandAction.START_JUMPING_WITH_HORSE,
                            PlayerCommandAction.STOP_JUMPING_WITH_HORSE,
                            PlayerCommandAction.OPEN_INVENTORY,
                            PlayerCommandAction.START_FLYING_WITH_ELYTRA
                        }
                        : new PlayerCommandAction[] {
                            PlayerCommandAction.STOP_SLEEPING,
                            PlayerCommandAction.START_SPRINTING,
                            PlayerCommandAction.STOP_SPRINTING,
                            PlayerCommandAction.START_JUMPING_WITH_HORSE,
                            PlayerCommandAction.STOP_JUMPING_WITH_HORSE,
                            PlayerCommandAction.OPEN_INVENTORY,
                            PlayerCommandAction.START_FLYING_WITH_ELYTRA
                        };
                for (int ordinal = 0; ordinal < expected.length; ordinal++) {
                    bytes.clear().writeByte(7).writeByte(ordinal).writeByte(90);
                    assertEquals(
                            expected[ordinal],
                            read(version, ServerboundPackets.PLAYER_COMMAND, bytes)
                                    .action());
                }
                bytes.clear().writeByte(7).writeByte(expected.length).writeByte(0);
                assertThrows(
                        MalformedPacketException.class, () -> read(version, ServerboundPackets.PLAYER_COMMAND, bytes));
            } finally {
                bytes.release();
            }
        }
    }

    @Test
    void compactVelocitySpawnLayoutExplosionPrefixAndRelativeRotationMatchTheirIntroduction() {
        for (var version : ProtocolVersion.values()) {
            boolean compact = version.atLeast(ProtocolVersion.V1_21_9);
            ByteBuf bytes = Unpooled.buffer();
            try {
                bytes.writeByte(7).writeBytes(ByteBufUtil.decodeHexDump(compact ? "f1ff7ffe0003" : "1f40e0c00000"));
                var motion = read(version, ClientboundPackets.ENTITY_MOTION, bytes);
                assertEquals(new Vec3d(1, -1, 0), motion.velocity());
                assertWrittenBytes(version, ClientboundPackets.ENTITY_MOTION, motion, bytes);

                bytes.clear().writeByte(7);
                Wire.writeUuid(bytes, new java.util.UUID(1, 2));
                Wire.writeVarInt(
                        bytes,
                        ProtocolData.load(version)
                                .registry("minecraft:entity_type")
                                .id("minecraft:pig"));
                bytes.writeDouble(1).writeDouble(2).writeDouble(3);
                if (compact) bytes.writeBytes(ByteBufUtil.decodeHexDump("f1ff7ffe0003"));
                bytes.writeByte(32).writeByte(64).writeByte(0).writeByte(12);
                if (!compact) bytes.writeBytes(ByteBufUtil.decodeHexDump("1f40e0c00000"));
                var spawn = read(version, ClientboundPackets.ADD_ENTITY, bytes);
                assertEquals("minecraft:pig", spawn.entityType());
                assertEquals(90, spawn.yaw());
                assertEquals(45, spawn.pitch());
                assertEquals(12, spawn.data());
                assertEquals(new Vec3d(1, -1, 0), spawn.velocity());

                bytes.clear().writeDouble(1).writeDouble(2).writeDouble(3);
                if (compact) bytes.writeFloat(4).writeInt(5);
                bytes.writeBoolean(true).writeDouble(0.25).writeDouble(-0.5).writeDouble(1);
                // Particle and sound suffixes are deliberately unconsumed.
                bytes.writeByte(99);
                assertEquals(
                        new Vec3d(0.25, -0.5, 1),
                        read(version, ClientboundPackets.EXPLODE, bytes).knockback());

                bytes.clear().writeDouble(1).writeDouble(2).writeDouble(3);
                if (compact) bytes.writeFloat(4).writeInt(0);
                bytes.writeBoolean(true).writeDouble(0.25).writeDouble(-0.5).writeDouble(1);
                Wire.writeVarInt(
                        bytes,
                        ModelRegistryData.load(version)
                                .registry("minecraft:particle_type")
                                .id("minecraft:explosion"));
                Wire.writeVarInt(bytes, 0);
                Wire.writeString(bytes, "minecraft:entity.generic.explode", 32767);
                bytes.writeBoolean(false);
                if (compact) Wire.writeVarInt(bytes, 0);
                if (version.atLeast(ProtocolVersion.V26_3)) bytes.writeBoolean(true);
                assertWrittenBytes(
                        version,
                        ClientboundPackets.EXPLODE,
                        new ClientboundExplode(new Vec3d(1, 2, 3), new Vec3d(0.25, -0.5, 1)),
                        bytes);

                bytes.clear().writeFloat(90);
                if (compact) bytes.writeBoolean(true);
                bytes.writeFloat(-20);
                if (compact) bytes.writeBoolean(false);
                var rotation = read(version, ClientboundPackets.PLAYER_ROTATION, bytes);
                assertEquals(90, rotation.yaw());
                assertEquals(-20, rotation.pitch());
                assertEquals(compact, rotation.relativeYaw());
                assertFalse(rotation.relativePitch());
                assertWrittenBytes(version, ClientboundPackets.PLAYER_ROTATION, rotation, bytes);
            } finally {
                bytes.release();
            }
        }
    }

    @Test
    void attackAndGameruleCommandStartAtV26_1AndSpectatorIdBecomesOptionalAtV26_2() {
        for (var version : ProtocolVersion.values()) {
            ByteBuf bytes = Unpooled.buffer();
            try {
                bytes.writeByte(2);
                if (!version.atLeast(ProtocolVersion.V26_1)) {
                    assertThrows(
                            MalformedPacketException.class,
                            () -> read(version, ServerboundPackets.CLIENT_COMMAND, bytes));
                    assertFalse(ProtocolRuntime.create(ProtocolData.load(version))
                            .supports(ServerboundPackets.SPECTATOR_ACTION));
                } else {
                    assertEquals(
                            "REQUEST_GAMERULE_VALUES",
                            read(version, ServerboundPackets.CLIENT_COMMAND, bytes)
                                    .action()
                                    .name());
                    bytes.clear().writeByte(7);
                    assertEquals(
                            InteractAction.ATTACK,
                            read(version, ServerboundPackets.INTERACT, "minecraft:attack", bytes)
                                    .action());
                    bytes.clear()
                            .writeByte(7)
                            .writeByte(1)
                            .writeBytes(ByteBufUtil.decodeHexDump("f1ff7ffe0003"))
                            .writeByte(1);
                    var interact = read(version, ServerboundPackets.INTERACT, "minecraft:interact", bytes);
                    assertEquals(InteractAction.INTERACT_AT, interact.action());
                    assertEquals(new Vec3d(1, -1, 0), interact.target().orElseThrow());
                    for (int id : new int[] {0, 1, 7}) {
                        bytes.clear().writeByte(id);
                        OptionalInt expected = version == ProtocolVersion.V26_1
                                ? OptionalInt.of(id)
                                : id == 0 ? OptionalInt.empty() : OptionalInt.of(id - 1);
                        assertEquals(
                                new ServerboundSpectatorAction(expected),
                                read(version, ServerboundPackets.SPECTATOR_ACTION, bytes));
                    }
                }
                bytes.clear();
                Wire.writeAngle(bytes, version, Float.NEGATIVE_INFINITY);
                assertEquals(!version.atLeast(ProtocolVersion.V26_1) ? 255 : 0, bytes.readUnsignedByte());
            } finally {
                bytes.release();
            }
        }
    }

    private static <R> R read(ProtocolVersion version, PacketType<R> type, ByteBuf bytes) {
        return read(version, type, type.wireNames(ProtocolData.load(version)).get(0), bytes);
    }

    private static <R> R read(ProtocolVersion version, PacketType<R> type, String name, ByteBuf bytes) {
        var runtime = ProtocolRuntime.create(ProtocolData.load(version));
        var connection = new CodecFixture(runtime);
        connection.phase(ConnectionPhase.PLAY);
        return type.recordClass()
                .cast(connection.read(
                        type.direction(),
                        runtime.data()
                                .packets(ConnectionPhase.PLAY, type.direction())
                                .id(name),
                        bytes));
    }

    private static <R> void assertWrittenBytes(
            ProtocolVersion version, PacketType<R> type, R record, ByteBuf expected) {
        ByteBuf output = Unpooled.buffer();
        try {
            ProtocolRuntime.create(ProtocolData.load(version)).encode(ConnectionPhase.PLAY, type, record, output);
            Wire.readVarInt(output);
            assertEquals(ByteBufUtil.hexDump(expected), ByteBufUtil.hexDump(output), version + "/" + type);
        } finally {
            output.release();
        }
    }
}
