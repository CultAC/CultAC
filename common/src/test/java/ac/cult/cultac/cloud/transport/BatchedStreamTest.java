package ac.cult.cultac.cloud.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.wire.IntakeWire;
import com.github.luben.zstd.ZstdDecompressCtx;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A message written in parts decompresses to the same bytes, in the same order, as the parts written as messages of
 * their own, and is whole blocks the receiver takes: none the frame's last, none cut short.
 */
class BatchedStreamTest {
    private static final int BLOCK_HEADER_BYTES = 3;
    private static final int RLE_BLOCK = 1;
    private static final int RESERVED_BLOCK = 3;
    private static final int FRAME_HEADER_BYTES = 6;

    private final List<ExecutorService> pools = new ArrayList<>();

    @AfterEach
    void stopPools() {
        pools.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void partsDecompressToTheSameStreamAsWholeMessages() throws Exception {
        List<List<byte[]>> parts = parts(new Random(7), 600);
        for (int perMessage : new int[] {1, 3, 20, 101}) {
            QueuedBytes wholeQueued = new QueuedBytes(new AtomicLong());
            QueuedBytes batchedQueued = new QueuedBytes(new AtomicLong());
            byte[] whole = stream(pipeline(wholeQueued), wholeQueued, parts, 1);
            byte[] batched = stream(pipeline(batchedQueued), batchedQueued, parts, perMessage);
            assertArrayEquals(whole, batched, perMessage + " parts per message");
            assertArrayEquals(concatenated(parts), batched);
            assertEquals(0, wholeQueued.get());
            assertEquals(0, batchedQueued.get());
        }
    }

    @Test
    void anEmptyLastPartEndsTheMessage() throws Exception {
        List<List<byte[]>> parts = parts(new Random(11), 40);
        parts.add(List.of());
        QueuedBytes queued = new QueuedBytes(new AtomicLong());
        assertArrayEquals(concatenated(parts), stream(pipeline(queued), queued, parts, parts.size()));
        assertEquals(0, queued.get());
    }

    @Test
    void anOpenMessageHoldsOnlyItsCompressedBytesInTheCount() throws Exception {
        QueuedBytes queued = new QueuedBytes(new AtomicLong());
        try (CompressionPipeline pipeline = pipeline(queued)) {
            long raw = 0;
            for (List<byte[]> part : parts(new Random(3), 200)) {
                int bytes = bytes(part);
                raw += bytes;
                queued.addAndGet(bytes);
                pipeline.submit(part, bytes, false);
            }
            pipeline.submit(List.of(), 0, true);
            byte[] message = pipeline.take();
            assertEquals(message.length, queued.get());
            assertTrue(message.length < raw * 3 / 4, message.length + " of " + raw);
            pipeline.sent(message);
        }
        assertEquals(0, queued.get());
    }

    @Test
    void droppingAnOpenMessageGivesItsBytesBack() throws Exception {
        QueuedBytes queued = new QueuedBytes(new AtomicLong());
        CompressionPipeline pipeline = pipeline(queued);
        for (List<byte[]> part : parts(new Random(5), 50)) {
            queued.addAndGet(bytes(part));
            pipeline.submit(part, bytes(part), false);
        }
        assertTrue(pipeline.open());
        pipeline.close();
        assertEquals(0, queued.get());
        assertEquals(0, pipeline.writing());
    }

    /** Frames of the shapes the capture sends: mostly small, partly repetitive, now and then a large one. */
    private static List<List<byte[]>> parts(Random random, int count) {
        List<List<byte[]>> parts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            List<byte[]> part = new ArrayList<>();
            int records = 1 + random.nextInt(12);
            for (int r = 0; r < records; r++) {
                int size = random.nextInt(50) == 0 ? 16 * 1024 + random.nextInt(96 * 1024) : 4 + random.nextInt(1500);
                byte[] record = new byte[size];
                for (int b = 0; b < size; b++) record[b] = (byte) (random.nextInt(4) == 0 ? random.nextInt() : b % 23);
                part.add(record);
            }
            parts.add(part);
        }
        return parts;
    }

    private CompressionPipeline pipeline(QueuedBytes queued) {
        ExecutorService pool = CompressionPipeline.newPool(3, "batched-stream-test");
        pools.add(pool);
        CompressionPipeline pipeline = new CompressionPipeline(pool, 3, queued);
        pipeline.stream();
        return pipeline;
    }

    /**
     * Every part through {@code pipeline}, {@code perMessage} to a message, each message checked as the receiver checks
     * it and decompressed in order; returns what the stream decompresses to.
     */
    private static byte[] stream(
            CompressionPipeline pipeline, QueuedBytes queued, List<List<byte[]>> parts, int perMessage)
            throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (pipeline;
                ZstdDecompressCtx zstd = new ZstdDecompressCtx()) {
            boolean first = true;
            for (int i = 0; i < parts.size(); i++) {
                List<byte[]> part = parts.get(i);
                boolean end = (i + 1) % perMessage == 0 || i == parts.size() - 1;
                queued.addAndGet(bytes(part));
                pipeline.submit(new ArrayList<>(part), bytes(part), end);
                if (!end) continue;
                byte[] message = pipeline.take();
                assertEquals(IntakeWire.COMPRESSED, message[0]);
                wholeBlocks(message, first ? 1 + FRAME_HEADER_BYTES : 1);
                first = false;
                out.writeBytes(ConnectionFixture.decompress(zstd, message));
                pipeline.sent(message);
            }
            assertEquals(0, pipeline.writing());
            assertEquals(0, pipeline.writingBytes());
        }
        return out.toByteArray();
    }

    /** The receiver's check: from {@code at} on, the message is whole blocks, none of them the frame's last. */
    private static void wholeBlocks(byte[] message, int at) {
        while (at < message.length) {
            assertTrue(message.length - at >= BLOCK_HEADER_BYTES, "stops inside a block header");
            int header = (message[at] & 0xFF) | (message[at + 1] & 0xFF) << 8 | (message[at + 2] & 0xFF) << 16;
            int type = header >>> 1 & 0x03;
            assertEquals(0, header & 0x01, "ends the frame");
            assertTrue(type != RESERVED_BLOCK, "reserved block type");
            at += BLOCK_HEADER_BYTES + (type == RLE_BLOCK ? 1 : header >>> 3);
        }
        assertEquals(message.length, at, "stops inside a block");
    }

    private static byte[] concatenated(List<List<byte[]>> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (List<byte[]> part : parts) for (byte[] record : part) out.writeBytes(record);
        return out.toByteArray();
    }

    private static int bytes(List<byte[]> records) {
        int bytes = 0;
        for (byte[] record : records) bytes += record.length;
        return bytes;
    }
}
