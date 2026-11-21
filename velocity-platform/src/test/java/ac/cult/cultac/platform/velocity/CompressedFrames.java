package ac.cult.cultac.platform.velocity;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.wire.Wire;
import com.velocitypowered.proxy.network.limiter.PacketLimiter;
import com.velocitypowered.proxy.protocol.util.NettyPreconditions;
import com.velocitypowered.proxy.util.except.QuietDecoderException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stand-ins for Velocity's frame, compression and cipher handlers, written to the same wire rules (they are not
 * copies of Velocity's code), and a client-side reader that checks what a vanilla client checks.
 */
final class CompressedFrames {
    static final byte[] KEY = "0123456789abcdef".getBytes();
    /** A two-byte packet ID no route uses, so Cult's encoder passes the frame on unchanged. */
    static final int UNROUTED_ID = 2047;

    private CompressedFrames() {}

    /** A packet: the unrouted ID, then {@code size - 2} compressible bytes. */
    static byte[] packet(int size, int seed) {
        byte[] bytes = new byte[size];
        Random random = new Random(seed);
        bytes[0] = (byte) ((UNROUTED_ID & 0x7f) | 0x80);
        bytes[1] = (byte) (UNROUTED_ID >>> 7);
        for (int i = 2; i < size; i++) bytes[i] = (byte) (random.nextInt(8) == 0 ? random.nextInt(256) : i % 7);
        return bytes;
    }

    /** The backends deflate at this level and the client encoder at {@link #CLIENT_LEVEL}. */
    static final int BACKEND_LEVEL = 1;

    static final int CLIENT_LEVEL = 6;

    static byte[] zlib(byte[] data, int level) {
        Deflater deflater = new Deflater(level);
        deflater.setInput(data);
        deflater.finish();
        var out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk));
        deflater.end();
        return out.toByteArray();
    }

    /** A frame body as a compressing backend sends it: {@code varint(dataLength)} then zlib, or 0 then the packet. */
    static byte[] body(byte[] packet, int backendThreshold) {
        ByteBuf body = Unpooled.buffer();
        if (backendThreshold < 0) {
            body.writeBytes(packet);
        } else if (packet.length >= backendThreshold) {
            Wire.writeVarInt(body, packet.length);
            body.writeBytes(zlib(packet, BACKEND_LEVEL));
        } else {
            body.writeByte(0);
            body.writeBytes(packet);
        }
        return bytes(body);
    }

    /** {@code varint(length)} and the body. */
    static byte[] frame(byte[] body) {
        ByteBuf frame = Unpooled.buffer();
        Wire.writeVarInt(frame, body.length);
        frame.writeBytes(body);
        return bytes(frame);
    }

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    private static byte[] bytes(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        return bytes;
    }

    static Cipher cipher(int mode) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
            cipher.init(mode, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(KEY));
            return cipher;
        } catch (GeneralSecurityException failure) {
            throw new AssertionError(failure);
        }
    }

    /** A frame as the client reads it: the frame body and the packet it inflates to. */
    record ClientFrame(byte[] body, boolean compressed, byte[] packet) {}

    /** Decrypts and reads the client's stream, applying the client's threshold checks. */
    static List<ClientFrame> read(byte[] encrypted, int threshold) {
        return read(encrypted, threshold, false);
    }

    /** As {@link #read(byte[], int)}; {@code vanilla} inflates by {@link #inflateVanilla}'s rule. */
    static List<ClientFrame> read(byte[] encrypted, int threshold, boolean vanilla) {
        byte[] plain = cipher(Cipher.DECRYPT_MODE).update(encrypted);
        if (plain == null) plain = new byte[0];
        ByteBuf in = Unpooled.wrappedBuffer(plain);
        var frames = new ArrayList<ClientFrame>();
        while (in.isReadable()) {
            int start = in.readerIndex();
            int length = Wire.readVarInt(in);
            assertTrue(in.readerIndex() - start <= 3, "frame length fits a 21-bit VarInt");
            byte[] body = new byte[length];
            in.readBytes(body);
            ByteBuf b = Unpooled.wrappedBuffer(body);
            int dataLength = Wire.readVarInt(b);
            byte[] rest = new byte[b.readableBytes()];
            b.readBytes(rest);
            if (dataLength == 0) {
                assertTrue(rest.length < threshold, "uncompressed frame below the client's threshold");
                frames.add(new ClientFrame(body, false, rest));
            } else {
                assertTrue(dataLength >= threshold, "compressed frame at or above the client's threshold");
                byte[] packet = vanilla ? inflateVanilla(rest, dataLength) : inflate(rest, dataLength);
                frames.add(new ClientFrame(body, true, packet));
            }
        }
        return frames;
    }

    /**
     * The vanilla client's rule: every byte goes to the inflater, which fills exactly {@code dataLength} bytes; bytes
     * after the stream are not checked.
     */
    static byte[] inflateVanilla(byte[] zlib, int dataLength) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(zlib);
            byte[] out = new byte[dataLength];
            int got = inflater.inflate(out);
            if (got != dataLength) throw new DecoderException("Badly compressed packet");
            return out;
        } catch (DataFormatException failure) {
            throw new DecoderException(failure);
        } finally {
            inflater.end();
        }
    }

    /** The stand-in compressor classes, in the roles of Velocity's Java and libdeflate compressors, both forwarded. */
    static final VelocityCompressedForward.Compressors COMPRESSORS = new VelocityCompressedForward.Compressors(
            SharedCompressor.class, AddressCompressor.class, VelocityCompressedForward.Mode.TRUSTED_BACKENDS);

    /** As {@link #COMPRESSORS}, under the mode that forwards only the Java compressor's frames. */
    static final VelocityCompressedForward.Compressors ONE_STREAM_COMPRESSORS =
            new VelocityCompressedForward.Compressors(
                    SharedCompressor.class, AddressCompressor.class, VelocityCompressedForward.Mode.ONE_STREAM);

    /**
     * A compressor in the role of Velocity's Java one, which a client's two codecs share and a decoder closes when it
     * is removed: its {@link #inflate} takes a source of one NIO buffer, reads the zlib stream and ignores what follows
     * it, fails past {@code max} bytes, and consumes what it read.
     */
    static class SharedCompressor {
        boolean disposed;
        /** Every destination an inflate wrote into. */
        final List<ByteBuf> destinations = new ArrayList<>();

        void check() {
            if (disposed) throw new IllegalStateException("Object already disposed");
        }

        void close() {
            disposed = true;
        }

        public void inflate(ByteBuf source, ByteBuf destination, int max) throws DataFormatException {
            check();
            if (source.nioBufferCount() != 1) throw new IllegalArgumentException("source has multiple backing buffers");
            if (destination.nioBufferCount() != 1)
                throw new IllegalArgumentException("destination has multiple backing buffers");
            destinations.add(destination);
            byte[] zlib = new byte[source.readableBytes()];
            source.getBytes(source.readerIndex(), zlib);
            Inflater inflater = new Inflater();
            try {
                inflater.setInput(zlib);
                byte[] out = new byte[max];
                int produced = 0;
                while (!inflater.finished() && inflater.getBytesRead() < zlib.length) {
                    if (produced >= max) throw new DataFormatException("Got too much data");
                    int got = inflater.inflate(out, produced, max - produced);
                    if (got == 0 && !inflater.finished()) throw new DataFormatException("Badly compressed packet");
                    produced += got;
                }
                if (!inflater.finished()) throw new DataFormatException("Badly compressed packet");
                destination.writeBytes(out, 0, produced);
                source.skipBytes((int) inflater.getBytesRead());
            } finally {
                inflater.end();
            }
        }

        /** The input as the decoder passes it to {@link #inflate}: retained when compatible, else a copy. */
        ByteBuf compatible(ChannelHandlerContext ctx, ByteBuf in) {
            return in.retain();
        }
    }

    /**
     * A compressor in the role of Velocity's libdeflate one: its {@link #inflate} reads the source and the destination
     * through their memory addresses, fills exactly {@code max} bytes and leaves the source's index alone.
     */
    static final class AddressCompressor extends SharedCompressor {
        @Override
        public void inflate(ByteBuf source, ByteBuf destination, int max) throws DataFormatException {
            check();
            destination.ensureWritable(max);
            source.memoryAddress();
            destination.memoryAddress();
            destinations.add(destination);
            byte[] zlib = new byte[source.readableBytes()];
            source.getBytes(source.readerIndex(), zlib);
            Inflater inflater = new Inflater();
            try {
                inflater.setInput(zlib);
                byte[] out = new byte[max];
                if (inflater.inflate(out) != max || !inflater.finished())
                    throw new DataFormatException("inflate failed");
                destination.writeBytes(out);
            } finally {
                inflater.end();
            }
        }

        @Override
        ByteBuf compatible(ChannelHandlerContext ctx, ByteBuf in) {
            if (in.hasMemoryAddress()) return in.retain();
            return ctx.alloc().directBuffer(in.readableBytes()).writeBytes(in);
        }
    }

    /** A compressor of another class: it inflates as {@link SharedCompressor} does, then flips the packet's first byte. */
    static final class TranslatingCompressor extends SharedCompressor {
        @Override
        public void inflate(ByteBuf source, ByteBuf destination, int max) throws DataFormatException {
            super.inflate(source, destination, max);
            int first = destination.readerIndex();
            destination.setByte(first, destination.getByte(first) ^ 1);
        }
    }

    static byte[] inflate(byte[] zlib, int dataLength) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(zlib);
            byte[] out = new byte[dataLength];
            int got = inflater.inflate(out);
            if (got != dataLength || !inflater.finished() || inflater.getRemaining() != 0)
                throw new DecoderException("Badly compressed packet");
            return out;
        } catch (DataFormatException failure) {
            throw new DecoderException(failure);
        } finally {
            inflater.end();
        }
    }

    static List<byte[]> packets(List<ClientFrame> frames) {
        return frames.stream().map(ClientFrame::packet).toList();
    }

    static void assertPackets(List<byte[]> expected, List<ClientFrame> frames) {
        assertEquals(expected.size(), frames.size(), "frame count");
        for (int i = 0; i < expected.size(); i++)
            assertArrayEquals(expected.get(i), frames.get(i).packet(), "packet " + i);
    }

    static void assertSameBytes(byte[] expected, byte[] actual) {
        assertTrue(Arrays.equals(expected, actual), "wire bytes equal today's");
    }

    /** Splits frames from a backend read, emitting retained slices of the cumulation. */
    static final class FrameDecoder extends ByteToMessageDecoder {
        final List<ByteBuf> frames = new ArrayList<>();

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            while (in.isReadable()) {
                in.markReaderIndex();
                int length = 0;
                boolean complete = false;
                int read = 0;
                for (; read < 3 && in.isReadable(); read++) {
                    int next = in.readUnsignedByte();
                    length |= (next & 0x7f) << (read * 7);
                    if ((next & 0x80) == 0) {
                        complete = true;
                        break;
                    }
                }
                if (!complete && read == 3) throw new DecoderException("frame length wider than 21 bits");
                if (!complete || in.readableBytes() < length) {
                    in.resetReaderIndex();
                    return;
                }
                ByteBuf frame = in.readRetainedSlice(length);
                frames.add(frame);
                out.add(frame);
            }
        }
    }

    /** A limiter that runs {@code action} each time it accounts and allows every packet. */
    static PacketLimiter limiter(Runnable action) {
        return bytes -> {
            action.run();
            return true;
        };
    }

    /**
     * In Velocity's decode order: checks a compressed claim against threshold and cap, inflates into a direct buffer of
     * that length, checks the length and accounts the limiter, or passes an uncompressed body on; fails through the
     * {@link NettyPreconditions} and {@link QuietDecoderException} stand-ins.
     */
    static final class CompressDecoder extends MessageToMessageDecoder<ByteBuf> {
        private static final int CAP = 8 * 1024 * 1024;
        int threshold;
        /** Every buffer this decoder published. */
        final List<ByteBuf> inflated = new ArrayList<>();
        /** Stands in for Velocity's settable limiter field and is accounted where Velocity's decoder accounts it. */
        PacketLimiter packetLimiter;
        /**
         * When set, receives each exception this decoder's {@code exceptionCaught} gets, and a removal leaves the
         * compressor open for the read in progress.
         */
        List<String> caught;

        final SharedCompressor compressor;

        CompressDecoder(int threshold) {
            this(threshold, new SharedCompressor());
        }

        CompressDecoder(int threshold, SharedCompressor compressor) {
            this.threshold = threshold;
            this.compressor = compressor;
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            if (caught == null) compressor.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (caught != null)
                caught.add("decoder: " + cause.getClass().getName() + " / "
                        + (cause.getCause() == null
                                ? null
                                : cause.getCause().getClass().getName()));
            super.exceptionCaught(ctx, cause);
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            int dataLength = Wire.readVarInt(in);
            if (dataLength == 0) {
                int actual = in.readableBytes();
                NettyPreconditions.checkFrame(actual < threshold, "uncompressed above threshold", actual, threshold);
                if (packetLimiter != null && !packetLimiter.account(in.readableBytes()))
                    throw new QuietDecoderException("Rate limit exceeded while processing packets for %s"
                            .formatted(ctx.channel().remoteAddress()));
                out.add(in.retain());
                return;
            }
            NettyPreconditions.checkFrame(dataLength >= threshold, "compressed below threshold", dataLength, threshold);
            NettyPreconditions.checkFrame(dataLength <= CAP, "compressed above the cap", dataLength, CAP);
            ByteBuf compatible = compressor.compatible(ctx, in);
            ByteBuf uncompressed = ctx.alloc().directBuffer(dataLength);
            try {
                compressor.inflate(compatible, uncompressed, dataLength);
                NettyPreconditions.checkFrame(
                        uncompressed.writerIndex() == dataLength,
                        "decompressed size does not match the claimed size",
                        uncompressed.writerIndex(),
                        dataLength);
                if (packetLimiter != null && !packetLimiter.account(dataLength))
                    throw new QuietDecoderException("Rate limit exceeded while processing packets for %s"
                            .formatted(ctx.channel().remoteAddress()));
                inflated.add(uncompressed);
                out.add(uncompressed);
            } catch (Exception failure) {
                uncompressed.release();
                throw failure;
            } finally {
                compatible.release();
            }
        }
    }

    /** Copies every buffer, as a translating decoder does. */
    static final class CopyDecoder extends MessageToMessageDecoder<ByteBuf> {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            ByteBuf copy = ctx.alloc().directBuffer(in.readableBytes());
            copy.writeBytes(in, in.readerIndex(), in.readableBytes());
            out.add(copy);
        }
    }

    /**
     * In Velocity's encode order: allocates as {@code allocateBuffer} does, then frames the packet uncompressed, or
     * writes a 3-byte length and the claim, reads the compressor's compatible input (a retain or a copy) and releases
     * it, deflates, and fails at 2 MiB compressed.
     */
    static final class CompressEncoder extends MessageToByteEncoder<ByteBuf> {
        private int threshold;
        private final boolean consume;
        private final SharedCompressor compressor;
        private int level = CLIENT_LEVEL;
        private boolean heap;
        /** Every buffer {@link #allocateBuffer} returned. */
        final List<ByteBuf> outputs = new ArrayList<>();

        CompressEncoder(int threshold) {
            this(threshold, true);
        }

        CompressEncoder(int threshold, boolean consume) {
            this(threshold, consume, null);
        }

        CompressEncoder(int threshold, boolean consume, SharedCompressor compressor) {
            this.threshold = threshold;
            this.consume = consume;
            this.compressor = compressor;
        }

        void setThreshold(int threshold) {
            this.threshold = threshold;
        }

        void setLevel(int level) {
            this.level = level;
        }

        /** Frames below the threshold go into heap buffers, as under Velocity's Java cipher. */
        void setHeap(boolean heap) {
            this.heap = heap;
        }

        @Override
        protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, ByteBuf msg, boolean preferDirect) {
            int uncompressed = msg.readableBytes();
            ByteBuf out;
            if (uncompressed < threshold) {
                int size = uncompressed + 1;
                size += CompressedForwarder.varIntSize(size);
                out = heap ? ctx.alloc().heapBuffer(size) : ctx.alloc().directBuffer(size);
            } else {
                out = ctx.alloc().directBuffer(uncompressed - 1 + 3 + CompressedForwarder.varIntSize(uncompressed));
            }
            outputs.add(out);
            return out;
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws DataFormatException {
            int uncompressed = msg.readableBytes();
            if (uncompressed < threshold) {
                Wire.writeVarInt(out, uncompressed + 1);
                out.writeByte(0);
                out.writeBytes(msg);
                return;
            }
            out.writeMedium(0);
            Wire.writeVarInt(out, uncompressed);
            ByteBuf compatible = compressor == null ? msg.retain() : compressor.compatible(ctx, msg);
            byte[] packet = new byte[uncompressed];
            try {
                if (compressor != null) compressor.check();
                if (consume) compatible.readBytes(packet);
                else compatible.getBytes(compatible.readerIndex(), packet);
            } finally {
                compatible.release();
            }
            byte[] compressed = zlib(packet, level);
            if (compressed.length >= 1 << 21) throw new DataFormatException("over 2 MiB compressed");
            out.writeBytes(compressed);
            int length = out.readableBytes() - 3;
            out.setMedium(0, ((length & 0x7f) | 0x80) << 16 | (((length >>> 7) & 0x7f) | 0x80) << 8 | (length >>> 14));
        }
    }

    /** Encrypts each buffer in place with AES/CFB8 and passes it on. */
    static final class CipherEncoder extends MessageToMessageEncoder<ByteBuf> {
        private final Cipher cipher = cipher(Cipher.ENCRYPT_MODE);

        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
            byte[] plain = new byte[msg.readableBytes()];
            msg.getBytes(msg.readerIndex(), plain);
            byte[] encrypted = cipher.update(plain);
            if (encrypted != null) msg.setBytes(msg.readerIndex(), encrypted);
            out.add(msg.retain());
        }
    }
}
