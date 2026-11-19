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
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.anticheat.LogUtil;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.proxy.network.limiter.PacketLimiter;
import io.netty.buffer.AbstractByteBuf;
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
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
import io.netty.channel.DefaultChannelPipeline;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.UnorderedThreadPoolEventExecutor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.Adler32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;

/**
 * Real frames through the forwarding handlers between stand-ins for Velocity's own, on two embedded channels. Every
 * case checks what the client inflates, the counters, and that every buffer it can see ends released.
 */
class VelocityCompressedForwardTest {
    private static final ProtocolRuntime RUNTIME = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3));
    private static final int THRESHOLD = 256;
    private static final Class<?> DECODER = CompressDecoder.class;
    private static ResourceLeakDetector.Level previousLevel;
    private static final List<String> LEAKS = new ArrayList<>();
    /**
     * Netty's allocators at any leak detection level and {@link RecordingAllocator}, whose hooks stand for the log and
     * leak-listener code netty's allocators run when they report a leak.
     */
    private static final Predicate<ByteBufAllocator> TRUSTED =
            CompressedKeep.NETTY_CLASSES.or(alloc -> alloc instanceof RecordingAllocator);

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

    private static Object staticField(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
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
            byte[] wire = f.wire();
            var frames = read(wire, THRESHOLD);
            assertPackets(List.of(packet), frames);
            assertTrue(frames.getFirst().compressed());
            assertArrayEquals(body, frames.getFirst().body(), "the backend's frame body, unchanged");
            assertEquals(1, f.forwarder.forwarded());
            String sent = ", forwarded=1 (" + wire.length + " B sent, " + packet.length + " B inflated),";
            assertTrue(f.forwarder.stats().contains(sent), f.forwarder.stats());
            assertEquals(0, f.forwarder.notMarked() + f.forwarder.indicesChanged() + f.forwarder.guardMismatch());
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
    void heapCipherGetsTheBufferKindsOfTheSwitchOff() {
        byte[] large = packet(3000, 38);
        byte[] small = packet(40, 39);
        byte[] read = concat(frame(body(large, THRESHOLD)), frame(body(small, THRESHOLD)));
        List<List<Boolean>> kinds = new ArrayList<>();
        for (boolean forward : new boolean[] {false, true}) {
            try (var f = new Fixture((forward ? Options.forwarding() : Options.today()).heapCipher())) {
                f.read(read.clone());
                var wire = new ByteArrayOutputStream();
                List<Boolean> direct = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    ByteBuf out = f.client.readOutbound();
                    try {
                        direct.add(out.isDirect());
                        wire.writeBytes(ByteBufUtil.getBytes(out));
                    } finally {
                        out.release();
                    }
                }
                assertPackets(List.of(large, small), read(wire.toByteArray(), THRESHOLD));
                if (forward) assertEquals(1, f.forwarder.forwarded());
                kinds.add(direct);
            }
        }
        assertEquals(List.of(true, false), kinds.getFirst(), "switched off: compressed direct, below threshold heap");
        assertEquals(kinds.getFirst(), kinds.get(1));
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
            assertEquals(2, f.forwarder.notMarked());
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
            assertEquals(2, f.forwarder.notMarked());
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
                            new CompressedKeep(f.forwarder, TRUSTED));
            var keep = (CompressedKeep) second.channel.pipeline().get(CompressedKeep.NAME);
            var p = second.channel.pipeline();
            keep.attach(
                    p.context(keep),
                    p.context(VelocityCompressedForward.COMPRESSION_DECODER),
                    VelocityCompressedForward.codec(
                            second.decoder,
                            VelocityCompressedForward.limiterReader(second.decoder),
                            COMPRESSORS,
                            VelocityCompressedForward.decode(DECODER)));
            nested.add(second);
            f.read(frame(body(outer, THRESHOLD)));
            assertPackets(List.of(inner, outer), read(f.wire(), THRESHOLD));
            assertEquals(2, f.forwarder.forwarded());
            assertNull(f.forwarder.markBuf);
        }
    }

    @Test
    void removedCompressionEncoderDisablesForwarding() {
        var written = new ArrayList<ByteBuf>();
        try (var f = new Fixture(Options.forwarding().onFrame((fixture, buf) -> {
            written.add(buf);
            fixture.client.write(buf);
        }))) {
            f.client.pipeline().remove(CompressedForwarder.COMPRESSION_ENCODER);
            f.read(frame(body(packet(400, 24), THRESHOLD)));
            assertEquals("compression-removed", f.forwarder.status());
            ByteBuf out = f.client.readOutbound();
            assertSame(written.getFirst(), out, "the marked buffer passes on untouched");
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
                assertEquals("minecraft-decoder", backend.get(decoder + 1));
                assertTrue(backend.indexOf("read-timeout") < backend.indexOf(CompressedKeep.NAME));
                assertEquals("linked", f.forwarder.linkStatus());

                VelocityCompressedForward.uninstall(f.client);
                assertNull(f.client.pipeline().get(CompressedForwarder.NAME));
                for (String name : f.backend.pipeline().names()) assertFalse(name.startsWith("cult-compressed"), name);
            }
        }
    }

    @Test
    void secondBackendReplacesTheLink() {
        try (var f = new Fixture(Options.forwarding())) {
            var second = f.backend(Options.forwarding());
            VelocityCompressedForward.place(
                    f.forwarder, f.client.pipeline(), second.channel, DECODER, COMPRESSORS, TRUSTED);
            assertNull(f.backend.pipeline().get(CompressedKeep.NAME));
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
            VelocityCompressedForward.link(f.forwarder, f.client, f.backend, DECODER, COMPRESSORS);
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
            assertEquals(0, f.forwarder.forwarded());
        }
        try (var f = new Fixture(Options.forwarding().unlinked())) {
            f.client
                    .pipeline()
                    .addAfter(CompressedForwarder.COMPRESSION_ENCODER, "tap", new ChannelOutboundHandlerAdapter());
            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), f.backend, DECODER, COMPRESSORS);
            assertEquals("adjacency", f.forwarder.status());
            assertNull(f.backend.pipeline().get(CompressedKeep.NAME));
        }
    }

    @Test
    void onlyTheNamedEncoderClassGetsAForwarder() {
        Class<?> standIn = CompressEncoder.class;
        var channel = channel();
        try {
            var pipeline = channel.pipeline();
            var compressor = new SharedCompressor();
            pipeline.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(512, compressor));
            assertNull(VelocityCompressedForward.thresholdReader(null, standIn), "no encoder");
            assertNull(VelocityCompressedForward.install(pipeline, standIn, DECODER, COMPRESSORS), "no encoder");
            pipeline.addLast(CompressedForwarder.COMPRESSION_ENCODER, new OtherEncoder(512));
            assertNull(
                    VelocityCompressedForward.thresholdReader(
                            pipeline.get(CompressedForwarder.COMPRESSION_ENCODER), standIn),
                    "another class, same field");
            assertNull(VelocityCompressedForward.install(pipeline, standIn, DECODER, COMPRESSORS));
            assertNull(pipeline.get(CompressedForwarder.NAME));
            pipeline.replace(
                    CompressedForwarder.COMPRESSION_ENCODER,
                    CompressedForwarder.COMPRESSION_ENCODER,
                    new CompressEncoder(512, true, compressor));
            assertNull(
                    VelocityCompressedForward.install(pipeline, OtherEncoder.class, DECODER, COMPRESSORS),
                    "the stand-in is not Velocity's encoder");
            assertNull(pipeline.get(CompressedForwarder.NAME));
            var forwarder = VelocityCompressedForward.install(pipeline, standIn, DECODER, COMPRESSORS);
            assertNotNull(forwarder);
            assertEquals(512, forwarder.threshold(), "the encoder's own threshold");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void installRefusesWithoutCompression() {
        var channel = channel();
        try {
            assertNull(
                    VelocityCompressedForward.install(channel.pipeline(), CompressEncoder.class, DECODER, COMPRESSORS));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void negativeInstallThresholdInstallsNothing() {
        var channel = channel();
        try {
            var pipeline = channel.pipeline();
            var compressor = new SharedCompressor();
            pipeline.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(-1, compressor));
            pipeline.addLast(CompressedForwarder.COMPRESSION_ENCODER, new CompressEncoder(-1, true, compressor));
            assertNull(VelocityCompressedForward.install(pipeline, CompressEncoder.class, DECODER, COMPRESSORS));
            assertNull(pipeline.get(CompressedForwarder.NAME));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void onlyTheNamedDecoderClassIsLinked() {
        try (var f = new Fixture(Options.forwarding().unlinked())) {
            VelocityCompressedForward.place(
                    f.forwarder, f.client.pipeline(), f.backend, CopyDecoder.class, COMPRESSORS);
            assertEquals("backend-decoder", f.forwarder.linkStatus());
            for (String name : f.backend.pipeline().names()) assertFalse(name.startsWith("cult-compressed"), name);
            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), f.backend, DECODER, COMPRESSORS);
            assertEquals("linked", f.forwarder.linkStatus());
        }
    }

    @Test
    void rewriteAfterTheDecoderIsNotForwarded() {
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
        byte[] read = concat(frame(body(packet, THRESHOLD)), frame(body(packet, THRESHOLD)));
        checkRewritten(read, rewrite, List.of(changed, changed));
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
        }
    }

    /**
     * {@code read} through a backend {@code insert} changes after the decoder decodes as switched off, unforwarded,
     * with the link kept: the inserted handler gets the keep's output and writes another buffer.
     */
    private void checkRewritten(byte[] read, BiConsumer<Fixture, ChannelPipeline> insert, List<byte[]> expected) {
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
            assertEquals("linked", f.forwarder.linkStatus());
            assertNotNull(f.backend.pipeline().get(CompressedKeep.NAME));
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
        checkRewritten(
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
        byte[] read = concat(frame(body(a, THRESHOLD)), frame(body(b, THRESHOLD)));
        BiConsumer<Fixture, ChannelPipeline> hold = (f, pipeline) -> pipeline.addAfter(
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
                });
        try (var f = new Fixture(Options.today())) {
            hold.accept(f, f.backend.pipeline());
            f.read(read);
            assertPackets(List.of(a, b), read(f.wire(), THRESHOLD));
        }
        try (var f = new Fixture(Options.forwarding())) {
            hold.accept(f, f.backend.pipeline());
            f.read(read);
            var frames = read(f.wire(), THRESHOLD);
            assertPackets(List.of(a, b), frames);
            assertArrayEquals(body(b, THRESHOLD), frames.get(1).body(), "the frame published in its own read");
            assertEquals(1, f.forwarder.forwarded());
            assertEquals(1, f.forwarder.notMarked(), "the frame held past its read");
            assertEquals("linked", f.forwarder.linkStatus());
        }
    }

    @Test
    void handlerAddedDuringAReadThatRemovesItselfIsNotForwarded() {
        byte[] packet = packet(3000, 54);
        byte[] changed = packet.clone();
        changed[100] ^= 0x5a;
        checkRewritten(
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

    /** The handler's executor runs it after the read returns, as another loop runs a task the read queues on it. */
    @Test
    void ownExecutorHandlerAfterTheDecoderGetsAnUnmarkedBuffer() throws Exception {
        byte[] packet = packet(3000, 55);
        byte[] changed = packet.clone();
        changed[100] ^= 0x5a;
        var group = new DefaultEventExecutorGroup(1);
        try {
            for (boolean forward : new boolean[] {false, true}) {
                var returned = new CountDownLatch(1);
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
                                        public void channelRead(ChannelHandlerContext ctx, Object msg)
                                                throws InterruptedException {
                                            assertTrue(returned.await(5, TimeUnit.SECONDS));
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
                    returned.countDown();
                    assertTrue(latch.await(5, TimeUnit.SECONDS));
                    f.backend.runPendingTasks();
                    f.client.flush();
                    assertPackets(List.of(changed), read(f.wire(), THRESHOLD));
                    if (!forward) continue;
                    assertEquals(0, f.forwarder.forwarded());
                    assertEquals(1, f.forwarder.notMarked());
                    assertEquals("linked", f.forwarder.linkStatus());
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
    void keepReplacedByNamePassesFramesOnUnmarked() {
        byte[] packet = packet(3000, 64);
        try (var f = new Fixture(Options.forwarding())) {
            f.read(frame(body(packet, THRESHOLD)));
            assertEquals(1, f.forwarder.forwarded());
            var pipeline = f.backend.pipeline();
            pipeline.remove(CompressedKeep.NAME);
            pipeline.addBefore(
                    VelocityCompressedForward.COMPRESSION_DECODER,
                    CompressedKeep.NAME,
                    new CompressedKeep(f.forwarder));
            f.read(frame(body(packet, THRESHOLD)));
            assertPackets(List.of(packet, packet), read(f.wire(), THRESHOLD));
            assertEquals(1, f.forwarder.forwarded());
            assertEquals(1, f.forwarder.notMarked());
            assertFalse(f.forwarder.linkedTo(f.backend));
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
            assertEquals(0, f.forwarder.forwarded());
            assertNull(f.backend.pipeline().get(CompressedKeep.NAME), "the link is removed with it");
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
                    // Under the libdeflate compressor the keep holds only frames with a memory address.
                    if (!consume)
                        replaceFrames(
                                f,
                                frame -> PooledByteBufAllocator.DEFAULT
                                        .directBuffer(frame.readableBytes())
                                        .writeBytes(frame, frame.readerIndex(), frame.readableBytes()));
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
            ChannelPipeline failing = delegating(real);
            doAnswer(call -> {
                        real.addBefore((String) call.getArgument(0), (String) call.getArgument(1), (ChannelHandler)
                                call.getArgument(2));
                        throw new IllegalStateException("insert");
                    })
                    .when(failing)
                    .addBefore(
                            eq(VelocityCompressedForward.COMPRESSION_DECODER),
                            eq(CompressedKeep.NAME),
                            any(ChannelHandler.class));
            Channel backend = mock(Channel.class);
            when(backend.pipeline()).thenReturn(failing);
            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), backend, DECODER, COMPRESSORS);
            assertEquals("backend-install-failed", f.forwarder.linkStatus());
            for (String name : real.names()) assertFalse(name.startsWith("cult-compressed"), name);
            assertFalse(f.forwarder.linkedTo(f.backend));

            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), f.backend, DECODER, COMPRESSORS, TRUSTED);
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
    void retainedAliasChangedAtTheKeepsLastAllocationFailsAsWithTheSwitchOff() {
        byte[] body = body(packet(3000, 72), THRESHOLD);
        checkSameFailure(
                "trailer changed at the last allocation",
                Options.forwarding(),
                f -> {
                    var aliases = new ArrayList<ByteBuf>();
                    f.backend.pipeline().addAfter("frame-decoder", "alias", new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            aliases.add(((ByteBuf) msg).retainedDuplicate());
                            ctx.fireChannelRead(msg);
                        }
                    });
                    Runnable change = once(() -> {
                        ByteBuf duplicate = aliases.removeFirst();
                        int last = duplicate.writerIndex() - 1;
                        duplicate.setByte(last, duplicate.getByte(last) ^ 0x5a);
                        duplicate.release();
                    });
                    var allocator = (RecordingAllocator) f.backend.config().getAllocator();
                    // The decoder's one allocation is direct; the keep's last is its comparison copy, on the heap.
                    if (f.forwarder == null) allocator.beforeDirect = change;
                    else allocator.beforeHeap = change;
                },
                frame(body));
    }

    @Test
    void failedAllocationFinishesTheDecodeAsWithTheSwitchOff() {
        byte[] packet = packet(3000, 79);
        byte[] read = frame(body(packet, THRESHOLD));
        // Direct allocation 1 is the decoder's output, 2 the keep's private frame, which the switch-off read lacks.
        for (int at : new int[] {1, 2}) {
            List<String> seen = new ArrayList<>();
            for (boolean forward : new boolean[] {false, true}) {
                String label = "direct allocation " + at + ", forward " + forward;
                try (var f = new Fixture(forward ? Options.forwarding() : Options.today())) {
                    var allocator = new RecordingAllocator();
                    allocator.beforeDirect = atDirectAllocation(at, () -> {
                        throw new OutOfMemoryError("direct memory");
                    });
                    f.backend.config().setAllocator(allocator);
                    Throwable thrown = null;
                    try {
                        f.read(read.clone());
                    } catch (Throwable failure) {
                        thrown = failure;
                    }
                    seen.add((thrown == null ? "none" : thrown.getClass().getName()) + ", " + sent(f.wire()));
                    ByteBuf frame = f.frameDecoder.frames.getFirst();
                    // Velocity's decoder keeps its retained input when its allocation throws; the keep drops its own.
                    assertEquals(!forward && at == 1 ? 1 : 0, frame.refCnt(), label);
                    if (frame.refCnt() > 0) frame.release(frame.refCnt());
                    if (forward) {
                        assertEquals(0, f.forwarder.forwarded(), label);
                        assertEquals(at == 1 ? 0 : 1, f.forwarder.notMarked(), label);
                        assertEquals(List.of(), f.decoder.inflated, "the keep finishes the decode, " + label);
                    }
                    for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation, " + label);
                }
            }
            assertEquals(seen.get(0), seen.get(1), "direct allocation " + at);
        }
    }

    /** The packets in {@code wire}, read by the vanilla rule. */
    private static String sent(byte[] wire) {
        return read(wire, THRESHOLD, true).stream()
                .map(frame -> Arrays.toString(frame.packet()))
                .toList()
                .toString();
    }

    @Test
    void nonCanonicalZeroDataLengthIsPassedOnUnmarked() {
        byte[] packet = packet(100, 80);
        byte[] read = frame(concat(new byte[] {(byte) 0x80, 0x00}, packet));
        try (var f = new Fixture(Options.forwarding())) {
            var allocator = new RecordingAllocator();
            f.backend.config().setAllocator(allocator);
            f.read(read);
            byte[] wire = f.wire();
            assertSameBytes(today(Options.forwarding(), read), wire);
            assertPackets(List.of(packet), read(wire, THRESHOLD));
            assertEquals(List.of(), allocator.allocated, "the keep does not hold a zero data length");
            assertEquals(1, f.forwarder.notMarked(), "the passed-on frame is not marked");
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
                assertEquals(0, f.forwarder.forwarded());
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
            f.read(read);
            today = f.wire();
        }
        assertPackets(List.of(packet), read(today, THRESHOLD, true));
        try (var f = new Fixture(Options.forwarding())) {
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
                f.decoder.packetLimiter = limiter(sequences.get(i).apply(f));
                f.read(read.clone());
                today = f.wire();
            }
            assertPackets(expected.get(i), read(today, THRESHOLD));
            for (boolean beforeLink : new boolean[] {true, false}) {
                String label = "sequence " + i + ", limiter set before the link " + beforeLink;
                try (var f = new Fixture(beforeLink ? Options.forwarding().unlinked() : Options.forwarding())) {
                    f.decoder.packetLimiter = limiter(sequences.get(i).apply(f));
                    if (beforeLink) {
                        VelocityCompressedForward.place(
                                f.forwarder, f.client.pipeline(), f.backend, DECODER, COMPRESSORS);
                        assertEquals("backend-limiter", f.forwarder.linkStatus(), label);
                        assertNull(f.backend.pipeline().get(CompressedKeep.NAME), label);
                    }
                    f.read(read.clone());
                    assertSameBytes(today, f.wire());
                    assertEquals(0, f.forwarder.forwarded(), label);
                    assertEquals("backend-limiter", f.forwarder.linkStatus(), label);
                    assertNull(f.backend.pipeline().get(CompressedKeep.NAME), label);
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
                if (forward) assertEquals(0, f.forwarder.forwarded(), label);
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
                f.decoder.packetLimiter = limiter(limiter.apply(f));
                f.read(read.clone());
                today = f.wire();
            }
            assertPackets(List.of(other, packet), read(today, THRESHOLD));
            try (var f = new Fixture(Options.forwarding())) {
                var allocator = new RecordingAllocator();
                allocator.beforeDirect = once(() -> f.decoder.packetLimiter = limiter(limiter.apply(f)));
                f.backend.config().setAllocator(allocator);
                f.read(read.clone());
                assertSameBytes(today, f.wire());
                assertEquals(0, f.forwarder.forwarded(), label);
                assertEquals("backend-limiter", f.forwarder.linkStatus(), label);
                assertNull(f.backend.pipeline().get(CompressedKeep.NAME), label);
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
            VelocityCompressedForward.place(
                    f.forwarder, f.client.pipeline(), f.backend, CopyDecoder.class, COMPRESSORS);
            assertEquals("backend-limiter", f.forwarder.linkStatus());
            assertNull(pipeline.get(CompressedKeep.NAME));
        }
    }

    /** A limiter that clears itself when it runs and fires {@code other}, inflated, from the decoder's context. */
    private static PacketLimiter selfClearingLimiter(Fixture f, byte[] other) {
        return limiter(once(() -> {
            f.decoder.packetLimiter = null;
            f.backend
                    .pipeline()
                    .context(VelocityCompressedForward.COMPRESSION_DECODER)
                    .fireChannelRead(f.track(Unpooled.directBuffer(other.length).writeBytes(other)));
        }));
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
        checkSelfClearingLimiterSetAt(1);
    }

    @Test
    void selfClearingLimiterSetByTheKeepsInflateAllocationLeavesTheReadAsWithTheSwitchOff() {
        checkSelfClearingLimiterSetAt(2);
    }

    /** The limiter is set before the {@code n}th direct allocation on the backend: the keep's frame, then its inflate's. */
    private void checkSelfClearingLimiterSetAt(int n) {
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
            assertEquals("backend-limiter", f.forwarder.linkStatus());
            for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation");
        }
    }

    @Test
    void nestedReadThroughTheKeepFromAHandlerAfterTheDecoderForwardsBothFrames() {
        byte[] outer = packet(3000, 98);
        byte[] inner = packet(3000, 99);
        byte[] read = frame(body(outer, THRESHOLD));
        BiConsumer<Fixture, ChannelPipeline> nested = (f, pipeline) -> pipeline.addAfter(
                VelocityCompressedForward.COMPRESSION_DECODER, "nested", new ChannelInboundHandlerAdapter() {
                    private boolean fired;

                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        if (!fired) {
                            fired = true;
                            ctx.pipeline()
                                    .context("read-timeout")
                                    .fireChannelRead(f.track(Unpooled.wrappedBuffer(body(inner, THRESHOLD))));
                        }
                        ctx.fireChannelRead(msg);
                    }
                });
        byte[] today;
        try (var f = new Fixture(Options.today())) {
            nested.accept(f, f.backend.pipeline());
            f.read(read.clone());
            today = f.wire();
        }
        assertPackets(List.of(inner, outer), read(today, THRESHOLD));
        try (var f = new Fixture(Options.forwarding())) {
            nested.accept(f, f.backend.pipeline());
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
                                client, CompressEncoder.class, DECODER, COMPRESSORS, refusals::add);
                        if (forwarder != null)
                            VelocityCompressedForward.place(forwarder, client, f.backend, DECODER, COMPRESSORS);
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
                f.read(read);
                assertEquals(
                        1,
                        f.decoder.inflated.size(),
                        "the decoder decodes it, below the client's threshold " + belowClientThreshold);
                assertSameBytes(today(options, read), f.wire());
                assertEquals(0, f.forwarder.forwarded());
            }
        }
    }

    @Test
    void clientDecoderOnItsOwnExecutorInstallsNothing() throws Exception {
        var channel = channel();
        var group = new DefaultEventExecutorGroup(1);
        try {
            ChannelPipeline pipeline = channel.pipeline();
            pipeline.addLast(group, VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD));
            pipeline.addLast(CompressedForwarder.COMPRESSION_ENCODER, new CompressEncoder(THRESHOLD));
            var refusals = new ArrayList<VelocityCompressedForward.Refusal>();
            assertNull(VelocityCompressedForward.install(
                    pipeline, CompressEncoder.class, DECODER, COMPRESSORS, refusals::add));
            assertEquals(List.of(VelocityCompressedForward.Refusal.EXECUTOR), refusals);
            assertNull(pipeline.get(CompressedForwarder.NAME));
        } finally {
            channel.finishAndReleaseAll();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).await();
        }
    }

    @Test
    void unmarkedMessageGetsNoCallFromTheForwarder() {
        byte[] packet = packet(3000, 84);
        leakDetectionDisabled(() -> {
            for (boolean throwing : new boolean[] {false, true}) {
                List<String> seen = new ArrayList<>();
                for (boolean forward : new boolean[] {false, true}) {
                    try (var f = new Fixture(forward ? Options.forwarding() : Options.today())) {
                        ByteBuf real = Unpooled.buffer(packet.length).writeBytes(packet);
                        var delegate = AdditionalAnswers.delegatesTo(real);
                        List<String> calls = new ArrayList<>();
                        ByteBuf msg = mock(ByteBuf.class, call -> {
                            calls.add(call.getMethod().getName());
                            if (throwing)
                                throw new UnsupportedOperationException(
                                        call.getMethod().getName());
                            return delegate.answer(call);
                        });
                        ChannelFuture write =
                                f.client.pipeline().context("minecraft-decoder").write(msg);
                        f.client.flush();
                        seen.add(sent(f.wire()) + ", promise " + describe(write.cause()) + ", calls " + calls
                                + ", count " + real.refCnt());
                        if (real.refCnt() > 0) real.release(real.refCnt());
                        if (forward) assertEquals("linked", f.forwarder.linkStatus());
                    }
                }
                assertTrue(seen.get(0).contains(throwing ? "UnsupportedOperationException" : Arrays.toString(packet)));
                assertEquals(seen.get(0), seen.get(1), "every method throws " + throwing);
            }
        });
    }

    /** A heap buffer whose {@code skipBytes} throws; netty's {@code writeBytes} consumes it through its reader index. */
    static final class NoSkip extends UnpooledHeapByteBuf {
        NoSkip(int capacity) {
            super(UnpooledByteBufAllocator.DEFAULT, capacity, capacity);
        }

        @Override
        public ByteBuf skipBytes(int length) {
            throw new UnsupportedOperationException("skipBytes");
        }
    }

    @Test
    void unmarkedBufferWhoseSkipBytesThrowsIsSentAsWithTheSwitchOff() {
        byte[] packet = packet(100, 152);
        List<String> seen = new ArrayList<>();
        leakDetectionDisabled(() -> {
            for (boolean forward : new boolean[] {false, true}) {
                try (var f = new Fixture(forward ? Options.forwarding() : Options.today())) {
                    f.client.config().setAllocator(UnpooledByteBufAllocator.DEFAULT);
                    var input = new NoSkip(packet.length);
                    input.writeBytes(packet);
                    ChannelFuture write = f.client.writeAndFlush(input);
                    seen.add(sent(f.wire()) + ", promise " + describe(write.cause()) + ", reader " + input.readerIndex()
                            + ", count " + input.refCnt());
                    if (input.refCnt() > 0) input.release(input.refCnt());
                }
            }
        });
        assertTrue(seen.get(0).endsWith(", promise ok, reader 100, count 0"), seen.get(0));
        assertEquals(seen.get(0), seen.get(1));
    }

    @Test
    void publishedBufferAtNettysMaximumCountFailsAsWithTheSwitchOff() {
        assertEquals(1_073_741_823, CompressedForwarder.MAX_REFERENCES, "the largest live count of netty 4.2.19");
        ByteBuf probe = Unpooled.buffer(1).retain(CompressedForwarder.MAX_REFERENCES - 1);
        assertEquals(CompressedForwarder.MAX_REFERENCES, probe.refCnt());
        assertThrows(IllegalReferenceCountException.class, probe::retain);
        probe.release(probe.refCnt());
        byte[] read = frame(body(packet(3000, 156), THRESHOLD));
        for (int count : new int[] {CompressedForwarder.MAX_REFERENCES - 1, CompressedForwarder.MAX_REFERENCES}) {
            List<String> seen = new ArrayList<>();
            for (boolean forward : new boolean[] {false, true}) {
                String label = "count " + count + ", forward " + forward;
                var writes = new ArrayList<ChannelFuture>();
                var held = new ArrayList<ByteBuf>();
                var options = (forward ? Options.forwarding() : Options.today()).onFrame((fixture, buf) -> {
                    if (!held.isEmpty()) {
                        fixture.delayedWrite(buf);
                        return;
                    }
                    buf.retain(count - buf.refCnt());
                    held.add(buf);
                    writes.add(fixture.client.write(buf));
                });
                try (var f = new Fixture(options)) {
                    var allocator = new RecordingAllocator();
                    f.backend.config().setAllocator(allocator);
                    f.read(read.clone());
                    ByteBuf buf = held.getFirst();
                    seen.add(sent(f.wire()) + ", promise "
                            + describe(writes.getFirst().cause()) + ", reader " + buf.readerIndex() + ", count "
                            + buf.refCnt());
                    buf.release(buf.refCnt());
                    if (forward) {
                        int forwarded = count < CompressedForwarder.MAX_REFERENCES ? 1 : 0;
                        assertEquals(forwarded, f.forwarder.forwarded(), label);
                        assertEquals("on", f.forwarder.status(), label);
                        byte[] next = packet(3000, 160);
                        f.read(frame(body(next, THRESHOLD)));
                        var frames = read(f.wire(), THRESHOLD);
                        assertPackets(List.of(next), frames.subList(forwarded, frames.size()));
                        assertEquals(forwarded + 1, f.forwarder.forwarded(), "the next frame, " + label);
                    }
                    for (ByteBuf allocated : allocator.allocated)
                        assertEquals(0, allocated.refCnt(), "an allocation, " + label);
                }
            }
            boolean fails = count == CompressedForwarder.MAX_REFERENCES;
            assertEquals(
                    fails,
                    seen.get(0).contains("EncoderException / io.netty.util.IllegalReferenceCountException"),
                    seen.get(0));
            assertEquals(seen.get(0), seen.get(1), "count " + count);
        }
    }

    @Test
    void publishedBufferOfAForwarderDisabledDuringItsPublishIsSentAsWithTheSwitchOff() {
        byte[] packet = packet(3000, 161);
        byte[] read = frame(body(packet, THRESHOLD));
        List<byte[]> wires = new ArrayList<>();
        List<String> promises = new ArrayList<>();
        for (boolean forward : new boolean[] {false, true}) {
            var writes = new ArrayList<ChannelFuture>();
            var options = (forward ? Options.forwarding() : Options.today()).onFrame((fixture, buf) -> {
                if (fixture.forwarder != null) {
                    var client = fixture.client.pipeline();
                    client.addAfter(
                            CompressedForwarder.COMPRESSION_ENCODER, "tap", new ChannelOutboundHandlerAdapter());
                    var other = fixture.backend(Options.forwarding()).channel;
                    VelocityCompressedForward.place(fixture.forwarder, client, other, DECODER, COMPRESSORS, TRUSTED);
                    client.remove("tap");
                }
                writes.add(fixture.client.write(buf));
            });
            try (var f = new Fixture(options)) {
                f.read(read);
                byte[] wire = f.wire();
                assertPackets(List.of(packet), read(wire, THRESHOLD));
                wires.add(wire);
                promises.add(describe(writes.getFirst().cause()));
                if (forward) {
                    assertEquals("adjacency", f.forwarder.status());
                    assertEquals(0, f.forwarder.forwarded());
                }
            }
        }
        assertSameBytes(wires.get(0), wires.get(1));
        assertEquals(List.of("ok", "ok"), promises);
    }

    @Test
    void failedReleaseAfterThePublishIsDeliveredFromTheDecoder() {
        byte[] packet = packet(3000, 157);
        List<String> caught = new ArrayList<>();
        try (var f = new Fixture(Options.forwarding().onFrame((fixture, buf) -> {
            fixture.backend
                    .pipeline()
                    .addBefore(VelocityCompressedForward.COMPRESSION_DECODER, "between", catching("between", caught));
            fixture.delayedWrite(buf);
            fixture.forwarder.markCopy().release();
        }))) {
            f.backend.pipeline().addLast("last", catching("last", caught));
            var thrown =
                    assertThrows(IllegalReferenceCountException.class, () -> f.read(frame(body(packet, THRESHOLD))));
            assertPackets(List.of(packet), read(f.wire(), THRESHOLD));
            assertEquals(1, f.forwarder.forwarded());
            assertEquals(List.of("last: " + describe(thrown)), caught);
        }
        caught.clear();
        // The frame's last release throws after the decode; the copy's release in the publish throws as well.
        try (var f = new Fixture(Options.forwarding().onFrame((fixture, buf) -> {
            fixture.delayedWrite(buf);
            fixture.forwarder.markCopy().release();
        }))) {
            ByteBuf[] own = new ByteBuf[1];
            f.backend.pipeline().addAfter("frame-decoder", "own", new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    ByteBuf frame = (ByteBuf) msg;
                    try {
                        own[0] = Unpooled.copiedBuffer(frame);
                    } finally {
                        frame.release();
                    }
                    ctx.fireChannelRead(own[0]);
                }
            });
            var allocator = new RecordingAllocator();
            allocator.beforeHeap = once(() -> own[0].release());
            f.backend.config().setAllocator(allocator);
            f.backend.pipeline().addLast("last", catching("last", caught));
            var thrown = assertThrows(DecoderException.class, () -> f.read(frame(body(packet, THRESHOLD))));
            assertInstanceOf(IllegalReferenceCountException.class, thrown.getCause());
            assertEquals(1, thrown.getSuppressed().length, "the publish's release failure");
            assertInstanceOf(IllegalReferenceCountException.class, thrown.getSuppressed()[0]);
            assertPackets(List.of(packet), read(f.wire(), THRESHOLD));
            assertEquals(1, f.forwarder.forwarded());
            assertEquals(List.of("last: " + describe(thrown)), caught);
            assertEquals(0, own[0].refCnt());
        }
    }

    @Test
    void nonMinimalClaimIsDecodedAndRecompressedAsWithTheSwitchOff() {
        byte[] packet = packet(3000, 158);
        byte[] body = body(packet, THRESHOLD);
        assertArrayEquals(new byte[] {(byte) 0xb8, 0x17}, Arrays.copyOf(body, 2), "3000 as a varint");
        byte[] read =
                frame(concat(new byte[] {(byte) 0xb8, (byte) 0x97, 0x00}, Arrays.copyOfRange(body, 2, body.length)));
        byte[] today = today(Options.today(), read);
        assertPackets(List.of(packet), read(today, THRESHOLD));
        try (var f = new Fixture(Options.forwarding())) {
            f.read(read);
            assertSameBytes(today, f.wire());
            assertEquals(0, f.forwarder.forwarded());
            assertEquals(1, f.forwarder.notMarked());
            assertEquals(1, f.decoder.inflated.size(), "the decoder decodes it");
            assertEquals("linked", f.forwarder.linkStatus());
        }
    }

    @Test
    void limiterSetAtAFailedPrivateAllocationUnlinksAsWithTheSwitchOff() {
        byte[] packet = packet(3000, 159);
        byte[] read = frame(body(packet, THRESHOLD));
        byte[] today;
        List<Integer> accounted;
        try (var f = new Fixture(Options.today())) {
            var allocator = new RecordingAllocator();
            allocator.beforeDirect = once(() -> f.decoder.packetLimiter = recording(f, true));
            f.backend.config().setAllocator(allocator);
            f.read(read.clone());
            today = f.wire();
            accounted = List.copyOf(f.accounted);
        }
        assertEquals(List.of(3000), accounted);
        // Direct allocation 2 is the keep's private frame, the heap allocation its comparison copy.
        for (boolean copy : new boolean[] {false, true}) {
            String label = copy ? "comparison copy" : "private frame";
            try (var f = new Fixture(Options.forwarding())) {
                var allocator = new RecordingAllocator();
                Runnable fail = () -> {
                    f.decoder.packetLimiter = recording(f, true);
                    throw new OutOfMemoryError("memory");
                };
                if (copy) allocator.beforeHeap = once(fail);
                else allocator.beforeDirect = atDirectAllocation(2, fail);
                f.backend.config().setAllocator(allocator);
                f.read(read.clone());
                assertSameBytes(today, f.wire());
                assertEquals(accounted, f.accounted, label);
                assertEquals(0, f.forwarder.forwarded(), label);
                assertEquals(CompressedKeep.LIMITED, f.forwarder.linkStatus(), label);
                assertNull(f.backend.pipeline().get(CompressedKeep.NAME), label);
                for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation, " + label);
            }
        }
    }

    /** How the switched-off client fails the write of {@code packet} in a buffer whose first release throws. */
    private static String releaseFailureSwitchedOff(byte[] packet, boolean encoderFailure) {
        try (var f = new Fixture(Options.today())) {
            var input = FailingRelease.of(packet.length, encoderFailure);
            input.writeBytes(packet);
            ChannelFuture write = f.client.write(input);
            String failure = describe(write.cause());
            assertEquals(
                    "io.netty.handler.codec.EncoderException / io.netty.util.IllegalReferenceCountException", failure);
            if (encoderFailure) assertSame(input.failure, write.cause());
            assertEquals(0, f.wire().length);
            assertTrue(input.release());
            return failure;
        }
    }

    @Test
    void keepReleasesItsBuffersWhenTheInflatedReleaseThrows() {
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
            assertEquals(0, f.decoder.compressor.destinations.getFirst().refCnt(), "the keep's inflated buffer");
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
            var channel = channel();
            try {
                ChannelPipeline real = channel.pipeline();
                var compressor = new SharedCompressor();
                real.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD, compressor));
                real.addLast(name, new CompressEncoder(THRESHOLD, true, compressor));
                ChannelPipeline racing = delegating(real);
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
                                racing, CompressEncoder.class, DECODER, COMPRESSORS, refusals::add),
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
            ChannelPipeline racing = delegating(real);
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
            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), backend, DECODER, COMPRESSORS);
            assertEquals("backend-adjacency", f.forwarder.linkStatus());
            for (String handler : real.names()) assertFalse(handler.startsWith("cult-compressed"), handler);
        }
    }

    @Test
    void handlerInsertedBeforeTheForwarderDuringInstallInstallsNothing() {
        String name = CompressedForwarder.COMPRESSION_ENCODER;
        var channel = channel();
        try {
            ChannelPipeline real = channel.pipeline();
            var compressor = new SharedCompressor();
            real.addLast(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD, compressor));
            real.addLast(name, new CompressEncoder(THRESHOLD, true, compressor));
            ChannelPipeline racing = delegating(real);
            doAnswer(call -> {
                        real.addAfter(name, CompressedForwarder.NAME, (ChannelHandler) call.getArgument(2));
                        return real.addAfter(name, "foreign", new ChannelOutboundHandlerAdapter());
                    })
                    .when(racing)
                    .addAfter(eq(name), eq(CompressedForwarder.NAME), any(ChannelHandler.class));
            var refusals = new ArrayList<VelocityCompressedForward.Refusal>();
            assertNull(VelocityCompressedForward.install(
                    racing, CompressEncoder.class, DECODER, COMPRESSORS, refusals::add));
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
        try (var f = new Fixture(Options.forwarding().unlinked())) {
            ChannelPipeline real = f.backend.pipeline();
            ChannelPipeline racing = delegating(real);
            doAnswer(call -> {
                        real.addBefore(name, CompressedKeep.NAME, (ChannelHandler) call.getArgument(2));
                        return real.addBefore(name, "foreign", new ChannelInboundHandlerAdapter());
                    })
                    .when(racing)
                    .addBefore(eq(name), eq(CompressedKeep.NAME), any(ChannelHandler.class));
            Channel backend = mock(Channel.class);
            when(backend.pipeline()).thenReturn(racing);
            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), backend, DECODER, COMPRESSORS);
            assertEquals("backend-adjacency", f.forwarder.linkStatus());
            assertNotNull(real.get("foreign"));
            for (String handler : real.names()) assertFalse(handler.startsWith("cult-compressed"), handler);
        }
    }

    @Test
    void installRefusalsLogTheirOwnWarningOnce() throws Exception {
        String name = CompressedForwarder.COMPRESSION_ENCODER;
        var client = channel();
        var other = channel();
        var group = new DefaultEventExecutorGroup(1);
        try (var log = mockStatic(LogUtil.class)) {
            var forward = new VelocityCompressedForward(
                    mock(ProxyServer.class),
                    new Object(),
                    p -> null,
                    new VelocityCompressedForward.Codecs(OtherEncoder.class, DECODER, COMPRESSORS));
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
                        real, CompressEncoder.class, DECODER, COMPRESSORS, forward::refused));
            log.verify(() -> LogUtil.warn(VelocityCompressedForward.Refusal.DECODER.warning), times(1));
            real.addFirst(VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD));
            for (int i = 0; i < 2; i++)
                assertNull(VelocityCompressedForward.install(
                        real, CompressEncoder.class, DECODER, COMPRESSORS, forward::refused));
            log.verify(
                    () -> LogUtil.warn("A client's compression encoder and decoder do not share one open Velocity"
                            + " compressor; its frames are compressed by Velocity"),
                    times(1));
            real.replace(
                    VelocityCompressedForward.COMPRESSION_DECODER,
                    VelocityCompressedForward.COMPRESSION_DECODER,
                    new CompressDecoder(THRESHOLD, compressor));
            ChannelPipeline racing = delegating(real);
            doAnswer(call -> {
                        real.replace(name, name, new CompressEncoder(THRESHOLD));
                        return real.addAfter(name, CompressedForwarder.NAME, (ChannelHandler) call.getArgument(2));
                    })
                    .when(racing)
                    .addAfter(eq(name), eq(CompressedForwarder.NAME), any(ChannelHandler.class));
            for (int i = 0; i < 2; i++)
                assertNull(VelocityCompressedForward.install(
                        racing, CompressEncoder.class, DECODER, COMPRESSORS, forward::refused));
            log.verify(
                    () -> LogUtil.warn("A client's compression encoder is not where the forwarder expects it after the"
                            + " insert; its frames are compressed by Velocity"),
                    times(1));
            real.remove(name);
            real.addLast(group, name, new CompressEncoder(THRESHOLD));
            for (int i = 0; i < 2; i++)
                assertNull(VelocityCompressedForward.install(
                        real, CompressEncoder.class, DECODER, COMPRESSORS, forward::refused));
            log.verify(() -> LogUtil.warn(VelocityCompressedForward.Refusal.EXECUTOR.warning), times(1));
            log.verify(() -> LogUtil.warn(anyString()), times(5));
        } finally {
            client.finishAndReleaseAll();
            other.finishAndReleaseAll();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).await();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void forwardedFrameIsReleasedWhenTheInputsReleaseThrows(boolean encoderFailure) {
        byte[] packet = packet(3000, 71);
        byte[] body = body(packet, THRESHOLD);
        try (var f = new Fixture(Options.forwarding().unlinked())) {
            var input = FailingRelease.of(packet.length, encoderFailure);
            input.writeBytes(packet);
            ByteBuf copy = f.track(Unpooled.copiedBuffer(packet));
            ByteBuf kept = f.track(Unpooled.wrappedBuffer(body));
            ByteBuf frame = f.forwarder.frameBuffer(ByteBufAllocator.DEFAULT, kept.readableBytes());
            assertNotNull(frame);
            int header = CompressedForwarder.varIntSize(packet.length);
            CompressedForwarder.fillFrame(
                    frame, packet.length, kept, kept.readerIndex() + header, kept.readableBytes() - header);
            ChannelFuture write;
            f.forwarder.mark(input, input.readerIndex(), input.writerIndex(), copy, frame);
            try {
                write = f.client.write(input);
            } finally {
                f.forwarder.mark(null, 0, 0, null, null);
            }
            assertEquals(releaseFailureSwitchedOff(packet, encoderFailure), describe(write.cause()));
            if (encoderFailure) assertSame(input.failure, write.cause());
            assertEquals(0, frame.refCnt(), "the frame no write took is released");
            assertEquals(0, f.wire().length);
            assertEquals(0, f.forwarder.forwarded());
            assertTrue(f.forwarder.stats().contains(", forwarded=0 (0 B sent, 0 B inflated),"), f.forwarder.stats());
            assertTrue(input.release());
            copy.release();
            kept.release();
        }
    }

    /**
     * Unpooled buffers, each recorded, from an allocator these tests let the keep trust; the first {@link #failing}
     * direct allocations throw, and with {@link #failingSlices} a direct buffer's slice throws.
     */
    static final class RecordingAllocator extends AbstractByteBufAllocator {
        final List<ByteBuf> allocated = new ArrayList<>();
        int failing;
        boolean failingSlices;
        // Runs before each direct allocation.
        Runnable beforeDirect;
        // Runs before each heap allocation.
        Runnable beforeHeap;

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            if (beforeHeap != null) beforeHeap.run();
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
                        public ByteBuf slice(int index, int length) {
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
        final RuntimeException failure;
        private boolean failed;

        FailingRelease(int capacity) {
            this(capacity, new IllegalReferenceCountException(0, -1));
        }

        FailingRelease(int capacity, RuntimeException failure) {
            super(UnpooledByteBufAllocator.DEFAULT, capacity, capacity);
            this.failure = failure;
        }

        /** With {@code encoderFailure}, the release throws an encoder exception caused by the count failure. */
        static FailingRelease of(int capacity, boolean encoderFailure) {
            var failure = new IllegalReferenceCountException(0, -1);
            return new FailingRelease(capacity, encoderFailure ? new EncoderException(failure) : failure);
        }

        @Override
        public boolean release() {
            if (!failed) {
                failed = true;
                throw failure;
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
     * A heap buffer of a class netty does not provide that records the calls it gets, reports {@code nio} NIO buffers,
     * and runs the hook set by {@link #onFirst} once, on the first call of that name.
     */
    static final class Watched extends UnpooledHeapByteBuf {
        private final List<String> calls;
        private final int nio;
        private String hookOn;
        private Runnable hook;

        Watched(int capacity, int nio, List<String> calls) {
            super(UnpooledByteBufAllocator.DEFAULT, capacity, capacity);
            this.nio = nio;
            this.calls = calls;
        }

        Watched onFirst(String call, Runnable hook) {
            hookOn = call;
            this.hook = hook;
            return this;
        }

        private void seen(String call) {
            if (calls == null) return;
            calls.add(call);
            Runnable run = call.equals(hookOn) ? hook : null;
            if (run == null) return;
            hook = null;
            run.run();
        }

        @Override
        public boolean isReadable() {
            seen("isReadable");
            return super.isReadable();
        }

        @Override
        public int readableBytes() {
            seen("readableBytes");
            return super.readableBytes();
        }

        @Override
        public int refCnt() {
            seen("refCnt");
            return super.refCnt();
        }

        @Override
        public int nioBufferCount() {
            seen("nioBufferCount");
            return nio;
        }

        @Override
        public byte getByte(int index) {
            seen("getByte");
            return super.getByte(index);
        }

        @Override
        public ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
            seen("getBytes");
            return super.getBytes(index, dst, dstIndex, length);
        }

        @Override
        public ByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length) {
            seen("getBytes");
            return super.getBytes(index, dst, dstIndex, length);
        }

        @Override
        public ByteBuf getBytes(int index, ByteBuffer dst) {
            seen("getBytes");
            return super.getBytes(index, dst);
        }

        @Override
        public ByteBuffer nioBuffer(int index, int length) {
            seen("nioBuffer");
            return super.nioBuffer(index, length);
        }

        @Override
        public boolean release() {
            seen("release");
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

    /**
     * Replaces each backend frame with an equal {@link HookedRelease} that runs {@code hook} on its release, passed
     * on as {@code wrap} returns it.
     */
    private static void hookRelease(Fixture f, Runnable hook, Function<ByteBuf, ByteBuf> wrap) {
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
                ctx.fireChannelRead(f.track(wrap.apply(input)));
            }
        });
    }

    /** A one-component composite that owns {@code buf}. */
    private static ByteBuf composite(ByteBuf buf) {
        return Unpooled.compositeBuffer(1).addComponent(true, buf);
    }

    /** A slice of {@code buf} that shares its reference count, so the slice's release is {@code buf}'s. */
    private static ByteBuf slice(ByteBuf buf) {
        return buf.slice();
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
                    assertEquals(CompressedKeep.UNTRUSTED, f.forwarder.linkStatus());
                }
            }
        }
        assertSameBytes(wires[0], wires[1]);
        assertPackets(List.of(packet, other), read(wires[1], THRESHOLD));
    }

    /** A handler before the decoder that drops the next frame it gets, fires {@code other} compressed and leaves. */
    private static ChannelInboundHandlerAdapter swap(Fixture f, byte[] other) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ((ByteBuf) msg).release();
                ctx.pipeline().remove(this);
                ctx.fireChannelRead(f.track(Unpooled.wrappedBuffer(body(other, THRESHOLD))));
            }
        };
    }

    /**
     * A backend frame of a class netty does not provide whose release runs {@code hook}: the client gets {@code
     * expected}, with the same bytes as with the switch off, and nothing is forwarded.
     */
    private void checkInputReleaseHook(Function<Fixture, Runnable> hook, List<byte[]> expected) {
        checkInputReleaseHook(hook, expected, Function.identity());
    }

    /** As {@link #checkInputReleaseHook(Function, List)}, the frame passed on as {@code wrap} returns it. */
    private void checkInputReleaseHook(
            Function<Fixture, Runnable> hook, List<byte[]> expected, Function<ByteBuf, ByteBuf> wrap) {
        byte[] read = frame(body(packet(3000, 108), THRESHOLD));
        byte[][] wires = new byte[2][];
        for (boolean forward : new boolean[] {false, true}) {
            try (var f = new Fixture(forward ? Options.forwarding() : Options.today())) {
                hookRelease(f, hook.apply(f), wrap);
                f.read(read.clone());
                wires[forward ? 1 : 0] = f.wire();
                if (forward) assertEquals(0, f.forwarder.forwarded());
            }
        }
        assertSameBytes(wires[0], wires[1]);
        assertPackets(expected, read(wires[1], THRESHOLD));
    }

    /** Inserts {@link #swap} before the decoder. */
    private static Function<Fixture, Runnable> insertSwap() {
        return f -> () -> f.backend
                .pipeline()
                .addBefore(VelocityCompressedForward.COMPRESSION_DECODER, "swap", swap(f, packet(3000, 109)));
    }

    @Test
    void sliceOrCompositeOverAForeignFrameLeavesTheReadAsWithTheSwitchOff() {
        checkInputReleaseHook(insertSwap(), List.of(packet(3000, 108)), VelocityCompressedForwardTest::slice);
        checkInputReleaseHook(insertSwap(), List.of(packet(3000, 108)), VelocityCompressedForwardTest::composite);
    }

    @Test
    void handlerInsertedByTheInputsReleaseLeavesTheReadAsWithTheSwitchOff() {
        checkInputReleaseHook(insertSwap(), List.of(packet(3000, 108)));
    }

    @Test
    void limiterSetByTheInputsReleaseLeavesTheReadAsWithTheSwitchOff() {
        checkInputReleaseHook(
                f -> () -> f.decoder.packetLimiter = selfClearingLimiter(f, packet(3000, 109)),
                List.of(packet(3000, 108)));
    }

    @Test
    void allocatorSetByTheInputsReleaseLeavesTheReadAsWithTheSwitchOff() {
        checkInputReleaseHook(
                f -> () -> f.backend.config().setAllocator(new ForeignAllocator()), List.of(packet(3000, 108)));
    }

    @Test
    void handlerInsertedByTheKeepsAllocationLeavesTheReadAsWithTheSwitchOff() {
        byte[] packet = packet(3000, 110);
        byte[] other = packet(3000, 109);
        byte[] read = frame(body(packet, THRESHOLD));
        // Switched off, a handler inserted before the decoder at its allocation never sees the frame being decoded.
        byte[] today;
        try (var f = new Fixture(Options.today())) {
            var allocator = new RecordingAllocator();
            allocator.beforeDirect = atDirectAllocation(
                    1,
                    () -> f.backend
                            .pipeline()
                            .addBefore(VelocityCompressedForward.COMPRESSION_DECODER, "swap", swap(f, other)));
            f.backend.config().setAllocator(allocator);
            f.read(read.clone());
            today = f.wire();
        }
        var published = new ArrayList<byte[]>();
        try (var f = new Fixture(recording(published))) {
            var allocator = new RecordingAllocator();
            allocator.beforeDirect = atDirectAllocation(
                    1,
                    () -> f.backend
                            .pipeline()
                            .addBefore(VelocityCompressedForward.COMPRESSION_DECODER, "swap", swap(f, other)));
            f.backend.config().setAllocator(allocator);
            f.read(read.clone());
            assertSameBytes(today, f.wire());
            assertPackets(published, read(today, THRESHOLD));
            assertArrayEquals(packet, published.getFirst());
            assertEquals(0, f.forwarder.forwarded());
            assertEquals("backend-adjacency", f.forwarder.linkStatus());
            for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation");
        }
    }

    @Test
    void rejectedFireIsDeliveredFromTheDecodersContextAsWithTheSwitchOff() throws Exception {
        byte[] read = frame(body(packet(3000, 110), THRESHOLD));
        boolean[] rejecting = {false};
        var executor = new DefaultEventLoop() {
            @Override
            public void execute(Runnable task) {
                if (rejecting[0]) throw new RejectedExecutionException("shut down");
                super.execute(task);
            }
        };
        var seen = new ArrayList<List<String>>();
        try {
            for (boolean forward : new boolean[] {false, true}) {
                var caught = new ArrayList<String>();
                try (var f = new Fixture(forward ? Options.forwarding() : Options.today())) {
                    ChannelPipeline pipeline = f.backend.pipeline();
                    pipeline.addAfter(
                            executor,
                            VelocityCompressedForward.COMPRESSION_DECODER,
                            "rejecting",
                            new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    ctx.fireChannelRead(msg);
                                }
                            });
                    pipeline.addAfter(
                            VelocityCompressedForward.COMPRESSION_DECODER, "after", new ChannelInboundHandlerAdapter() {
                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    caught.add("after: " + describe(cause));
                                }
                            });
                    var allocator = new RecordingAllocator();
                    allocator.beforeDirect = atDirectAllocation(
                            1,
                            () -> pipeline.addBefore(
                                    VelocityCompressedForward.COMPRESSION_DECODER,
                                    "between",
                                    catching("between", caught)));
                    f.backend.config().setAllocator(allocator);
                    rejecting[0] = true;
                    try {
                        f.read(read.clone());
                    } finally {
                        rejecting[0] = false;
                    }
                    for (ByteBuf buf : allocator.allocated) if (buf.refCnt() != 0) buf.release(buf.refCnt());
                    assertEquals(0, f.wire().length, "forward " + forward);
                    if (forward) {
                        assertEquals(0, f.forwarder.forwarded());
                        assertEquals("backend-adjacency", f.forwarder.linkStatus());
                    }
                }
                seen.add(caught);
            }
        } finally {
            executor.shutdownGracefully(0, 1, TimeUnit.SECONDS).await();
        }
        String rejected = "after: " + RejectedExecutionException.class.getName() + " / null";
        assertEquals(List.of(rejected, rejected), seen.getFirst(), "the read and its read complete");
        assertEquals(seen.getFirst(), seen.get(1));
    }

    @Test
    void limiterSetByTheKeepsAllocationsGetsTheReadUnheld() {
        byte[] packet = packet(3000, 111);
        byte[] read = frame(body(packet, THRESHOLD));
        byte[] today = today(Options.forwarding(), read);
        for (int n = 1; n <= 2; n++) {
            String label = "direct allocation " + n;
            try (var f = new Fixture(Options.forwarding())) {
                var allocator = new RecordingAllocator();
                boolean[] set = {false};
                allocator.beforeDirect = atDirectAllocation(n, () -> {
                    set[0] = true;
                    f.decoder.packetLimiter = limiter(() -> {});
                });
                f.backend.config().setAllocator(allocator);
                f.read(read.clone());
                assertTrue(set[0], label);
                assertSameBytes(today, f.wire());
                assertEquals(0, f.forwarder.forwarded(), label);
                assertEquals("backend-limiter", f.forwarder.linkStatus(), label);
                assertNull(f.backend.pipeline().get(CompressedKeep.NAME), label);
                for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation, " + label);
            }
        }
    }

    @Test
    void clientEncoderOnItsOwnOpenCompressorInstallsNothing() {
        byte[] large = packet(3000, 112);
        byte[] read = frame(body(large, THRESHOLD));
        String name = CompressedForwarder.COMPRESSION_ENCODER;
        byte[][] wires = new byte[2][];
        for (boolean forward : new boolean[] {false, true}) {
            String label = "forward " + forward;
            var writes = new ArrayList<ChannelFuture>();
            var refusals = new ArrayList<VelocityCompressedForward.Refusal>();
            try (var f =
                    new Fixture(Options.today().onFrame((fixture, buf) -> writes.add(fixture.client.write(buf))))) {
                ChannelPipeline client = f.client.pipeline();
                client.replace(name, name, new CompressEncoder(THRESHOLD, true, new SharedCompressor()));
                assertFalse(f.compressor.disposed, label);
                if (forward) {
                    var forwarder = VelocityCompressedForward.install(
                            client, CompressEncoder.class, DECODER, COMPRESSORS, refusals::add);
                    if (forwarder != null)
                        VelocityCompressedForward.place(forwarder, client, f.backend, DECODER, COMPRESSORS);
                    assertEquals(List.of(VelocityCompressedForward.Refusal.COMPRESSOR), refusals, label);
                    assertNull(forwarder, label);
                    assertNull(client.get(CompressedForwarder.NAME), label);
                }
                f.read(read);
                assertEquals(1, writes.size(), label);
                assertTrue(writes.getFirst().isSuccess(), label);
                wires[forward ? 1 : 0] = f.wire();
            }
        }
        assertSameBytes(wires[0], wires[1]);
        assertPackets(List.of(large), read(wires[1], THRESHOLD));
    }

    @Test
    void compressorGettersAreCachedPerRoleAndAClosedCompressorIsRefused() throws Exception {
        var compressor = new SharedCompressor();
        var encoder = new CompressEncoder(THRESHOLD, true, compressor);
        var decoder = new CompressDecoder(THRESHOLD, compressor);
        assertSame(compressor, VelocityCompressedForward.sharedCompressor(encoder, decoder, COMPRESSORS));
        Object encoderGetter = staticField(VelocityCompressedForward.class, "encoderCompressorGetter");
        Object decoderGetter = staticField(VelocityCompressedForward.class, "decoderCompressorGetter");
        assertInstanceOf(MethodHandle.class, encoderGetter);
        assertInstanceOf(MethodHandle.class, decoderGetter);
        assertSame(compressor, VelocityCompressedForward.sharedCompressor(encoder, decoder, COMPRESSORS));
        assertSame(encoderGetter, staticField(VelocityCompressedForward.class, "encoderCompressorGetter"));
        assertSame(decoderGetter, staticField(VelocityCompressedForward.class, "decoderCompressorGetter"));
        compressor.disposed = true;
        assertNull(VelocityCompressedForward.sharedCompressor(encoder, decoder, COMPRESSORS));
    }

    @Test
    void keepRemovedUnderneathTheUnlinkStillPassesItsFrameOn() {
        byte[] packet = packet(3000, 68);
        try (var f = new Fixture(Options.forwarding().unlinked())) {
            ChannelPipeline real = f.backend.pipeline();
            ChannelPipeline racing = delegating(real);
            doAnswer(call -> {
                        real.remove((ChannelHandler) call.getArgument(0));
                        throw new NoSuchElementException(CompressedKeep.NAME);
                    })
                    .when(racing)
                    .remove(any(CompressedKeep.class));
            Channel backend = mock(Channel.class);
            when(backend.pipeline()).thenReturn(racing);
            when(backend.eventLoop()).thenReturn(f.backend.eventLoop());
            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), backend, DECODER, COMPRESSORS);
            assertEquals("linked", f.forwarder.linkStatus());
            real.addBefore(VelocityCompressedForward.COMPRESSION_DECODER, "tap", new ChannelInboundHandlerAdapter());
            f.read(frame(body(packet, THRESHOLD)));
            assertEquals(0, f.frameDecoder.frames.getFirst().refCnt(), "the frame is passed on and released");
            assertPackets(List.of(packet), read(f.wire(), THRESHOLD));
            assertEquals(0, f.forwarder.forwarded());
            assertEquals("backend-adjacency", f.forwarder.linkStatus());
            assertNull(real.get(CompressedKeep.NAME));
        }
    }

    /** Replaces each backend frame with what {@code replace} makes of it, and releases the frame. */
    private static void replaceFrames(Fixture f, Function<ByteBuf, ByteBuf> replace) {
        f.backend.pipeline().addAfter("frame-decoder", "replace", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ByteBuf frame = (ByteBuf) msg;
                ByteBuf replaced;
                try {
                    replaced = replace.apply(frame);
                } finally {
                    frame.release();
                }
                ctx.fireChannelRead(replaced);
            }
        });
    }

    /**
     * Each backend frame replaced as {@code replace} makes it, given the fixture and a list its {@link Watched} buffers
     * record into: the client gets {@code expected} with the same bytes as with the switch off, nothing is forwarded,
     * and the watched buffers get the same calls in the same order.
     */
    private void checkReplacedFrames(
            BiFunction<Fixture, List<String>, Function<ByteBuf, ByteBuf>> replace, List<byte[]> expected) {
        byte[] read = frame(body(packet(3000, 108), THRESHOLD));
        byte[][] wires = new byte[2][];
        List<List<String>> calls = new ArrayList<>();
        for (boolean forward : new boolean[] {false, true}) {
            var recorded = new ArrayList<String>();
            try (var f = new Fixture(forward ? Options.forwarding() : Options.today())) {
                replaceFrames(f, replace.apply(f, recorded));
                f.read(read.clone());
                wires[forward ? 1 : 0] = f.wire();
                if (forward) assertEquals(0, f.forwarder.forwarded());
            }
            calls.add(recorded);
        }
        assertSameBytes(wires[0], wires[1]);
        assertPackets(expected, read(wires[1], THRESHOLD));
        assertEquals(calls.get(0), calls.get(1), "the watched buffers' calls");
    }

    /**
     * A FixedCompositeByteBuf over a watched copy of the frame, whose first {@code hookOn} runs {@code hook}, and an
     * empty watched buffer that reports no NIO buffers, so the composite reports one.
     */
    private static Function<ByteBuf, ByteBuf> fixedComposite(
            Fixture f, List<String> calls, String hookOn, Runnable hook) {
        return frame -> {
            var input = new Watched(frame.readableBytes(), 1, calls);
            f.track(input).writeBytes(frame);
            input.onFirst(hookOn, hook);
            ByteBuf fixed = Unpooled.wrappedUnmodifiableBuffer(input, f.track(new Watched(0, 0, calls)));
            assertEquals(
                    "io.netty.buffer.FixedCompositeByteBuf", fixed.getClass().getName());
            assertEquals(1, fixed.nioBufferCount());
            return f.track(fixed);
        };
    }

    @Test
    void fixedCompositeOverForeignBuffersLeavesTheReadAsWithTheSwitchOff() {
        checkReplacedFrames(
                (f, calls) -> fixedComposite(f, calls, "release", insertSwap().apply(f)), List.of(packet(3000, 108)));
        checkReplacedFrames(
                (f, calls) -> fixedComposite(
                        f, calls, "release", () -> f.decoder.packetLimiter = selfClearingLimiter(f, packet(3000, 109))),
                List.of(packet(3000, 108)));
    }

    @Test
    void foreignFrameIsPassedOnBeforeTheKeepCallsAnyOfItsMethods() {
        checkReplacedFrames(
                (f, calls) -> frame -> {
                    var input = new Watched(frame.readableBytes(), 1, calls);
                    f.track(input).writeBytes(frame);
                    return input.onFirst("isReadable", insertSwap().apply(f));
                },
                List.of(packet(3000, 108)));
    }

    @Test
    void bufferOfNettysPackageFromAnotherLoaderIsNotHeld() throws Exception {
        Class<? extends UnpooledHeapByteBuf> type = new ByteBuddy()
                .subclass(UnpooledHeapByteBuf.class)
                .name("io.netty.buffer.OtherLoaderHeapByteBuf")
                .make()
                .load(VelocityCompressedForwardTest.class.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();
        assertEquals(ByteBuf.class.getPackageName(), type.getPackageName());
        assertNotSame(ByteBuf.class.getClassLoader(), type.getClassLoader());
        var constructor = type.getConstructor(ByteBufAllocator.class, int.class, int.class);
        checkReplacedFrames(
                (f, calls) -> frame -> {
                    ByteBuf input;
                    try {
                        int n = frame.readableBytes();
                        input = constructor.newInstance(UnpooledByteBufAllocator.DEFAULT, n, n);
                    } catch (ReflectiveOperationException failure) {
                        throw new AssertionError(failure);
                    }
                    return f.track(input.writeBytes(frame));
                },
                List.of(packet(3000, 108)));
    }

    @Test
    void releasedFrameRaisesWhatTheDecoderRaisesWithTheSwitchOff() {
        checkReleasedFrame(frame -> {
            ByteBuf copy = Unpooled.buffer(frame.readableBytes()).writeBytes(frame);
            copy.release();
            return copy;
        });
        var pooled = new PooledByteBufAllocator(true);
        checkReleasedFrame(frame -> {
            ByteBuf parent = pooled.directBuffer(frame.readableBytes()).writeBytes(frame);
            ByteBuf slice = parent.retainedSlice();
            parent.release();
            slice.release();
            return slice;
        });
    }

    /** A backend frame at reference count 0 raises the decoder's exception in both modes, and no packet is sent. */
    private void checkReleasedFrame(Function<ByteBuf, ByteBuf> released) {
        byte[] read = frame(body(packet(3000, 113), THRESHOLD));
        for (boolean forward : new boolean[] {false, true}) {
            String label = "forward " + forward;
            try (var f = new Fixture(forward ? Options.forwarding() : Options.today())) {
                replaceFrames(f, released);
                var failure = assertThrows(DecoderException.class, () -> f.read(read.clone()), label);
                assertInstanceOf(IllegalReferenceCountException.class, failure.getCause(), label);
                assertEquals(0, f.wire().length, label);
                if (forward) assertEquals(0, f.forwarder.forwarded(), label);
            }
        }
    }

    @Test
    void frameAtTheMaximumCountFailsAsWithTheSwitchOff() {
        byte[] read = frame(body(packet(3000, 126), THRESHOLD));
        List<String> seen = new ArrayList<>();
        for (boolean forward : new boolean[] {false, true}) {
            String label = "forward " + forward;
            var frames = new ArrayList<ByteBuf>();
            try (var f = new Fixture(forward ? Options.forwarding() : Options.today())) {
                replaceFrames(f, frame -> {
                    ByteBuf copy = Unpooled.buffer(frame.readableBytes()).writeBytes(frame);
                    frames.add(saturated(copy));
                    return copy;
                });
                var failure = assertThrows(Exception.class, () -> f.read(read.clone()), label);
                ByteBuf frame = frames.getFirst();
                seen.add(failure.getClass().getName() + " caused by "
                        + (failure.getCause() == null
                                ? null
                                : failure.getCause().getClass().getName())
                        + ", packets " + read(f.wire(), THRESHOLD).size() + ", reader " + frame.readerIndex()
                        + ", count " + frame.refCnt());
                if (forward) assertEquals(0, f.forwarder.forwarded(), label);
                assertTrue(frame.release(frame.refCnt()), label);
            }
        }
        assertEquals(seen.get(0), seen.get(1));
    }

    /** {@code buf} retained to the largest count netty allows, where one more retain throws. */
    private static ByteBuf saturated(ByteBuf buf) {
        for (int step = 1 << 30; step > 0; step >>= 1) {
            try {
                buf.retain(step);
            } catch (IllegalReferenceCountException full) {
                // The count stays where it was.
            }
        }
        int count = buf.refCnt();
        assertThrows(IllegalReferenceCountException.class, buf::retain);
        assertEquals(count, buf.refCnt());
        return buf;
    }

    @Test
    void disabledOrRemovedForwarderReportsNoLink() {
        for (boolean removed : new boolean[] {false, true}) {
            String label = removed ? "removed" : "disabled";
            try (var f = new Fixture(Options.forwarding())) {
                assertEquals("linked", f.forwarder.linkStatus(), label);
                if (removed) f.client.pipeline().remove(f.forwarder);
                else f.forwarder.disable("test-disabled");
                assertNull(f.backend.pipeline().get(CompressedKeep.NAME), label);
                assertEquals("none", f.forwarder.linkStatus(), label);
                assertTrue(f.forwarder.stats().contains(", link=none,"), label);
            }
        }
    }

    @Test
    void allocatorSetByTheKeepsAllocationIsTheNextReadsAllocator() {
        byte[] packet = packet(3000, 114);
        byte[] body = body(packet, THRESHOLD);
        assertRecompressedDiffers(body);
        try (var f = new Fixture(Options.forwarding())) {
            var allocator = new RecordingAllocator();
            var foreign = new ForeignAllocator();
            boolean[] heap = {false};
            foreign.onHeap = () -> heap[0] = true;
            allocator.beforeDirect =
                    atDirectAllocation(1, () -> f.backend.config().setAllocator(foreign));
            f.backend.config().setAllocator(allocator);
            f.read(frame(body));
            var frames = read(f.wire(), THRESHOLD);
            assertPackets(List.of(packet), frames);
            assertArrayEquals(body, frames.getFirst().body(), "the backend's frame body, from the read's allocator");
            assertEquals(1, f.forwarder.forwarded());
            assertFalse(heap[0], "the keep's copy is not of the allocator set during the read");
            assertEquals("linked", f.forwarder.linkStatus());
            f.read(frame(body));
            assertPackets(List.of(packet, packet), read(f.wire(), THRESHOLD));
            assertEquals(1, f.forwarder.forwarded());
            assertEquals(CompressedKeep.UNTRUSTED, f.forwarder.linkStatus());
            assertNull(f.backend.pipeline().get(CompressedKeep.NAME));
            for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation");
        }
    }

    @Test
    void failingReleaseOfAHeldPooledSliceSurfacesAfterItsPacket() {
        byte[] packet = packet(3000, 115);
        byte[] read = frame(body(packet, THRESHOLD));
        var pooled = new PooledByteBufAllocator(true);
        for (boolean forward : new boolean[] {false, true}) {
            String label = "forward " + forward;
            var parents = new ArrayList<ByteBuf>();
            var slices = new ArrayList<ByteBuf>();
            var allocator = new RecordingAllocator();
            try (var f = new Fixture(forward ? Options.forwarding() : Options.today())) {
                f.backend.config().setAllocator(allocator);
                // The slice's release releases its released duplicate, which throws.
                replaceFrames(f, frame -> {
                    ResourceLeakDetector.Level level = ResourceLeakDetector.getLevel();
                    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
                    try {
                        ByteBuf parent =
                                pooled.directBuffer(frame.readableBytes()).writeBytes(frame);
                        ByteBuf duplicate = parent.retainedDuplicate();
                        ByteBuf slice = duplicate.retainedSlice();
                        duplicate.release();
                        duplicate.release();
                        parents.add(parent);
                        slices.add(slice);
                        return slice;
                    } finally {
                        ResourceLeakDetector.setLevel(level);
                    }
                });
                var failure = assertThrows(DecoderException.class, () -> f.read(read.clone()), label);
                assertInstanceOf(IllegalReferenceCountException.class, failure.getCause(), label);
                assertPackets(List.of(packet), read(f.wire(), THRESHOLD));
                if (forward) assertEquals(1, f.forwarder.forwarded(), label);
                assertEquals(0, slices.getFirst().refCnt(), "the slice whose release failed, " + label);
                assertEquals(1, parents.getFirst().refCnt(), "the slice's pooled buffer, " + label);
                for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation, " + label);
            } finally {
                for (ByteBuf parent : parents) parent.release();
            }
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
        byte[] large = packet(3000, 113);
        byte[] read = frame(body(large, THRESHOLD));
        byte[][] wires = new byte[2][];
        for (boolean forward : new boolean[] {false, true}) {
            String label = "forward " + forward;
            var refusals = new ArrayList<VelocityCompressedForward.Refusal>();
            try (var f = new Fixture(Options.today())) {
                ChannelPipeline client = f.client.pipeline();
                var other = new TranslatingCompressor();
                var decoder = new CompressDecoder(THRESHOLD, other);
                var encoder = new CompressEncoder(THRESHOLD, true, other);
                assertNull(VelocityCompressedForward.sharedCompressor(encoder, decoder, COMPRESSORS), label);
                client.replace(
                        VelocityCompressedForward.COMPRESSION_DECODER,
                        VelocityCompressedForward.COMPRESSION_DECODER,
                        decoder);
                client.replace(
                        CompressedForwarder.COMPRESSION_ENCODER, CompressedForwarder.COMPRESSION_ENCODER, encoder);
                if (forward) {
                    var forwarder = VelocityCompressedForward.install(
                            client, CompressEncoder.class, DECODER, COMPRESSORS, refusals::add);
                    assertEquals(List.of(VelocityCompressedForward.Refusal.COMPRESSOR), refusals, label);
                    assertNull(forwarder, label);
                    assertNull(client.get(CompressedForwarder.NAME), label);
                }
                f.read(read);
                wires[forward ? 1 : 0] = f.wire();
            }
        }
        assertSameBytes(wires[0], wires[1]);
        assertPackets(List.of(large), read(wires[1], THRESHOLD));
    }

    @Test
    void backendCompressorOfAnotherClassIsNotLinked() {
        byte[] packet = packet(3000, 114);
        byte[] flipped = packet.clone();
        flipped[0] ^= 1;
        byte[] read = frame(body(packet, THRESHOLD));
        String name = VelocityCompressedForward.COMPRESSION_DECODER;
        byte[] today;
        try (var f = new Fixture(Options.today())) {
            f.backend.pipeline().replace(name, name, new CompressDecoder(THRESHOLD, new TranslatingCompressor()));
            f.read(read.clone());
            today = f.wire();
        }
        assertPackets(List.of(flipped), read(today, THRESHOLD));
        try (var f = new Fixture(Options.forwarding().unlinked())) {
            f.backend.pipeline().replace(name, name, new CompressDecoder(THRESHOLD, new TranslatingCompressor()));
            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), f.backend, DECODER, COMPRESSORS, TRUSTED);
            assertEquals("backend-compressor", f.forwarder.linkStatus());
            assertNull(f.backend.pipeline().get(CompressedKeep.NAME));
            f.read(read.clone());
            assertSameBytes(today, f.wire());
            assertEquals(0, f.forwarder.forwarded());
        }
    }

    /** Flips one byte of the inflated packet in place, removes itself, and fires the same buffer. */
    private static final class SelfRemovingFlip extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf in = (ByteBuf) msg;
            int at = in.readerIndex() + 100;
            in.setByte(at, in.getByte(at) ^ 0x5a);
            ctx.pipeline().remove(this);
            ctx.fireChannelRead(in);
        }
    }

    @Test
    void rewriteAddedAfterTheDecoderByAnAllocationIsNotForwarded() {
        byte[] packet = packet(3000, 115);
        byte[] changed = packet.clone();
        changed[100] ^= 0x5a;
        byte[] read = frame(body(packet, THRESHOLD));
        String name = VelocityCompressedForward.COMPRESSION_DECODER;
        for (boolean inPlace : new boolean[] {false, true}) {
            Function<Fixture, ChannelHandler> rewrite =
                    f -> inPlace ? new SelfRemovingFlip() : new SelfRemovingRewrite(f);
            byte[] today;
            try (var f = new Fixture(Options.today())) {
                var allocator = new RecordingAllocator();
                allocator.beforeDirect =
                        atDirectAllocation(1, () -> f.backend.pipeline().addAfter(name, "rewrite", rewrite.apply(f)));
                f.backend.config().setAllocator(allocator);
                f.read(read.clone());
                today = f.wire();
            }
            assertPackets(List.of(changed), read(today, THRESHOLD));
            for (int n = 1; n <= 2; n++) {
                String label = "in place " + inPlace + ", direct allocation " + n;
                try (var f = new Fixture(Options.forwarding())) {
                    var allocator = new RecordingAllocator();
                    boolean[] added = {false};
                    allocator.beforeDirect = atDirectAllocation(n, () -> {
                        added[0] = true;
                        f.backend.pipeline().addAfter(name, "rewrite", rewrite.apply(f));
                    });
                    f.backend.config().setAllocator(allocator);
                    f.read(read.clone());
                    assertTrue(added[0], label);
                    assertSameBytes(today, f.wire());
                    assertEquals(0, f.forwarder.forwarded(), label);
                    assertEquals(inPlace ? 0 : 1, f.forwarder.notMarked(), label);
                    assertEquals(inPlace ? 1 : 0, f.forwarder.guardMismatch(), label);
                    assertEquals("linked", f.forwarder.linkStatus(), label);
                    for (ByteBuf buf : allocator.allocated)
                        if (!f.tracked.contains(buf)) assertEquals(0, buf.refCnt(), "an allocation, " + label);
                }
            }
        }
    }

    /** {@code read} through the fixture after {@code setup}, switched off and on: the same failure, nothing sent. */
    private static void checkSameFailure(String label, Options options, Consumer<Fixture> setup, byte[] read) {
        String[] failures = new String[2];
        for (boolean forward : new boolean[] {false, true}) {
            var allocator = new RecordingAllocator();
            try (var f = new Fixture(forward ? options : options.withoutForwarding())) {
                f.backend.config().setAllocator(allocator);
                setup.accept(f);
                var failure = assertThrows(DecoderException.class, () -> f.read(read.clone()), label);
                Throwable cause = failure.getCause();
                failures[forward ? 1 : 0] = failure.getClass().getName() + " / "
                        + (cause == null ? null : cause.getClass().getName());
                assertEquals(0, f.wire().length, label);
                if (forward) {
                    assertEquals(0, f.forwarder.forwarded(), label);
                    assertEquals("linked", f.forwarder.linkStatus(), label);
                }
                for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation, " + label);
            }
        }
        assertEquals(failures[0], failures[1], label);
    }

    @Test
    void corruptZlibBodyFailsAsWithTheSwitchOff() {
        byte[] body = body(packet(3000, 116), THRESHOLD);
        checkSameFailure("truncated", Options.forwarding(), f -> {}, frame(Arrays.copyOf(body, body.length - 20)));
        byte[] damaged = body.clone();
        for (int i = 10; i < 40; i++) damaged[i] = (byte) 0xff;
        checkSameFailure("damaged", Options.forwarding(), f -> {}, frame(damaged));
    }

    @Test
    void claimedLengthAboveTheInflatedLengthFailsAsWithTheSwitchOff() {
        byte[] body = body(packet(3000, 117), THRESHOLD);
        assertEquals((byte) 0xb8, body[0], "3000 as a varint");
        body[0] = (byte) 0xb9;
        checkSameFailure("claimed 3001", Options.forwarding(), f -> {}, frame(body));
    }

    @Test
    void claimBelowTheBackendDecodersThresholdFailsAsWithTheSwitchOff() {
        byte[] body = body(packet(300, 118), THRESHOLD);
        checkSameFailure("claimed 300 of 512", Options.forwarding().backendThreshold(512), f -> {}, frame(body));
    }

    @Test
    void closedBackendCompressorFailsAsWithTheSwitchOff() {
        byte[] body = body(packet(3000, 119), THRESHOLD);
        checkSameFailure("closed", Options.forwarding(), f -> f.decoder.compressor.close(), frame(body));
    }

    @Test
    void autoReadOffGetsTheSameReadRequestsAsWithTheSwitchOff() {
        byte[] good = frame(body(packet(3000, 120), THRESHOLD));
        byte[] body = body(packet(3000, 121), THRESHOLD);
        byte[] corrupt = frame(Arrays.copyOf(body, body.length - 20));
        var reads = List.of(good, concat(good, good), corrupt, concat(good, corrupt), concat(corrupt, good));
        for (int i = 0; i < reads.size(); i++) {
            byte[] read = reads.get(i);
            int[] requests = new int[2];
            for (boolean forward : new boolean[] {false, true}) {
                String label = "read " + i + ", forward " + forward;
                try (var f = new Fixture(forward ? Options.forwarding() : Options.today())) {
                    f.backend.config().setAutoRead(false);
                    int[] count = {0};
                    f.backend.pipeline().addFirst("reads", new ChannelOutboundHandlerAdapter() {
                        @Override
                        public void read(ChannelHandlerContext ctx) {
                            count[0]++;
                            ctx.read();
                        }
                    });
                    if (i >= 2) assertThrows(DecoderException.class, () -> f.read(read.clone()), label);
                    else f.read(read.clone());
                    requests[forward ? 1 : 0] = count[0];
                    if (forward && i < 2) assertEquals(i + 1, f.forwarder.forwarded(), label);
                }
            }
            assertEquals(requests[0], requests[1], "read " + i);
        }
    }

    @Test
    void clientCompressorClosedAfterInstallWritesAsWithTheSwitchOff() {
        byte[] large = packet(3000, 122);
        byte[] small = packet(100, 123);
        byte[] read = concat(frame(body(large, THRESHOLD)), frame(body(small, THRESHOLD)));
        byte[][] wires = new byte[2][];
        List<String> outcomes = new ArrayList<>();
        for (boolean forward : new boolean[] {false, true}) {
            String label = "forward " + forward;
            var writes = new ArrayList<ChannelFuture>();
            Options options = Options.forwarding().onFrame((fixture, buf) -> writes.add(fixture.client.write(buf)));
            try (var f = new Fixture(forward ? options : options.withoutForwarding())) {
                f.compressor.close();
                f.read(read);
                wires[forward ? 1 : 0] = f.wire();
                assertEquals(2, writes.size(), label);
                var outcome = new StringBuilder();
                for (ChannelFuture write : writes)
                    outcome.append(
                                    write.cause() == null
                                            ? "ok"
                                            : write.cause().getClass().getName())
                            .append(' ');
                outcomes.add(outcome.toString());
                if (forward) {
                    assertEquals(0, f.forwarder.forwarded(), label);
                    assertEquals("compression-closed", f.forwarder.status(), label);
                    assertNull(f.backend.pipeline().get(CompressedKeep.NAME), label);
                }
            }
        }
        assertEquals(outcomes.get(0), outcomes.get(1));
        assertSameBytes(wires[0], wires[1]);
        assertPackets(List.of(small), read(wires[1], THRESHOLD));
    }

    @Test
    void retainedBackendFrameEndsWithTheIndicesAndCountOfTheSwitchOff() {
        byte[] packet = packet(3000, 124);
        byte[] read = frame(concat(body(packet, THRESHOLD), new byte[] {1, 2, 3}));
        for (boolean consume : new boolean[] {true, false}) {
            for (boolean direct : new boolean[] {false, true}) {
                List<String> seen = new ArrayList<>();
                for (boolean forward : new boolean[] {false, true}) {
                    String label =
                            "compressor consumes " + consume + ", direct frame " + direct + ", forward " + forward;
                    var options = Options.forwarding().consume(consume);
                    try (var f = new Fixture(forward ? options : options.withoutForwarding())) {
                        var held = new ArrayList<ByteBuf>();
                        var during = new StringBuilder();
                        f.backend.pipeline().addAfter("frame-decoder", "hold", new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                held.add(((ByteBuf) msg).retain());
                                ctx.fireChannelRead(msg);
                            }
                        });
                        if (direct)
                            replaceFrames(
                                    f,
                                    frame -> PooledByteBufAllocator.DEFAULT
                                            .directBuffer(frame.readableBytes())
                                            .writeBytes(frame, frame.readerIndex(), frame.readableBytes()));
                        f.backend
                                .pipeline()
                                .addAfter(
                                        VelocityCompressedForward.COMPRESSION_DECODER,
                                        "probe",
                                        new ChannelInboundHandlerAdapter() {
                                            @Override
                                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                                during.append(held.getLast().refCnt());
                                                ctx.fireChannelRead(msg);
                                            }
                                        });
                        f.read(read.clone());
                        assertPackets(List.of(packet), read(f.wire(), THRESHOLD, true));
                        if (forward) assertEquals(consume || direct ? 1 : 0, f.forwarder.forwarded(), label);
                        ByteBuf frame = held.getFirst();
                        assertEquals(direct, frame.hasMemoryAddress(), label);
                        seen.add("reader " + frame.readerIndex() + ", writer " + frame.writerIndex() + ", count "
                                + frame.refCnt() + ", count after the decoder " + during);
                        assertTrue(frame.release(), label);
                    }
                }
                assertEquals(seen.get(0), seen.get(1), "consume " + consume + ", direct " + direct);
            }
        }
    }

    @Test
    void errorInTheKeepsCopiesFinishesTheDecodeUnheld() {
        byte[] packet = packet(3000, 125);
        byte[] read = frame(body(packet, THRESHOLD));
        // The keep's private frame is its second direct allocation, its comparison copy its first heap allocation.
        for (boolean copy : new boolean[] {false, true}) {
            String label = copy ? "comparison copy" : "private frame";
            try (var f = new Fixture(Options.forwarding())) {
                var allocator = new RecordingAllocator();
                Runnable overflow = () -> {
                    throw new StackOverflowError(label);
                };
                if (copy) allocator.beforeHeap = atDirectAllocation(1, overflow);
                else allocator.beforeDirect = atDirectAllocation(2, overflow);
                f.backend.config().setAllocator(allocator);
                f.read(read);
                assertEquals(List.of(), f.decoder.inflated, "the keep finishes the decode, " + label);
                assertEquals(1, f.decoder.compressor.destinations.size(), "one inflate, of the frame, " + label);
                assertSameBytes(today(Options.forwarding(), read), f.wire());
                assertEquals(0, f.forwarder.forwarded(), label);
                assertEquals(0, f.frameDecoder.frames.getFirst().refCnt(), label);
                for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation, " + label);
            }
        }
    }

    /** Retains every backend frame into {@code held} before it reaches the keep. */
    private static void hold(Fixture f, List<ByteBuf> held) {
        f.backend.pipeline().addAfter("frame-decoder", "hold", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                held.add(((ByteBuf) msg).retain());
                ctx.fireChannelRead(msg);
            }
        });
    }

    /** A compressed body with a stored zlib stream, whose length depends only on the packet's length. */
    private static byte[] storedBody(byte[] packet) {
        ByteBuf body = Unpooled.buffer();
        Wire.writeVarInt(body, packet.length);
        body.writeBytes(zlib(packet, Deflater.NO_COMPRESSION));
        byte[] bytes = ByteBufUtil.getBytes(body);
        body.release();
        return bytes;
    }

    @Test
    void retainedFrameOverwrittenAtEachKeepAllocationForwardsWhatTheDecoderReads() {
        byte[] packet = packet(3000, 126);
        byte[] other = packet(3000, 127);
        byte[] body = storedBody(packet);
        byte[] replacement = storedBody(other);
        assertEquals(body.length, replacement.length);
        byte[] read = frame(body);
        // At 0 the switch is off and the hook runs at the decoder's one allocation; the keep's third is on the heap.
        for (int k = 0; k <= 3; k++) {
            String label = k == 0 ? "switch off" : "keep allocation " + k;
            try (var f = new Fixture(k == 0 ? Options.today() : Options.forwarding())) {
                var held = new ArrayList<ByteBuf>();
                hold(f, held);
                var allocator = new RecordingAllocator();
                Runnable overwrite = once(() -> {
                    ByteBuf frame = held.getFirst();
                    frame.setBytes(frame.writerIndex() - replacement.length, replacement);
                });
                if (k == 3) allocator.beforeHeap = overwrite;
                else allocator.beforeDirect = atDirectAllocation(Math.max(k, 1), overwrite);
                f.backend.config().setAllocator(allocator);
                f.read(read.clone());
                var frames = read(f.wire(), THRESHOLD);
                assertPackets(List.of(other), frames);
                if (k > 0) {
                    assertEquals(1, f.forwarder.forwarded(), label);
                    assertArrayEquals(replacement, frames.getFirst().body(), label);
                }
                assertTrue(held.getFirst().release(), label);
                for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation, " + label);
            }
        }
    }

    @Test
    void frameIndicesChangedAtAKeepAllocationPassTheFrameOnUnheld() {
        byte[] packet = packet(3000, 131);
        byte[] read = frame(concat(body(packet, THRESHOLD), new byte[] {1, 2, 3}));
        List<String> seen = new ArrayList<>();
        // At 0 the switch is off and the hook runs at the decoder's one allocation; the keep's third is on the heap.
        for (int k = 0; k <= 3; k++) {
            String label = k == 0 ? "switch off" : "keep allocation " + k;
            try (var f = new Fixture(k == 0 ? Options.today() : Options.forwarding())) {
                var held = new ArrayList<ByteBuf>();
                hold(f, held);
                var allocator = new RecordingAllocator();
                Runnable trim = once(() -> {
                    ByteBuf frame = held.getFirst();
                    frame.writerIndex(frame.writerIndex() - 3);
                });
                if (k == 3) allocator.beforeHeap = trim;
                else allocator.beforeDirect = atDirectAllocation(Math.max(k, 1), trim);
                f.backend.config().setAllocator(allocator);
                f.read(read.clone());
                byte[] wire = f.wire();
                assertPackets(List.of(packet), read(wire, THRESHOLD));
                if (k > 0) {
                    assertEquals(0, f.forwarder.forwarded(), label);
                    assertEquals(1, f.forwarder.notMarked(), label);
                    assertEquals("linked", f.forwarder.linkStatus(), label);
                }
                ByteBuf frame = held.getFirst();
                seen.add(Arrays.toString(wire) + ", reader " + frame.readerIndex() + ", writer " + frame.writerIndex());
                assertTrue(frame.release(), label);
                for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation, " + label);
            }
        }
        for (int k = 1; k <= 3; k++) assertEquals(seen.get(0), seen.get(k), "keep allocation " + k);
    }

    @Test
    void nestedReadFromTheKeepsLastAllocationLeavesTheOuterFrameAsWithTheSwitchOff() {
        byte[] outer = packet(3000, 128);
        byte[] inner = packet(9000, 129);
        byte[] read = frame(concat(body(outer, THRESHOLD), new byte[] {1, 2, 3}));
        byte[] innerBody = body(inner, THRESHOLD);
        List<String> seen = new ArrayList<>();
        for (boolean forward : new boolean[] {false, true}) {
            String label = "forward " + forward;
            try (var f = new Fixture(forward ? Options.forwarding() : Options.today())) {
                var held = new ArrayList<ByteBuf>();
                hold(f, held);
                var allocator = new RecordingAllocator();
                Runnable nested = once(() -> f.backend
                        .pipeline()
                        .context("read-timeout")
                        .fireChannelRead(f.track(Unpooled.wrappedBuffer(innerBody.clone()))));
                // The decoder's one allocation is direct; the keep's last is its comparison copy, on the heap.
                if (forward) allocator.beforeHeap = nested;
                else allocator.beforeDirect = nested;
                f.backend.config().setAllocator(allocator);
                f.read(read.clone());
                assertPackets(List.of(inner, outer), read(f.wire(), THRESHOLD, true));
                if (forward) assertEquals(2, f.forwarder.forwarded(), label);
                ByteBuf frame = held.getFirst();
                seen.add("reader " + frame.readerIndex() + ", writer " + frame.writerIndex() + ", count "
                        + frame.refCnt());
                assertTrue(frame.release(), label);
                for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation, " + label);
            }
        }
        assertEquals(seen.get(0), seen.get(1));
    }

    /** Runs {@code hook} at keep allocation {@code k}, or at the decoder's one allocation when {@code k} is 0. */
    private static void atAllocation(RecordingAllocator allocator, int k, Runnable hook) {
        // The keep's third allocation is its comparison copy, on the heap.
        if (k == 3) allocator.beforeHeap = hook;
        else allocator.beforeDirect = atDirectAllocation(Math.max(k, 1), hook);
    }

    @Test
    void heldFrameAtAKeepAllocationIsAsTheDecoderLeavesItAtItsAllocation() {
        byte[] read = frame(body(packet(3000, 132), THRESHOLD));
        List<String> seen = new ArrayList<>();
        for (int k = 0; k <= 3; k++) {
            String label = k == 0 ? "switch off" : "keep allocation " + k;
            try (var f = new Fixture(k == 0 ? Options.today() : Options.forwarding())) {
                var held = new ArrayList<ByteBuf>();
                hold(f, held);
                var allocator = new RecordingAllocator();
                atAllocation(allocator, k, once(() -> {
                    ByteBuf frame = held.getFirst();
                    seen.add("reader " + frame.readerIndex() + ", readable " + frame.readableBytes() + ", count "
                            + frame.refCnt());
                }));
                f.backend.config().setAllocator(allocator);
                f.read(read.clone());
                if (k > 0) assertEquals(1, f.forwarder.forwarded(), label);
                assertTrue(held.getFirst().release(), label);
            }
        }
        for (int k = 1; k <= 3; k++) assertEquals(seen.get(0), seen.get(k), "keep allocation " + k);
    }

    @Test
    void claimChangedAtAKeepAllocationDecodesAsWithTheSwitchOff() {
        byte[] packet = packet(3000, 133);
        byte[] body = body(packet, THRESHOLD);
        byte[] read = frame(body);
        for (int k = 0; k <= 3; k++) {
            String label = k == 0 ? "switch off" : "keep allocation " + k;
            try (var f = new Fixture(k == 0 ? Options.today() : Options.forwarding())) {
                var held = new ArrayList<ByteBuf>();
                hold(f, held);
                var allocator = new RecordingAllocator();
                // 3000 is 0xB8 0x17; the bump makes the frame claim 3001.
                atAllocation(allocator, k, once(() -> held.getFirst().setByte(0, 0xB9)));
                f.backend.config().setAllocator(allocator);
                f.read(read.clone());
                var frames = read(f.wire(), THRESHOLD);
                assertPackets(List.of(packet), frames);
                if (k > 0) {
                    assertEquals(1, f.forwarder.forwarded(), label);
                    assertArrayEquals(body, frames.getFirst().body(), label);
                }
                assertTrue(held.getFirst().release(), label);
                for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation, " + label);
            }
        }
    }

    @Test
    void readerMovedAtAKeepAllocationFailsAsWithTheSwitchOff() {
        byte[] read = frame(body(packet(3000, 134), THRESHOLD));
        for (int k = 0; k <= 3; k++) {
            String label = k == 0 ? "switch off" : "keep allocation " + k;
            try (var f = new Fixture(k == 0 ? Options.today() : Options.forwarding())) {
                var held = new ArrayList<ByteBuf>();
                hold(f, held);
                var allocator = new RecordingAllocator();
                atAllocation(allocator, k, once(() -> held.getFirst().skipBytes(1)));
                f.backend.config().setAllocator(allocator);
                var failure = assertThrows(DecoderException.class, () -> f.read(read.clone()), label);
                assertEquals(DecoderException.class, failure.getClass(), label);
                assertInstanceOf(DataFormatException.class, failure.getCause(), label);
                assertEquals(0, f.wire().length, label);
                if (k > 0) assertEquals(0, f.forwarder.forwarded(), label);
                assertTrue(held.getFirst().release(), label);
                for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation, " + label);
            }
        }
    }

    @Test
    void frameOverReleasedAtAKeepAllocationEndsAsWithTheSwitchOff() {
        byte[] packet = packet(3000, 135);
        byte[] read = frame(body(packet, THRESHOLD));
        for (int releases = 2; releases <= 3; releases++) {
            List<String> seen = new ArrayList<>();
            for (int k = 0; k <= 3; k++) {
                String label = (k == 0 ? "switch off" : "keep allocation " + k) + ", releases " + releases;
                try (var f = new Fixture(k == 0 ? Options.today() : Options.forwarding())) {
                    var held = new ArrayList<ByteBuf>();
                    hold(f, held);
                    var allocator = new RecordingAllocator();
                    int count = releases;
                    atAllocation(allocator, k, once(() -> held.getFirst().release(count)));
                    f.backend.config().setAllocator(allocator);
                    Throwable thrown = null;
                    try {
                        f.read(read.clone());
                    } catch (RuntimeException failure) {
                        thrown = failure;
                    }
                    assertNotNull(thrown, label);
                    List<byte[]> packets = new ArrayList<>();
                    for (var frame : read(f.wire(), THRESHOLD)) packets.add(frame.packet());
                    seen.add(thrown.getClass().getName() + " / "
                            + (thrown.getCause() == null
                                    ? null
                                    : thrown.getCause().getClass().getName())
                            + ", packets "
                            + packets.stream().map(Arrays::toString).toList()
                            + ", count " + held.getFirst().refCnt());
                    for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation, " + label);
                }
            }
            for (int k = 1; k <= 3; k++) assertEquals(seen.get(0), seen.get(k), "keep allocation " + k);
        }
    }

    @Test
    void onlyNettysOwnLeakDetectorIsTrusted() throws Exception {
        assertTrue(CompressedKeep.nettyLeakDetector(leakDetector()));
        assertTrue(CompressedKeep.NETTY_CLASSES.test(PooledByteBufAllocator.DEFAULT));
        assertFalse(CompressedKeep.nettyLeakDetector(new ResourceLeakDetector<ByteBuf>(ByteBuf.class, 1) {}));
        assertFalse(CompressedKeep.nettyLeakDetector(null));
    }

    @Test
    void decoderAndCompressorOfVelocitysNamesFromAnotherLoaderAreRefused() throws Exception {
        ClassLoader own = VelocityCompressedForwardTest.class.getClassLoader();
        Set<String> names = Set.of(CompressDecoder.class.getName(), SharedCompressor.class.getName());
        ClassLoader other = new ClassLoader(own) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!names.contains(name)) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded != null) return loaded;
                    try (InputStream in = own.getResourceAsStream(name.replace('.', '/') + ".class")) {
                        byte[] bytes = in.readAllBytes();
                        return defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException failure) {
                        throw new ClassNotFoundException(name, failure);
                    }
                }
            }
        };
        Class<?> compressorType = other.loadClass(SharedCompressor.class.getName());
        Class<?> decoderType = other.loadClass(CompressDecoder.class.getName());
        assertNotSame(SharedCompressor.class, compressorType);
        assertNotSame(CompressDecoder.class, decoderType);
        Constructor<?> newCompressor = compressorType.getDeclaredConstructor();
        Constructor<?> newDecoder = decoderType.getDeclaredConstructor(int.class, compressorType);
        newCompressor.setAccessible(true);
        newDecoder.setAccessible(true);
        Object compressor = newCompressor.newInstance();
        assertFalse(COMPRESSORS.accepts(compressor), "a compressor of the name from another loader");
        assertTrue(COMPRESSORS.accepts(new SharedCompressor()));
        String name = VelocityCompressedForward.COMPRESSION_DECODER;
        try (var f = new Fixture(Options.today())) {
            ChannelPipeline client = f.client.pipeline();
            client.replace(name, name, (ChannelHandler) newDecoder.newInstance(THRESHOLD, compressor));
            var refusals = new ArrayList<VelocityCompressedForward.Refusal>();
            assertNull(VelocityCompressedForward.install(
                    client, CompressEncoder.class, DECODER, COMPRESSORS, refusals::add));
            assertEquals(List.of(VelocityCompressedForward.Refusal.DECODER), refusals);
            assertNull(client.get(CompressedForwarder.NAME));
        }
        try (var f = new Fixture(Options.forwarding().unlinked())) {
            Object backendCompressor = newCompressor.newInstance();
            f.backend.pipeline().replace(name, name, (ChannelHandler)
                    newDecoder.newInstance(THRESHOLD, backendCompressor));
            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), f.backend, DECODER, COMPRESSORS, TRUSTED);
            assertEquals("backend-decoder", f.forwarder.linkStatus());
            assertNull(f.backend.pipeline().get(CompressedKeep.NAME));
        }
    }

    @Test
    void velocityClassesThatCannotBeResolvedInstallNothing() {
        assertNull(VelocityCompressedForward.Codecs.resolve(VelocityCompressedForwardTest.class.getClassLoader()));
        String property = VelocityCompressedForward.ENABLED_PROPERTY;
        String previous = System.getProperty(property);
        System.setProperty(property, "true");
        try (var log = mockStatic(LogUtil.class)) {
            assertNull(
                    VelocityCompressedForward.fromSystemProperties(mock(ProxyServer.class), new Object(), p -> null));
            log.verify(
                    () -> LogUtil.warn(
                            "Cannot find Velocity's compression classes; compressed frames are not forwarded"),
                    times(1));
            log.verify(() -> LogUtil.warn(anyString()), times(1));
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test
    void heapFrameUnderTheLibdeflateCompressorIsPassedOnUnheld() {
        byte[] packet = packet(3000, 130);
        byte[] read = frame(body(packet, THRESHOLD));
        Options options = Options.forwarding().consume(false);
        byte[] today = today(options, read);
        for (boolean direct : new boolean[] {false, true}) {
            String label = "direct frame " + direct;
            try (var f = new Fixture(options)) {
                if (direct)
                    replaceFrames(
                            f,
                            frame -> PooledByteBufAllocator.DEFAULT
                                    .directBuffer(frame.readableBytes())
                                    .writeBytes(frame, frame.readerIndex(), frame.readableBytes()));
                f.read(read.clone());
                byte[] wire = f.wire();
                assertPackets(List.of(packet), read(wire, THRESHOLD));
                assertEquals("linked", f.forwarder.linkStatus(), label);
                assertEquals(direct ? 1 : 0, f.forwarder.forwarded(), label);
                if (direct) continue;
                assertSameBytes(today, wire);
                assertEquals(1, f.forwarder.notMarked(), label);
                assertEquals(1, f.decoder.inflated.size(), "the decoder decodes the backend's frame");
            }
        }
    }

    @Test
    void enabledLeakDetectionPassesFramesOnUnheldAndUnlinksWithOneWarning() {
        byte[] packet = packet(3000, 136);
        byte[] body = body(packet, THRESHOLD);
        byte[] read = frame(body);
        byte[] today = today(Options.forwarding(), read);
        ResourceLeakDetector.Level level = ResourceLeakDetector.getLevel();
        try (var log = mockStatic(LogUtil.class)) {
            var forward = new VelocityCompressedForward(
                    mock(ProxyServer.class),
                    new Object(),
                    p -> null,
                    new VelocityCompressedForward.Codecs(CompressEncoder.class, DECODER, COMPRESSORS));
            for (var enabled : List.of(ResourceLeakDetector.Level.SIMPLE, ResourceLeakDetector.Level.PARANOID)) {
                String label = "level " + enabled;
                ResourceLeakDetector.setLevel(enabled);
                try (var f = new Fixture(Options.forwarding().unlinked())) {
                    VelocityCompressedForward.place(
                            f.forwarder,
                            f.client.pipeline(),
                            f.backend,
                            DECODER,
                            COMPRESSORS,
                            CompressedKeep.NETTY_ALLOCATORS,
                            forward::refused);
                    assertEquals("linked", f.forwarder.linkStatus(), label);
                    f.read(read.clone());
                    assertSameBytes(today, f.wire());
                    assertEquals(0, f.forwarder.forwarded(), label);
                    assertEquals(1, f.forwarder.notMarked(), label);
                    assertEquals(CompressedKeep.UNTRUSTED, f.forwarder.linkStatus(), label);
                    assertNull(f.backend.pipeline().get(CompressedKeep.NAME), label);
                }
            }
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
            try (var f = new Fixture(Options.forwarding().unlinked())) {
                VelocityCompressedForward.place(
                        f.forwarder,
                        f.client.pipeline(),
                        f.backend,
                        DECODER,
                        COMPRESSORS,
                        CompressedKeep.NETTY_ALLOCATORS,
                        forward::refused);
                f.read(read.clone());
                var frames = read(f.wire(), THRESHOLD);
                assertPackets(List.of(packet), frames);
                assertArrayEquals(body, frames.getFirst().body(), "the backend's frame body, unchanged");
                assertEquals(1, f.forwarder.forwarded());
                assertEquals("linked", f.forwarder.linkStatus());
            }
            log.verify(() -> LogUtil.warn(VelocityCompressedForward.Refusal.UNTRUSTED.warning), times(1));
            log.verify(() -> LogUtil.warn(anyString()), times(1));
        } finally {
            ResourceLeakDetector.setLevel(level);
        }
    }

    @Test
    void claimBelowTheBackendThresholdMakesNoKeepAllocationAndFailsAsWithTheSwitchOff() {
        byte[] small = body(packet(300, 137), THRESHOLD);
        byte[] large = body(packet(600, 138), THRESHOLD);
        assertTrue(small.length < large.length);
        byte[] read = frame(Arrays.copyOf(small, large.length));
        Options options = Options.forwarding().backendThreshold(512);
        List<String> seen = new ArrayList<>();
        for (int k = 0; k <= 3; k++) {
            String label = k == 0 ? "switch off" : "keep allocation " + k;
            try (var f = new Fixture(k == 0 ? options.withoutForwarding() : options)) {
                var held = new ArrayList<ByteBuf>();
                hold(f, held);
                var allocator = new RecordingAllocator();
                boolean[] ran = {false};
                atAllocation(allocator, k, once(() -> {
                    ran[0] = true;
                    ByteBuf frame = held.getFirst();
                    frame.setBytes(frame.writerIndex() - large.length, large);
                }));
                f.backend.config().setAllocator(allocator);
                var failure = assertThrows(DecoderException.class, () -> f.read(read.clone()), label);
                seen.add(failure.getClass().getName() + " / "
                        + (failure.getCause() == null
                                ? null
                                : failure.getCause().getClass().getName()));
                assertEquals(0, f.wire().length, label);
                assertEquals(List.of(), allocator.allocated, label);
                assertFalse(ran[0], label);
                if (k > 0) assertEquals(0, f.forwarder.forwarded(), label);
                assertTrue(held.getFirst().release(), label);
            }
        }
        for (int k = 1; k <= 3; k++) assertEquals(seen.get(0), seen.get(k), "keep allocation " + k);
    }

    @Test
    void decoderThresholdRaisedAtAKeepAllocationDecodesAsWithTheSwitchOff() {
        byte[] packet = packet(3000, 139);
        byte[] read = frame(body(packet, THRESHOLD));
        for (int k = 0; k <= 3; k++) {
            String label = k == 0 ? "switch off" : "keep allocation " + k;
            try (var f = new Fixture(k == 0 ? Options.today() : Options.forwarding())) {
                var allocator = new RecordingAllocator();
                atAllocation(allocator, k, once(() -> f.decoder.threshold = 3001));
                f.backend.config().setAllocator(allocator);
                f.read(read.clone());
                assertPackets(List.of(packet), read(f.wire(), THRESHOLD));
                assertEquals(3001, f.decoder.threshold, label);
                if (k > 0) assertEquals(1, f.forwarder.forwarded(), label);
                for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation, " + label);
            }
        }
    }

    /**
     * An embedded channel whose pipeline is built with netty's leak detection disabled, as Velocity's default builds
     * it; the level is restored after.
     */
    static EmbeddedChannel channel() {
        ResourceLeakDetector.Level level = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
        try {
            return new EmbeddedChannel();
        } finally {
            ResourceLeakDetector.setLevel(level);
        }
    }

    /** A netty pipeline, built with leak detection disabled, that hands every call to {@code real}. */
    private static ChannelPipeline delegating(ChannelPipeline real) {
        ResourceLeakDetector.Level level = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
        try {
            return mock(
                    DefaultChannelPipeline.class,
                    withSettings().useConstructor(real.channel()).defaultAnswer(AdditionalAnswers.delegatesTo(real)));
        } finally {
            ResourceLeakDetector.setLevel(level);
        }
    }

    /** A heap buffer that records each touch netty gives it with a hint, and fails the second. */
    static final class TouchedOnce extends UnpooledHeapByteBuf {
        final List<Object> touches = new ArrayList<>();

        TouchedOnce(byte[] bytes) {
            super(UnpooledByteBufAllocator.DEFAULT, bytes.length, bytes.length);
            writeBytes(bytes);
        }

        @Override
        public ByteBuf touch(Object hint) {
            touches.add(hint);
            if (touches.size() > 1) throw new IllegalStateException("second touch");
            return this;
        }
    }

    @Test
    void clientPipelineBuiltWithLeakDetectionGetsNoForwarderAsWithTheSwitchOff() {
        byte[] packet = packet(100, 153);
        List<String> seen = new ArrayList<>();
        for (boolean on : new boolean[] {false, true}) {
            var channel = new EmbeddedChannel();
            try {
                ChannelPipeline pipeline = channel.pipeline();
                assertTrue(CompressedForwarder.touches(pipeline), "built under the suite's paranoid level");
                var compressor = new SharedCompressor();
                pipeline.addLast(
                        VelocityCompressedForward.COMPRESSION_DECODER, new CompressDecoder(THRESHOLD, compressor));
                pipeline.addLast(
                        CompressedForwarder.COMPRESSION_ENCODER, new CompressEncoder(THRESHOLD, true, compressor));
                if (on) {
                    var refusals = new ArrayList<VelocityCompressedForward.Refusal>();
                    assertNull(VelocityCompressedForward.install(
                            pipeline,
                            CompressEncoder.class,
                            DECODER,
                            COMPRESSORS,
                            CompressedKeep.NETTY_ALLOCATORS,
                            refusals::add));
                    assertEquals(List.of(VelocityCompressedForward.Refusal.LEAK_DETECTION), refusals);
                    assertNull(pipeline.get(CompressedForwarder.NAME));
                }
                var msg = new TouchedOnce(packet);
                ChannelFuture write = channel.writeAndFlush(msg);
                StringBuilder sent = new StringBuilder();
                Object out;
                while ((out = channel.readOutbound()) != null) {
                    sent.append(ByteBufUtil.hexDump((ByteBuf) out));
                    ((ByteBuf) out).release();
                }
                seen.add("touches " + msg.touches.size() + ", promise " + describe(write.cause()) + ", sent " + sent
                        + ", count " + msg.refCnt());
                if (msg.refCnt() > 0) msg.release(msg.refCnt());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
        assertTrue(seen.get(0).startsWith("touches 1, promise ok, sent 6500"), seen.get(0));
        assertEquals(seen.get(0), seen.get(1));
    }

    @Test
    void backendPipelineBuiltWithLeakDetectionGetsNoKeepAsWithTheSwitchOff() {
        byte[] packet = packet(3000, 154);
        byte[] body = body(packet, THRESHOLD);
        List<String> seen = new ArrayList<>();
        for (boolean on : new boolean[] {false, true}) {
            try (var f = new Fixture(on ? Options.forwarding().unlinked() : Options.today())) {
                var touching = f.backend(Options.forwarding(), new EmbeddedChannel());
                ChannelPipeline pipeline = touching.channel.pipeline();
                assertTrue(CompressedForwarder.touches(pipeline), "built under the suite's paranoid level");
                if (on) {
                    VelocityCompressedForward.place(
                            f.forwarder,
                            f.client.pipeline(),
                            touching.channel,
                            DECODER,
                            COMPRESSORS,
                            CompressedKeep.NETTY_ALLOCATORS);
                    assertEquals(CompressedKeep.LEAK_DETECTION, f.forwarder.linkStatus());
                    assertNull(pipeline.get(CompressedKeep.NAME));
                }
                var frame = new TouchedOnce(body);
                Throwable thrown = null;
                try {
                    pipeline.context("read-timeout").fireChannelRead(frame);
                    pipeline.context("read-timeout").fireChannelReadComplete();
                    touching.channel.checkException();
                } catch (Throwable failure) {
                    thrown = failure;
                }
                seen.add("touches " + frame.touches.size() + ", " + sent(f.wire()) + ", " + describe(thrown)
                        + ", count " + frame.refCnt());
                if (frame.refCnt() > 0) frame.release(frame.refCnt());
            }
        }
        assertTrue(seen.get(0).startsWith("touches 1, [" + Arrays.toString(packet) + "], ok"), seen.get(0));
        assertEquals(seen.get(0), seen.get(1));
    }

    @Test
    void refusalWarningRunsAfterThePacketItReportsAsWithTheSwitchOff() {
        byte[] packet = packet(3000, 155);
        byte[] read = frame(body(packet, THRESHOLD));
        byte[] own = packet(50, 156);
        for (boolean client : new boolean[] {true, false}) {
            String side = client ? "untrusted client write" : "backend refused before the claim";
            List<String> offPackets = null;
            String offPromise = null;
            for (boolean on : new boolean[] {false, true}) {
                List<String> order = new ArrayList<>();
                var writes = new ArrayList<ChannelFuture>();
                Options options = Options.today().onFrame((fixture, buf) -> {
                    order.add("packet");
                    writes.add(fixture.client.write(buf));
                });
                try (var log = mockStatic(LogUtil.class);
                        var f = new Fixture(options)) {
                    int[] encoded = {0};
                    f.client.pipeline().addFirst("encoded", new ChannelOutboundHandlerAdapter() {
                        @Override
                        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                            encoded[0]++;
                            ctx.write(msg, promise);
                        }
                    });
                    log.when(() -> LogUtil.warn(anyString())).thenAnswer(call -> {
                        order.add("warning with " + encoded[0] + " sent");
                        f.client.writeAndFlush(Unpooled.wrappedBuffer(own));
                        return null;
                    });
                    if (on) {
                        var forward = forward();
                        CompressedForwarder forwarder = VelocityCompressedForward.install(
                                f.client.pipeline(),
                                CompressEncoder.class,
                                DECODER,
                                COMPRESSORS,
                                CompressedKeep.NETTY_ALLOCATORS,
                                forward::refused);
                        assertNotNull(forwarder, side);
                        VelocityCompressedForward.place(
                                forwarder,
                                f.client.pipeline(),
                                f.backend,
                                DECODER,
                                COMPRESSORS,
                                client ? TRUSTED : CompressedKeep.NETTY_ALLOCATORS,
                                forward::refused);
                        assertEquals("linked", forwarder.linkStatus(), side);
                    }
                    if (client) f.client.config().setAllocator(new RecordingAllocator());
                    else f.backend.config().setAllocator(new ForeignAllocator());
                    f.read(read.clone());
                    f.client.runPendingTasks();
                    List<String> packets = read(f.wire(), THRESHOLD, true).stream()
                            .map(frame -> Arrays.toString(frame.packet()))
                            .toList();
                    assertEquals(1, writes.size(), side);
                    String promise = describe(writes.getFirst().cause());
                    if (!on) {
                        assertEquals(List.of("packet"), order, side);
                        assertEquals(List.of(Arrays.toString(packet)), packets, side);
                        offPackets = packets;
                        offPromise = promise;
                        continue;
                    }
                    var refusal = client
                            ? VelocityCompressedForward.Refusal.CLIENT_UNTRUSTED
                            : VelocityCompressedForward.Refusal.UNTRUSTED;
                    log.verify(() -> LogUtil.warn(refusal.warning), times(1));
                    assertEquals(List.of("packet", "warning with 1 sent"), order, side);
                    var expected = new ArrayList<>(offPackets);
                    expected.add(Arrays.toString(own));
                    assertEquals(expected, packets, side);
                    assertEquals(offPromise, promise, side);
                }
            }
        }
    }

    /** Runs {@code body} with netty's leak detection disabled, as Velocity sets it, and restores the level after. */
    private static void leakDetectionDisabled(Runnable body) {
        ResourceLeakDetector.Level level = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
        try {
            body.run();
        } finally {
            ResourceLeakDetector.setLevel(level);
        }
    }

    /** The encoder's client allocation of a write. */
    private static void atClientAllocation(RecordingAllocator allocator, Runnable hook) {
        Runnable first = once(hook);
        allocator.beforeHeap = first;
        allocator.beforeDirect = first;
    }

    /** Gives the buffer a client write sends for the backend's packet {@code buf}, and may arm {@code allocator}. */
    private interface WriteHook {
        ByteBuf prepare(ByteBuf buf, RecordingAllocator allocator);
    }

    /** {@code failure}'s class and its cause's class, or "ok". */
    private static String describe(Throwable failure) {
        if (failure == null) return "ok";
        return failure.getClass().getName() + " / "
                + (failure.getCause() == null
                        ? null
                        : failure.getCause().getClass().getName());
    }

    /**
     * The packets sent and the promise's outcome for one write of {@code read}'s packet through a fixture whose client
     * allocator (another than the backend's) {@code hook} arms; {@code clientTrust} null is the switch off. Adds the
     * forwarder's status and counters to {@code statuses}.
     */
    private static String clientWrite(
            byte[] read, Predicate<ByteBufAllocator> clientTrust, WriteHook hook, List<String> statuses) {
        var allocator = new RecordingAllocator();
        var writes = new ArrayList<ChannelFuture>();
        var written = new ArrayList<ByteBuf>();
        Options options = Options.forwarding().onFrame((fixture, buf) -> {
            ByteBuf out = hook.prepare(buf, allocator);
            written.add(out);
            writes.add(fixture.client.writeAndFlush(out));
        });
        try (var f =
                clientTrust == null ? new Fixture(options.withoutForwarding()) : new Fixture(options, clientTrust)) {
            f.client.config().setAllocator(allocator);
            f.read(read.clone());
            assertEquals(1, writes.size());
            String outcome =
                    sent(f.wire()) + ", promise " + describe(writes.getFirst().cause());
            if (f.forwarder != null) statuses.add(f.forwarder.status() + ", forwarded " + f.forwarder.forwarded());
            for (ByteBuf buf : written) if (buf.refCnt() > 0) buf.release(buf.refCnt());
            return outcome;
        }
    }

    @Test
    void publishedPacketEditedAtAnUntrustedClientAllocationIsSentAsWithTheSwitchOff() {
        byte[] packet = packet(3000, 141);
        byte[] read = frame(body(packet, THRESHOLD));
        byte[] edited = packet.clone();
        edited[10] ^= 0x5a;
        WriteHook edit = (buf, allocator) -> {
            atClientAllocation(allocator, () -> {
                int at = buf.readerIndex() + 10;
                buf.setByte(at, buf.getByte(at) ^ 0x5a);
            });
            return buf;
        };
        leakDetectionDisabled(() -> {
            List<String> statuses = new ArrayList<>();
            String off = clientWrite(read, null, edit, statuses);
            assertTrue(off.contains(Arrays.toString(edited)), off);
            assertEquals(off, clientWrite(read, CompressedKeep.NETTY_ALLOCATORS, edit, statuses), "production check");
            assertEquals(List.of(CompressedForwarder.UNTRUSTED + ", forwarded 0"), statuses);
        });
    }

    @Test
    void clientAllocationThatFailsOnTheUntrustedPathFailsTheWriteAsWithTheSwitchOff() {
        WriteHook refuse = (buf, allocator) -> {
            atClientAllocation(allocator, () -> {
                throw new OutOfMemoryError("client allocation");
            });
            return buf;
        };
        byte[] read = frame(body(packet(3000, 142), THRESHOLD));
        leakDetectionDisabled(() -> {
            List<String> statuses = new ArrayList<>();
            String off = clientWrite(read, null, refuse, statuses);
            assertTrue(
                    off.endsWith("promise io.netty.handler.codec.EncoderException / java.lang.OutOfMemoryError"), off);
            assertEquals(off, clientWrite(read, CompressedKeep.NETTY_ALLOCATORS, refuse, statuses));
            assertEquals(List.of(CompressedForwarder.UNTRUSTED + ", forwarded 0"), statuses);
        });
    }

    @Test
    void frameChangedAtEachKeepAllocationFinishesAsWithTheSwitchOff() {
        byte[] packet = packet(3000, 143);
        byte[] read = frame(body(packet, THRESHOLD));
        String decoderName = VelocityCompressedForward.COMPRESSION_DECODER;
        Map<String, BiConsumer<Fixture, ByteBuf>> changes = new LinkedHashMap<>();
        changes.put("discardReadBytes", (f, frame) -> frame.discardReadBytes());
        changes.put("reader rewound", (f, frame) -> frame.readerIndex(0));
        changes.put("skipBytes(1)", (f, frame) -> frame.skipBytes(1));
        changes.put("limiter that refuses", (f, frame) -> f.decoder.packetLimiter = recording(f, false));
        changes.put("limiter that allows", (f, frame) -> f.decoder.packetLimiter = recording(f, true));
        changes.put("threshold raised", (f, frame) -> f.decoder.threshold = 3001);
        changes.put(
                "handler between",
                (f, frame) ->
                        f.backend.pipeline().addBefore(decoderName, "between", new ChannelInboundHandlerAdapter()));
        changes.put("decoder removed", (f, frame) -> f.backend.pipeline().remove(decoderName));
        for (var change : changes.entrySet()) {
            String off = changedAt(read, 0, false, change.getValue());
            for (int k = 1; k <= 3; k++)
                for (boolean finalTrust : new boolean[] {false, true})
                    assertEquals(
                            off,
                            changedAt(read, k, finalTrust, change.getValue()),
                            change.getKey() + " at keep allocation " + k + ", final trust " + finalTrust);
        }
    }

    /** A limiter that records each count it accounts in the fixture's list and answers {@code allow}. */
    private static PacketLimiter recording(Fixture f, boolean allow) {
        return bytes -> {
            f.accounted.add(bytes);
            return allow;
        };
    }

    /**
     * One read of {@code read} with auto-read off, a plugin retaining the frame, and {@code change} at keep allocation
     * {@code k} (or at the decoder's one allocation, switched off, when {@code k} is 0): what the client receives, the
     * exception, the frame's indices and count, the accounted counts and the read requests. With {@code finalTrust}
     * false the change also enables leak detection, which the keep's predicate reads.
     */
    private static String changedAt(byte[] read, int k, boolean finalTrust, BiConsumer<Fixture, ByteBuf> change) {
        ResourceLeakDetector.Level level = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
        try (var f = new Fixture(k == 0 ? Options.today() : Options.forwarding().unlinked())) {
            if (k > 0) {
                Predicate<ByteBufAllocator> trust =
                        finalTrust ? TRUSTED : TRUSTED.and(alloc -> !ResourceLeakDetector.isEnabled());
                VelocityCompressedForward.place(
                        f.forwarder, f.client.pipeline(), f.backend, DECODER, COMPRESSORS, trust);
                assertEquals("linked", f.forwarder.linkStatus());
            }
            var held = new ArrayList<ByteBuf>();
            hold(f, held);
            f.backend.config().setAutoRead(false);
            int[] requests = {0};
            f.backend.pipeline().addFirst("reads", new ChannelOutboundHandlerAdapter() {
                @Override
                public void read(ChannelHandlerContext ctx) {
                    requests[0]++;
                    ctx.read();
                }
            });
            var allocator = new RecordingAllocator();
            atAllocation(allocator, k, once(() -> {
                if (!finalTrust) ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
                change.accept(f, held.getFirst());
            }));
            f.backend.config().setAllocator(allocator);
            Throwable thrown = null;
            try {
                f.read(read.clone());
            } catch (Throwable failure) {
                thrown = failure;
            }
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
            ByteBuf frame = held.getFirst();
            String seen = sent(f.wire()) + ", " + describe(thrown) + ", reader " + frame.readerIndex() + ", writer "
                    + frame.writerIndex() + ", count " + frame.refCnt() + ", accounted " + f.accounted + ", reads "
                    + requests[0];
            if (k > 0 && !finalTrust)
                assertEquals(0, f.forwarder.forwarded(), "a refused trust check forwards nothing");
            if (frame.refCnt() > 0) frame.release(frame.refCnt());
            for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation");
            return seen;
        } finally {
            ResourceLeakDetector.setLevel(level);
        }
    }

    @Test
    void limiterSetAfterTheLinkMakesNoKeepAllocation() {
        byte[] packet = packet(3000, 144);
        byte[] read = frame(body(packet, THRESHOLD));
        try (var f = new Fixture(Options.forwarding())) {
            assertEquals("linked", f.forwarder.linkStatus());
            var allocator = new RecordingAllocator();
            f.backend.config().setAllocator(allocator);
            f.decoder.packetLimiter = recording(f, true);
            f.read(read);
            assertPackets(List.of(packet), read(f.wire(), THRESHOLD));
            assertEquals(1, allocator.allocated.size(), "only the decoder's own allocation");
            assertEquals(1, f.decoder.inflated.size(), "the decoder decodes the frame");
            assertEquals(List.of(3000), f.accounted);
            assertEquals(CompressedKeep.LIMITED, f.forwarder.linkStatus());
            assertNull(f.backend.pipeline().get(CompressedKeep.NAME));
        }
    }

    @Test
    void decoderWhoseLimiterCannotAccountIsNotLinked() {
        try (var f = new Fixture(Options.forwarding().unlinked())) {
            String name = VelocityCompressedForward.COMPRESSION_DECODER;
            f.backend.pipeline().replace(name, name, new RunnableLimiterDecoder());
            VelocityCompressedForward.place(
                    f.forwarder, f.client.pipeline(), f.backend, RunnableLimiterDecoder.class, COMPRESSORS, TRUSTED);
            assertEquals("backend-decoder", f.forwarder.linkStatus());
            assertNull(f.backend.pipeline().get(CompressedKeep.NAME));
            assertNull(VelocityCompressedForward.decode(RunnableLimiterDecoder.class));
            assertNotNull(VelocityCompressedForward.decode(f.decoder.getClass()));
        }
    }

    @Test
    void decodeFailureReachesTheHandlersAfterTheDecoderAsWithTheSwitchOff() {
        byte[] body = body(packet(3000, 145), THRESHOLD);
        Map<String, byte[]> reads = new LinkedHashMap<>();
        reads.put("corrupt body", frame(Arrays.copyOf(body, body.length - 20)));
        reads.put("refusing limiter", frame(body));
        for (var read : reads.entrySet()) {
            boolean refuse = read.getKey().equals("refusing limiter");
            String off = caughtAt(read.getValue(), 0, false, refuse);
            assertTrue(off.contains(", caught [last: "), off);
            assertFalse(off.contains("between"), off);
            for (int k = 1; k <= 3; k++)
                for (boolean finalTrust : new boolean[] {false, true})
                    assertEquals(
                            off,
                            caughtAt(read.getValue(), k, finalTrust, refuse),
                            read.getKey() + " at keep allocation " + k + ", final trust " + finalTrust);
        }
    }

    /**
     * {@link #changedAt} with a handler that records each exception it is given inserted between the keep and the
     * decoder and one at the end of the pipeline, and with {@code refuse}, a limiter that refuses; adds what they saw.
     */
    private static String caughtAt(byte[] read, int k, boolean finalTrust, boolean refuse) {
        List<String> caught = new ArrayList<>();
        String seen = changedAt(read, k, finalTrust, (f, frame) -> {
            if (refuse) f.decoder.packetLimiter = recording(f, false);
            f.backend
                    .pipeline()
                    .addBefore(VelocityCompressedForward.COMPRESSION_DECODER, "between", catching("between", caught));
            f.backend.pipeline().addLast("last", catching("last", caught));
        });
        return seen + ", caught " + caught;
    }

    /** Records each exception it is given as {@code name} and its description, and passes it on. */
    private static ChannelInboundHandlerAdapter catching(String name, List<String> caught) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                caught.add(name + ": " + describe(cause));
                ctx.fireExceptionCaught(cause);
            }
        };
    }

    @Test
    void claimOfTwiceTheInflatedLengthFailsAsWithTheSwitchOff() {
        byte[] body = body(packet(3000, 147), THRESHOLD);
        assertArrayEquals(new byte[] {(byte) 0xb8, 0x17}, Arrays.copyOf(body, 2), "3000 as a varint");
        body[0] = (byte) 0xf0;
        body[1] = 0x2e;
        checkSameFailure("claimed 6000", Options.forwarding(), f -> {}, frame(body));
    }

    @Test
    void trustRefusalWhoseLoggerThrowsDeliversThePacketAsWithTheSwitchOff() {
        byte[] packet = packet(3000, 148);
        byte[] read = frame(body(packet, THRESHOLD));
        String off = sent(today(Options.today(), read.clone())) + ", ok";
        assertTrue(off.contains(Arrays.toString(packet)), off);
        leakDetectionDisabled(() -> {
            for (boolean preClaim : new boolean[] {false, true}) {
                String label = preClaim ? "refused before the claim" : "refused after the keep's allocations";
                try (var log = mockStatic(LogUtil.class);
                        var f = new Fixture(Options.forwarding().unlinked())) {
                    log.when(() -> LogUtil.warn(anyString())).thenThrow(new IllegalStateException("logger"));
                    var forward = forward();
                    VelocityCompressedForward.place(
                            f.forwarder,
                            f.client.pipeline(),
                            f.backend,
                            DECODER,
                            COMPRESSORS,
                            TRUSTED.and(alloc -> !ResourceLeakDetector.isEnabled()),
                            forward::refused);
                    assertEquals("linked", f.forwarder.linkStatus(), label);
                    var allocator = new RecordingAllocator();
                    if (preClaim) {
                        f.backend.config().setAllocator(new ForeignAllocator());
                    } else {
                        atAllocation(
                                allocator,
                                1,
                                once(() -> ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID)));
                        f.backend.config().setAllocator(allocator);
                    }
                    Throwable thrown = null;
                    try {
                        f.read(read.clone());
                    } catch (Throwable failure) {
                        thrown = failure;
                    }
                    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
                    assertEquals(off, sent(f.wire()) + ", " + describe(thrown), label);
                    log.verify(() -> LogUtil.warn(VelocityCompressedForward.Refusal.UNTRUSTED.warning), times(1));
                    assertEquals(0, f.forwarder.forwarded(), label);
                    assertEquals(CompressedKeep.UNTRUSTED, f.forwarder.linkStatus(), label);
                    for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation, " + label);
                }
            }
        });
    }

    @Test
    void untrustedClientWriteWhoseLoggerThrowsSucceedsAsWithTheSwitchOff() {
        byte[] read = frame(body(packet(3000, 150), THRESHOLD));
        List<String> seen = new ArrayList<>();
        for (boolean on : new boolean[] {false, true}) {
            var writes = new ArrayList<ChannelFuture>();
            try (var log = mockStatic(LogUtil.class);
                    var f = new Fixture(
                            Options.today().onFrame((fixture, buf) -> writes.add(fixture.client.write(buf))))) {
                log.when(() -> LogUtil.warn(anyString())).thenThrow(new IllegalStateException("logger"));
                CompressedForwarder forwarder = on
                        ? VelocityCompressedForward.install(
                                f.client.pipeline(),
                                CompressEncoder.class,
                                DECODER,
                                COMPRESSORS,
                                CompressedKeep.NETTY_ALLOCATORS,
                                forward()::refused)
                        : null;
                if (on) {
                    assertNotNull(forwarder);
                    VelocityCompressedForward.place(
                            forwarder, f.client.pipeline(), f.backend, DECODER, COMPRESSORS, TRUSTED);
                    assertEquals("linked", forwarder.linkStatus());
                }
                f.client.config().setAllocator(new RecordingAllocator());
                f.read(read.clone());
                assertEquals(1, writes.size());
                seen.add(sent(f.wire()) + ", promise "
                        + describe(writes.getFirst().cause()));
                if (on) {
                    assertEquals(CompressedForwarder.UNTRUSTED, forwarder.status());
                    log.verify(
                            () -> LogUtil.warn(VelocityCompressedForward.Refusal.CLIENT_UNTRUSTED.warning), times(1));
                }
            }
        }
        assertTrue(seen.get(0).endsWith(", promise ok"), seen.get(0));
        assertEquals(seen.get(0), seen.get(1));
    }

    /** A forward over the stand-in codecs whose {@link VelocityCompressedForward#refused} logs through LogUtil. */
    private static VelocityCompressedForward forward() {
        return new VelocityCompressedForward(
                mock(ProxyServer.class),
                new Object(),
                p -> null,
                new VelocityCompressedForward.Codecs(CompressEncoder.class, DECODER, COMPRESSORS));
    }

    @Test
    void backendAllocatorSwappedAfterTheTrustCheckGivesTheKeepNoBuffer() {
        byte[] packet = packet(3000, 151);
        byte[] read = frame(body(packet, THRESHOLD));
        String off = sent(today(Options.today(), read.clone()));
        try (var f = new Fixture(Options.forwarding().unlinked())) {
            var trusted = new RecordingAllocator();
            var foreign = new RecordingAllocator();
            foreign.beforeHeap = () -> f.backend.config().setAllocator(trusted);
            int[] checks = {0};
            Predicate<ByteBufAllocator> trust = alloc -> {
                boolean accepted = alloc == trusted;
                f.backend.config().setAllocator(++checks[0] == 1 ? foreign : trusted);
                return accepted;
            };
            VelocityCompressedForward.place(f.forwarder, f.client.pipeline(), f.backend, DECODER, COMPRESSORS, trust);
            assertEquals("linked", f.forwarder.linkStatus());
            f.backend.config().setAllocator(trusted);
            f.read(read.clone());
            assertEquals(off, sent(f.wire()));
            assertEquals(2, checks[0], "the keep's two trust checks");
            assertEquals(List.of(), foreign.allocated, "no buffer of the allocator set after the first check");
            assertEquals(1, f.forwarder.forwarded());
            for (ByteBuf buf : trusted.allocated) assertEquals(0, buf.refCnt(), "an allocation");
        }
    }

    @Test
    void statsReportALinkWhoseBackendChannelClosedAsClosed() {
        try (var f = new Fixture(Options.forwarding())) {
            assertTrue(f.forwarder.stats().contains(", link=linked,"), f.forwarder.stats());
            f.backend.close();
            assertFalse(f.backend.isActive());
            assertTrue(f.forwarder.stats().contains(", link=backend-closed,"), f.forwarder.stats());
        }
    }

    @Test
    void noStaticClassValueHoldsAPluginType() throws Exception {
        Set<Map.Entry<String, String>> allowed = Set.of(Map.entry(CompressedKeep.class.getName(), "NETTY_BUFFERS"));
        ClassLoader loader = VelocityCompressedForward.class.getClassLoader();
        String pkg = VelocityCompressedForward.class.getPackageName();
        Path dir = Path.of(VelocityCompressedForward.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .resolve(pkg.replace('.', '/'));
        List<String> types = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".class")) types.add(pkg + "." + name.substring(0, name.length() - 6));
            }
        }
        assertTrue(types.contains(CompressedKeep.class.getName()), dir.toString());
        // Read from the class files: some field types in the package are absent from the test classpath.
        TypePool pool = TypePool.Default.of(loader);
        for (String type : types)
            for (FieldDescription field : pool.describe(type).resolve().getDeclaredFields()) {
                if (!field.isStatic() || !field.getType().asErasure().represents(ClassValue.class)) continue;
                assertTrue(
                        allowed.contains(Map.entry(type, field.getName())),
                        type + "." + field.getName() + " is a static ClassValue off the allow-list");
                Field reflected = Class.forName(type, false, loader).getDeclaredField(field.getName());
                for (Class<?> held : classes(reflected.getGenericType()))
                    assertNull(held.getClassLoader(), type + "." + field.getName() + " holds " + held);
            }
    }

    /** Every class {@code type} names, its type arguments included. */
    private static List<Class<?>> classes(Type type) {
        List<Class<?>> found = new ArrayList<>();
        if (type instanceof Class<?> named) found.add(named);
        if (type instanceof ParameterizedType parameterized) {
            found.addAll(classes(parameterized.getRawType()));
            for (Type argument : parameterized.getActualTypeArguments()) found.addAll(classes(argument));
        }
        return found;
    }

    @Test
    void decodeFailureReachesTheDecodersOwnExceptionCaughtAsWithTheSwitchOff() {
        checkDecoderCaught(false);
    }

    @Test
    void decodeFailureAfterTheDecodersRemovalPassesItOverAsWithTheSwitchOff() {
        checkDecoderCaught(true);
    }

    /**
     * A corrupt body read through a decoder that records the exceptions its {@code exceptionCaught} gets, removed at
     * the decoder's or each keep allocation when {@code remove} is set: the same packets, exception and handlers'
     * records as with the switch off.
     */
    private static void checkDecoderCaught(boolean remove) {
        byte[] body = body(packet(3000, 153), THRESHOLD);
        byte[] read = frame(Arrays.copyOf(body, body.length - 20));
        String off = decoderCaughtAt(read, 0, remove);
        String failure = "io.netty.handler.codec.DecoderException / java.util.zip.DataFormatException";
        assertTrue(
                off.endsWith(", caught [" + (remove ? "" : "decoder: " + failure + ", ") + "last: " + failure + "]"),
                off);
        for (int k = 1; k <= (remove ? 3 : 1); k++)
            assertEquals(off, decoderCaughtAt(read, k, remove), "keep allocation " + k);
    }

    /** One read of {@code read} as {@link #checkDecoderCaught} describes it, at allocation {@code k} (0 switched off). */
    private static String decoderCaughtAt(byte[] read, int k, boolean remove) {
        String name = VelocityCompressedForward.COMPRESSION_DECODER;
        try (var f = new Fixture(k == 0 ? Options.today() : Options.forwarding())) {
            List<String> caught = new ArrayList<>();
            f.decoder.caught = caught;
            f.backend.pipeline().addLast("last", catching("last", caught));
            if (k > 0) assertEquals("linked", f.forwarder.linkStatus());
            var allocator = new RecordingAllocator();
            if (remove)
                atAllocation(allocator, k, once(() -> f.backend.pipeline().remove(name)));
            f.backend.config().setAllocator(allocator);
            Throwable thrown = null;
            try {
                f.read(read.clone());
            } catch (Throwable failure) {
                thrown = failure;
            }
            if (k > 0) assertEquals(0, f.forwarder.forwarded());
            assertEquals(remove, f.backend.pipeline().get(name) == null);
            for (ByteBuf buf : allocator.allocated) assertEquals(0, buf.refCnt(), "an allocation");
            return sent(f.wire()) + ", " + describe(thrown) + ", caught " + caught;
        }
    }

    @Test
    void pendingContextIsInvokedOnlyUnderAnUnorderedExecutor() throws Exception {
        var unordered = new UnorderedThreadPoolEventExecutor(1);
        var channel = new EmbeddedChannel(false, false);
        try {
            channel.pipeline().addLast(unordered, "unordered", new ChannelInboundHandlerAdapter());
            channel.pipeline().addLast("ordered", new ChannelInboundHandlerAdapter());
            Class<?> context = Class.forName("io.netty.channel.AbstractChannelHandlerContext");
            Field state = context.getDeclaredField("handlerState");
            state.setAccessible(true);
            Object pending = staticField(context, "ADD_PENDING");
            for (String name : List.of("unordered", "ordered"))
                assertEquals(pending, state.getInt(channel.pipeline().context(name)), name);
            assertTrue(CompressedKeep.invoked(channel.pipeline().context("unordered")));
            assertFalse(CompressedKeep.invoked(channel.pipeline().context("ordered")));
        } finally {
            unordered.shutdownGracefully(0, 1, TimeUnit.SECONDS).await();
        }
    }

    @Test
    void frameReadWhileTheDecodersAddIsPendingPassesOnAsWithTheSwitchOff() throws Exception {
        byte[] read = frame(body(packet(3000, 149), THRESHOLD));
        List<String> seen = new ArrayList<>();
        for (boolean forward : new boolean[] {false, true}) {
            try (var f = new Fixture(forward ? Options.forwarding() : Options.today())) {
                ChannelHandlerContext decoder =
                        f.backend.pipeline().context(VelocityCompressedForward.COMPRESSION_DECODER);
                handlerState(decoder, "ADD_PENDING");
                Throwable thrown = null;
                try {
                    f.read(read.clone());
                } catch (Throwable failure) {
                    thrown = failure;
                } finally {
                    handlerState(decoder, "ADD_COMPLETE");
                }
                seen.add(sent(f.wire()) + ", " + describe(thrown) + ", decoded " + f.decoder.inflated.size());
                if (forward) {
                    assertEquals(0, f.forwarder.forwarded());
                    assertEquals("linked", f.forwarder.linkStatus());
                }
            }
        }
        assertEquals(seen.get(0), seen.get(1));
    }

    /** Sets {@code ctx}'s handler state to netty's state constant {@code state}. */
    private static void handlerState(ChannelHandlerContext ctx, String state) throws ReflectiveOperationException {
        Class<?> context = Class.forName("io.netty.channel.AbstractChannelHandlerContext");
        Field field = context.getDeclaredField("handlerState");
        field.setAccessible(true);
        field.setInt(ctx, (int) staticField(context, state));
    }

    /** A decoder of the stand-in's shape whose limiter field's type has no {@code account(int)}. */
    static final class RunnableLimiterDecoder extends MessageToMessageDecoder<ByteBuf> {
        int threshold = THRESHOLD;
        final SharedCompressor compressor = new SharedCompressor();
        Runnable packetLimiter;

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            out.add(in.retain());
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
        final EmbeddedChannel client = channel();
        final SharedCompressor compressor;
        final CompressEncoder encoder;
        final EmbeddedChannel backend;
        final FrameDecoder frameDecoder;
        final CompressDecoder decoder;
        final CompressedForwarder forwarder;
        final CultConnection connection;
        private final List<ByteBuf> tracked = new ArrayList<>();
        /** The counts a {@link #recording} limiter accounted. */
        final List<Integer> accounted = new ArrayList<>();

        private final List<Backend> backends = new ArrayList<>();
        private final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        private final Options options;

        Fixture(Options options) {
            this(options, TRUSTED);
        }

        /** As the one-argument fixture; the forwarder forwards only under a client allocator {@code clientTrust} accepts. */
        Fixture(Options options, Predicate<ByteBufAllocator> clientTrust) {
            this.options = options;
            compressor = options.consume() ? new SharedCompressor() : new AddressCompressor();
            encoder = new CompressEncoder(THRESHOLD, options.consume(), compressor);
            encoder.setHeap(options.heap());
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
                            pipeline, CompressEncoder.class, DECODER, COMPRESSORS, clientTrust, refusal -> {})
                    : null;
            var first = backend(options);
            backend = first.channel;
            frameDecoder = first.frames;
            decoder = first.decoder;
            if (options.link())
                VelocityCompressedForward.place(forwarder, pipeline, backend, DECODER, COMPRESSORS, TRUSTED);
        }

        /** A backend channel with the stand-in handlers, writing to this client. */
        Backend backend(Options options) {
            return backend(options, channel());
        }

        /** As {@link #backend(Options)}, on {@code channel}. */
        Backend backend(Options options, EmbeddedChannel channel) {
            var frames = new FrameDecoder();
            var pipeline = channel.pipeline();
            pipeline.addLast("frame-decoder", frames);
            pipeline.addLast("read-timeout", new ChannelInboundHandlerAdapter());
            CompressDecoder decoder = null;
            if (options.backendThreshold() >= 0) {
                decoder = options.consume()
                        ? new CompressDecoder(options.backendThreshold())
                        : new CompressDecoder(options.backendThreshold(), new AddressCompressor());
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
                if (decoder == null) return;
                for (ByteBuf buf : decoder.inflated) assertEquals(0, buf.refCnt(), "an inflated frame is released");
                for (ByteBuf buf : decoder.compressor.destinations)
                    assertEquals(0, buf.refCnt(), "an inflate's destination is released");
            }
        }
    }
}
