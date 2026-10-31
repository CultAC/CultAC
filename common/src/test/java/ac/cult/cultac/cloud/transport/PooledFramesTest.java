package ac.cult.cultac.cloud.transport;

import static ac.cult.cultac.cloud.transport.ConnectionFixture.await;
import static ac.cult.cultac.cloud.transport.ConnectionFixture.settings;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Globals;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Receiver;
import ac.cult.cultac.cloud.wire.IntakeWire;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Frames sent in arrays from {@link CloudConnection#frame}, longer than their records: each array is reused once the
 * socket wrote or refused it, and the service reads what it reads for the same records sent whole.
 */
class PooledFramesTest {
    private static final UUID PLAYER = new UUID(1, 2);

    @Test
    void anArrayARefusedSendTakesIsReused() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver(timing(TimeUnit.MILLISECONDS.toNanos(100)))) {
            AtomicLong generation = new AtomicLong(-1);
            CloudConnection connection = connect(receiver, generation);
            Random random = new Random(3);

            Pooled other = pooled(connection, random, 1);
            assertFalse(connection.send(generation.get() + 1, other.array(), other.length(), 0));
            assertSame(other.array(), other.again(connection), "another socket's send gives the array back");

            Pooled full = pooled(connection, random, 2);
            SharedTransport.PROCESS_QUEUED.addAndGet(SharedTransport.MAX_PROCESS_QUEUED_BYTES);
            try {
                assertFalse(connection.send(generation.get(), full.array(), full.length(), 0));
            } finally {
                SharedTransport.PROCESS_QUEUED.addAndGet(-SharedTransport.MAX_PROCESS_QUEUED_BYTES);
            }
            assertSame(full.array(), full.again(connection), "a send past the queued cap gives the array back");

            Pooled sent = pooled(connection, random, 3);
            assertTrue(connection.send(generation.get(), sent.array(), sent.length(), 0));
            assertTrue(connection.send(generation.get(), IntakeWire.leave(PLAYER), 0));
            assertEquals(3, next(receiver).getFrame().getSeq());
            assertEquals(IntakeMessage.KindCase.LEAVE, next(receiver).getKindCase());
            await(() -> sent.again(connection) == sent.array(), "a written frame's array is reused");

            connection.close();
            Pooled closed = pooled(connection, random, 4);
            assertFalse(connection.send(generation.get(), closed.array(), closed.length(), 0));
            assertSame(closed.array(), closed.again(connection), "a closed socket's send gives the array back");
        }
    }

    @Test
    void theServiceReadsPooledFramesAsTheSameFramesSentWhole() throws Exception {
        try (Globals globals = new Globals();
                Receiver whole = new Receiver(timing(TimeUnit.MILLISECONDS.toNanos(100)));
                Receiver pooled = new Receiver(timing(TimeUnit.MILLISECONDS.toNanos(100)))) {
            AtomicLong wholeGeneration = new AtomicLong(-1);
            AtomicLong pooledGeneration = new AtomicLong(-1);
            CloudConnection wholeConnection = connect(whole, wholeGeneration);
            CloudConnection pooledConnection = connect(pooled, pooledGeneration);
            Random random = new Random(5);
            Set<byte[]> arrays = Collections.newSetFromMap(new IdentityHashMap<>());
            int reused = 0;
            long seq = 0;
            for (int burst = 0; burst < 6; burst++) {
                for (int i = 0; i < 40; i++, seq++) {
                    IntakeWire.FrameBuilder builder = builder(random);
                    byte[] exact = builder.build(PLAYER, seq);
                    byte[] array = pooledConnection.frame(exact.length);
                    if (!arrays.add(array)) reused++;
                    random.nextBytes(array);
                    builder.build(PLAYER, seq, array);
                    assertArrayEquals(exact, Arrays.copyOf(array, exact.length));
                    assertTrue(wholeConnection.send(wholeGeneration.get(), exact, 0));
                    assertTrue(pooledConnection.send(pooledGeneration.get(), array, exact.length, 0));
                }
                assertTrue(wholeConnection.send(wholeGeneration.get(), IntakeWire.leave(PLAYER), 0));
                assertTrue(pooledConnection.send(pooledGeneration.get(), IntakeWire.leave(PLAYER), 0));
                for (int i = 0; i <= 40; i++) assertEquals(next(whole), next(pooled));
                Thread.sleep(50);
            }
            assertTrue(reused > 0, "arrays come back to be written into again");
        }
    }

    @Test
    void entriesInLongerArraysAreCutAsTheirRecordsAre() {
        Random random = new Random(9);
        Queue<CloudConnection.Queued> wholeQueue = new ConcurrentLinkedQueue<>();
        Queue<CloudConnection.Queued> pooledQueue = new ConcurrentLinkedQueue<>();
        QueuedBytes wholeBytes = new QueuedBytes(new AtomicLong());
        QueuedBytes pooledBytes = new QueuedBytes(new AtomicLong());
        CloudConnection.Batch wholeBatch = new CloudConnection.Batch();
        CloudConnection.Batch pooledBatch = new CloudConnection.Batch();
        byte[] join = IntakeWire.join(PLAYER, 773, "vanilla", -64, 384, null, null, 0);
        int limit = 6000;
        for (int round = 0; round < 2000; round++) {
            byte[] record;
            boolean frame = random.nextInt(8) != 0;
            if (frame) record = builder(random).build(PLAYER, round);
            else record = random.nextBoolean() ? join : IntakeWire.leave(PLAYER);
            long generation = random.nextInt(10) == 0 ? 2 : 1;
            long resolvedExtra = random.nextInt(4) == 0 ? random.nextInt(3000) : 0;
            wholeQueue.add(new CloudConnection.Queued(generation, record, resolvedExtra));
            wholeBytes.addAndGet(record.length);
            if (frame) {
                byte[] array = new byte[record.length + 1 + random.nextInt(2 * limit)];
                for (int at = record.length; at < array.length; at += join.length)
                    System.arraycopy(join, 0, array, at, Math.min(join.length, array.length - at));
                System.arraycopy(record, 0, array, 0, record.length);
                pooledQueue.add(new CloudConnection.Queued(generation, array, record.length, resolvedExtra, true));
            } else pooledQueue.add(new CloudConnection.Queued(generation, record, resolvedExtra));
            pooledBytes.addAndGet(record.length);
            if (random.nextInt(3) != 0) continue;
            while (true) {
                List<CloudConnection.Queued> wholePart = new ArrayList<>();
                List<CloudConnection.Queued> pooledPart = new ArrayList<>();
                int wholeTaken =
                        CloudConnection.nextPart(wholeQueue, wholeBytes, 1, limit, limit, wholeBatch, wholePart);
                int pooledTaken =
                        CloudConnection.nextPart(pooledQueue, pooledBytes, 1, limit, limit, pooledBatch, pooledPart);
                assertEquals(wholeTaken, pooledTaken);
                assertEquals(wholeBatch.full, pooledBatch.full);
                assertEquals(wholeBytes.get(), pooledBytes.get());
                assertEquals(wholePart.size(), pooledPart.size());
                for (int i = 0; i < wholePart.size(); i++) {
                    CloudConnection.Queued pooled = pooledPart.get(i);
                    assertArrayEquals(wholePart.get(i).record(), Arrays.copyOf(pooled.record(), pooled.length()));
                }
                wholeBytes.addAndGet(-wholeTaken);
                pooledBytes.addAndGet(-pooledTaken);
                if (!wholeBatch.full) break;
                wholeBatch.clear();
                pooledBatch.clear();
            }
        }
    }

    /** A frame of one to eight records of up to 1500 bytes. */
    private static IntakeWire.FrameBuilder builder(Random random) {
        IntakeWire.FrameBuilder builder = new IntakeWire.FrameBuilder();
        for (int i = 0, records = 1 + random.nextInt(8); i < records; i++) {
            byte[] payload = new byte[random.nextInt(1500)];
            random.nextBytes(payload);
            builder.add(IntakeWire.CLIENTBOUND, random.nextInt(50_000), payload, payload.length, 1, 0, 0);
        }
        return builder;
    }

    /** A frame in the first {@code length} bytes of an array from a socket. */
    private record Pooled(byte[] array, int length) {
        /** The next array the socket hands out for a frame of this length. */
        byte[] again(CloudConnection connection) {
            return connection.frame(length);
        }
    }

    /** An array from {@code connection} holding frame {@code seq}, whatever was in it before past the frame. */
    private static Pooled pooled(CloudConnection connection, Random random, long seq) {
        IntakeWire.FrameBuilder builder = builder(random);
        int length = builder.length(seq);
        byte[] array = connection.frame(length);
        random.nextBytes(array);
        builder.build(PLAYER, seq, array);
        return new Pooled(array, length);
    }

    private static CloudConnection.Timing timing(long batchNanos) {
        return new CloudConnection.Timing(
                CloudConnection.Timing.DEFAULT.quietNanos(),
                CloudConnection.Timing.DEFAULT.idleNanos(),
                System::nanoTime,
                CloudConnection.Timing.DEFAULT.takeoverNanos(),
                batchNanos);
    }

    private static CloudConnection connect(Receiver receiver, AtomicLong generation) throws Exception {
        receiver.connections.configure(settings(receiver.url(), "test-key", List.of(5)));
        CloudConnection connection = receiver.connections.forServer("proxy.a");
        assertNotNull(connection);
        connection.register(PLAYER, new CloudConnection.Stream() {
            public void connected(long connected) {
                generation.set(connected);
            }

            public void disconnected(long ended) {}

            public void reply(long replied, IntakeWire.Reply reply) {}
        });
        receiver.allowConnect("\"protocol\":5");
        await(() -> generation.get() > 0, "connected");
        return connection;
    }

    private static IntakeMessage next(Receiver receiver) throws InterruptedException {
        IntakeMessage message = receiver.messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(message, "no message within 5 s");
        return message;
    }
}
