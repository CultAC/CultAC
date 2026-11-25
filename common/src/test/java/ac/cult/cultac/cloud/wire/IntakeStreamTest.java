package ac.cult.cultac.cloud.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** The socket's zstd frame as {@link IntakeWire.StreamWriter} writes it, read back as a receiver reads it. */
class IntakeStreamTest {
    private static final byte[] MAGIC = {(byte) 0x28, (byte) 0xB5, (byte) 0x2F, (byte) 0xFD};

    /** Messages of a few records each, from a few bytes to past a zstd block, part repeating and part noise. */
    private static List<List<byte[]>> messages() {
        SplittableRandom random = new SplittableRandom(7);
        List<List<byte[]>> messages = new ArrayList<>();
        int[] sizes = {1, 40, 700, 5_000, 70_000, 300_000, 12, 2_000};
        byte[] repeated = new byte[512];
        for (int i = 0; i < repeated.length; i++) repeated[i] = (byte) (i * 31);
        for (int size : sizes) {
            List<byte[]> records = new ArrayList<>();
            for (int r = 0; r < 3; r++) {
                byte[] record = new byte[size];
                for (int i = 0; i < size; i++) {
                    record[i] = random.nextInt(4) == 0 ? (byte) random.nextInt(256) : repeated[i % repeated.length];
                }
                records.add(record);
            }
            messages.add(records);
        }
        return messages;
    }

    private static int bytes(List<byte[]> records) {
        int bytes = 0;
        for (byte[] record : records) bytes += record.length;
        return bytes;
    }

    private static byte[] concat(List<byte[]> records) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] record : records) out.writeBytes(record);
        return out.toByteArray();
    }

    private static byte[] array(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }

    /** One message of the frame, from its bytes and the messages before it alone. */
    private static byte[] decompress(ZstdDecompressCtx context, byte[] message) {
        assertEquals(IntakeWire.COMPRESSED, message[0]);
        ByteBuffer in = ByteBuffer.allocateDirect(message.length - 1);
        in.put(message, 1, message.length - 1).flip();
        ByteBuffer out = ByteBuffer.allocateDirect(64 * 1024);
        ByteArrayOutputStream records = new ByteArrayOutputStream();
        while (true) {
            out.clear();
            assertFalse(context.decompressDirectByteBufferStream(out, in), "a message never ends the frame");
            out.flip();
            byte[] chunk = new byte[out.remaining()];
            out.get(chunk);
            records.writeBytes(chunk);
            if (!in.hasRemaining() && chunk.length < out.capacity()) return records.toByteArray();
        }
    }

    @Test
    void eachMessageDecompressesToItsRecords() {
        try (IntakeWire.StreamWriter writer = new IntakeWire.StreamWriter();
                ZstdDecompressCtx reader = new ZstdDecompressCtx()) {
            for (List<byte[]> records : messages()) {
                byte[] message = array(writer.write(records, bytes(records), true));
                assertArrayEquals(concat(records), decompress(reader, message));
            }
        }
    }

    @Test
    void theFrameHeaderDeclaresTheWindowAndNothingElse() {
        for (List<byte[]> first : messages()) {
            byte[] written;
            try (IntakeWire.StreamWriter writer = new IntakeWire.StreamWriter()) {
                written = array(writer.write(first, bytes(first), true));
            }
            for (int level : new int[] {1, 3}) {
                byte[] reference = frameHeader(first, level);
                for (int i = 0; i < MAGIC.length; i++) assertEquals(MAGIC[i], written[1 + i], "magic byte " + i);
                assertEquals(reference[4], written[5], "frame header descriptor at level " + level);
                assertEquals(reference[5], written[6], "window descriptor at level " + level);
            }
            assertEquals(0x00, written[5], "no content size, single segment, checksum or dictionary");
            assertEquals((byte) 0x38, written[6], "a 128 KiB window");
        }
    }

    @Test
    void eachMessageIsWhatAContextAtTheStreamsSettingsWrites() {
        boolean hashLogShows = false;
        try (IntakeWire.StreamWriter writer = new IntakeWire.StreamWriter();
                ZstdCompressCtx reference = context(13);
                ZstdCompressCtx widerHash = context(14)) {
            for (List<byte[]> records : messages()) {
                byte[] written = array(writer.write(records, bytes(records), true));
                assertArrayEquals(flushed(reference, records), written);
                hashLogShows |= !Arrays.equals(flushed(widerHash, records), written);
            }
        }
        assertTrue(hashLogShows, "the messages tell hash log 13 from 14");
    }

    /** Level 1, a 128 KiB window and {@code hashLog}, with no checksum or content size. */
    private static ZstdCompressCtx context(int hashLog) {
        return new ZstdCompressCtx()
                .setLevel(1)
                .setWindowLog(17)
                .setHashLog(hashLog)
                .setChecksum(false)
                .setContentSize(false);
    }

    /** {@code records} compressed by {@code context} to a flush, as a message: the compressed flag, then the bytes. */
    private static byte[] flushed(ZstdCompressCtx context, List<byte[]> records) {
        byte[] input = concat(records);
        ByteBuffer in = ByteBuffer.allocateDirect(input.length).put(input).flip();
        ByteBuffer out = ByteBuffer.allocateDirect((int) Zstd.compressBound(input.length) + 64);
        while (!context.compressDirectByteBufferStream(out, in, EndDirective.FLUSH)) {
            out = ByteBuffer.allocateDirect(out.capacity() * 2).put(out.flip());
        }
        out.flip();
        byte[] message = new byte[1 + out.remaining()];
        message[0] = IntakeWire.COMPRESSED;
        out.get(message, 1, message.length - 1);
        return message;
    }

    private static byte[] frameHeader(List<byte[]> first, int level) {
        byte[] input = concat(first);
        ByteBuffer in = ByteBuffer.allocateDirect(input.length).put(input).flip();
        ByteBuffer out = ByteBuffer.allocateDirect((int) Zstd.compressBound(input.length));
        try (ZstdCompressCtx streaming = new ZstdCompressCtx()
                .setLevel(level)
                .setWindowLog(IntakeWire.StreamWriter.WINDOW_LOG)
                .setChecksum(false)
                .setContentSize(false)) {
            streaming.compressDirectByteBufferStream(out, in, EndDirective.FLUSH);
        }
        out.flip();
        byte[] header = new byte[6];
        out.get(header);
        return header;
    }
}
