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
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.zip.Adler32;
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
            mark.attach(second.channel.pipeline());
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
            assertNull(VelocityCompressedForward.thresholdReader(pipeline, standIn), "no encoder");
            pipeline.addLast(CompressedForwarder.COMPRESSION_ENCODER, new OtherEncoder(512));
            assertNull(VelocityCompressedForward.thresholdReader(pipeline, standIn), "another class, same field");
            assertNull(VelocityCompressedForward.install(pipeline, standIn, true, true, false, true));
            assertNull(pipeline.get(CompressedForwarder.NAME));
            pipeline.replace(
                    CompressedForwarder.COMPRESSION_ENCODER,
                    CompressedForwarder.COMPRESSION_ENCODER,
                    new CompressEncoder(512));
            assertNull(
                    VelocityCompressedForward.install(
                            pipeline, VelocityCompressedForward.VELOCITY_ENCODER, true, true, false, true),
                    "the stand-in is not Velocity's encoder");
            assertNull(pipeline.get(CompressedForwarder.NAME));
            var forwarder = VelocityCompressedForward.install(pipeline, standIn, true, true, false, true);
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
                    channel.pipeline(), CompressEncoder.class.getName(), true, true, false, true));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void negativeInstallThresholdInstallsNothing() {
        var channel = new EmbeddedChannel();
        try {
            var pipeline = channel.pipeline();
            pipeline.addLast(CompressedForwarder.COMPRESSION_ENCODER, new CompressEncoder(-1));
            assertNull(VelocityCompressedForward.install(
                    pipeline, CompressEncoder.class.getName(), true, true, false, true));
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

    /**
     * Two frames of {@code packet} through a backend that {@code insert} changes after the link: the client decodes
     * {@code expected} twice, as with the switch off, and the link is removed without forwarding.
     */
    private void checkDetached(byte[] packet, BiConsumer<Fixture, ChannelPipeline> insert, byte[] expected) {
        byte[] read = concat(frame(body(packet, THRESHOLD)), frame(body(packet, THRESHOLD)));
        checkSubstituted(read, insert, List.of(expected, expected));
    }

    /**
     * {@code read} through a backend that {@code insert} changes after the link: the client decodes {@code expected},
     * as with the switch off, no frame is forwarded, and the link is removed.
     */
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
                    else assertEquals(consume ? 1 : 2, f.forwarder.forwarded(), label);
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

    /** An outbound handler with a threshold field that is not the encoder class the forwarder accepts. */
    static final class OtherEncoder extends ChannelOutboundHandlerAdapter {
        final int threshold;

        OtherEncoder(int threshold) {
            this.threshold = threshold;
        }
    }

    /** Today's path sends another body for the same packet, so only a forwarded frame carries {@code body}. */
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
            boolean guard,
            boolean link,
            int backendThreshold,
            boolean via,
            boolean copyAfterMark,
            boolean heap,
            BiConsumer<Fixture, ByteBuf> onFrame,
            boolean consume) {
        static Options forwarding() {
            return new Options(true, true, true, THRESHOLD, false, false, false, null, true);
        }

        static Options today() {
            return forwarding().withoutForwarding();
        }

        Options withoutForwarding() {
            return new Options(false, guard, false, backendThreshold, via, copyAfterMark, heap, onFrame, consume);
        }

        Options guard(boolean value) {
            return new Options(forward, value, link, backendThreshold, via, copyAfterMark, heap, onFrame, consume);
        }

        Options unlinked() {
            return new Options(forward, guard, false, backendThreshold, via, copyAfterMark, heap, onFrame, consume);
        }

        Options backendThreshold(int value) {
            return new Options(forward, guard, link, value, via, copyAfterMark, heap, onFrame, consume);
        }

        Options via(boolean value) {
            return new Options(forward, guard, link, backendThreshold, value, copyAfterMark, heap, onFrame, consume);
        }

        Options copying() {
            return new Options(forward, guard, link, backendThreshold, via, true, heap, onFrame, consume);
        }

        Options heapCipher() {
            return new Options(forward, guard, link, backendThreshold, via, copyAfterMark, true, onFrame, consume);
        }

        Options onFrame(BiConsumer<Fixture, ByteBuf> value) {
            return new Options(forward, guard, link, backendThreshold, via, copyAfterMark, heap, value, consume);
        }

        Options consume(boolean value) {
            return new Options(forward, guard, link, backendThreshold, via, copyAfterMark, heap, onFrame, value);
        }
    }

    static final class Fixture implements AutoCloseable {
        final EmbeddedChannel client = new EmbeddedChannel();
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
            encoder = new CompressEncoder(THRESHOLD, options.consume());
            var pipeline = client.pipeline();
            pipeline.addLast("cipher-encoder", new CipherEncoder());
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
                            pipeline,
                            CompressEncoder.class.getName(),
                            options.guard(),
                            true,
                            options.heap(),
                            options.consume())
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
