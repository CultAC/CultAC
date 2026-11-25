package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.value.BlockPos;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CodecFailureRecoveryTest {
    @ParameterizedTest
    @EnumSource(ProtocolVersion.class)
    void outboundDecodeFailurePreservesBytesPromiseAndInspection(ProtocolVersion version) {
        try (var fixture = new RecordReceiveFixture(version)) {
            var calls = new AtomicInteger();
            fixture.registrar.register(batch -> batch.sendRoute(
                    ClientboundPackets.PING,
                    PacketListenerPriority.NORMAL,
                    (event, player, packet) -> calls.incrementAndGet()));
            var encoder = fixture.channel.pipeline().get(CultEncoder.NAME);
            var decoder = fixture.channel.pipeline().get(CultDecoder.NAME);
            var truncated = fixture.id(ClientboundPackets.PING, "ping").writeByte(1);
            String bytes = ByteBufUtil.hexDump(truncated);
            var promise = fixture.channel.writeAndFlush(truncated);
            assertTrue(promise.isSuccess());
            ByteBuf forwarded = fixture.channel.readOutbound();
            assertEquals(bytes, ByteBufUtil.hexDump(forwarded));
            forwarded.release();
            assertEquals(0, truncated.refCnt());
            assertEquals(0, calls.get());
            assertTrue(fixture.channel
                    .writeAndFlush(fixture.id(ClientboundPackets.PING, "ping").writeInt(7))
                    .isSuccess());
            ((ByteBuf) fixture.channel.readOutbound()).release();
            assertEquals(1, calls.get());
            assertTrue(fixture.channel.isOpen());
            assertSame(encoder, fixture.channel.pipeline().get(CultEncoder.NAME));
            assertSame(decoder, fixture.channel.pipeline().get(CultDecoder.NAME));
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProtocolVersion.class, names = "V26_3", mode = EnumSource.Mode.EXCLUDE)
    void unknownRegistryDecodeFailureKeepsTheSameHandlersAndLaterStateUpdates(ProtocolVersion version)
            throws Exception {
        try (var fixture = RecordReceiveFixture.olderHost(version)) {
            var type = fixture.connection.packets().writableType(update());
            var calls = new AtomicInteger();
            fixture.registrar.register(batch -> batch.sendRoute(
                    type, PacketListenerPriority.NORMAL, (event, player, packet) -> calls.incrementAndGet()));
            var original = frame(fixture, type);
            String bytes = ByteBufUtil.hexDump(original);
            installFailingAdapter(fixture, true);
            var encoder = fixture.channel.pipeline().get(CultEncoder.NAME);
            var decoder = fixture.channel.pipeline().get(CultDecoder.NAME);
            assertTrue(fixture.channel.writeAndFlush(original).isSuccess());
            ByteBuf forwarded = fixture.channel.readOutbound();
            assertEquals(bytes, ByteBufUtil.hexDump(forwarded));
            forwarded.release();
            assertEquals(0, original.refCnt());
            assertEquals(0, calls.get());
            assertTrue(fixture.channel.writeAndFlush(frame(fixture, type)).isSuccess());
            ((ByteBuf) fixture.channel.readOutbound()).release();
            assertEquals(1, calls.get());
            assertTrue(fixture.channel.isOpen());
            assertSame(encoder, fixture.channel.pipeline().get(CultEncoder.NAME));
            assertSame(decoder, fixture.channel.pipeline().get(CultDecoder.NAME));
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProtocolVersion.class, names = "V26_3", mode = EnumSource.Mode.EXCLUDE)
    void untrackedPlayValuesForwardWithoutOlderWireDecoding(ProtocolVersion version) throws Exception {
        try (var fixture = RecordReceiveFixture.olderHost(version, false)) {
            var type = fixture.connection.packets().writableType(update());
            var calls = new AtomicInteger();
            fixture.registrar.register(batch -> batch.sendRoute(
                    type, PacketListenerPriority.NORMAL, (event, player, packet) -> calls.incrementAndGet()));
            var original = frame(fixture, type);
            String bytes = ByteBufUtil.hexDump(original);
            var reads = countReads(fixture);
            assertTrue(fixture.channel.writeAndFlush(original).isSuccess());
            ByteBuf forwarded = fixture.channel.readOutbound();
            assertEquals(bytes, ByteBufUtil.hexDump(forwarded));
            forwarded.release();
            assertEquals(0, original.refCnt());
            assertEquals(0, reads.get());
            assertEquals(0, calls.get());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProtocolVersion.class, names = "V26_3", mode = EnumSource.Mode.EXCLUDE)
    void authoredEncodeFailureFailsOnlyThatWriteAndTheNextWriteIsInspected(ProtocolVersion version) throws Exception {
        try (var fixture = RecordReceiveFixture.olderHost(version)) {
            var type = fixture.connection.packets().writableType(update());
            var calls = new AtomicInteger();
            fixture.registrar.register(batch -> batch.sendRoute(
                    type, PacketListenerPriority.NORMAL, (event, player, packet) -> calls.incrementAndGet()));
            installFailingAdapter(fixture, false);
            var encoder = fixture.channel.pipeline().get(CultEncoder.NAME);
            var decoder = fixture.channel.pipeline().get(CultDecoder.NAME);
            var failed = fixture.connection.write(new CultWrite(update(), false));
            assertTrue(failed.isDone());
            assertInstanceOf(ProtocolResolutionException.class, failed.cause());
            assertNull(fixture.channel.readOutbound());
            var next = fixture.connection.write(new CultWrite(update(), false));
            assertTrue(next.isSuccess());
            ((ByteBuf) fixture.channel.readOutbound()).release();
            assertEquals(1, calls.get());
            assertTrue(fixture.channel.isOpen());
            assertSame(encoder, fixture.channel.pipeline().get(CultEncoder.NAME));
            assertSame(decoder, fixture.channel.pipeline().get(CultDecoder.NAME));
        }
    }

    private static WorldPackets.BlockUpdate update() {
        return new WorldPackets.BlockUpdate(new BlockPos(4, 64, 5), 1);
    }

    private static ByteBuf frame(RecordReceiveFixture fixture, PacketType<WorldPackets.BlockUpdate> type) {
        return fixture.connection
                .packets()
                .encode(ConnectionPhase.PLAY, type, update(), UnpooledByteBufAllocator.DEFAULT, fixture.connection)
                .frames()
                .getFirst();
    }

    private static AtomicInteger countReads(RecordReceiveFixture fixture) throws Exception {
        var field = CultConnection.class.getDeclaredField("platform");
        field.setAccessible(true);
        var platform = (PlatformConnection) field.get(fixture.connection);
        var delegate = platform.packetValues(fixture.connection);
        var reads = new AtomicInteger();
        when(platform.packetValues(fixture.connection)).thenReturn(new PacketValueAdapter() {
            @Override
            public Object read(ByteBuf input, ProtocolContext context) {
                reads.incrementAndGet();
                return delegate.read(input, context);
            }

            @Override
            public void write(ByteBuf output, ProtocolContext context, Object packet) {
                delegate.write(output, context, packet);
            }
        });
        return reads;
    }

    private static void installFailingAdapter(RecordReceiveFixture fixture, boolean reading) throws Exception {
        var field = CultConnection.class.getDeclaredField("platform");
        field.setAccessible(true);
        var platform = (PlatformConnection) field.get(fixture.connection);
        var delegate = platform.packetValues(fixture.connection);
        var attempts = new AtomicInteger();
        when(platform.packetValues(fixture.connection)).thenReturn(new PacketValueAdapter() {
            private void failOnce(boolean read) {
                if (read == reading && attempts.getAndIncrement() == 0)
                    ModelRegistryData.load(fixture.connection.getObservedProtocol())
                            .registry("test:unknown_registry");
            }

            @Override
            public Object read(ByteBuf input, ProtocolContext context) {
                failOnce(true);
                return delegate.read(input, context);
            }

            @Override
            public void write(ByteBuf output, ProtocolContext context, Object packet) {
                failOnce(false);
                delegate.write(output, context, packet);
            }
        });
    }
}
