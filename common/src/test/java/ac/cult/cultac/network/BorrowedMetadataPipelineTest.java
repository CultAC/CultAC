package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Metadata the encoder decodes borrows the frame's bytes for as long as it holds the frame. */
class BorrowedMetadataPipelineTest {
    @Test
    void anUnchangedFrameIsForwardedAndItsEntriesAreUnreadableAfterward() throws Exception {
        for (ProtocolVersion version : new ProtocolVersion[] {ProtocolVersion.V26_3, ProtocolVersion.V1_21_11}) {
            try (var services = new RecordConsumerServices();
                    var fixture = version == ProtocolVersion.V26_3
                            ? new RecordReceiveFixture(version)
                            : RecordReceiveFixture.olderHost(version)) {
                var kept = new ArrayList<EntityMetadata.Entry>();
                var detached = new ArrayList<EntityMetadata.Entry>();
                var live = new ArrayList<byte[]>();
                consume(fixture, (event, player, packet) -> {
                    for (var entry : ((EntityMetadata) packet).packedItems()) {
                        kept.add(entry);
                        detached.add(entry.detach());
                        live.add(bytes(entry));
                    }
                });
                ByteBuf frame = metadata(fixture, 42, new byte[] {0, 0, 2}, new byte[] {9, 0, 5});
                byte[] expected = ByteBufUtil.getBytes(frame);
                assertTrue(fixture.channel.writeOutbound(frame));
                assertArrayEquals(expected, forwarded(fixture), version.toString());

                assertEquals(2, kept.size(), version.toString());
                assertArrayEquals(new byte[] {0, 0, 2}, live.get(0));
                assertArrayEquals(new byte[] {9, 0, 5}, live.get(1));
                assertEquals((byte) 2, kept.get(0).value());
                assertThrows(IllegalStateException.class, () -> kept.get(0).bytes());
                assertArrayEquals(new byte[] {0, 0, 2}, bytes(detached.get(0)));
                assertArrayEquals(new byte[] {9, 0, 5}, bytes(detached.get(1)));
            }
        }
    }

    @Test
    void aReplacementBuiltFromBorrowedEntriesIsEncodedBeforeTheFrameIsReleased() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            consume(fixture, (event, player, packet) -> {
                var metadata = (EntityMetadata) packet;
                event.replace(new EntityMetadata(
                        metadata.id(), List.of(metadata.packedItems().get(1))));
            });
            assertTrue(
                    fixture.channel.writeOutbound(metadata(fixture, 42, new byte[] {0, 0, 2}, new byte[] {9, 0, 5})));
            ByteBuf expected = metadata(fixture, 42, new byte[] {9, 0, 5});
            try {
                assertArrayEquals(ByteBufUtil.getBytes(expected), forwarded(fixture));
            } finally {
                expected.release();
            }
        }
    }

    @Test
    void theServerSentSelfFlagsAreResentAfterTheirFrameIsReleased() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            fixture.user.bind(mock(PlatformPlayer.class));
            fixture.registrar.register(routes ->
                    routes.registerSendListener(PacketListenerPriority.HIGH, new PacketSelfMetadataListener()));
            drain(fixture);
            int self = fixture.player.entityID;
            byte[] flags = {(byte) WatchableIndexUtil.ENTITY_SHARED_FLAGS, 0, 0x08};
            byte[] pose = {(byte) WatchableIndexUtil.ENTITY_POSE, 20, 1};
            assertTrue(fixture.channel.writeOutbound(metadata(fixture, self, flags, pose)));
            ByteBuf withoutPose = metadata(fixture, self, flags);
            byte[] expected = ByteBufUtil.getBytes(withoutPose);
            withoutPose.release();
            assertTrue(drain(fixture).stream().anyMatch(written -> Arrays.equals(expected, written)));

            var stored = fixture.player.serverSentSelfSharedFlags;
            assertNotNull(stored);
            assertArrayEquals(flags, bytes(stored));
            fixture.player.resyncPose();
            assertTrue(drain(fixture).stream().anyMatch(written -> Arrays.equals(expected, written)));
        }
    }

    private static void consume(RecordReceiveFixture fixture, PacketSendHandler<Object> handler) {
        fixture.registrar.register(routes -> routes.sendRoute(
                fixture.registrar.runtime().typeForKey("clientbound.set_entity_data"),
                PacketListenerPriority.NORMAL,
                handler));
    }

    private static byte[] bytes(EntityMetadata.Entry entry) {
        var view = entry.bytes();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return bytes;
    }

    private static ByteBuf metadata(RecordReceiveFixture fixture, int entity, byte[]... entries) {
        var frame = Unpooled.buffer();
        int id = fixture.data
                .packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                .id("minecraft:set_entity_data");
        Wire.writeVarInt(frame, id);
        Wire.writeVarInt(frame, entity);
        for (byte[] entry : entries) frame.writeBytes(entry);
        frame.writeByte(255);
        return frame;
    }

    private static byte[] forwarded(RecordReceiveFixture fixture) {
        List<byte[]> written = drain(fixture);
        assertEquals(1, written.size());
        return written.getFirst();
    }

    private static List<byte[]> drain(RecordReceiveFixture fixture) {
        fixture.channel.runPendingTasks();
        var written = new ArrayList<byte[]>();
        Object message;
        while ((message = fixture.channel.readOutbound()) != null) {
            if (message instanceof ByteBuf buffer) {
                written.add(ByteBufUtil.getBytes(buffer));
                buffer.release();
            }
        }
        assertNull(fixture.channel.readOutbound());
        return written;
    }
}
