package ac.cult.cultac.protocol;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.codec.connection.PingCodec;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.Packets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.testing.CodecFixture;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PacketCatalogTest {
    @Test
    void everyDeclaredWireNameExistsOnSomeVerifiedVersion() {
        List<ProtocolData> versions =
                Arrays.stream(ProtocolVersion.values()).map(ProtocolData::load).toList();
        for (PacketType<?> type : Packets.all()) {
            for (String name : type.wireNames()) {
                assertTrue(
                        versions.stream().anyMatch(data -> type.wireNames(data).contains(name)),
                        type + " declares " + name + ", which no verified version carries in its phases");
            }
        }
    }

    @Test
    void everyConstantRegisteredItselfInItsDirection() throws IllegalAccessException {
        for (var catalog : List.of(ServerboundPackets.class, ClientboundPackets.class)) {
            PacketDirection direction =
                    catalog == ServerboundPackets.class ? PacketDirection.SERVERBOUND : PacketDirection.CLIENTBOUND;
            List<PacketType<?>> declared = new ArrayList<>();
            for (Field field : catalog.getDeclaredFields()) {
                if (Modifier.isPublic(field.getModifiers()) && field.getType() == PacketType.class) {
                    declared.add((PacketType<?>) field.get(null));
                }
            }
            List<PacketType<?>> registered =
                    catalog == ServerboundPackets.class ? ServerboundPackets.all() : ClientboundPackets.all();
            assertEquals(declared, registered);
            assertTrue(registered.stream().allMatch(type -> type.direction() == direction));
        }
        assertEquals(
                Stream.concat(ServerboundPackets.all().stream(), ClientboundPackets.all().stream())
                        .toList(),
                Packets.all());
        assertEquals(
                Packets.all().size(),
                new HashSet<>(Packets.all().stream().map(PacketType::key).toList()).size());
    }

    @Test
    void namesPresentInOnlySomeDeclaredPhasesOrRenamesCarriedTogetherFailInitialization() {
        ProtocolData data = ProtocolData.load(ProtocolVersion.V26_2);
        // set_health exists in play only, so a configuration declaration is wrong.
        var phases = new PacketType<>(
                "phases",
                ClientboundPing.class,
                PacketDirection.CLIENTBOUND,
                Set.of(ConnectionPhase.CONFIGURATION, ConnectionPhase.PLAY),
                List.of("minecraft:set_health"),
                ProtocolVersion.V1_21_3,
                new PingCodec());
        assertThrows(ProtocolResolutionException.class, () -> ProtocolRuntime.create(data, List.of(phases)));
        // A rename's names never coexist; two present names need a codec that tells them apart.
        var renamed = new PacketType<>(
                "renamed",
                ClientboundPing.class,
                PacketDirection.CLIENTBOUND,
                Set.of(ConnectionPhase.PLAY),
                List.of("minecraft:ping", "minecraft:set_health"),
                ProtocolVersion.V1_21_3,
                new PingCodec());
        assertThrows(ProtocolResolutionException.class, () -> ProtocolRuntime.create(data, List.of(renamed)));
    }

    @Test
    void variantCodecsMustSupplyEveryDeclaredName() {
        VariantCodec<ClientboundPing> codec = new VariantCodec<>() {
            public List<String> variants() {
                return List.of("ping");
            }

            public ClientboundPing read(ByteBuf input, ProtocolContext context) {
                return new ClientboundPing(0);
            }
        };
        assertThrows(
                IllegalArgumentException.class,
                () -> new PacketType<>(
                        "variants",
                        ClientboundPing.class,
                        PacketDirection.CLIENTBOUND,
                        Set.of(ConnectionPhase.PLAY),
                        List.of("minecraft:ping", "minecraft:pong"),
                        ProtocolVersion.V1_21_3,
                        codec));
    }

    @Test
    void emptyFamiliesRejectTrailingBytesWhileIgnoredFamiliesLeaveThemUnread() {
        ProtocolData data = ProtocolData.load(ProtocolVersion.V26_3);
        CodecFixture connection = new CodecFixture(ProtocolRuntime.create(data));
        connection.phase(ConnectionPhase.PLAY);
        int tickEnd =
                data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND).id("minecraft:client_tick_end");
        int sign =
                data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND).id("minecraft:sign_update");
        ByteBuf bytes = Unpooled.buffer();
        try {
            assertSame(
                    ServerboundPackets.CLIENT_TICK_END.opaqueValue(),
                    connection.read(ServerboundPackets.CLIENT_TICK_END, tickEnd, bytes));
            bytes.writeByte(0);
            assertThrows(
                    MalformedPacketException.class,
                    () -> connection.read(ServerboundPackets.CLIENT_TICK_END, tickEnd, bytes));
            assertSame(
                    ServerboundPackets.SIGN_UPDATE.opaqueValue(),
                    connection.read(ServerboundPackets.SIGN_UPDATE, sign, bytes));
        } finally {
            bytes.release();
        }
        assertTrue(ClientboundPackets.BUNDLE_DELIMITER.writable());
        assertFalse(ServerboundPackets.CLIENT_TICK_END.writable());
        assertFalse(ServerboundPackets.SIGN_UPDATE.writable());
    }
}
