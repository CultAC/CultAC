package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.codec.ClientPacketCodecs;
import ac.cult.cultac.network.codec.ConnectionModelValues;
import ac.cult.cultac.network.codec.ModelItemValues;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.codec.WireRegistryState;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.inventory.ItemUtil;
import com.google.gson.JsonPrimitive;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Stacks the server sends again decode once per registry generation, through each connection's own decode. */
class SentStackReuseTest {
    private static final ProtocolVersion OLDER = ProtocolVersion.of(774);

    @Test
    void olderWireReusesStacksUntilRegistryDataArrives() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(OLDER)) {
            var writer = writer(OLDER);
            var sword = sword();
            var first = content(fixture, writer, sword, sword, apple());
            assertSame(first.items().get(0).patch(), first.items().get(1).patch());
            assertNotSame(first.items().get(0), first.items().get(1));
            assertSlots(first);
            var again = content(fixture, writer, sword, sword, apple());
            assertSame(first.items().get(0).patch(), again.items().get(0).patch());
            assertSlots(again);

            fixture.phase(ConnectionPhase.CONFIGURATION);
            var registry = frame(fixture.data, ConnectionPhase.CONFIGURATION, "registry_data");
            identifier(registry, "minecraft:enchantment");
            Wire.writeVarInt(registry, 1);
            identifier(registry, "minecraft:sharpness");
            registry.writeBoolean(false);
            read(fixture, ConnectionPhase.CONFIGURATION, registry);
            fixture.phase(ConnectionPhase.PLAY);

            var after = content(fixture, writer, sword, sword, apple());
            assertNotSame(first.items().get(0).patch(), after.items().get(0).patch());
            assertSlots(after);
            for (int slot = 0; slot < 3; slot++)
                assertSameStack(first.items().get(slot), after.items().get(slot));
        }
    }

    @Test
    void olderWireObservationsLeaveHeldStacksAlone() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = RecordReceiveFixture.olderHost(OLDER)) {
            var writer = writer(OLDER);
            var observed = observe(fixture, writer, sword(), apple());
            var read = content(fixture, writer, sword(), apple());
            assertNotSame(observed.items().get(0).patch(), read.items().get(0).patch());
            var later = observe(fixture, writer, sword(), apple());
            assertNotSame(read.items().get(0).patch(), later.items().get(0).patch());
            assertSameStack(read.items().get(0), later.items().get(0));
            assertSame(
                    read.items().get(0).patch(),
                    content(fixture, writer, sword(), apple()).items().get(0).patch());
        }
    }

    @Test
    void modelWireReusesStacksUntilNamesArePublished() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3, false)) {
            var values = new ConnectionModelValues(ProtocolCodecs.decoder());
            var platform = fixture.connection.platform();
            Mockito.when(platform.modelValues()).thenReturn(values);
            Mockito.when(platform.registryNames()).thenAnswer(ignored -> values.registryNames());
            var data = ProtocolData.load(ProtocolVersion.V26_3);
            var runtime = ProtocolRuntime.create(data, ClientPacketCodecs.connectionCatalog());
            var writer = writer(ProtocolVersion.V26_3);
            var sword = sword();

            var first = decode(runtime, fixture, content(data, writer, sword, sword, apple()));
            assertSame(first.items().get(0).patch(), first.items().get(1).patch());
            assertSlots(first);
            var observed = values.observe(() -> decode(runtime, fixture, content(data, writer, sword, sword, apple())));
            assertNotSame(first.items().get(0).patch(), observed.items().get(0).patch());
            assertNotSame(
                    observed.items().get(0).patch(), observed.items().get(1).patch());
            var again = decode(runtime, fixture, content(data, writer, sword, sword, apple()));
            assertSame(first.items().get(0).patch(), again.items().get(0).patch());

            var snapshot = values.registryNames();
            values.fallback();
            assertNotSame(snapshot, values.registryNames());
            var after = decode(runtime, fixture, content(data, writer, sword, sword, apple()));
            assertNotSame(first.items().get(0).patch(), after.items().get(0).patch());
            assertSlots(after);
            for (int slot = 0; slot < 3; slot++)
                assertSameStack(first.items().get(slot), after.items().get(slot));
        }
    }

    private static ModelItemValues writer(ProtocolVersion version) {
        return new ModelItemValues(
                ProtocolCodecs.decoder(),
                version,
                new WireRegistryState(version, ModelRegistryNamesState::defaults),
                ItemUtil.modelItems());
    }

    private static SimItemStack sword() {
        var stack = ItemUtil.modelItems().stack("minecraft:diamond_sword", 1);
        stack.components(stack.components().with("minecraft:damage", new JsonPrimitive(5)));
        return stack;
    }

    private static SimItemStack apple() {
        return ItemUtil.modelItems().stack("minecraft:apple", 3);
    }

    /** Window 0, state 7, the given slots and an empty carried stack. */
    private static ByteBuf content(ProtocolData data, ModelItemValues writer, SimItemStack... slots) {
        var frame = frame(data, ConnectionPhase.PLAY, "container_set_content");
        Wire.writeVarInt(frame, 0);
        Wire.writeVarInt(frame, 7);
        Wire.writeVarInt(frame, slots.length);
        for (var slot : slots) writer.write(frame, slot);
        writer.write(frame, SimItemStack.EMPTY);
        return frame;
    }

    private static InventoryPackets.Content content(
            RecordReceiveFixture fixture, ModelItemValues writer, SimItemStack... slots) {
        return (InventoryPackets.Content) read(fixture, ConnectionPhase.PLAY, content(fixture.data, writer, slots));
    }

    private static InventoryPackets.Content observe(
            RecordReceiveFixture fixture, ModelItemValues writer, SimItemStack... slots) {
        var frame = content(fixture.data, writer, slots);
        try {
            var values = fixture.connection
                    .packets()
                    .observe(
                            ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND, frame, fixture.connection, type -> true);
            assertEquals(1, values.size());
            return (InventoryPackets.Content) values.getFirst().packet();
        } finally {
            frame.release();
        }
    }

    private static InventoryPackets.Content decode(
            ProtocolRuntime runtime, RecordReceiveFixture fixture, ByteBuf frame) {
        try {
            int id = Wire.readVarInt(frame);
            var packet = (InventoryPackets.Content)
                    runtime.decode(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND, id, frame, fixture.connection);
            assertEquals(0, frame.readableBytes());
            return packet;
        } finally {
            frame.release();
        }
    }

    /** Two swords, three apples and nothing carried, each decoded in place. */
    private static void assertSlots(InventoryPackets.Content content) {
        assertEquals(7, content.stateId());
        assertEquals(3, content.items().size());
        assertSameStack(content.items().get(0), content.items().get(1));
        assertEquals("minecraft:diamond_sword", content.items().get(0).itemKey());
        assertEquals("minecraft:apple", content.items().get(2).itemKey());
        assertEquals(3, content.items().get(2).count());
        assertTrue(content.carriedItem().isEmpty());
    }

    private static void assertSameStack(SimItemStack expected, SimItemStack actual) {
        assertEquals(expected.itemKey(), actual.itemKey());
        assertEquals(expected.count(), actual.count());
        assertEquals(expected.components(), actual.components());
        assertEquals(expected.patch(), actual.patch());
    }

    private static ByteBuf frame(ProtocolData data, ConnectionPhase phase, String name) {
        var frame = Unpooled.buffer();
        int id = data.packets(phase, PacketDirection.CLIENTBOUND).id("minecraft:" + name);
        assertTrue(id >= 0, name);
        Wire.writeVarInt(frame, id);
        return frame;
    }

    private static Object read(RecordReceiveFixture fixture, ConnectionPhase phase, ByteBuf frame) {
        try {
            var values = fixture.connection
                    .packets()
                    .read(phase, PacketDirection.CLIENTBOUND, frame, fixture.connection, type -> true);
            assertEquals(1, values.size());
            return values.getFirst().packet();
        } finally {
            frame.release();
        }
    }

    private static void identifier(ByteBuf output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        Wire.writeVarInt(output, bytes.length);
        output.writeBytes(bytes);
    }
}
