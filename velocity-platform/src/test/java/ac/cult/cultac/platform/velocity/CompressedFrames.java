package ac.cult.cultac.platform.velocity;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.wire.Wire;
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

    /** A compressor the client's two codecs share; removing the decoder closes it, as Velocity's does. */
    static final class SharedCompressor {
        boolean disposed;

        void check() {
            if (disposed) throw new IllegalStateException("Already closed");
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

    /**
     * Allocates through the context, then inflates a single-buffer frame body into that direct buffer, of two
     * components when {@link #composite} is set, or passes an uncompressed body on as itself.
     */
    static final class CompressDecoder extends MessageToMessageDecoder<ByteBuf> {
        final int threshold;
        final List<ByteBuf> inflated = new ArrayList<>();
        boolean composite;
        /** Inflates by {@link #inflateVanilla}'s rule, as Velocity's decoders accept bytes after the stream. */
        boolean vanilla;
        /** Runs between the inflate and the publish, where Velocity's decoder calls its packet limiter. */
        Runnable afterInflate;
        /** Stands in for Velocity's settable limiter field and runs where Velocity's decoder calls it. */
        Runnable packetLimiter;

        private final SharedCompressor compressor;

        CompressDecoder(int threshold) {
            this(threshold, null);
        }

        CompressDecoder(int threshold, SharedCompressor compressor) {
            this.threshold = threshold;
            this.compressor = compressor;
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            if (compressor != null) compressor.disposed = true;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            int dataLength = Wire.readVarInt(in);
            if (dataLength == 0) {
                if (in.readableBytes() >= threshold) throw new DecoderException("uncompressed above threshold");
                if (packetLimiter != null) packetLimiter.run();
                out.add(in.retain());
                return;
            }
            if (dataLength < threshold) throw new DecoderException("compressed below threshold");
            int half = composite ? dataLength / 2 : dataLength;
            ByteBuf first = ctx.alloc().directBuffer(half);
            ByteBuf second = null;
            try {
                if (composite) second = ctx.alloc().directBuffer(dataLength - half);
                if (in.nioBufferCount() != 1) throw new IllegalArgumentException("source has multiple backing buffers");
                byte[] zlib = new byte[in.readableBytes()];
                in.readBytes(zlib);
                byte[] packet = vanilla ? inflateVanilla(zlib, dataLength) : inflate(zlib, dataLength);
                if (afterInflate != null) afterInflate.run();
                if (packetLimiter != null) packetLimiter.run();
                first.writeBytes(packet, 0, half);
                ByteBuf uncompressed = first;
                if (second != null) {
                    second.writeBytes(packet, half, dataLength - half);
                    uncompressed = ctx.alloc().compositeDirectBuffer(2).addComponents(true, first, second);
                }
                inflated.add(uncompressed);
                out.add(uncompressed);
            } catch (RuntimeException | Error failure) {
                first.release();
                if (second != null) second.release();
                throw failure;
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
     * Compresses at or above the threshold with a 3-byte length, failing at 2 MiB compressed, else frames the packet
     * uncompressed, which consumes it; compressing consumes the packet when {@code consume} is set.
     */
    static final class CompressEncoder extends MessageToByteEncoder<ByteBuf> {
        private int threshold;
        private final boolean consume;
        private final SharedCompressor compressor;
        private int level = CLIENT_LEVEL;

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

        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws DataFormatException {
            int uncompressed = msg.readableBytes();
            if (uncompressed < threshold) {
                Wire.writeVarInt(out, uncompressed + 1);
                out.writeByte(0);
                out.writeBytes(msg);
                return;
            }
            if (compressor != null) compressor.check();
            byte[] packet = new byte[uncompressed];
            if (consume) msg.readBytes(packet);
            else msg.getBytes(msg.readerIndex(), packet);
            out.writeMedium(0);
            Wire.writeVarInt(out, uncompressed);
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
