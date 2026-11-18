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
import ac.cult.cultac.utils.anticheat.LogUtil;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.buffer.AbstractByteBuf;
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import java.io.ByteArrayOutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.zip.Adler32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;

/**
 * Real frames through the forwarding handlers between stand-ins for Velocity's own, on two embedded channels. Every
 * case checks what the client inflates, the counters, and that every buffer it can see ends released.
 */
class VelocityCompressedForwardTest {
    private static final ProtocolRuntime RUNTIME = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3));
    private static final int THRESHOLD = 256;
    private static final String DECODER = CompressDecoder.class.getName();
    private static ResourceLeakDetector.Level previousLevel;
    private static final List<String> LEAKS = new ArrayList<>();
    private static Set<Class<?>> previousAllocators;

    @BeforeAll
    static void paranoid() throws Exception {
        previousAllocators = CompressedKeep.allocators;
        var allocators = new HashSet<>(previousAllocators);
        allocators.add(RecordingAllocator.class);
        CompressedKeep.allocators = Set.copyOf(allocators);
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
            CompressedKeep.allocators = previousAllocators;
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
        assertRecompressedDiffers(body);
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
            assertEquals(0, f.forwarder.belowThreshold(), "a frame below the client's threshold is not held");
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
        try (var f = new Fixture(Options.forwarding().onFrame((fixture, buf) -> {
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
        assertRecompressedDiffers(body);
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
            var mark = new CompressedMark(keep, f.forwarder);
            second.channel
                    .pipeline()
                    .addAfter(VelocityCompressedForward.COMPRESSION_DECODER, CompressedMark.NAME, mark);
            var p = second.channel.pipeline();
            keep.attach(
                    p.context(keep),
                    p.context(VelocityCompressedForward.COMPRESSION_DECODER),
                    p.context(mark),
                    VelocityCompressedForward.limiterReader(second.decoder));
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
                try (var f = new Fixture(Options.forwarding().copying().onFrame((fixture, buf) -> {
                    ByteBuf fresh = fixture.track(fixture.backend.alloc().directBuffer(reencoded.length));
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
            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), second.channel, DECODER);
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
            VelocityCompressedForward.link(f.forwarder, f.client, f.backend, DECODER);
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
            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), f.backend, DECODER);
            assertEquals("adjacency", f.forwarder.status());
            assertNull(f.backend.pipeline().get(CompressedKeep.NAME));
        }
    }

    @Test
    void onlyTheNamedEncoderClassGetsAForwarder() {
        String standIn = CompressEncoder.class.getName();
        var channel = new EmbeddedChannel();
        try {
            var pipeline = channel.pipeline();
            var compressor = new SharedCompressor();
            pipeline.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(512, compressor));
            assertNull(VelocityCompressedForward.thresholdReader(null, standIn), "no encoder");
            assertNull(VelocityCompressedForward.install(pipeline, standIn, DECODER, true, false, true), "no encoder");
            pipeline.addLast(CompressedForwarder.COMPRESSION_ENCODER, new OtherEncoder(512));
            assertNull(
                    VelocityCompressedForward.thresholdReader(
                            pipeline.get(CompressedForwarder.COMPRESSION_ENCODER), standIn),
                    "another class, same field");
            assertNull(VelocityCompressedForward.install(pipeline, standIn, DECODER, true, false, true));
            assertNull(pipeline.get(CompressedForwarder.NAME));
            pipeline.replace(
                    CompressedForwarder.COMPRESSION_ENCODER,
                    CompressedForwarder.COMPRESSION_ENCODER,
                    new CompressEncoder(512, true, compressor));
            assertNull(
                    VelocityCompressedForward.install(
                            pipeline, VelocityCompressedForward.VELOCITY_ENCODER, DECODER, true, false, true),
                    "the stand-in is not Velocity's encoder");
            assertNull(pipeline.get(CompressedForwarder.NAME));
            var forwarder = VelocityCompressedForward.install(pipeline, standIn, DECODER, true, false, true);
            assertNotNull(forwarder);
            assertEquals(512, forwarder.threshold(), "the encoder's own threshold");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void installRefusesWithoutCompression() {
        var channel = new EmbeddedChannel();
        try {
            assertNull(VelocityCompressedForward.install(
                    channel.pipeline(), CompressEncoder.class.getName(), DECODER, true, false, true));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void negativeInstallThresholdInstallsNothing() {
        var channel = new EmbeddedChannel();
        try {
            var pipeline = channel.pipeline();
            var compressor = new SharedCompressor();
            pipeline.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(-1, compressor));
            pipeline.addLast(CompressedForwarder.COMPRESSION_ENCODER, new CompressEncoder(-1, true, compressor));
            assertNull(VelocityCompressedForward.install(
                    pipeline, CompressEncoder.class.getName(), DECODER, true, false, true));
            assertNull(pipeline.get(CompressedForwarder.NAME));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void onlyTheNamedDecoderClassIsLinked() {
        try (var f = new Fixture(Options.forwarding().unlinked())) {
            VelocityCompressedForward.place(
                    f.forwarder, f.client.pipeline(), f.backend, VelocityCompressedForward.VELOCITY_DECODER);
            assertEquals("backend-decoder", f.forwarder.linkStatus());
            for (String name : f.backend.pipeline().names()) assertFalse(name.startsWith("cult-compressed"), name);
            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), f.backend, DECODER);
            assertEquals("linked", f.forwarder.linkStatus());
        }
    }

    @Test
    void handlerBetweenTheDecoderAndTheMarkUnlinks() {
        byte[] packet = packet(3000, 45);
        byte[] changed = packet.clone();
        changed[100] ^= 0x5a;
        BiConsumer<Fixture, ChannelPipeline> rewrite = (f, pipeline) -> pipeline.addAfter(
                VelocityCompressedForward.COMPRESSION_DECODER, "rewrite", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ByteBuf in = (ByteBuf) msg;
                        int n = in.readableBytes();
                        ByteBuf out = f.track(ctx.alloc().directBuffer(n));
                        out.writeBytes(in, in.readerIndex(), n);
                        out.setByte(100, out.getByte(100) ^ 0x5a);
                        in.release();
                        ctx.fireChannelRead(out);
                    }
                });
        checkDetached(packet, rewrite, changed);
    }

    @Test
    void handlerBetweenTheKeepAndTheDecoderUnlinks() {
        byte[] packet = packet(3000, 46);
        byte[] replacement = packet(3000, 47);
        byte[] replacementBody = body(replacement, THRESHOLD);
        BiConsumer<Fixture, ChannelPipeline> replace = (f, pipeline) -> pipeline.addBefore(
                VelocityCompressedForward.COMPRESSION_DECODER, "replace", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ((ByteBuf) msg).release();
                        ByteBuf frame = f.track(ctx.alloc().directBuffer(replacementBody.length));
                        ctx.fireChannelRead(frame.writeBytes(replacementBody));
                    }
                });
        checkDetached(packet, replace, replacement);
    }

    /** Two frames of {@code packet} through {@code insert}'s backend, checked as {@link #checkSubstituted}. */
    private void checkDetached(byte[] packet, BiConsumer<Fixture, ChannelPipeline> insert, byte[] expected) {
        byte[] read = concat(frame(body(packet, THRESHOLD)), frame(body(packet, THRESHOLD)));
        checkSubstituted(read, insert, List.of(expected, expected));
    }

    /** {@code read} through a backend {@code insert} changes after the link decodes as switched off, unforwarded. */
    private void checkSubstituted(byte[] read, BiConsumer<Fixture, ChannelPipeline> insert, List<byte[]> expected) {
        byte[] today;
        try (var f = new Fixture(Options.today())) {
            insert.accept(f, f.backend.pipeline());
            f.read(read);
            today = f.wire();
        }
        assertPackets(expected, read(today, THRESHOLD));
        try (var f = new Fixture(Options.forwarding())) {
            insert.accept(f, f.backend.pipeline());
            f.read(read);
            byte[] wire = f.wire();
            assertSameBytes(today, wire);
            assertPackets(expected, read(wire, THRESHOLD));
            assertEquals(0, f.forwarder.forwarded());
            assertEquals(expected.size(), f.forwarder.notMarked());
            assertEquals("backend-adjacency", f.forwarder.linkStatus());
            assertEquals("on", f.forwarder.status());
            assertNull(f.backend.pipeline().get(CompressedKeep.NAME));
            assertNull(f.backend.pipeline().get(CompressedMark.NAME));
        }
    }

    /** Copies the inflated packet with one byte changed, removes itself, and fires the copy. */
    private static final class SelfRemovingRewrite extends ChannelInboundHandlerAdapter {
        private final Fixture fixture;

        SelfRemovingRewrite(Fixture fixture) {
            this.fixture = fixture;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf in = (ByteBuf) msg;
            int n = in.readableBytes();
            ByteBuf out = fixture.track(ctx.alloc().directBuffer(n));
            out.writeBytes(in, in.readerIndex(), n);
            out.setByte(100, out.getByte(100) ^ 0x5a);
            in.release();
            ctx.pipeline().remove(this);
            ctx.fireChannelRead(out);
        }
    }

    @Test
    void selfRemovingRewriteAfterTheDecoderIsNotForwarded() {
        byte[] packet = packet(3000, 49);
        byte[] changed = packet.clone();
        changed[100] ^= 0x5a;
        checkSubstituted(
                frame(body(packet, THRESHOLD)),
                (f, pipeline) -> pipeline.addAfter(
                        VelocityCompressedForward.COMPRESSION_DECODER, "rewrite", new SelfRemovingRewrite(f)),
                List.of(changed));
    }

    @Test
    void selfRemovingReplaceBeforeTheDecoderIsNotForwarded() {
        byte[] packet = packet(3000, 50);
        byte[] replacement = packet(3000, 51);
        byte[] replacementBody = body(replacement, THRESHOLD);
        checkSubstituted(
                frame(body(packet, THRESHOLD)),
                (f, pipeline) -> pipeline.addBefore(
                        VelocityCompressedForward.COMPRESSION_DECODER, "replace", new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                ((ByteBuf) msg).release();
                                ByteBuf frame = f.track(ctx.alloc().directBuffer(replacementBody.length));
                                ctx.pipeline().remove(this);
                                ctx.fireChannelRead(frame.writeBytes(replacementBody));
                            }
                        }),
                List.of(replacement));
    }

    @Test
    void heldFrameFiredOnSelfRemovalIsNotForwarded() {
        byte[] a = packet(3000, 52);
        byte[] b = packet(3000, 53);
        checkSubstituted(
                concat(frame(body(a, THRESHOLD)), frame(body(b, THRESHOLD))),
                (f, pipeline) -> pipeline.addAfter(
                        VelocityCompressedForward.COMPRESSION_DECODER, "hold", new ChannelInboundHandlerAdapter() {
                            private ByteBuf held;

                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                if (held == null) {
                                    held = (ByteBuf) msg;
                                    return;
                                }
                                ByteBuf first = held;
                                held = null;
                                ctx.pipeline().remove(this);
                                ctx.fireChannelRead(first);
                                ctx.fireChannelRead(msg);
                            }
                        }),
                List.of(a, b));
    }

    @Test
    void handlerAddedDuringAReadThatRemovesItselfIsNotForwarded() {
        byte[] packet = packet(3000, 54);
        byte[] changed = packet.clone();
        changed[100] ^= 0x5a;
        checkSubstituted(
                concat(frame(body(packet, THRESHOLD)), frame(body(packet, THRESHOLD))),
                (f, pipeline) -> pipeline.addAfter("frame-decoder", "adder", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ctx.pipeline()
                                .addAfter(
                                        VelocityCompressedForward.COMPRESSION_DECODER,
                                        "rewrite",
                                        new SelfRemovingRewrite(f));
                        ctx.fireChannelRead(msg);
                    }
                }),
                List.of(changed, changed));
    }

    @Test
    void ownExecutorHandlerAfterTheDecoderUnlinks() throws Exception {
        byte[] packet = packet(3000, 55);
        byte[] changed = packet.clone();
        changed[100] ^= 0x5a;
        var group = new DefaultEventExecutorGroup(1);
        try {
            for (boolean forward : new boolean[] {false, true}) {
                var latch = new CountDownLatch(1);
                try (var f = new Fixture(forward ? Options.forwarding() : Options.today())) {
                    f.backend
                            .pipeline()
                            .addAfter(
                                    group,
                                    VelocityCompressedForward.COMPRESSION_DECODER,
                                    "rewrite",
                                    new ChannelInboundHandlerAdapter() {
                                        @Override
                                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                            ByteBuf in = (ByteBuf) msg;
                                            int at = in.readerIndex() + 100;
                                            in.setByte(at, in.getByte(at) ^ 0x5a);
                                            ctx.fireChannelRead(in);
                                        }

                                        @Override
                                        public void channelReadComplete(ChannelHandlerContext ctx) {
                                            ctx.fireChannelReadComplete();
                                            latch.countDown();
                                        }
                                    });
                    f.read(frame(body(packet, THRESHOLD)));
                    assertTrue(latch.await(5, TimeUnit.SECONDS));
                    f.backend.runPendingTasks();
                    f.client.flush();
                    assertPackets(List.of(changed), read(f.wire(), THRESHOLD));
                    if (!forward) continue;
                    assertEquals(0, f.forwarder.forwarded());
                    assertEquals(1, f.forwarder.notMarked());
                    assertEquals("backend-adjacency", f.forwarder.linkStatus());
                    assertNull(f.backend.pipeline().get(CompressedKeep.NAME));
                    assertNull(f.backend.pipeline().get(CompressedMark.NAME));
                }
            }
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).await();
        }
    }

    @Test
    void twoFramesFiredFromOneFrameHeadSideOfTheKeepAreForwarded() {
        byte[] a = packet(3000, 56);
        byte[] b = packet(4000, 57);
        byte[] bodyA = body(a, THRESHOLD);
        byte[] bodyB = body(b, THRESHOLD);
        BiConsumer<Fixture, ChannelPipeline> split =
                (f, pipeline) -> pipeline.addAfter("frame-decoder", "split", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ctx.fireChannelRead(msg);
                        ByteBuf second = f.track(ctx.alloc().directBuffer(bodyB.length));
                        ctx.fireChannelRead(second.writeBytes(bodyB));
                    }
                });
        byte[] read = frame(bodyA);
        try (var f = new Fixture(Options.today())) {
            split.accept(f, f.backend.pipeline());
            f.read(read);
            assertPackets(List.of(a, b), read(f.wire(), THRESHOLD));
        }
        try (var f = new Fixture(Options.forwarding())) {
            split.accept(f, f.backend.pipeline());
            f.read(read);
            var frames = read(f.wire(), THRESHOLD);
            assertPackets(List.of(a, b), frames);
            assertArrayEquals(bodyA, frames.get(0).body());
            assertArrayEquals(bodyB, frames.get(1).body());
            assertEquals(2, f.forwarder.forwarded());
            assertEquals("linked", f.forwarder.linkStatus());
        }
    }

    @Test
    void earlierBufferFiredTailSideOfTheMarkIsNotMarked() {
        byte[] a = packet(3000, 58);
        byte[] b = packet(3000, 59);
        BiConsumer<Fixture, ChannelPipeline> hold =
                (f, pipeline) -> pipeline.addBefore("minecraft-decoder", "hold", new ChannelInboundHandlerAdapter() {
                    private ByteBuf held;

                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        if (held == null) {
                            held = (ByteBuf) msg;
                            return;
                        }
                        ByteBuf first = held;
                        held = null;
                        ctx.fireChannelRead(first);
                        ctx.fireChannelRead(msg);
                    }
                });
        byte[] read = concat(frame(body(a, THRESHOLD)), frame(body(b, THRESHOLD)));
        byte[] today;
        try (var f = new Fixture(Options.today())) {
            hold.accept(f, f.backend.pipeline());
            f.read(read);
            today = f.wire();
        }
        assertPackets(List.of(a, b), read(today, THRESHOLD));
        try (var f = new Fixture(Options.forwarding())) {
            hold.accept(f, f.backend.pipeline());
            f.read(read);
            assertPackets(List.of(a, b), read(f.wire(), THRESHOLD));
            assertEquals(1, f.forwarder.forwarded());
            assertEquals(1, f.forwarder.notMarked());
            assertEquals("linked", f.forwarder.linkStatus());
        }
    }

    @Test
    void secondBufferThroughTheDecoderContextIsNotMarked() {
        byte[] packet = packet(3000, 60);
        byte[] other = packet(3000, 61);
        var done = new ArrayList<Fixture>();
        BiConsumer<Fixture, ByteBuf> again = (f, buf) -> {
            f.delayedWrite(buf);
            if (done.contains(f)) return;
            done.add(f);
            ByteBuf second =
                    f.track(f.backend.alloc().directBuffer(other.length).writeBytes(other));
            f.backend
                    .pipeline()
                    .context(VelocityCompressedForward.COMPRESSION_DECODER)
                    .fireChannelRead(second);
        };
        byte[] read = frame(body(packet, THRESHOLD));
        assertPackets(List.of(packet, other), read(today(Options.forwarding().onFrame(again), read), THRESHOLD));
        try (var f = new Fixture(Options.forwarding().onFrame(again))) {
            f.read(read);
            assertPackets(List.of(packet, other), read(f.wire(), THRESHOLD));
            assertEquals(1, f.forwarder.forwarded());
            assertEquals(1, f.forwarder.notMarked());
            assertEquals("linked", f.forwarder.linkStatus());
        }
    }

    @Test
    void bufferFiredAfterADecoderFailureIsNotMarked() {
        byte[] other = packet(3000, 62);
        byte[] broken = body(packet(3000, 63), THRESHOLD);
        broken[2] ^= (byte) 0xff;
        BiConsumer<Fixture, ChannelPipeline> refire =
                (f, pipeline) -> pipeline.addLast("failure", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        ByteBuf second =
                                f.track(ctx.alloc().directBuffer(other.length).writeBytes(other));
                        ctx.pipeline()
                                .context(VelocityCompressedForward.COMPRESSION_DECODER)
                                .fireChannelRead(second);
                    }
                });
        byte[] read = frame(broken);
        try (var f = new Fixture(Options.today())) {
            refire.accept(f, f.backend.pipeline());
            f.read(read);
            assertPackets(List.of(other), read(f.wire(), THRESHOLD));
        }
        try (var f = new Fixture(Options.forwarding())) {
            refire.accept(f, f.backend.pipeline());
            f.read(read);
            assertPackets(List.of(other), read(f.wire(), THRESHOLD));
            assertEquals(0, f.forwarder.forwarded());
            assertEquals(1, f.forwarder.notMarked());
            assertEquals("linked", f.forwarder.linkStatus());
        }
    }

    @Test
    void keepOrMarkReplacedByNameUnlinks() {
        byte[] packet = packet(3000, 64);
        for (boolean replaceKeep : new boolean[] {true, false}) {
            try (var f = new Fixture(Options.forwarding())) {
                f.read(frame(body(packet, THRESHOLD)));
                assertEquals(1, f.forwarder.forwarded());
                var pipeline = f.backend.pipeline();
                var keep = (CompressedKeep) pipeline.get(CompressedKeep.NAME);
                if (replaceKeep) {
                    pipeline.remove(CompressedKeep.NAME);
                    pipeline.addBefore(
                            VelocityCompressedForward.COMPRESSION_DECODER,
                            CompressedKeep.NAME,
                            new CompressedKeep(f.forwarder));
                } else {
                    pipeline.remove(CompressedMark.NAME);
                    pipeline.addAfter(
                            VelocityCompressedForward.COMPRESSION_DECODER,
                            CompressedMark.NAME,
                            new CompressedMark(keep, f.forwarder));
                }
                f.read(frame(body(packet, THRESHOLD)));
                assertPackets(List.of(packet, packet), read(f.wire(), THRESHOLD));
                assertEquals(1, f.forwarder.forwarded());
                assertEquals(1, f.forwarder.notMarked());
                assertEquals("backend-adjacency", f.forwarder.linkStatus());
                assertNull(pipeline.get(replaceKeep ? CompressedMark.NAME : CompressedKeep.NAME));
            }
        }
    }

    @Test
    void markRemovedByNameUnlinks() {
        byte[] packet = packet(3000, 65);
        try (var f = new Fixture(Options.forwarding())) {
            f.read(frame(body(packet, THRESHOLD)));
            assertEquals(1, f.forwarder.forwarded());
            var pipeline = f.backend.pipeline();
            pipeline.remove(CompressedMark.NAME);
            f.read(frame(body(packet, THRESHOLD)));
            assertPackets(List.of(packet, packet), read(f.wire(), THRESHOLD));
            assertEquals(1, f.forwarder.forwarded());
            assertEquals(1, f.forwarder.notMarked());
            assertEquals("backend-adjacency", f.forwarder.linkStatus());
            assertNull(pipeline.get(CompressedKeep.NAME));
        }
    }

    @Test
    void multiComponentFrameFailsTheCheck() {
        byte[] packet = packet(3000, 48);
        try (var f = new Fixture(Options.forwarding())) {
            f.decoder.composite = true;
            f.read(frame(body(packet, THRESHOLD)));
            assertPackets(List.of(packet), read(f.wire(), THRESHOLD));
            assertEquals(1, f.forwarder.guardMismatch());
            assertEquals(0, f.forwarder.forwarded());
        }
    }

    @Test
    void checksumPreservingRewriteFailsTheCheck() {
        byte[] packet = packet(70_000, 40);
        int first = 1000;
        while (packet[first] == packet[first + 65521]) first++;
        int i = first;
        int j = first + 65521;
        byte[] rewritten = packet.clone();
        rewritten[i] = packet[j];
        rewritten[j] = packet[i];
        assertEquals(adler(packet), adler(rewritten), "the swap keeps the Adler-32");
        BiConsumer<Fixture, ByteBuf> swap = (f, buf) -> {
            int at = buf.readerIndex();
            byte a = buf.getByte(at + i);
            buf.setByte(at + i, buf.getByte(at + j));
            buf.setByte(at + j, a);
            f.client.write(buf);
        };
        try (var f = new Fixture(Options.forwarding().onFrame(swap))) {
            f.read(frame(body(packet, THRESHOLD)));
            assertPackets(List.of(rewritten), read(f.wire(), THRESHOLD));
            assertEquals(1, f.forwarder.guardMismatch());
            assertEquals(0, f.forwarder.forwarded());
        }
    }

    private static long adler(byte[] bytes) {
        var sum = new Adler32();
        sum.update(bytes);
        return sum.getValue();
    }

    @Test
    void changedEncoderThresholdDisablesForwarding() {
        byte[] packet = packet(300, 41);
        try (var f = new Fixture(Options.forwarding())) {
            f.encoder.setThreshold(512);
            f.read(frame(body(packet, THRESHOLD)));
            var frames = read(f.wire(), 512);
            assertPackets(List.of(packet), frames);
            assertFalse(frames.getFirst().compressed(), "framed by the encoder at its new threshold");
            assertEquals("threshold", f.forwarder.status());
            assertEquals(0, f.forwarder.forwarded() + f.forwarder.framedSmall());
            assertNull(f.backend.pipeline().get(CompressedKeep.NAME), "the link is removed with it");
        }
        byte[] small = packet(100, 42);
        try (var f = new Fixture(Options.forwarding().backendThreshold(64))) {
            f.encoder.setThreshold(64);
            f.read(frame(body(small, 64)));
            var frames = read(f.wire(), 64);
            assertPackets(List.of(small), frames);
            assertTrue(frames.getFirst().compressed(), "compressed by the encoder at its new threshold");
            assertEquals("threshold", f.forwarder.status());
            assertEquals(0, f.forwarder.forwarded() + f.forwarder.framedSmall());
        }
    }

    @Test
    void retainedInputIsConsumedAsTheCompressorConsumesIt() {
        for (boolean consume : new boolean[] {true, false}) {
            for (int size : new int[] {3000, 100}) {
                String label = "size " + size + ", compressor consumes " + consume;
                byte[] packet = packet(size, 43);
                var readable = new ArrayList<Integer>();
                BiConsumer<Fixture, ByteBuf> twice = (f, buf) -> {
                    f.track(buf.retain(2));
                    f.client.write(buf);
                    readable.add(buf.readableBytes());
                    f.client.write(buf);
                    readable.add(buf.readableBytes());
                    buf.release();
                };
                var options = Options.forwarding().consume(consume).onFrame(twice);
                byte[] read = frame(body(packet, THRESHOLD));
                byte[] today = today(options, read);
                boolean consumed = consume || size < THRESHOLD;
                var todayReadable = List.copyOf(readable);
                assertEquals(consumed ? List.of(0, 0) : List.of(size, size), todayReadable, label);
                var todayPackets = packets(read(today, THRESHOLD));
                assertPackets(List.of(packet, consumed ? new byte[0] : packet), read(today, THRESHOLD));
                readable.clear();
                try (var f = new Fixture(options)) {
                    f.read(read);
                    byte[] wire = f.wire();
                    assertEquals(todayReadable, readable, label);
                    assertPackets(todayPackets, read(wire, THRESHOLD));
                    if (size < THRESHOLD) assertSameBytes(today, wire);
                    else assertEquals(1, f.forwarder.forwarded(), label);
                }
            }
        }
    }

    @Test
    void partialLinkInstallLeavesNothingBehind() {
        try (var f = new Fixture(Options.forwarding().unlinked())) {
            ChannelPipeline real = f.backend.pipeline();
            ChannelPipeline failing = mock(ChannelPipeline.class, AdditionalAnswers.delegatesTo(real));
            doThrow(new NoSuchElementException(VelocityCompressedForward.COMPRESSION_DECODER))
                    .when(failing)
                    .addAfter(
                            eq(VelocityCompressedForward.COMPRESSION_DECODER),
                            eq(CompressedMark.NAME),
                            any(ChannelHandler.class));
            Channel backend = mock(Channel.class);
            when(backend.pipeline()).thenReturn(failing);
            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), backend, DECODER);
            assertEquals("backend-install-failed", f.forwarder.linkStatus());
            for (String name : real.names()) assertFalse(name.startsWith("cult-compressed"), name);
            assertFalse(f.forwarder.linkedTo(f.backend));

            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), f.backend, DECODER);
            assertEquals("linked", f.forwarder.linkStatus());
            byte[] packet = packet(5000, 44);
            f.read(frame(body(packet, THRESHOLD)));
            assertPackets(List.of(packet), read(f.wire(), THRESHOLD));
            assertEquals(1, f.forwarder.forwarded());
        }
    }

    @Test
    void retainedAliasChangedAfterInflationLeavesTheBackendsBytes() {
        byte[] packet = packet(3000, 66);
        byte[] body = body(packet, THRESHOLD);
        var aliases = new ArrayList<ByteBuf>();
        BiConsumer<Fixture, ChannelPipeline> alias =
                (f, pipeline) -> pipeline.addAfter("frame-decoder", "alias", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        aliases.add(((ByteBuf) msg).retainedDuplicate());
                        ctx.fireChannelRead(msg);
                    }
                });
        BiConsumer<Fixture, ByteBuf> changeTrailer = (f, buf) -> {
            ByteBuf duplicate = aliases.removeFirst();
            int last = duplicate.writerIndex() - 1;
            duplicate.setByte(last, duplicate.getByte(last) ^ 0x5a);
            duplicate.release();
            f.client.write(buf);
        };
        byte[] read = frame(body);
        byte[] today;
        try (var f = new Fixture(Options.today().onFrame(changeTrailer))) {
            alias.accept(f, f.backend.pipeline());
            f.read(read.clone());
            today = f.wire();
        }
        assertPackets(List.of(packet), read(today, THRESHOLD));
        try (var f = new Fixture(Options.forwarding().onFrame(changeTrailer))) {
            alias.accept(f, f.backend.pipeline());
            f.read(read.clone());
            var frames = read(f.wire(), THRESHOLD);
            assertPackets(List.of(packet), frames);
            assertArrayEquals(body, frames.getFirst().body(), "the backend's frame as it arrived");
            assertEquals(1, f.forwarder.forwarded());
        }
    }

    @Test
    void retainedAliasChangedBetweenInflateAndPublishLeavesTheBackendsBytes() {
        byte[] packet = packet(3000, 72);
        byte[] body = body(packet, THRESHOLD);
        var aliases = new ArrayList<ByteBuf>();
        BiConsumer<Fixture, ChannelPipeline> alias = (f, pipeline) -> {
            pipeline.addAfter("frame-decoder", "alias", new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    aliases.add(((ByteBuf) msg).retainedDuplicate());
                    ctx.fireChannelRead(msg);
                }
            });
            f.decoder.afterInflate = () -> {
                ByteBuf duplicate = aliases.removeFirst();
                int last = duplicate.writerIndex() - 1;
                duplicate.setByte(last, duplicate.getByte(last) ^ 0x5a);
                duplicate.release();
            };
        };
        byte[] read = frame(body);
        byte[] today;
        try (var f = new Fixture(Options.today())) {
            alias.accept(f, f.backend.pipeline());
            f.read(read.clone());
            today = f.wire();
        }
        assertPackets(List.of(packet), read(today, THRESHOLD));
        try (var f = new Fixture(Options.forwarding())) {
            alias.accept(f, f.backend.pipeline());
            f.read(read.clone());
            var frames = read(f.wire(), THRESHOLD);
            assertPackets(List.of(packet), frames);
            assertArrayEquals(body, frames.getFirst().body(), "the backend's frame as it arrived");
            assertEquals(1, f.forwarder.forwarded());
            assertTrue(aliases.isEmpty());
        }
    }

    @Test
    void keptCopyIsReleasedWhenTheDecoderThrows() {
        byte[] packet = packet(3000, 78);
        var copies = new ArrayList<ByteBuf>();
        try (var f = new Fixture(Options.forwarding())) {
            var keep = (CompressedKeep) f.backend.pipeline().get(CompressedKeep.NAME);
            f.decoder.afterInflate = () -> {
                copies.add(keep.kept);
                throw new IllegalStateException("limiter");
            };
            var failure = assertThrows(DecoderException.class, () -> f.read(frame(body(packet, THRESHOLD))));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(0, copies.getFirst().refCnt(), "the keep's copy");
            assertEquals(0, f.frameDecoder.frames.getFirst().refCnt(), "the backend's frame");
            assertEquals(0, f.wire().length);
            assertEquals("linked", f.forwarder.linkStatus());
        }
    }

    @Test
    void failedCopyPassesTheBackendsFrameOnUnheld() {
        byte[] packet = packet(3000, 79);
        byte[] read = frame(body(packet, THRESHOLD));
        try (var f = new Fixture(Options.forwarding())) {
            var keep = (CompressedKeep) f.backend.pipeline().get(CompressedKeep.NAME);
            var allocator = new RecordingAllocator();
            allocator.failing = 1;
            f.backend.config().setAllocator(allocator);
            var seen = new ArrayList<ByteBuf>();
            f.decoder.afterInflate = () -> seen.add(keep.kept);
            f.read(read);
            assertEquals(Arrays.asList((ByteBuf) null), seen, "the decoder reads the backend's frame");
            assertSameBytes(today(Options.forwarding(), read), f.wire());
            assertEquals(0, f.forwarder.forwarded());
            assertEquals(1, f.forwarder.notMarked());
            assertEquals(0, f.frameDecoder.frames.getFirst().refCnt());
        }
    }

    @Test
    void nonCanonicalZeroDataLengthIsPassedOnUnmarked() {
        byte[] packet = packet(100, 80);
        byte[] read = frame(concat(new byte[] {(byte) 0x80, 0x00}, packet));
        var copies = new ArrayList<ByteBuf>();
        try (var f = new Fixture(Options.forwarding().onFrame((fixture, buf) -> {
            copies.add(((CompressedKeep) fixture.backend.pipeline().get(CompressedKeep.NAME)).kept);
            fixture.delayedWrite(buf);
        }))) {
            f.read(read);
            byte[] wire = f.wire();
            assertSameBytes(today(Options.forwarding(), read), wire);
            assertPackets(List.of(packet), read(wire, THRESHOLD));
            assertNull(copies.getFirst(), "the keep does not hold a zero data length");
            assertEquals(0, f.forwarder.belowThreshold(), "the passed-on frame is not marked");
            assertEquals(1, f.forwarder.framedSmall());
            assertEquals(0, f.frameDecoder.frames.getFirst().refCnt());
        }
    }

    @Test
    void removedClientDecoderSendsEveryWriteThroughTheEncoder() {
        byte[] large = packet(3000, 81);
        byte[] small = packet(100, 82);
        byte[] read = concat(frame(body(large, THRESHOLD)), frame(body(small, THRESHOLD)));
        byte[][] wires = new byte[2][];
        for (boolean forward : new boolean[] {false, true}) {
            var writes = new ArrayList<ChannelFuture>();
            var options = (forward ? Options.forwarding() : Options.today())
                    .onFrame((fixture, buf) -> writes.add(fixture.client.write(buf)));
            try (var f = new Fixture(options)) {
                f.client.pipeline().remove(VelocityCompressedForward.COMPRESSION_DECODER);
                assertTrue(f.compressor.disposed, "removing the decoder closes the shared compressor");
                f.read(read);
                wires[forward ? 1 : 0] = f.wire();
                String label = "forward " + forward;
                assertEquals(2, writes.size(), label);
                assertInstanceOf(
                        IllegalStateException.class, writes.get(0).cause().getCause(), label);
                assertTrue(writes.get(1).isSuccess(), label);
                if (!forward) continue;
                assertEquals("compression-removed", f.forwarder.status());
                assertEquals(0, f.forwarder.forwarded() + f.forwarder.framedSmall());
            }
        }
        assertSameBytes(wires[0], wires[1]);
        assertPackets(List.of(small), read(wires[1], THRESHOLD));
    }

    @Test
    void bytesAfterTheZlibStreamAreForwardedAndReadByTheVanillaRule() {
        byte[] packet = packet(3000, 83);
        byte[] body = concat(body(packet, THRESHOLD), new byte[] {0x33});
        byte[] read = frame(body);
        byte[] today;
        try (var f = new Fixture(Options.today())) {
            f.decoder.vanilla = true;
            f.read(read);
            today = f.wire();
        }
        assertPackets(List.of(packet), read(today, THRESHOLD, true));
        try (var f = new Fixture(Options.forwarding())) {
            f.decoder.vanilla = true;
            f.read(read);
            byte[] wire = f.wire();
            var frames = read(wire, THRESHOLD, true);
            assertPackets(List.of(packet), frames);
            assertArrayEquals(body, frames.getFirst().body(), "the backend's frame, its last byte included");
            assertEquals(1, f.forwarder.forwarded());
            assertThrows(DecoderException.class, () -> read(wire, THRESHOLD), "a reader that wants an exact stream");
        }
    }

    @Test
    void packetLimiterOnTheBackendDecoderLeavesEveryReadAsWithTheSwitchOff() {
        byte[] packet = packet(3000, 86);
        byte[] other = packet(3000, 87);
        byte[] flipped = packet.clone();
        flipped[10] ^= 0x5a;
        byte[] read = frame(body(packet, THRESHOLD));
        String decoderName = VelocityCompressedForward.COMPRESSION_DECODER;
        Function<Fixture, Runnable> compressedIntoTheDecoder = f -> once(() -> {
            ChannelPipeline p = f.backend.pipeline();
            ChannelHandlerContext before = p.context(CompressedKeep.NAME);
            if (before == null) before = p.context("read-timeout");
            before.fireChannelRead(f.track(Unpooled.wrappedBuffer(body(other, THRESHOLD))));
        });
        Function<Fixture, Runnable> inflatedFromTheDecoder = f -> once(() -> f.backend
                .pipeline()
                .context(decoderName)
                .fireChannelRead(f.track(Unpooled.directBuffer(other.length).writeBytes(other))));
        Function<Fixture, Runnable> changedAfterTheDecoder =
                f -> once(() -> f.backend.pipeline().addAfter(decoderName, "flip", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ByteBuf buf = (ByteBuf) msg;
                        int at = buf.readerIndex() + 10;
                        buf.setByte(at, buf.getByte(at) ^ 0x5a);
                        ctx.pipeline().remove(this);
                        ctx.fireChannelRead(msg);
                    }
                }));
        var sequences = List.of(compressedIntoTheDecoder, inflatedFromTheDecoder, changedAfterTheDecoder);
        var expected = List.of(List.of(other, packet), List.of(other, packet), List.of(flipped));
        for (int i = 0; i < sequences.size(); i++) {
            byte[] today;
            try (var f = new Fixture(Options.today())) {
                f.decoder.packetLimiter = sequences.get(i).apply(f);
                f.read(read.clone());
                today = f.wire();
            }
            assertPackets(expected.get(i), read(today, THRESHOLD));
            for (boolean beforeLink : new boolean[] {true, false}) {
                String label = "sequence " + i + ", limiter set before the link " + beforeLink;
                try (var f = new Fixture(beforeLink ? Options.forwarding().unlinked() : Options.forwarding())) {
                    f.decoder.packetLimiter = sequences.get(i).apply(f);
                    if (beforeLink) {
                        VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), f.backend, DECODER);
                        assertEquals("backend-limiter", f.forwarder.linkStatus(), label);
                        assertNull(f.backend.pipeline().get(CompressedKeep.NAME), label);
                    }
                    f.read(read.clone());
                    assertSameBytes(today, f.wire());
                    assertEquals(0, f.forwarder.forwarded(), label);
                    assertEquals("backend-limiter", f.forwarder.linkStatus(), label);
                    assertNull(f.backend.pipeline().get(CompressedKeep.NAME), label);
                    assertNull(f.backend.pipeline().get(CompressedMark.NAME), label);
                }
            }
        }
    }

    /** Runs {@code action} on its first call only. */
    private static Runnable once(Runnable action) {
        boolean[] ran = {false};
        return () -> {
            if (ran[0]) return;
            ran[0] = true;
            action.run();
        };
    }

    @Test
    void failedSlicePassesTheBackendsFrameOnUnheld() {
        byte[] packet = packet(3000, 88);
        byte[] read = frame(body(packet, THRESHOLD));
        try (var f = new Fixture(Options.forwarding())) {
            var allocator = new RecordingAllocator();
            allocator.failingSlices = true;
            f.backend.config().setAllocator(allocator);
            f.read(read);
            assertSameBytes(today(Options.forwarding(), read), f.wire());
            assertEquals(0, f.forwarder.forwarded());
            assertEquals(1, f.forwarder.notMarked());
            assertEquals("linked", f.forwarder.linkStatus());
            assertEquals(0, f.frameDecoder.frames.getFirst().refCnt(), "the backend's frame");
            for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "the keep's copy");
        }
    }

    @Test
    void failingReleaseOfTheBackendsFrameDecodesAsWithTheSwitchOff() {
        byte[] packet = packet(3000, 91);
        byte[] read = frame(body(packet, THRESHOLD));
        for (boolean forward : new boolean[] {false, true}) {
            String label = "forward " + forward;
            var inputs = new ArrayList<FailingRelease>();
            var allocator = new RecordingAllocator();
            try (var f = new Fixture(forward ? Options.forwarding() : Options.today())) {
                f.backend.config().setAllocator(allocator);
                f.backend.pipeline().addAfter("frame-decoder", "failing", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ByteBuf frame = (ByteBuf) msg;
                        var input = new FailingRelease(frame.readableBytes());
                        try {
                            input.writeBytes(frame);
                        } finally {
                            frame.release();
                        }
                        inputs.add(input);
                        ctx.fireChannelRead(input);
                    }
                });
                var failure = assertThrows(DecoderException.class, () -> f.read(read.clone()), label);
                assertInstanceOf(IllegalReferenceCountException.class, failure.getCause(), label);
                assertPackets(List.of(packet), read(f.wire(), THRESHOLD));
                if (forward) assertEquals(1, f.forwarder.forwarded(), label);
                assertEquals(1, inputs.getFirst().refCnt(), "the frame whose release failed, " + label);
                for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation, " + label);
                assertTrue(inputs.getFirst().release(), label);
            }
        }
    }

    @Test
    void limiterSetAfterTheKeepsCheckLeavesTheReadAsWithTheSwitchOff() {
        byte[] packet = packet(3000, 92);
        byte[] other = packet(3000, 93);
        byte[] read = frame(body(packet, THRESHOLD));
        String decoderName = VelocityCompressedForward.COMPRESSION_DECODER;
        for (boolean inflated : new boolean[] {false, true}) {
            String label = "inflated " + inflated;
            Function<Fixture, Runnable> limiter = f -> once(() -> {
                ChannelPipeline p = f.backend.pipeline();
                if (inflated) {
                    p.context(decoderName)
                            .fireChannelRead(
                                    f.track(Unpooled.directBuffer(other.length).writeBytes(other)));
                    return;
                }
                ChannelHandlerContext before = p.context(CompressedKeep.NAME);
                if (before == null) before = p.context("read-timeout");
                before.fireChannelRead(f.track(Unpooled.wrappedBuffer(body(other, THRESHOLD))));
            });
            byte[] today;
            try (var f = new Fixture(Options.today())) {
                f.decoder.packetLimiter = limiter.apply(f);
                f.read(read.clone());
                today = f.wire();
            }
            assertPackets(List.of(other, packet), read(today, THRESHOLD));
            try (var f = new Fixture(Options.forwarding())) {
                var allocator = new RecordingAllocator();
                allocator.beforeDirect = once(() -> f.decoder.packetLimiter = limiter.apply(f));
                f.backend.config().setAllocator(allocator);
                f.read(read.clone());
                assertSameBytes(today, f.wire());
                assertEquals(0, f.forwarder.forwarded(), label);
                assertEquals("backend-limiter", f.forwarder.linkStatus(), label);
                assertNull(f.backend.pipeline().get(CompressedKeep.NAME), label);
                assertNull(f.backend.pipeline().get(CompressedMark.NAME), label);
                for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation, " + label);
            }
        }
    }

    @Test
    void decoderWithoutALimiterFieldIsNotLinked() {
        try (var f = new Fixture(Options.forwarding().unlinked())) {
            ChannelPipeline pipeline = f.backend.pipeline();
            String name = VelocityCompressedForward.COMPRESSION_DECODER;
            pipeline.replace(name, name, new CopyDecoder());
            assertNull(VelocityCompressedForward.limiterReader(pipeline.get(name)));
            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), f.backend, CopyDecoder.class.getName());
            assertEquals("backend-limiter", f.forwarder.linkStatus());
            assertNull(pipeline.get(CompressedKeep.NAME));
            assertNull(pipeline.get(CompressedMark.NAME));
        }
    }

    /** A limiter that clears itself when it runs and fires {@code other}, inflated, from the decoder's context. */
    private static Runnable selfClearingLimiter(Fixture f, byte[] other) {
        return once(() -> {
            f.decoder.packetLimiter = null;
            f.backend
                    .pipeline()
                    .context(VelocityCompressedForward.COMPRESSION_DECODER)
                    .fireChannelRead(f.track(Unpooled.directBuffer(other.length).writeBytes(other)));
        });
    }

    /** Runs {@code action} before the {@code n}th direct allocation only. */
    private static Runnable atDirectAllocation(int n, Runnable action) {
        int[] count = {0};
        return () -> {
            if (++count[0] == n) action.run();
        };
    }

    @Test
    void selfClearingLimiterSetByTheKeepsAllocationLeavesTheReadAsWithTheSwitchOff() {
        checkSelfClearingLimiterSetAt(1, "backend-limiter");
    }

    @Test
    void selfClearingLimiterSetByTheDecodersAllocationLeavesTheReadAsWithTheSwitchOff() {
        checkSelfClearingLimiterSetAt(2, "linked");
    }

    /** The limiter is set before the {@code n}th direct allocation on the backend: the keep's copy, then the decoder's. */
    private void checkSelfClearingLimiterSetAt(int n, String linkStatus) {
        byte[] packet = packet(3000, 94);
        byte[] other = packet(3000, 95);
        byte[] read = frame(body(packet, THRESHOLD));
        byte[] today;
        try (var f = new Fixture(Options.today())) {
            f.decoder.packetLimiter = selfClearingLimiter(f, other);
            f.read(read.clone());
            today = f.wire();
        }
        assertPackets(List.of(other, packet), read(today, THRESHOLD));
        try (var f = new Fixture(Options.forwarding())) {
            var allocator = new RecordingAllocator();
            allocator.beforeDirect =
                    atDirectAllocation(n, () -> f.decoder.packetLimiter = selfClearingLimiter(f, other));
            f.backend.config().setAllocator(allocator);
            f.read(read.clone());
            assertSameBytes(today, f.wire());
            assertEquals(0, f.forwarder.forwarded());
            assertEquals(linkStatus, f.forwarder.linkStatus());
            for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation");
        }
    }

    @Test
    void compressedFrameFiredIntoTheDecoderFromInsideItsDecodeIsNotMarked() {
        byte[] packet = packet(3000, 96);
        byte[] other = packet(3000, 97);
        byte[] read = frame(body(packet, THRESHOLD));
        Function<Fixture, Runnable> fire = f -> once(() -> {
            ChannelPipeline p = f.backend.pipeline();
            ChannelHandlerContext before = p.context(CompressedKeep.NAME);
            if (before == null) before = p.context("read-timeout");
            before.fireChannelRead(f.track(Unpooled.wrappedBuffer(body(other, THRESHOLD))));
        });
        byte[] today;
        try (var f = new Fixture(Options.today())) {
            f.decoder.afterInflate = fire.apply(f);
            f.read(read.clone());
            today = f.wire();
        }
        assertPackets(List.of(other, packet), read(today, THRESHOLD));
        try (var f = new Fixture(Options.forwarding())) {
            f.decoder.afterInflate = fire.apply(f);
            f.read(read.clone());
            assertSameBytes(today, f.wire());
            assertEquals(0, f.forwarder.forwarded());
            assertEquals(2, f.forwarder.notMarked());
            assertEquals("linked", f.forwarder.linkStatus());
        }
    }

    @Test
    void nestedReadThroughTheKeepFromInsideDecodeForwardsBothFrames() {
        byte[] outer = packet(3000, 98);
        byte[] inner = packet(3000, 99);
        byte[] read = frame(body(outer, THRESHOLD));
        Function<Fixture, Runnable> nested = f -> once(() -> f.backend
                .pipeline()
                .context("read-timeout")
                .fireChannelRead(f.track(Unpooled.wrappedBuffer(body(inner, THRESHOLD)))));
        try (var f = new Fixture(Options.today())) {
            f.decoder.afterInflate = nested.apply(f);
            f.read(read.clone());
            assertPackets(List.of(inner, outer), read(f.wire(), THRESHOLD));
        }
        try (var f = new Fixture(Options.forwarding())) {
            f.decoder.afterInflate = nested.apply(f);
            f.read(read.clone());
            var frames = read(f.wire(), THRESHOLD);
            assertPackets(List.of(inner, outer), frames);
            assertArrayEquals(body(inner, THRESHOLD), frames.get(0).body(), "the inner frame, from its own copy");
            assertArrayEquals(body(outer, THRESHOLD), frames.get(1).body(), "the outer frame");
            assertEquals(2, f.forwarder.forwarded());
            assertEquals("linked", f.forwarder.linkStatus());
        }
    }

    @Test
    void twoComponentCompressedFrameFailsToDecodeAsWithTheSwitchOff() {
        byte[] packet = packet(3000, 100);
        byte[] read = frame(body(packet, THRESHOLD));
        for (boolean forward : new boolean[] {false, true}) {
            String label = "forward " + forward;
            try (var f = new Fixture(forward ? Options.forwarding() : Options.today())) {
                f.backend.pipeline().addAfter("frame-decoder", "composite", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ByteBuf frame = (ByteBuf) msg;
                        ByteBuf composite;
                        try {
                            int half = frame.readableBytes() / 2;
                            ByteBuf first = Unpooled.directBuffer(half).writeBytes(frame, half);
                            ByteBuf second =
                                    Unpooled.directBuffer(frame.readableBytes()).writeBytes(frame);
                            composite = f.track(Unpooled.compositeBuffer(2).addComponents(true, first, second));
                        } finally {
                            frame.release();
                        }
                        assertEquals(2, composite.nioBufferCount(), label);
                        ctx.fireChannelRead(composite);
                    }
                });
                var failure = assertThrows(DecoderException.class, () -> f.read(read.clone()), label);
                assertInstanceOf(IllegalArgumentException.class, failure.getCause(), label);
                assertEquals(0, f.wire().length, label);
                if (forward) {
                    assertEquals(0, f.forwarder.forwarded(), label);
                    assertEquals("linked", f.forwarder.linkStatus(), label);
                }
            }
        }
    }

    @Test
    void clientDecoderReplacedBeforeInstallInstallsNothing() {
        byte[] large = packet(3000, 102);
        byte[] read = frame(body(large, THRESHOLD));
        String name = VelocityCompressedForward.COMPRESSION_DECODER;
        for (boolean reused : new boolean[] {false, true}) {
            Throwable[] causes = new Throwable[2];
            for (boolean forward : new boolean[] {false, true}) {
                String label = "reused compressor " + reused + ", forward " + forward;
                var writes = new ArrayList<ChannelFuture>();
                var refusals = new ArrayList<VelocityCompressedForward.Refusal>();
                CompressedForwarder forwarder = null;
                try (var f =
                        new Fixture(Options.today().onFrame((fixture, buf) -> writes.add(fixture.client.write(buf))))) {
                    ChannelPipeline client = f.client.pipeline();
                    var compressor = reused ? f.compressor : new SharedCompressor();
                    client.replace(name, name, new CompressDecoder(THRESHOLD, compressor));
                    assertTrue(f.compressor.disposed, "replacing the decoder closes the encoder's compressor");
                    if (forward) {
                        forwarder = VelocityCompressedForward.install(
                                client, CompressEncoder.class.getName(), DECODER, true, false, true, refusals::add);
                        if (forwarder != null) VelocityCompressedForward.place(forwarder, client, f.backend, DECODER);
                    }
                    f.read(read);
                    assertEquals(1, writes.size(), label);
                    causes[forward ? 1 : 0] = writes.getFirst().cause();
                    if (forward) {
                        assertEquals(List.of(VelocityCompressedForward.Refusal.COMPRESSOR), refusals, label);
                        assertNull(forwarder, label);
                        assertNull(client.get(CompressedForwarder.NAME), label);
                    }
                    assertEquals(0, f.wire().length, label);
                }
            }
            for (Throwable cause : causes) {
                assertNotNull(cause, "the large write fails on the closed compressor, reused " + reused);
                assertInstanceOf(IllegalStateException.class, cause.getCause());
            }
        }
    }

    @Test
    void frameTheForwarderCannotSendIsNotCopied() {
        byte[] small = packet(100, 89);
        byte[] large = packet(CompressedForwarder.MAX_FORWARDED_LENGTH + 1, 90);
        for (boolean belowClientThreshold : new boolean[] {true, false}) {
            Options options = belowClientThreshold ? Options.forwarding().backendThreshold(64) : Options.forwarding();
            byte[] read = belowClientThreshold ? frame(body(small, 64)) : frame(body(large, THRESHOLD));
            try (var f = new Fixture(options)) {
                var keep = (CompressedKeep) f.backend.pipeline().get(CompressedKeep.NAME);
                var seen = new ArrayList<ByteBuf>();
                f.decoder.afterInflate = () -> seen.add(keep.kept);
                f.read(read);
                assertEquals(
                        Arrays.asList((ByteBuf) null), seen, "below the client's threshold " + belowClientThreshold);
                assertSameBytes(today(options, read), f.wire());
                assertEquals(0, f.forwarder.forwarded());
            }
        }
    }

    @Test
    void clientDecoderOnItsOwnExecutorInstallsNothing() throws Exception {
        var channel = new EmbeddedChannel();
        var group = new DefaultEventExecutorGroup(1);
        try {
            ChannelPipeline pipeline = channel.pipeline();
            pipeline.addLast(group, VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD));
            pipeline.addLast(CompressedForwarder.COMPRESSION_ENCODER, new CompressEncoder(THRESHOLD));
            var refusals = new ArrayList<VelocityCompressedForward.Refusal>();
            assertNull(VelocityCompressedForward.install(
                    pipeline, CompressEncoder.class.getName(), DECODER, true, false, true, refusals::add));
            assertEquals(List.of(VelocityCompressedForward.Refusal.EXECUTOR), refusals);
            assertNull(pipeline.get(CompressedForwarder.NAME));
        } finally {
            channel.finishAndReleaseAll();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).await();
        }
    }

    @Test
    void smallFrameIsReleasedWhenTheInputsReleaseThrows() {
        byte[] packet = packet(100, 84);
        var allocator = new RecordingAllocator();
        var allocated = allocator.allocated;
        try (var f = new Fixture(Options.forwarding().unlinked())) {
            f.client.config().setAllocator(allocator);
            var input = new FailingRelease(packet.length);
            input.writeBytes(packet);
            ChannelFuture write = f.client.write(input);
            assertInstanceOf(IllegalReferenceCountException.class, write.cause());
            assertFalse(allocated.isEmpty(), "the small path framed a copy");
            for (ByteBuf buf : allocated) assertEquals(0, buf.refCnt(), "the framed copy is released");
            assertEquals(0, f.wire().length);
            assertEquals(0, f.forwarder.framedSmall());
            assertTrue(input.release());
        }
    }

    @Test
    void markReleasesItsBuffersWhenTheInflatedReleaseThrows() {
        byte[] packet = packet(3000, 85);
        var held = new ArrayList<ByteBuf>();
        try (var f = new Fixture(Options.forwarding().onFrame((fixture, buf) -> {
            held.add(fixture.forwarder.markCopy());
            held.add(fixture.forwarder.markFrame());
            buf.release(2);
        }))) {
            assertThrows(IllegalReferenceCountException.class, () -> f.read(frame(body(packet, THRESHOLD))));
            assertEquals(0, held.get(0).refCnt(), "the comparison copy");
            assertEquals(0, held.get(1).refCnt(), "the untaken frame");
            assertEquals(0, f.decoder.inflated.getFirst().refCnt());
            assertNull(f.forwarder.markFrame());
            assertEquals(0, f.wire().length);
        }
    }

    @Test
    void unwrittenMarkedFrameIsReleasedWithItsRead() {
        byte[] packet = packet(3000, 67);
        var frames = new ArrayList<ByteBuf>();
        try (var f = new Fixture(Options.forwarding().onFrame((fixture, buf) -> {
            frames.add(fixture.forwarder.markFrame());
            buf.release();
        }))) {
            f.read(frame(body(packet, THRESHOLD)));
            assertNotNull(frames.getFirst());
            assertEquals(0, frames.getFirst().refCnt(), "the mark releases a frame no write took");
            assertNull(f.forwarder.markFrame());
            assertEquals(0, f.wire().length);
        }
    }

    @Test
    void encoderReplacedOrRemovedDuringInstallInstallsNothing() {
        String name = CompressedForwarder.COMPRESSION_ENCODER;
        for (boolean replace : new boolean[] {true, false}) {
            var channel = new EmbeddedChannel();
            try {
                ChannelPipeline real = channel.pipeline();
                var compressor = new SharedCompressor();
                real.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD, compressor));
                real.addLast(name, new CompressEncoder(THRESHOLD, true, compressor));
                ChannelPipeline racing = mock(ChannelPipeline.class, AdditionalAnswers.delegatesTo(real));
                doAnswer(call -> {
                            if (replace) real.replace(name, name, new CompressEncoder(THRESHOLD));
                            else real.remove(name);
                            return real.addAfter(
                                    (String) call.getArgument(0), (String) call.getArgument(1), (ChannelHandler)
                                            call.getArgument(2));
                        })
                        .when(racing)
                        .addAfter(eq(name), eq(CompressedForwarder.NAME), any(ChannelHandler.class));
                var refusals = new ArrayList<VelocityCompressedForward.Refusal>();
                assertNull(
                        VelocityCompressedForward.install(
                                racing, CompressEncoder.class.getName(), DECODER, true, false, true, refusals::add),
                        "replace " + replace);
                assertEquals(List.of(VelocityCompressedForward.Refusal.CHANGED), refusals, "replace " + replace);
                assertNull(real.get(CompressedForwarder.NAME));
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void decoderReplacedDuringLinkLinksNothing() {
        try (var f = new Fixture(Options.forwarding().unlinked())) {
            String name = VelocityCompressedForward.COMPRESSION_DECODER;
            ChannelPipeline real = f.backend.pipeline();
            ChannelPipeline racing = mock(ChannelPipeline.class, AdditionalAnswers.delegatesTo(real));
            doAnswer(call -> {
                        real.replace(name, name, new CompressDecoder(THRESHOLD));
                        return real.addBefore(
                                (String) call.getArgument(0), (String) call.getArgument(1), (ChannelHandler)
                                        call.getArgument(2));
                    })
                    .when(racing)
                    .addBefore(eq(name), eq(CompressedKeep.NAME), any(ChannelHandler.class));
            Channel backend = mock(Channel.class);
            when(backend.pipeline()).thenReturn(racing);
            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), backend, DECODER);
            assertEquals("backend-adjacency", f.forwarder.linkStatus());
            for (String handler : real.names()) assertFalse(handler.startsWith("cult-compressed"), handler);
        }
    }

    @Test
    void handlerInsertedBeforeTheForwarderDuringInstallInstallsNothing() {
        String name = CompressedForwarder.COMPRESSION_ENCODER;
        var channel = new EmbeddedChannel();
        try {
            ChannelPipeline real = channel.pipeline();
            var compressor = new SharedCompressor();
            real.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD, compressor));
            real.addLast(name, new CompressEncoder(THRESHOLD, true, compressor));
            ChannelPipeline racing = mock(ChannelPipeline.class, AdditionalAnswers.delegatesTo(real));
            doAnswer(call -> {
                        real.addAfter(name, CompressedForwarder.NAME, (ChannelHandler) call.getArgument(2));
                        return real.addAfter(name, "foreign", new ChannelOutboundHandlerAdapter());
                    })
                    .when(racing)
                    .addAfter(eq(name), eq(CompressedForwarder.NAME), any(ChannelHandler.class));
            var refusals = new ArrayList<VelocityCompressedForward.Refusal>();
            assertNull(VelocityCompressedForward.install(
                    racing, CompressEncoder.class.getName(), DECODER, true, false, true, refusals::add));
            assertEquals(List.of(VelocityCompressedForward.Refusal.CHANGED), refusals);
            assertNull(real.get(CompressedForwarder.NAME));
            assertNotNull(real.get("foreign"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void handlerInsertedNextToTheDecoderDuringLinkLinksNothing() {
        String name = VelocityCompressedForward.COMPRESSION_DECODER;
        for (boolean keepSide : new boolean[] {true, false}) {
            try (var f = new Fixture(Options.forwarding().unlinked())) {
                ChannelPipeline real = f.backend.pipeline();
                ChannelPipeline racing = mock(ChannelPipeline.class, AdditionalAnswers.delegatesTo(real));
                if (keepSide)
                    doAnswer(call -> {
                                real.addBefore(name, CompressedKeep.NAME, (ChannelHandler) call.getArgument(2));
                                return real.addBefore(name, "foreign", new ChannelInboundHandlerAdapter());
                            })
                            .when(racing)
                            .addBefore(eq(name), eq(CompressedKeep.NAME), any(ChannelHandler.class));
                else
                    doAnswer(call -> {
                                real.addAfter(name, CompressedMark.NAME, (ChannelHandler) call.getArgument(2));
                                return real.addAfter(name, "foreign", new ChannelInboundHandlerAdapter());
                            })
                            .when(racing)
                            .addAfter(eq(name), eq(CompressedMark.NAME), any(ChannelHandler.class));
                Channel backend = mock(Channel.class);
                when(backend.pipeline()).thenReturn(racing);
                VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), backend, DECODER);
                assertEquals("backend-adjacency", f.forwarder.linkStatus(), "keep side " + keepSide);
                assertNotNull(real.get("foreign"));
                for (String handler : real.names()) assertFalse(handler.startsWith("cult-compressed"), handler);
            }
        }
    }

    @Test
    void installRefusalsLogTheirOwnWarningOnce() throws Exception {
        String name = CompressedForwarder.COMPRESSION_ENCODER;
        var client = new EmbeddedChannel();
        var other = new EmbeddedChannel();
        var group = new DefaultEventExecutorGroup(1);
        try (var log = mockStatic(LogUtil.class)) {
            var forward =
                    VelocityCompressedForward.create(mock(ProxyServer.class), new Object(), p -> null, true, true);
            assertNotNull(forward);
            client.pipeline().addLast(name, new CompressEncoder(THRESHOLD));
            var connection = new CultConnection(client, new PacketDispatcher(RUNTIME), ignored -> null);
            forward.installClient(connection, null, null);
            forward.installClient(connection, null, null);
            log.verify(
                    () -> LogUtil.warn(
                            "Cannot read a client's compression threshold; its frames are compressed by Velocity"),
                    times(1));
            ChannelPipeline real = other.pipeline();
            var compressor = new SharedCompressor();
            real.addLast(name, new CompressEncoder(THRESHOLD, true, compressor));
            for (int i = 0; i < 2; i++)
                assertNull(VelocityCompressedForward.install(
                        real, CompressEncoder.class.getName(), DECODER, true, false, true, forward::refused));
            log.verify(() -> LogUtil.warn(VelocityCompressedForward.Refusal.DECODER.warning), times(1));
            real.addFirst(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD));
            for (int i = 0; i < 2; i++)
                assertNull(VelocityCompressedForward.install(
                        real, CompressEncoder.class.getName(), DECODER, true, false, true, forward::refused));
            log.verify(
                    () -> LogUtil.warn("A client's compression encoder and decoder do not share one open compressor;"
                            + " its frames are compressed by Velocity"),
                    times(1));
            real.replace(
                    VelocityCompressedForward.COMPRESSION_DECODER,
                    VelocityCompressedForward.COMPRESSION_DECODER,
                    new CompressDecoder(THRESHOLD, compressor));
            ChannelPipeline racing = mock(ChannelPipeline.class, AdditionalAnswers.delegatesTo(real));
            doAnswer(call -> {
                        real.replace(name, name, new CompressEncoder(THRESHOLD));
                        return real.addAfter(name, CompressedForwarder.NAME, (ChannelHandler) call.getArgument(2));
                    })
                    .when(racing)
                    .addAfter(eq(name), eq(CompressedForwarder.NAME), any(ChannelHandler.class));
            for (int i = 0; i < 2; i++)
                assertNull(VelocityCompressedForward.install(
                        racing, CompressEncoder.class.getName(), DECODER, true, false, true, forward::refused));
            log.verify(
                    () -> LogUtil.warn("A client's compression encoder is not where the forwarder expects it after the"
                            + " insert; its frames are compressed by Velocity"),
                    times(1));
            real.remove(name);
            real.addLast(group, name, new CompressEncoder(THRESHOLD));
            for (int i = 0; i < 2; i++)
                assertNull(VelocityCompressedForward.install(
                        real, CompressEncoder.class.getName(), DECODER, true, false, true, forward::refused));
            log.verify(() -> LogUtil.warn(VelocityCompressedForward.Refusal.EXECUTOR.warning), times(1));
            log.verify(() -> LogUtil.warn(anyString()), times(5));
        } finally {
            client.finishAndReleaseAll();
            other.finishAndReleaseAll();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).await();
        }
    }

    @Test
    void forwardedFrameIsReleasedWhenTheInputsReleaseThrows() {
        byte[] packet = packet(3000, 71);
        byte[] body = body(packet, THRESHOLD);
        try (var f = new Fixture(Options.forwarding().unlinked())) {
            var input = new FailingRelease(packet.length);
            input.writeBytes(packet);
            ByteBuf copy = f.track(Unpooled.copiedBuffer(packet));
            ByteBuf kept = f.track(Unpooled.wrappedBuffer(body));
            ByteBuf frame = f.forwarder.frame(ByteBufAllocator.DEFAULT, kept);
            assertNotNull(frame);
            ChannelFuture write;
            f.forwarder.mark(input, input.readerIndex(), input.writerIndex(), copy, frame);
            try {
                write = f.client.write(input);
            } finally {
                f.forwarder.mark(null, 0, 0, null, null);
            }
            assertInstanceOf(IllegalReferenceCountException.class, write.cause());
            assertEquals(0, frame.refCnt(), "the frame no write took is released");
            assertEquals(0, f.wire().length);
            assertTrue(input.release());
            copy.release();
            kept.release();
        }
    }

    /**
     * Unpooled buffers, each recorded, from an allocator these tests let the keep trust; the first {@link #failing}
     * direct allocations throw, and with {@link #failingSlices} a direct buffer's retained slice throws.
     */
    static final class RecordingAllocator extends AbstractByteBufAllocator {
        final List<ByteBuf> allocated = new ArrayList<>();
        int failing;
        boolean failingSlices;
        // Runs before each direct allocation.
        Runnable beforeDirect;

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            ByteBuf buf = new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
            allocated.add(buf);
            return buf;
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            if (beforeDirect != null) beforeDirect.run();
            if (failing > 0) {
                failing--;
                throw new OutOfMemoryError("direct memory");
            }
            ByteBuf buf = failingSlices
                    ? new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity) {
                        @Override
                        public ByteBuf retainedSlice(int index, int length) {
                            throw new IllegalStateException("slice");
                        }
                    }
                    : new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity);
            allocated.add(buf);
            return buf;
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }
    }

    /** A heap buffer whose first release throws, as releasing a reference the writer does not hold does. */
    static final class FailingRelease extends UnpooledHeapByteBuf {
        private boolean failed;

        FailingRelease(int capacity) {
            super(UnpooledByteBufAllocator.DEFAULT, capacity, capacity);
        }

        @Override
        public boolean release() {
            if (!failed) {
                failed = true;
                throw new IllegalReferenceCountException(0, -1);
            }
            return super.release();
        }
    }

    /** A heap buffer that runs its hook once, on its first release. */
    static final class HookedRelease extends UnpooledHeapByteBuf {
        private Runnable hook;

        HookedRelease(int capacity, Runnable hook) {
            super(UnpooledByteBufAllocator.DEFAULT, capacity, capacity);
            this.hook = hook;
        }

        @Override
        public boolean release() {
            Runnable run = hook;
            hook = null;
            if (run != null) run.run();
            return super.release();
        }
    }

    /**
     * Unpooled buffers from an allocator netty does not provide: {@link #onHeap} runs before the next heap allocation,
     * and {@link #onSharedRelease} when a release of the next direct buffer leaves it one reference.
     */
    static final class ForeignAllocator extends AbstractByteBufAllocator {
        Runnable onHeap;
        Runnable onSharedRelease;

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            Runnable hook = onHeap;
            onHeap = null;
            if (hook != null) hook.run();
            return new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            Runnable[] hook = {onSharedRelease};
            onSharedRelease = null;
            return new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity) {
                @Override
                public boolean release() {
                    boolean released = super.release();
                    Runnable run = hook[0];
                    if (refCnt() == 1 && run != null) {
                        hook[0] = null;
                        run.run();
                    }
                    return released;
                }
            };
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }
    }

    /** Replaces each backend frame with an equal {@link HookedRelease} that runs {@code hook} on its release. */
    private static void hookRelease(Fixture f, Runnable hook) {
        f.backend.pipeline().addAfter("frame-decoder", "hooked", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ByteBuf frame = (ByteBuf) msg;
                var input = f.track(new HookedRelease(frame.readableBytes(), hook));
                try {
                    input.writeBytes(frame);
                } finally {
                    frame.release();
                }
                ctx.fireChannelRead(input);
            }
        });
    }

    /** Forwarding options whose downstream handler records each packet it publishes before writing it. */
    private static Options recording(List<byte[]> published) {
        return Options.forwarding().onFrame((fixture, buf) -> {
            published.add(ByteBufUtil.getBytes(buf));
            fixture.client.write(buf);
        });
    }

    @Test
    void foreignBackendAllocatorLeavesTheReadAsWithTheSwitchOff() {
        byte[] packet = packet(3000, 106);
        byte[] other = packet(3000, 107);
        byte[] read = frame(body(packet, THRESHOLD));
        byte[][] wires = new byte[2][];
        for (boolean forward : new boolean[] {false, true}) {
            try (var f = new Fixture(forward ? Options.forwarding() : Options.today())) {
                var allocator = new ForeignAllocator();
                allocator.onSharedRelease = () -> f.backend
                        .pipeline()
                        .context(VelocityCompressedForward.COMPRESSION_DECODER)
                        .fireChannelRead(
                                f.track(Unpooled.directBuffer(other.length).writeBytes(other)));
                f.backend.config().setAllocator(allocator);
                f.read(read.clone());
                wires[forward ? 1 : 0] = f.wire();
                if (forward) {
                    assertEquals(0, f.forwarder.forwarded());
                    assertEquals("linked", f.forwarder.linkStatus());
                }
            }
        }
        assertSameBytes(wires[0], wires[1]);
        assertPackets(List.of(packet, other), read(wires[1], THRESHOLD));
    }

    @Test
    void handlerInsertedByTheInputsReleaseGetsTheReadUnmarked() {
        byte[] packet = packet(3000, 108);
        byte[] other = packet(3000, 109);
        var published = new ArrayList<byte[]>();
        try (var f = new Fixture(recording(published))) {
            hookRelease(
                    f,
                    () -> f.backend
                            .pipeline()
                            .addAfter(CompressedKeep.NAME, "swap", new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    ((ByteBuf) msg).release();
                                    ctx.pipeline().remove(this);
                                    ctx.fireChannelRead(f.track(Unpooled.wrappedBuffer(body(other, THRESHOLD))));
                                }
                            }));
            f.read(frame(body(packet, THRESHOLD)));
            assertPackets(published, read(f.wire(), THRESHOLD));
            assertArrayEquals(other, published.getFirst());
            assertEquals(0, f.forwarder.forwarded());
            assertEquals("backend-adjacency", f.forwarder.linkStatus());
        }
    }

    @Test
    void allocatorSetByTheInputsReleaseLeavesTheReadUnmarked() {
        byte[] packet = packet(3000, 110);
        var published = new ArrayList<byte[]>();
        try (var f = new Fixture(recording(published))) {
            var allocator = new ForeignAllocator();
            allocator.onHeap = () -> {
                ByteBuf inflated = f.decoder.inflated.getLast();
                inflated.setByte(inflated.readerIndex() + 10, inflated.getByte(inflated.readerIndex() + 10) ^ 0x55);
            };
            hookRelease(f, () -> f.backend.config().setAllocator(allocator));
            f.read(frame(body(packet, THRESHOLD)));
            assertEquals(1, published.size());
            assertPackets(published, read(f.wire(), THRESHOLD));
            assertEquals(0, f.forwarder.forwarded());
        }
    }

    @Test
    void compressorGettersAreCachedPerRoleAndAClosedCompressorIsRefused() {
        var compressor = new SharedCompressor();
        var encoder = new CompressEncoder(THRESHOLD, true, compressor);
        var decoder = new CompressDecoder(THRESHOLD, compressor);
        assertTrue(VelocityCompressedForward.sharedCompressor(encoder, decoder));
        MethodHandle encoderGetter = VelocityCompressedForward.encoderCompressorGetter;
        MethodHandle decoderGetter = VelocityCompressedForward.decoderCompressorGetter;
        assertTrue(VelocityCompressedForward.sharedCompressor(encoder, decoder));
        assertSame(encoderGetter, VelocityCompressedForward.encoderCompressorGetter);
        assertSame(decoderGetter, VelocityCompressedForward.decoderCompressorGetter);
        compressor.disposed = true;
        assertFalse(VelocityCompressedForward.sharedCompressor(encoder, decoder));
    }

    @Test
    void keepRemovedUnderneathTheUnlinkStillPassesItsFrameOn() {
        byte[] packet = packet(3000, 68);
        try (var f = new Fixture(Options.forwarding().unlinked())) {
            ChannelPipeline real = f.backend.pipeline();
            ChannelPipeline racing = mock(ChannelPipeline.class, AdditionalAnswers.delegatesTo(real));
            doAnswer(call -> {
                        real.remove((ChannelHandler) call.getArgument(0));
                        throw new NoSuchElementException(CompressedKeep.NAME);
                    })
                    .when(racing)
                    .remove(any(CompressedKeep.class));
            Channel backend = mock(Channel.class);
            when(backend.pipeline()).thenReturn(racing);
            when(backend.eventLoop()).thenReturn(f.backend.eventLoop());
            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), backend, DECODER);
            assertEquals("linked", f.forwarder.linkStatus());
            real.addBefore(VelocityCompressedForward.COMPRESSION_DECODER, "tap", new ChannelInboundHandlerAdapter());
            f.read(frame(body(packet, THRESHOLD)));
            assertEquals(0, f.frameDecoder.frames.getFirst().refCnt(), "the frame is passed on and released");
            assertPackets(List.of(packet), read(f.wire(), THRESHOLD));
            assertEquals(0, f.forwarder.forwarded());
            assertEquals("backend-adjacency", f.forwarder.linkStatus());
            assertNull(real.get(CompressedKeep.NAME));
            assertNull(real.get(CompressedMark.NAME));
        }
    }

    @Test
    void deflateBoundCoversEveryJavaLevel() {
        var random = new Random(70);
        for (int n : new int[] {0, 1, 255, 65_535, 65_536, 300_000}) {
            byte[] data = new byte[n];
            random.nextBytes(data);
            for (int level = 0; level <= 9; level++)
                assertTrue(
                        zlib(data, level).length <= CompressedForwarder.deflateBound(n), "n " + n + ", level " + level);
            assertTrue(n + n / 8 < CompressedForwarder.deflateBound(n), "an eighth's expansion, n " + n);
        }
        int limit = CompressedForwarder.MAX_FORWARDED_LENGTH;
        assertEquals(1_838_585, limit);
        assertTrue(CompressedForwarder.deflateBound(limit) < 1 << 21);
        assertTrue(CompressedForwarder.deflateBound(limit + 1) >= 1 << 21);
    }

    @Test
    void frameVelocityCouldRejectTakesTheEncodersPath() {
        int limit = CompressedForwarder.MAX_FORWARDED_LENGTH;
        for (int size : new int[] {limit, limit + 1, (limit + (1 << 21)) / 2, 1 << 21}) {
            String label = "size " + size;
            byte[] packet = packet(size, 69);
            byte[] read = frame(body(packet, THRESHOLD));
            Written off = storedWrite(Options.today(), read);
            Written on = storedWrite(Options.forwarding(), read);
            assertEquals(size == limit ? 1 : 0, on.forwarded(), label);
            if (size == 1 << 21) {
                assertInstanceOf(DataFormatException.class, off.failure().getCause(), label);
                assertInstanceOf(DataFormatException.class, on.failure().getCause(), label);
                assertEquals(0, off.wire().length + on.wire().length, label);
                continue;
            }
            assertNull(off.failure(), label);
            assertNull(on.failure(), label);
            assertPackets(List.of(packet), read(off.wire(), THRESHOLD));
            assertPackets(List.of(packet), read(on.wire(), THRESHOLD));
            if (size > limit) assertSameBytes(off.wire(), on.wire());
        }
    }

    private record Written(byte[] wire, Throwable failure, long forwarded) {}

    /** {@code read} through a client encoder that stores its blocks uncompressed, with the write's outcome. */
    private static Written storedWrite(Options options, byte[] read) {
        var writes = new ArrayList<ChannelFuture>();
        try (var f = new Fixture(options.onFrame((fixture, buf) -> writes.add(fixture.client.write(buf))))) {
            f.encoder.setLevel(Deflater.NO_COMPRESSION);
            f.read(read);
            byte[] wire = f.wire();
            assertEquals(1, writes.size());
            assertTrue(writes.getFirst().isDone());
            long forwarded = f.forwarder == null ? 0 : f.forwarder.forwarded();
            return new Written(wire, writes.getFirst().cause(), forwarded);
        }
    }

    @Test
    void unknownCompressorInstallsNothing() {
        var proxy = mock(ProxyServer.class);
        try (var log = mockStatic(LogUtil.class)) {
            assertNull(VelocityCompressedForward.create(proxy, new Object(), p -> null, true, null));
            log.verify(() -> LogUtil.warn(anyString()), times(1));
            assertNotNull(VelocityCompressedForward.create(proxy, new Object(), p -> null, true, true));
            assertNotNull(VelocityCompressedForward.create(proxy, new Object(), p -> null, true, false));
            log.verify(() -> LogUtil.warn(anyString()), times(1));
        }
    }

    /** An outbound handler with a threshold field that is not the encoder class the forwarder accepts. */
    static final class OtherEncoder extends ChannelOutboundHandlerAdapter {
        final int threshold;

        OtherEncoder(int threshold) {
            this.threshold = threshold;
        }
    }

    /** The switch-off path sends another body for the same packet, so only a forwarded frame carries {@code body}. */
    private static void assertRecompressedDiffers(byte[] body) {
        var baseline = read(today(Options.today(), frame(body)), THRESHOLD);
        assertEquals(1, baseline.size());
        assertFalse(Arrays.equals(body, baseline.getFirst().body()), "recompression gives another body");
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
            boolean link,
            int backendThreshold,
            boolean via,
            boolean copyAfterMark,
            boolean heap,
            BiConsumer<Fixture, ByteBuf> onFrame,
            boolean consume) {
        static Options forwarding() {
            return new Options(true, true, THRESHOLD, false, false, false, null, true);
        }

        static Options today() {
            return forwarding().withoutForwarding();
        }

        Options withoutForwarding() {
            return new Options(false, false, backendThreshold, via, copyAfterMark, heap, onFrame, consume);
        }

        Options unlinked() {
            return new Options(forward, false, backendThreshold, via, copyAfterMark, heap, onFrame, consume);
        }

        Options backendThreshold(int value) {
            return new Options(forward, link, value, via, copyAfterMark, heap, onFrame, consume);
        }

        Options via(boolean value) {
            return new Options(forward, link, backendThreshold, value, copyAfterMark, heap, onFrame, consume);
        }

        Options copying() {
            return new Options(forward, link, backendThreshold, via, true, heap, onFrame, consume);
        }

        Options heapCipher() {
            return new Options(forward, link, backendThreshold, via, copyAfterMark, true, onFrame, consume);
        }

        Options onFrame(BiConsumer<Fixture, ByteBuf> value) {
            return new Options(forward, link, backendThreshold, via, copyAfterMark, heap, value, consume);
        }

        Options consume(boolean value) {
            return new Options(forward, link, backendThreshold, via, copyAfterMark, heap, onFrame, value);
        }
    }

    static final class Fixture implements AutoCloseable {
        final EmbeddedChannel client = new EmbeddedChannel();
        final SharedCompressor compressor = new SharedCompressor();
        final CompressEncoder encoder;
        final EmbeddedChannel backend;
        final FrameDecoder frameDecoder;
        final CompressDecoder decoder;
        final CompressedForwarder forwarder;
        final CultConnection connection;
        private final List<ByteBuf> tracked = new ArrayList<>();
        private final List<Backend> backends = new ArrayList<>();
        private final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        private final Options options;

        Fixture(Options options) {
            this.options = options;
            encoder = new CompressEncoder(THRESHOLD, options.consume(), compressor);
            var pipeline = client.pipeline();
            pipeline.addLast("cipher-encoder", new CipherEncoder());
            pipeline.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD, compressor));
            pipeline.addLast(CompressedForwarder.COMPRESSION_ENCODER, encoder);
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
                    ? VelocityCompressedForward.install(
                            pipeline, CompressEncoder.class.getName(), DECODER, true, options.heap(), options.consume())
                    : null;
            var first = backend(options);
            backend = first.channel;
            frameDecoder = first.frames;
            decoder = first.decoder;
            if (options.link()) VelocityCompressedForward.place(forwarder, pipeline, backend, DECODER);
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
