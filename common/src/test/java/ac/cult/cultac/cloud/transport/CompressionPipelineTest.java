package ac.cult.cultac.cloud.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.wire.IntakeWire;
import com.github.luben.zstd.ZstdDecompressCtx;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CompressionPipelineTest {
    private static final String NAME = "compression-pipeline-test";

    private final List<ExecutorService> pools = new ArrayList<>();

    @AfterEach
    void stopPools() {
        pools.forEach(ExecutorService::shutdownNow);
    }

    /** A pipeline on a pool of its own, as the shared one every connection writes on, its socket open. */
    private CompressionPipeline pipeline(int threads, String name, QueuedBytes queued) {
        ExecutorService pool = CompressionPipeline.newPool(threads, name);
        pools.add(pool);
        CompressionPipeline pipeline = new CompressionPipeline(pool, threads, queued);
        pipeline.stream();
        return pipeline;
    }

    /** Messages of very different sizes, so later small ones finish before earlier big ones. */
    private static List<List<byte[]>> messages(int count) {
        Random random = new Random(42);
        List<List<byte[]>> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            List<byte[]> records = new ArrayList<>();
            int size = i % 7 == 0 ? 512 * 1024 + random.nextInt(256 * 1024) : 16 + random.nextInt(2048);
            byte[] record = new byte[size];
            if (i % 2 == 0) random.nextBytes(record);
            else Arrays.fill(record, (byte) i);
            ByteBuffer.wrap(record).putInt(0, i);
            records.add(record);
            records.add(new byte[] {(byte) i, (byte) (i >> 8)});
            messages.add(records);
        }
        return messages;
    }

    private static int bytes(List<byte[]> records) {
        int bytes = 0;
        for (byte[] record : records) bytes += record.length;
        return bytes;
    }

    /** The records back to back, as a message decompresses. */
    private static byte[] expected(List<byte[]> records) {
        ByteBuffer joined = ByteBuffer.allocate(bytes(records));
        for (byte[] record : records) joined.put(record);
        return joined.array();
    }

    @Test
    void messagesComeBackInSubmissionOrder() throws Exception {
        QueuedBytes queued = new QueuedBytes(new AtomicLong());
        List<List<byte[]>> messages = messages(120);
        try (CompressionPipeline pipeline = pipeline(4, NAME, queued);
                ZstdDecompressCtx zstd = new ZstdDecompressCtx()) {
            int submitted = 0;
            int taken = 0;
            while (taken < messages.size()) {
                while (submitted < messages.size() && pipeline.hasRoom()) {
                    List<byte[]> records = new ArrayList<>(messages.get(submitted++));
                    queued.addAndGet(bytes(records));
                    pipeline.submit(records, bytes(records), true);
                    records.clear();
                }
                byte[] message = pipeline.take();
                assertArrayEquals(expected(messages.get(taken++)), ConnectionFixture.decompress(zstd, message));
                pipeline.sent(message);
            }
            assertNull(pipeline.take());
        }
        assertEquals(0, queued.get());
    }

    @Test
    void holdsAtMostTwoMessagesPerThread() throws Exception {
        QueuedBytes queued = new QueuedBytes(new AtomicLong());
        try (CompressionPipeline pipeline = pipeline(2, NAME, queued)) {
            for (List<byte[]> records : messages(4)) {
                assertTrue(pipeline.hasRoom());
                queued.addAndGet(bytes(records));
                pipeline.submit(records, bytes(records), true);
            }
            assertFalse(pipeline.hasRoom());
            pipeline.sent(pipeline.take());
            assertTrue(pipeline.hasRoom());
        }
        assertEquals(0, queued.get());
    }

    @Test
    void countsEachMessageAsWrittenOnceCompressed() throws Exception {
        QueuedBytes queued = new QueuedBytes(new AtomicLong());
        List<List<byte[]>> messages = messages(6);
        long raw = 0;
        try (CompressionPipeline pipeline = pipeline(3, NAME, queued)) {
            for (List<byte[]> records : messages) {
                raw += bytes(records);
                queued.addAndGet(bytes(records));
                pipeline.submit(records, bytes(records), true);
            }
            assertTrue(queued.get() <= raw + messages.size() * 1024L);
            List<byte[]> written = new ArrayList<>();
            byte[] message;
            while ((message = pipeline.take()) != null) written.add(message);
            long writtenBytes = 0;
            for (byte[] each : written) writtenBytes += each.length;
            assertEquals(writtenBytes, queued.get());
            for (byte[] each : written) pipeline.sent(each);
        }
        assertEquals(0, queued.get());
    }

    @Test
    void anInterruptedTakeGivesTheMessagesBytesBackOnceWritten() throws Exception {
        AtomicLong process = new AtomicLong();
        QueuedBytes queued = new QueuedBytes(process);
        ExecutorService pool = CompressionPipeline.newPool(1, NAME + "-interrupted");
        pools.add(pool);
        CountDownLatch busy = new CountDownLatch(1);
        pool.execute(() -> {
            try {
                busy.await();
            } catch (InterruptedException ignored) {
            }
        });
        try (CompressionPipeline pipeline = new CompressionPipeline(pool, 1, queued)) {
            pipeline.stream();
            byte[] record = new byte[4096];
            queued.addAndGet(record.length);
            pipeline.submit(List.of(record), record.length, true);
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, pipeline::take);
            busy.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (queued.get() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(0, queued.get());
            assertEquals(0, process.get());
        }
    }

    @Test
    void aFailedWriteGivesItsBytesBack() throws Exception {
        QueuedBytes queued = new QueuedBytes(new AtomicLong());
        ExecutorService pool = CompressionPipeline.newPool(2, NAME + "-failing");
        pools.add(pool);
        try (CompressionPipeline pipeline = new CompressionPipeline(pool, 2, queued, () -> {
            IntakeWire.StreamWriter closed = new IntakeWire.StreamWriter();
            closed.close();
            return closed;
        })) {
            pipeline.stream();
            queued.addAndGet(10);
            pipeline.submit(List.of(new byte[10]), 10, true);
            assertThrows(ExecutionException.class, pipeline::take);
            assertEquals(0, queued.get());
        }
    }

    @Test
    void aWriteFailingInsideAnOpenMessageGivesEveryByteBack() throws Exception {
        QueuedBytes queued = new QueuedBytes(new AtomicLong());
        ExecutorService pool = CompressionPipeline.newPool(2, NAME + "-failing-open");
        pools.add(pool);
        try (CompressionPipeline pipeline = new CompressionPipeline(pool, 2, queued, () -> {
            IntakeWire.StreamWriter closed = new IntakeWire.StreamWriter();
            closed.close();
            return closed;
        })) {
            pipeline.stream();
            queued.addAndGet(30);
            pipeline.submit(List.of(new byte[10]), 10, false);
            pipeline.submit(List.of(new byte[20]), 20, true);
            assertThrows(ExecutionException.class, pipeline::take);
            assertEquals(0, queued.get());
            assertEquals(0, pipeline.writing());
            assertEquals(0, pipeline.writingBytes());
            queued.addAndGet(10);
            pipeline.submit(List.of(new byte[10]), 10, false);
            pipeline.drop();
            assertEquals(0, queued.get());
            assertEquals(0, pipeline.writing());
            assertEquals(0, pipeline.writingBytes());
        }
    }

    @Test
    void partsThePoolRefusesFailTheirMessageAndGiveEveryByteBack() throws Exception {
        QueuedBytes queued = new QueuedBytes(new AtomicLong());
        ExecutorService pool = CompressionPipeline.newPool(1, NAME + "-refusing");
        try (CompressionPipeline pipeline = new CompressionPipeline(pool, 1, queued)) {
            pipeline.stream();
            pool.shutdown();
            queued.addAndGet(30);
            pipeline.submit(List.of(new byte[10]), 10, false);
            pipeline.submit(List.of(new byte[20]), 20, true);
            ExecutionException failed = assertThrows(ExecutionException.class, pipeline::take);
            assertTrue(failed.getCause() instanceof RejectedExecutionException, failed.toString());
            assertEquals(0, queued.get());
            assertEquals(0, pipeline.writing());
            assertEquals(0, pipeline.writingBytes());

            queued.addAndGet(10);
            pipeline.submit(List.of(new byte[10]), 10, false);
            assertTrue(pipeline.open());
            pipeline.drop();
            assertFalse(pipeline.open());
            assertEquals(0, queued.get());
            assertEquals(0, pipeline.writing());
        }
    }

    @Test
    void closingDropsWhatIsPendingAndLeavesTheSharedPoolRunning() throws Exception {
        QueuedBytes queued = new QueuedBytes(new AtomicLong());
        ExecutorService pool = CompressionPipeline.newPool(4, NAME + "-close");
        pools.add(pool);
        CompressionPipeline pipeline = new CompressionPipeline(pool, 4, queued);
        pipeline.stream();
        for (List<byte[]> records : messages(8)) {
            queued.addAndGet(bytes(records));
            pipeline.submit(records, bytes(records), true);
        }
        queued.addAndGet(10);
        pipeline.submit(List.of(new byte[100]), 10, true);
        pipeline.close();
        assertEquals(0, queued.get());
        assertEquals(0, pipeline.writing());
        assertEquals(0, pipeline.writingBytes());
        assertNull(pipeline.take());
        assertFalse(pool.isShutdown());
    }

    @Test
    void pipelinesOnOnePoolKeepTheirOwnOrder() throws Exception {
        ExecutorService pool = CompressionPipeline.newPool(2, NAME + "-shared");
        pools.add(pool);
        List<List<byte[]>> messages = messages(12);
        List<CompressionPipeline> pipelines = new ArrayList<>();
        List<ZstdDecompressCtx> readers = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            CompressionPipeline pipeline = new CompressionPipeline(pool, 2, new QueuedBytes(new AtomicLong()));
            pipeline.stream();
            pipelines.add(pipeline);
            readers.add(new ZstdDecompressCtx());
        }
        try {
            for (int i = 0; i < messages.size(); i += 3) {
                for (int p = 0; p < 3; p++) {
                    List<byte[]> records = messages.get(i + p);
                    pipelines.get(p).submit(records, bytes(records), true);
                }
                for (int p = 0; p < 3; p++) {
                    assertArrayEquals(
                            expected(messages.get(i + p)),
                            ConnectionFixture.decompress(
                                    readers.get(p), pipelines.get(p).take()));
                }
            }
        } finally {
            pipelines.forEach(CompressionPipeline::close);
            readers.forEach(ZstdDecompressCtx::close);
        }
    }

    @Test
    void streamWriterClosesWithItsSocket() throws Exception {
        List<IntakeWire.StreamWriter> writers = new ArrayList<>();
        ExecutorService pool = CompressionPipeline.newPool(2, NAME + "-writers");
        pools.add(pool);
        CompressionPipeline pipeline = new CompressionPipeline(pool, 2, new QueuedBytes(new AtomicLong()), () -> {
            IntakeWire.StreamWriter writer = new IntakeWire.StreamWriter();
            writers.add(writer);
            return writer;
        });
        List<byte[]> records = List.of(new byte[] {1, 2, 3});
        pipeline.stream();
        pipeline.submit(records, 3, true);
        pipeline.stream();
        assertEquals(2, writers.size());
        assertThrows(IllegalStateException.class, () -> writers.get(0).write(records, 3, true));
        pipeline.submit(records, 3, true);
        assertTrue(pipeline.take().length > 1);
        assertTrue(pipeline.streaming());
        pipeline.close();
        assertFalse(pipeline.streaming());
        assertEquals(2, writers.size());
        assertThrows(IllegalStateException.class, () -> writers.get(1).write(records, 3, true));
    }
}
