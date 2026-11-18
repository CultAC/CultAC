package ac.cult.cultac.platform.velocity;

import static ac.cult.cultac.platform.velocity.CompressedFrames.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.PacketDispatcher;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.netty.CultEncoder;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.buffer.AbstractByteBuf;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ResourceLeakDetector;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Real frames through the forwarding handlers between stand-ins for Velocity's own, on two embedded channels. Every
 * case checks what the client inflates, the counters, and that every buffer it can see ends released.
 */
class VelocityCompressedForwardTest {
    private static final ProtocolRuntime RUNTIME = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3));
    private static final int THRESHOLD = 256;
    private static ResourceLeakDetector.Level previousLevel;
    private static final List<String> LEAKS = new ArrayList<>();

    @BeforeAll
    static void paranoid() throws Exception {
        previousLevel = ResourceLeakDetector.getLevel();
        drainLeaks();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        leakDetector().setLeakListener((type, records) -> {
            synchronized (LEAKS) {
                LEAKS.add(type + ": " + records);
            }
        });
    }

    @AfterAll
    static void noLeaks() throws Exception {
        try {
            drainLeaks();
            synchronized (LEAKS) {
                assertEquals(List.of(), LEAKS, "the paranoid leak detector reports nothing");
            }
        } finally {
            leakDetector().setLeakListener(null);
            ResourceLeakDetector.setLevel(previousLevel);
        }
    }

    @SuppressWarnings("unchecked")
    private static ResourceLeakDetector<ByteBuf> leakDetector() throws Exception {
        Field field = AbstractByteBuf.class.getDeclaredField("leakDetector");
        field.setAccessible(true);
        return (ResourceLeakDetector<ByteBuf>) field.get(null);
    }

    /** Collected unreleased buffers are reported on later allocations. */
    private static void drainLeaks() throws InterruptedException {
        for (int round = 0; round < 3; round++) {
            System.gc();
            Thread.sleep(20);
            for (int i = 0; i < 64; i++) {
                ByteBufAllocator.DEFAULT.directBuffer(256).release();
                ByteBufAllocator.DEFAULT.heapBuffer(256).release();
                Unpooled.buffer(16).release();
            }
        }
    }

    @Test
    void largeFrameIsForwardedAsTheBackendSentIt() {
        checkForwarded(packet(64 * 1024, 1));
    }

    @Test
    void frameAtTheThresholdIsForwarded() {
        checkForwarded(packet(THRESHOLD, 2));
    }

    private void checkForwarded(byte[] packet) {
        byte[] body = body(packet, THRESHOLD);
        try (var f = new Fixture(Options.forwarding())) {
            f.read(frame(body));
            var frames = read(f.wire(), THRESHOLD);
            assertPackets(List.of(packet), frames);
            assertTrue(frames.getFirst().compressed());
            assertArrayEquals(body, frames.getFirst().body(), "the backend's frame body, unchanged");
            assertEquals(1, f.forwarder.forwarded());
            assertEquals(0, f.forwarder.notMarked() + f.forwarder.indicesChanged() + f.forwarder.guardMismatch());
        }
    }

    @Test
    void frameBelowTheThresholdIsFramedAsToday() {
        byte[] packet = packet(100, 3);
        byte[] read = frame(body(packet, THRESHOLD));
        byte[] today = today(Options.forwarding(), read);
        try (var f = new Fixture(Options.forwarding())) {
            f.read(read);
            byte[] wire = f.wire();
            assertSameBytes(today, wire);
            assertPackets(List.of(packet), read(wire, THRESHOLD));
            assertEquals(1, f.forwarder.framedSmall());
            assertEquals(0, f.forwarder.belowThreshold());
        }
    }

    @Test
    void markedFrameBelowTheClientThresholdIsFramedAsToday() {
        byte[] packet = packet(100, 4);
        var options = Options.forwarding().backendThreshold(64);
        byte[] read = frame(body(packet, 64));
        byte[] today = today(options, read);
        try (var f = new Fixture(options)) {
            f.read(read);
            byte[] wire = f.wire();
            assertSameBytes(today, wire);
            assertPackets(List.of(packet), read(wire, THRESHOLD));
            assertEquals(1, f.forwarder.belowThreshold());
            assertEquals(1, f.forwarder.framedSmall());
            assertEquals(0, f.forwarder.forwarded());
        }
    }

    @Test
    void replacedPacketIsCompressedAgain() {
        byte[] packet = packet(4000, 5);
        byte[] replacement = packet(4100, 6);
        BiConsumer<Fixture, ByteBuf> replace = (f, buf) -> {
            ByteBuf copy = f.track(Unpooled.directBuffer(replacement.length).writeBytes(replacement));
            buf.release();
            f.client.write(copy);
        };
        byte[] read = frame(body(packet, THRESHOLD));
        byte[] today = today(Options.forwarding().onFrame(replace), read);
        try (var f = new Fixture(Options.forwarding().onFrame(replace))) {
            f.read(read);
            byte[] wire = f.wire();
            assertSameBytes(today, wire);
            assertPackets(List.of(replacement), read(wire, THRESHOLD));
            assertEquals(1, f.forwarder.notMarked());
            assertEquals(0, f.forwarder.forwarded());
        }
    }

    @Test
    void frameWrittenAfterItsReadIsCompressedAgain() {
        byte[] packet = packet(3000, 7);
        var held = new ArrayList<ByteBuf>();
        try (var f = new Fixture(Options.forwarding().onFrame((fixture, buf) -> held.add(buf)))) {
            f.read(frame(body(packet, THRESHOLD)));
            assertEquals(1, held.size());
            f.client.writeAndFlush(held.getFirst());
            assertPackets(List.of(packet), read(f.wire(), THRESHOLD));
            assertEquals(1, f.forwarder.notMarked());
            assertEquals(0, f.forwarder.forwarded());
        }
    }

    @Test
    void writtenContentFailsTheGuard() {
        byte[] packet = packet(3000, 8);
        BiConsumer<Fixture, ByteBuf> write = (f, buf) -> {
            buf.setByte(buf.readerIndex() + 100, buf.getByte(buf.readerIndex() + 100) ^ 0x5a);
            f.client.write(buf);
        };
        byte[] changed = packet.clone();
        changed[100] ^= 0x5a;
        try (var f = new Fixture(Options.forwarding().onFrame(write))) {
            f.read(frame(body(packet, THRESHOLD)));
            assertPackets(List.of(changed), read(f.wire(), THRESHOLD));
            assertEquals(1, f.forwarder.guardMismatch());
            assertEquals(0, f.forwarder.forwarded());
        }
        // Without the guard a write that keeps the indices goes unseen: the backend's bytes leave.
        try (var f = new Fixture(Options.forwarding().guard(false).onFrame(write))) {
            f.read(frame(body(packet, THRESHOLD)));
            assertPackets(List.of(packet), read(f.wire(), THRESHOLD));
            assertEquals(1, f.forwarder.forwarded());
        }
    }

    @Test
    void movedReaderIndexIsCompressedAgain() {
        byte[] packet = packet(3000, 9);
        try (var f =
                new Fixture(Options.forwarding().onFrame((fixture, buf) -> fixture.client.write(buf.skipBytes(2))))) {
            f.read(frame(body(packet, THRESHOLD)));
            byte[] rest = new byte[packet.length - 2];
            System.arraycopy(packet, 2, rest, 0, rest.length);
            assertPackets(List.of(rest), read(f.wire(), THRESHOLD));
            assertEquals(1, f.forwarder.indicesChanged());
        }
    }

    @Test
    void indicesMovedByTheSameAmountAreCompressedAgain() {
        byte[] packet = packet(3000, 29);
        byte[] appended = {0x11, 0x22};
        byte[] shifted = Arrays.copyOfRange(packet, 2, packet.length + 2);
        System.arraycopy(appended, 0, shifted, packet.length - 2, 2);
        try (var f = new Fixture(Options.forwarding().guard(false).onFrame((fixture, buf) -> {
            buf.skipBytes(2);
            buf.writeBytes(appended);
            fixture.client.write(buf);
        }))) {
            f.read(frame(body(packet, THRESHOLD)));
            assertPackets(List.of(shifted), read(f.wire(), THRESHOLD));
            assertEquals(1, f.forwarder.indicesChanged());
            assertEquals(0, f.forwarder.forwarded());
        }
    }

    @Test
    void heapCipherGetsHeapFrames() {
        byte[] large = packet(3000, 38);
        byte[] small = packet(40, 39);
        try (var f = new Fixture(Options.forwarding().heapCipher())) {
            f.read(concat(frame(body(large, THRESHOLD)), frame(body(small, THRESHOLD))));
            var wire = new ByteArrayOutputStream();
            for (int i = 0; i < 2; i++) {
                ByteBuf out = f.client.readOutbound();
                try {
                    assertTrue(out.hasArray(), "frame " + i + " is a heap buffer");
                    wire.writeBytes(ByteBufUtil.getBytes(out));
                } finally {
                    out.release();
                }
            }
            assertPackets(List.of(large, small), read(wire.toByteArray(), THRESHOLD));
            assertEquals(1, f.forwarder.forwarded());
            assertEquals(1, f.forwarder.framedSmall());
        }
    }

    @Test
    void frameSplitAcrossReadsIsForwarded() {
        byte[] packet = packet(9000, 10);
        byte[] body = body(packet, THRESHOLD);
        byte[] whole = frame(body);
        assertTrue(whole.length > 300, "the length VarInt has two bytes");
        int middle = whole.length / 2;
        try (var f = new Fixture(Options.forwarding())) {
            f.read(Arrays.copyOfRange(whole, 0, 1));
            f.read(Arrays.copyOfRange(whole, 1, middle));
            f.read(Arrays.copyOfRange(whole, middle, whole.length));
            var frames = read(f.wire(), THRESHOLD);
            assertPackets(List.of(packet), frames);
            assertArrayEquals(body, frames.getFirst().body());
            assertEquals(1, f.forwarder.forwarded());
        }
    }

    @Test
    void severalFramesInOneReadKeepTheirOrder() {
        List<byte[]> packets =
                List.of(packet(70_000, 11), packet(50, 12), packet(300, 13), packet(10, 14), packet(THRESHOLD, 15));
        byte[] read = concat(
                packets.stream().map(packet -> frame(body(packet, THRESHOLD))).toArray(byte[][]::new));
        try (var f = new Fixture(Options.forwarding())) {
            f.read(read);
            assertPackets(packets, read(f.wire(), THRESHOLD));
            assertEquals(3, f.forwarder.forwarded());
            assertEquals(2, f.forwarder.framedSmall());
        }
    }

    @Test
    void uncompressedBackendIsNotLinkedAndFramesAsToday() {
        var options = Options.forwarding().backendThreshold(-1);
        List<byte[]> packets = List.of(packet(3000, 16), packet(40, 17));
        byte[] read =
                concat(packets.stream().map(packet -> frame(body(packet, -1))).toArray(byte[][]::new));
        byte[] today = today(options, read);
        try (var f = new Fixture(options)) {
            assertEquals("backend-uncompressed", f.forwarder.linkStatus());
            assertNull(f.backend.pipeline().get(CompressedKeep.NAME));
            f.read(read);
            byte[] wire = f.wire();
            assertSameBytes(today, wire);
            assertPackets(packets, read(wire, THRESHOLD));
            assertEquals(1, f.forwarder.notMarked());
            assertEquals(1, f.forwarder.framedSmall());
        }
    }

    @Test
    void switchedOffInstallsNothing() {
        assertFalse(Boolean.getBoolean(VelocityCompressedForward.ENABLED_PROPERTY));
        assertNull(VelocityCompressedForward.fromSystemProperties(mock(ProxyServer.class), new Object(), p -> null));
        byte[] read = frame(body(packet(5000, 18), THRESHOLD));
        try (var f = new Fixture(Options.today())) {
            assertNull(f.forwarder);
            for (String name : f.client.pipeline().names()) assertFalse(name.startsWith("cult-compressed"), name);
            for (String name : f.backend.pipeline().names()) assertFalse(name.startsWith("cult-compressed"), name);
            f.read(read);
            assertSameBytes(today(Options.today(), read), f.wire());
        }
    }

    @Test
    void exceptionAfterTheMarkReleasesTheKeptFrameOnce() {
        byte[] packet = packet(3000, 19);
        var held = new ArrayList<ByteBuf>();
        try (var f = new Fixture(Options.forwarding().onFrame((fixture, buf) -> {
            held.add(buf);
            throw new IllegalStateException("handler failure");
        }))) {
            assertThrows(IllegalStateException.class, () -> f.read(frame(body(packet, THRESHOLD))));
            ByteBuf kept = f.frameDecoder.frames.getFirst();
            assertEquals(0, kept.refCnt(), "the kept frame is released with its read");
            assertNull(f.forwarder.markBuf);
            f.client.writeAndFlush(held.getFirst());
            assertPackets(List.of(packet), read(f.wire(), THRESHOLD));
            assertEquals(1, f.forwarder.notMarked());
        }
    }

    @Test
    void clientClosedMidRead() {
        byte[] packet = packet(3000, 20);
        try (var f = new Fixture(Options.forwarding().onFrame((fixture, buf) -> {
            fixture.client.close();
            fixture.delayedWrite(buf);
        }))) {
            f.read(concat(frame(body(packet, THRESHOLD)), frame(body(packet, THRESHOLD))));
            assertEquals(0, f.wire().length);
        }
    }

    @Test
    void backendClosedMidRead() {
        byte[] packet = packet(3000, 21);
        try (var f = new Fixture(Options.forwarding().onFrame((fixture, buf) -> {
            fixture.backend.close();
            fixture.delayedWrite(buf);
        }))) {
            f.read(frame(body(packet, THRESHOLD)));
            // A closed channel completes no read, so Velocity's flush on read completion does not run.
            f.client.flush();
            assertPackets(List.of(packet), read(f.wire(), THRESHOLD));
            assertEquals(1, f.forwarder.forwarded());
        }
    }

    @Test
    void nestedReadOnASecondBackendRestoresTheOuterMark() {
        byte[] outer = packet(3000, 22);
        byte[] inner = packet(2000, 23);
        var nested = new ArrayList<Fixture.Backend>();
        try (var f = new Fixture(Options.forwarding().onFrame((fixture, buf) -> {
            if (buf.readableBytes() == outer.length) nested.getFirst().read(frame(body(inner, THRESHOLD)));
            fixture.delayedWrite(buf);
        }))) {
            var second = f.backend(Options.forwarding());
            second.channel
                    .pipeline()
                    .addBefore(
                            VelocityCompressedForward.COMPRESSION_DECODER,
                            CompressedKeep.NAME,
                            new CompressedKeep(f.forwarder));
            var keep = (CompressedKeep) second.channel.pipeline().get(CompressedKeep.NAME);
            second.channel
                    .pipeline()
                    .addAfter(
                            VelocityCompressedForward.COMPRESSION_DECODER,
                            CompressedMark.NAME,
                            new CompressedMark(keep, f.forwarder));
            nested.add(second);
            f.read(frame(body(outer, THRESHOLD)));
            assertPackets(List.of(inner, outer), read(f.wire(), THRESHOLD));
            assertEquals(2, f.forwarder.forwarded());
            assertNull(f.forwarder.markBuf);
        }
    }

    @Test
    void removedCompressionEncoderDisablesForwarding() {
        try (var f = new Fixture(Options.forwarding())) {
            f.client.pipeline().remove(CompressedForwarder.COMPRESSION_ENCODER);
            ByteBuf raw = f.track(Unpooled.directBuffer().writeBytes(packet(400, 24)));
            f.client.writeAndFlush(raw);
            assertEquals("compression-removed", f.forwarder.status());
            ByteBuf out = f.client.readOutbound();
            assertSame(raw, out, "later frames pass on untouched");
            out.release();
        }
    }

    /** A translating decoder after the mark frees the inflated buffer; a same-size allocation must not take it over. */
    @Test
    void copyAfterTheMarkCannotRecycleTheMarkedBuffer() {
        var level = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
        try {
            for (int round = 0; round < 8; round++) {
                byte[] packet = packet(3000, 30 + round);
                byte[] reencoded = packet(3000, 60 + round);
                try (var f =
                        new Fixture(Options.forwarding().guard(false).copying().onFrame((fixture, buf) -> {
                            ByteBuf fresh =
                                    fixture.track(fixture.backend.alloc().directBuffer(reencoded.length));
                            fresh.writeBytes(reencoded);
                            buf.release();
                            fixture.client.write(fresh);
                        }))) {
                    f.read(frame(body(packet, THRESHOLD)));
                    assertPackets(List.of(reencoded), read(f.wire(), THRESHOLD));
                    assertEquals(0, f.forwarder.forwarded());
                }
            }
        } finally {
            ResourceLeakDetector.setLevel(level);
        }
    }

    @Test
    void positionsAndDetach() {
        for (boolean via : new boolean[] {false, true}) {
            try (var f = new Fixture(Options.forwarding().via(via))) {
                var client = f.client.pipeline().names();
                int at = client.indexOf(CompressedForwarder.COMPRESSION_ENCODER);
                assertEquals(CompressedForwarder.NAME, client.get(at + 1));
                assertTrue(at > client.indexOf("cipher-encoder"));
                assertTrue(client.indexOf(CompressedForwarder.NAME) < client.indexOf(CultEncoder.NAME));
                if (via) assertTrue(client.indexOf(CompressedForwarder.NAME) < client.indexOf("via-encoder"));
                var backend = f.backend.pipeline().names();
                int decoder = backend.indexOf(VelocityCompressedForward.COMPRESSION_DECODER);
                assertEquals(CompressedKeep.NAME, backend.get(decoder - 1));
                assertEquals(CompressedMark.NAME, backend.get(decoder + 1));
                assertTrue(backend.indexOf("read-timeout") < backend.indexOf(CompressedKeep.NAME));
                assertEquals("linked", f.forwarder.linkStatus());

                VelocityCompressedForward.uninstall(f.client);
                assertNull(f.client.pipeline().get(CompressedForwarder.NAME));
                assertNull(f.backend.pipeline().get(CompressedKeep.NAME));
                assertNull(f.backend.pipeline().get(CompressedMark.NAME));
            }
        }
    }

    @Test
    void secondBackendReplacesTheLink() {
        try (var f = new Fixture(Options.forwarding())) {
            var second = f.backend(Options.forwarding());
            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), second.channel);
            assertNull(f.backend.pipeline().get(CompressedKeep.NAME));
            assertNull(f.backend.pipeline().get(CompressedMark.NAME));
            assertNotNull(second.channel.pipeline().get(CompressedKeep.NAME));
            byte[] packet = packet(5000, 25);
            second.read(frame(body(packet, THRESHOLD)));
            f.read(frame(body(packet, THRESHOLD)));
            assertPackets(List.of(packet, packet), read(f.wire(), THRESHOLD));
            assertEquals(1, f.forwarder.forwarded());
            assertEquals(1, f.forwarder.notMarked());
        }
    }

    @Test
    void backendOnAnotherLoopIsNotLinked() {
        try (var f = new Fixture(Options.forwarding().unlinked())) {
            VelocityCompressedForward.link(f.forwarder, f.client, f.backend);
            assertEquals("loop", f.forwarder.linkStatus());
            assertNull(f.backend.pipeline().get(CompressedKeep.NAME));
            byte[] packet = packet(5000, 26);
            byte[] read = frame(body(packet, THRESHOLD));
            f.read(read);
            byte[] wire = f.wire();
            assertSameBytes(today(Options.forwarding(), read), wire);
            assertEquals(1, f.forwarder.notMarked());
        }
    }

    @Test
    void handlerBetweenTheForwarderAndTheEncoderDisablesForwarding() {
        try (var f = new Fixture(Options.forwarding())) {
            var seen = new ArrayList<Integer>();
            f.client
                    .pipeline()
                    .addAfter(CompressedForwarder.COMPRESSION_ENCODER, "tap", new ChannelOutboundHandlerAdapter() {
                        @Override
                        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                            seen.add(((ByteBuf) msg).readableBytes());
                            ctx.write(msg, promise);
                        }
                    });
            byte[] small = packet(30, 27);
            byte[] large = packet(3000, 28);
            f.read(concat(frame(body(small, THRESHOLD)), frame(body(large, THRESHOLD))));
            assertPackets(List.of(small, large), read(f.wire(), THRESHOLD));
            assertEquals(List.of(small.length, large.length), seen, "the inserted handler sees every frame");
            assertEquals("adjacency", f.forwarder.status());
            assertNull(f.backend.pipeline().get(CompressedKeep.NAME), "the link is removed with it");
            assertEquals(0, f.forwarder.forwarded() + f.forwarder.framedSmall());
        }
        try (var f = new Fixture(Options.forwarding().unlinked())) {
            f.client
                    .pipeline()
                    .addAfter(CompressedForwarder.COMPRESSION_ENCODER, "tap", new ChannelOutboundHandlerAdapter());
            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), f.backend);
            assertEquals("adjacency", f.forwarder.status());
            assertNull(f.backend.pipeline().get(CompressedKeep.NAME));
        }
    }

    @Test
    void thresholdIsReadFromTheClientsEncoder() {
        var channel = new EmbeddedChannel();
        try {
            var pipeline = channel.pipeline();
            assertNull(VelocityCompressedForward.sentThreshold(pipeline), "no encoder");
            pipeline.addLast(CompressedForwarder.COMPRESSION_ENCODER, new ChannelOutboundHandlerAdapter());
            assertNull(VelocityCompressedForward.sentThreshold(pipeline), "an encoder without the field");
            pipeline.replace(
                    CompressedForwarder.COMPRESSION_ENCODER,
                    CompressedForwarder.COMPRESSION_ENCODER,
                    new CompressEncoder(512));
            assertEquals(512, VelocityCompressedForward.sentThreshold(pipeline));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void installRefusesWithoutCompression() {
        var channel = new EmbeddedChannel();
        try {
            assertNull(VelocityCompressedForward.install(channel.pipeline(), THRESHOLD, true, true, false));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** The client's bytes for {@code read} through the same pipeline without the forwarding handlers. */
    private static byte[] today(Options options, byte[] read) {
        try (var f = new Fixture(options.withoutForwarding())) {
            f.read(read);
            return f.wire();
        }
    }

    record Options(
            boolean forward,
            boolean guard,
            boolean link,
            int backendThreshold,
            boolean via,
            boolean copyAfterMark,
            boolean heap,
            BiConsumer<Fixture, ByteBuf> onFrame) {
        static Options forwarding() {
            return new Options(true, true, true, THRESHOLD, false, false, false, null);
        }

        static Options today() {
            return forwarding().withoutForwarding();
        }

        Options withoutForwarding() {
            return new Options(false, guard, false, backendThreshold, via, copyAfterMark, heap, onFrame);
        }

        Options guard(boolean value) {
            return new Options(forward, value, link, backendThreshold, via, copyAfterMark, heap, onFrame);
        }

        Options unlinked() {
            return new Options(forward, guard, false, backendThreshold, via, copyAfterMark, heap, onFrame);
        }

        Options backendThreshold(int value) {
            return new Options(forward, guard, link, value, via, copyAfterMark, heap, onFrame);
        }

        Options via(boolean value) {
            return new Options(forward, guard, link, backendThreshold, value, copyAfterMark, heap, onFrame);
        }

        Options copying() {
            return new Options(forward, guard, link, backendThreshold, via, true, heap, onFrame);
        }

        Options heapCipher() {
            return new Options(forward, guard, link, backendThreshold, via, copyAfterMark, true, onFrame);
        }

        Options onFrame(BiConsumer<Fixture, ByteBuf> value) {
            return new Options(forward, guard, link, backendThreshold, via, copyAfterMark, heap, value);
        }
    }

    static final class Fixture implements AutoCloseable {
        final EmbeddedChannel client = new EmbeddedChannel();
        final EmbeddedChannel backend;
        final FrameDecoder frameDecoder;
        final CompressedForwarder forwarder;
        final CultConnection connection;
        private final List<ByteBuf> tracked = new ArrayList<>();
        private final List<Backend> backends = new ArrayList<>();
        private final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        private final Options options;

        Fixture(Options options) {
            this.options = options;
            var pipeline = client.pipeline();
            pipeline.addLast("cipher-encoder", new CipherEncoder());
            pipeline.addLast(CompressedForwarder.COMPRESSION_ENCODER, new CompressEncoder(THRESHOLD));
            if (options.via()) {
                pipeline.addLast("via-decoder", new ChannelInboundHandlerAdapter());
                pipeline.addLast("via-encoder", new ChannelOutboundHandlerAdapter());
            }
            pipeline.addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());
            pipeline.addLast("minecraft-encoder", new ChannelOutboundHandlerAdapter());
            connection = new CultConnection(client, new PacketDispatcher(RUNTIME), ignored -> null);
            connection.phase(PacketDirection.SERVERBOUND, ConnectionPhase.PLAY);
            connection.phase(PacketDirection.CLIENTBOUND, ConnectionPhase.PLAY);
            VelocityPacketPipeline.install(connection);
            forwarder = options.forward()
                    ? VelocityCompressedForward.install(pipeline, THRESHOLD, options.guard(), true, options.heap())
                    : null;
            var first = backend(options);
            backend = first.channel;
            frameDecoder = first.frames;
            if (options.link()) VelocityCompressedForward.place(forwarder, pipeline, backend);
        }

        /** A backend channel with the stand-in handlers, writing to this client. */
        Backend backend(Options options) {
            var channel = new EmbeddedChannel();
            var frames = new FrameDecoder();
            var pipeline = channel.pipeline();
            pipeline.addLast("frame-decoder", frames);
            pipeline.addLast("read-timeout", new ChannelInboundHandlerAdapter());
            CompressDecoder decoder = null;
            if (options.backendThreshold() >= 0) {
                decoder = new CompressDecoder(options.backendThreshold());
                pipeline.addLast(VelocityCompressedForward.COMPRESSION_DECODER, decoder);
            }
            if (options.copyAfterMark()) pipeline.addLast("via-decoder", new CopyDecoder());
            pipeline.addLast("minecraft-decoder", new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    ByteBuf buf = (ByteBuf) msg;
                    try {
                        ctx.fireChannelRead(buf.retain());
                    } finally {
                        buf.release();
                    }
                }
            });
            pipeline.addLast("handler", new ChannelDuplexHandler() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    ByteBuf buf = (ByteBuf) msg;
                    try {
                        if (options.onFrame() == null) delayedWrite(buf.retain());
                        else options.onFrame().accept(Fixture.this, buf.retain());
                    } finally {
                        buf.release();
                    }
                }

                @Override
                public void channelReadComplete(ChannelHandlerContext ctx) {
                    if (client.isActive()) client.flush();
                }
            });
            var created = new Backend(channel, frames, decoder);
            backends.add(created);
            return created;
        }

        /** Velocity's delayed write: written while the client is active, else released. */
        void delayedWrite(ByteBuf buf) {
            if (client.isActive()) client.write(buf);
            else buf.release();
        }

        void read(byte[] bytes) {
            backends.getFirst().read(bytes);
        }

        ByteBuf track(ByteBuf buf) {
            tracked.add(buf);
            return buf;
        }

        /** Everything the client has been sent so far, encrypted; each buffer is released once read. */
        byte[] wire() {
            Object out;
            while ((out = client.readOutbound()) != null) {
                ByteBuf buf = (ByteBuf) out;
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                wire.writeBytes(bytes);
                assertTrue(buf.release(), "an outbound buffer is released by its reader");
            }
            return wire.toByteArray();
        }

        @Override
        public void close() {
            var failures = new ArrayList<Throwable>();
            List<Runnable> steps = new ArrayList<>();
            steps.add(this::wire);
            steps.add(client::finishAndReleaseAll);
            for (var created : backends) steps.add(created::close);
            steps.add(() -> {
                for (ByteBuf buf : tracked) assertEquals(0, buf.refCnt(), "a test buffer is released");
            });
            steps.add(() -> {
                if (forwarder != null) assertNull(forwarder.markBuf, "no mark outlives its read");
            });
            for (Runnable step : steps) {
                try {
                    step.run();
                } catch (RuntimeException | AssertionError failure) {
                    failures.add(failure);
                }
            }
            if (failures.isEmpty()) return;
            Throwable first = failures.getFirst();
            for (Throwable later : failures.subList(1, failures.size())) first.addSuppressed(later);
            if (first instanceof AssertionError error) throw error;
            throw (RuntimeException) first;
        }

        final class Backend {
            final EmbeddedChannel channel;
            final FrameDecoder frames;
            final CompressDecoder decoder;
            private final List<ByteBuf> inputs = new ArrayList<>();

            Backend(EmbeddedChannel channel, FrameDecoder frames, CompressDecoder decoder) {
                this.channel = channel;
                this.frames = frames;
                this.decoder = decoder;
            }

            void read(byte[] bytes) {
                ByteBuf input = Unpooled.wrappedBuffer(bytes);
                inputs.add(input);
                channel.writeInbound(input);
            }

            void close() {
                channel.finishAndReleaseAll();
                for (ByteBuf input : inputs) assertEquals(0, input.refCnt(), "a backend read is released");
                for (ByteBuf frame : frames.frames) assertEquals(0, frame.refCnt(), "a kept frame is released");
                if (decoder != null)
                    for (ByteBuf buf : decoder.inflated) assertEquals(0, buf.refCnt(), "an inflated frame is released");
            }
        }
    }
}
