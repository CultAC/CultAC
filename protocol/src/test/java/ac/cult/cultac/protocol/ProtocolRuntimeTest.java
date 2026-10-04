package ac.cult.cultac.protocol;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.codec.connection.PingCodec;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.Packets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.testing.CodecFixture;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProtocolRuntimeTest {
    @Test
    void delayedEntityListsOwnTheirValuesAfterTheInputIsReusedAndReleased() {
        var ids = new java.util.ArrayList<>(List.of(7, -1, 7));
        var removal = new ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities(ids);
        var passengers = new ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers(99, ids);
        ids.clear();
        assertEquals(List.of(7, -1, 7), removal.entityIds());
        assertEquals(removal.entityIds(), passengers.passengers());
        assertThrows(
                UnsupportedOperationException.class,
                () -> passengers.passengers().clear());
        assertThrows(
                UnsupportedOperationException.class, () -> removal.entityIds().clear());
        for (ProtocolVersion version : ProtocolVersion.values()) {
            var data = ProtocolData.load(version);
            var connection = new CodecFixture(ProtocolRuntime.create(data));
            connection.phase(ConnectionPhase.PLAY);
            var bytes = Unpooled.buffer();
            ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers decoded;
            try {
                for (int value : new int[] {99, 3, 7, -1, 7}) Wire.writeVarInt(bytes, value);
                decoded = connection.read(
                        ClientboundPackets.SET_PASSENGERS,
                        data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                                .id("minecraft:set_passengers"),
                        bytes);
                bytes.setZero(0, bytes.writerIndex());
            } finally {
                bytes.release();
            }
            assertEquals(passengers, decoded);
        }
    }

    @Test
    void allMovementIdsResolveToOneTypeAndUnknownRoutesStayUnbound() {
        for (ProtocolVersion version : ProtocolVersion.values()) {
            ProtocolData data = ProtocolData.load(version);
            ProtocolRuntime runtime = ProtocolRuntime.create(data);
            CodecFixture connection = new CodecFixture(runtime);
            connection.phase(ConnectionPhase.PLAY);
            // Expectations from the exact vanilla packet reports, independent of catalog predicates.
            var absent =
                    new java.util.HashSet<>(Set.of("serverbound.pick_item", "serverbound.debug_sample_subscription"));
            if (!version.atLeast(ProtocolVersion.V26_3)) absent.add("clientbound.swing_animation");
            if (!version.atLeast(ProtocolVersion.V26_1))
                absent.addAll(Set.of("serverbound.spectator_action", "serverbound.set_game_rule"));
            if (!version.atLeast(ProtocolVersion.V1_21_9)) {
                absent.remove("serverbound.debug_sample_subscription");
                absent.add("serverbound.debug_subscription_request");
            }
            if (!version.atLeast(ProtocolVersion.V1_21_6))
                absent.addAll(Set.of("serverbound.change_game_mode", "serverbound.custom_click_action"));
            if (!version.atLeast(ProtocolVersion.V1_21_5))
                absent.addAll(Set.of("serverbound.set_test_block", "serverbound.test_instance_block_action"));
            if (!version.atLeast(ProtocolVersion.V1_21_4)) {
                absent.remove("serverbound.pick_item");
                absent.addAll(Set.of(
                        "serverbound.pick_item_from_block",
                        "serverbound.pick_item_from_entity",
                        "serverbound.player_loaded"));
            }
            assertEquals(
                    absent,
                    Packets.all().stream()
                            .filter(type -> !runtime.supports(type))
                            .map(PacketType::key)
                            .collect(java.util.stream.Collectors.toSet()));
            for (String variant : ServerboundPackets.MOVE_PLAYER.wireNames(data)) {
                int id = data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND)
                        .id(variant);
                assertSame(ServerboundPackets.MOVE_PLAYER, connection.typeOf(PacketDirection.SERVERBOUND, id));
            }
            int suggestions = data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND)
                    .id("minecraft:command_suggestion");
            assertSame(
                    ServerboundPackets.COMMAND_SUGGESTION, connection.typeOf(PacketDirection.SERVERBOUND, suggestions));
            int unmigrated = data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND)
                    .id("minecraft:container_click");
            assertTrue(unmigrated >= 0);
            assertNull(connection.typeOf(PacketDirection.SERVERBOUND, unmigrated));
            assertNull(connection.typeOf(PacketDirection.SERVERBOUND, Integer.MAX_VALUE));
        }
    }

    @Test
    void inventoryCodecsBindTheActualPacketNameOnEverySupportedNativeRelease() {
        for (var version : ProtocolVersion.values()) {
            var data = ProtocolData.load(version);
            var runtime = ProtocolRuntime.create(data);
            var connection = new CodecFixture(runtime);
            connection.phase(ConnectionPhase.PLAY);
            for (var type : List.of(
                    ClientboundPackets.OPEN_SCREEN,
                    ClientboundPackets.MOUNT_SCREEN_OPEN,
                    ClientboundPackets.SET_HELD_SLOT)) {
                int id = data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                        .id(type.wireNames(data).get(0));
                assertTrue(id >= 0);
                assertTrue(runtime.supports(type));
                assertSame(type, connection.typeOf(PacketDirection.CLIENTBOUND, id));
                assertEquals(
                        version,
                        runtime.binding(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND, id)
                                .context()
                                .version());
                assertSame(type, runtime.typeForRecord(type.recordClass()));
            }
        }
    }

    @Test
    void readPreservesIndexOwnershipAndRejectsTruncationAndTrailingData() {
        ProtocolData data = ProtocolData.load(ProtocolVersion.V26_2);
        CodecFixture connection = new CodecFixture(ProtocolRuntime.create(data));
        connection.phase(ConnectionPhase.PLAY);
        ServerboundMovePlayer source = new ServerboundMovePlayer(-0.0, 12, -3, 90, -20, true, true, true, true);
        ByteBuf bytes = Unpooled.buffer();
        try {
            bytes.writeInt(12345);
            connection.write(ServerboundPackets.MOVE_PLAYER, source, bytes);
            bytes.skipBytes(4);
            int id = Wire.readVarInt(bytes);
            int index = bytes.readerIndex();
            ServerboundMovePlayer decoded = connection.read(ServerboundPackets.MOVE_PLAYER, id, bytes);
            assertEquals(source, decoded);
            assertEquals(index, bytes.readerIndex());
            assertEquals(1, bytes.refCnt());
            int end = bytes.writerIndex();
            for (int size = 0; size < end - index; size++) {
                bytes.writerIndex(index + size);
                assertThrows(
                        MalformedPacketException.class,
                        () -> connection.read(ServerboundPackets.MOVE_PLAYER, id, bytes));
                assertEquals(index, bytes.readerIndex());
            }
            bytes.writerIndex(end).writeByte(0);
            assertThrows(
                    MalformedPacketException.class, () -> connection.read(ServerboundPackets.MOVE_PLAYER, id, bytes));
            assertEquals(index, bytes.readerIndex());
        } finally {
            bytes.release();
        }
    }

    @Test
    void absentNamesStayUnsupportedWhileDuplicateTypesOrMissingEnumMappingsFail() {
        ProtocolData data = ProtocolData.load(ProtocolVersion.V26_2);
        PacketType<ClientboundPing> missing = new PacketType<>(
                "missing",
                ClientboundPing.class,
                PacketDirection.CLIENTBOUND,
                Set.of(ConnectionPhase.PLAY),
                List.of("minecraft:missing"),
                ProtocolVersion.V1_21_3,
                new PingCodec());
        assertFalse(ProtocolRuntime.create(data, List.of(missing)).supports(missing));
        assertThrows(
                ProtocolResolutionException.class,
                () -> ProtocolRuntime.create(data, List.of(ClientboundPackets.PING, ClientboundPackets.PING)));
        PacketType<ClientboundPing> invalid = new PacketType<>(
                "invalid",
                ClientboundPing.class,
                PacketDirection.CLIENTBOUND,
                Set.of(ConnectionPhase.PLAY),
                List.of("minecraft:ping"),
                ProtocolVersion.V1_21_3,
                new PacketCodec<>() {
                    public ClientboundPing read(ByteBuf input, ProtocolContext context) {
                        return new ClientboundPing(0);
                    }

                    public void validate(ProtocolData ignored) {
                        throw new ProtocolResolutionException("Unmapped enum ordinal");
                    }
                });
        assertThrows(ProtocolResolutionException.class, () -> ProtocolRuntime.create(data, List.of(invalid)));
    }

    @Test
    void writingFailureRollsBackAndReadOnlyPacketsCannotBeSent() {
        ProtocolData data = ProtocolData.load(ProtocolVersion.V26_2);
        PacketType<ClientboundPing> broken = new PacketType<>(
                "broken",
                ClientboundPing.class,
                PacketDirection.CLIENTBOUND,
                Set.of(ConnectionPhase.PLAY),
                List.of("minecraft:ping"),
                ProtocolVersion.V1_21_3,
                new WritablePacketCodec<>() {
                    public ClientboundPing read(ByteBuf input, ProtocolContext context) {
                        return new ClientboundPing(input.readInt());
                    }

                    public void write(ByteBuf output, ProtocolContext context, ClientboundPing packet) {
                        output.writeInt(1);
                        throw new UnsupportedOnVersionException("unrepresentable field");
                    }
                });
        CodecFixture connection = new CodecFixture(ProtocolRuntime.create(data, List.of(broken)));
        connection.phase(ConnectionPhase.PLAY);
        ByteBuf bytes = Unpooled.buffer().writeInt(12345);
        try {
            assertThrows(
                    UnsupportedOnVersionException.class, () -> connection.write(broken, new ClientboundPing(1), bytes));
            assertEquals(4, bytes.writerIndex());
            assertEquals(12345, bytes.getInt(0));
            assertThrows(
                    UnsupportedOnVersionException.class,
                    () -> connection.write(
                            ac.cult.cultac.protocol.packet.ServerboundPackets.CLIENT_TICK_END.opaqueValue(), bytes));
        } finally {
            bytes.release();
        }
    }

    @Test
    void commandLimitsBelongToTheirRuntimeAcrossMultipleConnections() {
        var data = ProtocolData.load(ProtocolVersion.V26_3);
        var shorter = ProtocolRuntime.create(data, 7);
        var longer = ProtocolRuntime.create(data, 8);
        int id = data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND).id("minecraft:chat_command");
        var bytes = Unpooled.buffer();
        try {
            Wire.writeString(bytes, "12345678", 8);
            for (int i = 0; i < 3; i++) {
                var first = new CodecFixture(shorter);
                var second = new CodecFixture(longer);
                first.phase(ConnectionPhase.PLAY);
                second.phase(ConnectionPhase.PLAY);
                assertThrows(
                        MalformedPacketException.class, () -> first.read(ServerboundPackets.CHAT_COMMAND, id, bytes));
                assertEquals(
                        "12345678",
                        second.read(ServerboundPackets.CHAT_COMMAND, id, bytes).command());
                assertEquals(0, bytes.readerIndex());
                assertEquals(1, bytes.refCnt());
            }
        } finally {
            bytes.release();
        }
    }

    @Test
    void everyBindingCarriesItsTypesDenseSlot() {
        for (ProtocolVersion version : ProtocolVersion.values()) {
            ProtocolData data = ProtocolData.load(version);
            ProtocolRuntime runtime = ProtocolRuntime.create(data);
            List<PacketType<?>> catalog = Packets.all();
            assertEquals(catalog.size(), runtime.slotCount());
            for (int slot = 0; slot < catalog.size(); slot++) assertEquals(slot, runtime.slot(catalog.get(slot)));
            for (ConnectionPhase phase : ConnectionPhase.values()) {
                for (PacketDirection direction : PacketDirection.values()) {
                    for (int id = 0; id < data.packets(phase, direction).size(); id++) {
                        var bound = runtime.binding(phase, direction, id);
                        if (bound != null) assertEquals(runtime.slot(bound.type()), bound.slot());
                    }
                }
            }
            PacketType<ClientboundPing> foreign = new PacketType<>(
                    "clientbound.ping",
                    ClientboundPing.class,
                    PacketDirection.CLIENTBOUND,
                    Set.of(ConnectionPhase.PLAY),
                    List.of("minecraft:ping"),
                    ProtocolVersion.V1_21_3,
                    new PingCodec());
            assertThrows(IllegalArgumentException.class, () -> runtime.slot(foreign));
        }
    }
}
