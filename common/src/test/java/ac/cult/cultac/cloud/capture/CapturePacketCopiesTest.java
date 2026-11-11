package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CapturePacketCopiesTest {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final int CHUNK_COST = CapturePacketCopies.MAX_RETAINED_BYTES / CapturePacketCopies.MAX_CHUNKS;

    /** One copy: the array it is in, where it starts, and how long it is. */
    private record Copy(byte[] array, int offset, int length) {
        byte[] bytes() {
            return Arrays.copyOfRange(array, offset, offset + length);
        }
    }

    private static Copy copy(CapturePacketCopies copies, ByteBuf source, long nanos) {
        int length = source.readableBytes();
        byte[] array = copies.copy(source, nanos);
        return new Copy(array, copies.offset(), length);
    }

    @Test
    void copiesRemainDistinctAndOwnedUntilTheFrameIsReleased() {
        CapturePacketCopies.Budget budget = new CapturePacketCopies.Budget();
        CapturePacketCopies copies = CapturePacketCopies.of(budget, 0);
        for (boolean direct : new boolean[] {false, true}) {
            ByteBuf source = direct ? PooledByteBufAllocator.DEFAULT.directBuffer(20) : Unpooled.buffer(20);
            try {
                source.writeByte(9).writeInt(42).readerIndex(1);
                source.markReaderIndex();
                Copy first = copy(copies, source, 0);
                source.setInt(1, 71);
                Copy second = copy(copies, source, 0);
                assertArrayEquals(new byte[] {0, 0, 0, 42}, first.bytes());
                assertArrayEquals(new byte[] {0, 0, 0, 71}, second.bytes());
                assertSame(first.array(), second.array());
                assertEquals(first.offset() + 4, second.offset());
                assertEquals(1, source.readerIndex());
                assertEquals(5, source.writerIndex());
                assertEquals(1, source.refCnt());
                source.resetReaderIndex();
                assertEquals(1, source.readerIndex());
                copies.clear(0);
                Copy reused = copy(copies, source, 0);
                assertSame(first.array(), reused.array());
                assertEquals(0, reused.offset());
                copies.clear(0);
            } finally {
                source.release();
            }
        }
        copies.release();
        assertEquals(0, budget.bytes());
    }

    @Test
    void terrainRecordsFillChunksOfTheirOwnInOrderAndLeavePacketsTheirRoom() {
        CapturePacketCopies.Budget budget = new CapturePacketCopies.Budget();
        CapturePacketCopies copies = CapturePacketCopies.of(budget, 0);
        ByteBuf source = Unpooled.buffer(100).writeZero(100);
        try {
            Copy packet = copy(copies, source, 0);
            int length = 3000;
            int perChunk = CapturePacketCopies.TERRAIN_CHUNK_BYTES / length;
            List<byte[]> chunks = new ArrayList<>();
            List<int[]> ranges = new ArrayList<>();
            List<byte[]> arrays = new ArrayList<>();
            for (int i = 0; i < CapturePacketCopies.MAX_TERRAIN_CHUNKS * perChunk + 3; i++) {
                byte[] array = copies.reserve(length, 0);
                int offset = copies.offset();
                assertTrue(offset + length <= array.length);
                Arrays.fill(array, offset, offset + length, (byte) i);
                arrays.add(array);
                ranges.add(new int[] {offset, i});
                if (!chunks.contains(array) && array.length == CapturePacketCopies.TERRAIN_CHUNK_BYTES) chunks.add(array);
            }
            for (int i = 0; i < arrays.size(); i++) {
                byte[] array = arrays.get(i);
                int offset = ranges.get(i)[0];
                for (int at = offset; at < offset + length; at++) assertEquals((byte) i, array[at]);
                assertNotSame(packet.array(), array);
            }
            // Chunks stop where the player's budget would leave packets less than their room.
            assertTrue(chunks.size() <= CapturePacketCopies.MAX_TERRAIN_CHUNKS);
            assertTrue(CapturePacketCopies.PLAYER_BYTES - budget.bytes() >= CapturePacketCopies.PACKET_RESERVE_BYTES);
            assertEquals(CapturePacketCopies.POOL_BYTES + copies.retainedBytes(), budget.bytes());
            byte[] large = copies.reserve(CapturePacketCopies.TERRAIN_CHUNK_BYTES + 1, 0);
            assertEquals(0, copies.offset());
            assertEquals(CapturePacketCopies.TERRAIN_CHUNK_BYTES + 1, large.length);

            copies.clear(0);
            byte[] first = copies.reserve(length, 0);
            assertSame(chunks.get(0), first);
            assertEquals(0, copies.offset());
            copies.abandon(first, 0, length);
            assertSame(first, copies.reserve(length, 0));
            assertEquals(0, copies.offset());
            // Only the last room is given back, and none once the pool was cleared.
            copies.reserve(length, 0);
            copies.abandon(first, 0, length);
            assertSame(first, copies.reserve(10, 0));
            assertEquals(2 * length, copies.offset());
            copies.clear(0);
            copies.reserve(100, 0);
            copies.abandon(first, 0, length);
            assertSame(first, copies.reserve(10, 0));
            assertEquals(100, copies.offset());

            copies.clear(CapturePacketCopies.IDLE_NANOS + SECOND);
            assertEquals(CapturePacketCopies.POOL_BYTES + copies.retainedBytes(), budget.bytes());
            assertEquals(CapturePacketCopies.TERRAIN_BOOK_BYTES, copies.retainedBytes());
        } finally {
            source.release();
            copies.release();
        }
        assertEquals(0, budget.bytes());
    }

    @Test
    void terrainChunksTakeOnlyWhatTheBudgetHasBeyondThePacketsRoom() {
        CapturePacketCopies.Budget budget = new CapturePacketCopies.Budget();
        List<CapturePacketCopies> pools = new ArrayList<>();
        int pooled = 0;
        for (int p = 0; p < 6; p++) {
            CapturePacketCopies pool = CapturePacketCopies.of(budget, 0);
            if (pool == null) break;
            pools.add(pool);
            for (int i = 0; i < 40; i++) {
                byte[] array = pool.reserve(4000, 0);
                if (array.length == CapturePacketCopies.TERRAIN_CHUNK_BYTES) pooled++;
                assertTrue(CapturePacketCopies.PLAYER_BYTES - budget.bytes() >= CapturePacketCopies.PACKET_RESERVE_BYTES
                        || array.length == 4000);
            }
        }
        assertTrue(pooled > 0);
        assertTrue(budget.bytes() <= CapturePacketCopies.PLAYER_BYTES);
        for (CapturePacketCopies pool : pools) pool.release();
        assertEquals(0, budget.bytes());
    }

    @Test
    void largeAndSaturatedFramesRemainCompleteWithoutUnboundedRetention() {
        CapturePacketCopies.Budget budget = new CapturePacketCopies.Budget();
        CapturePacketCopies copies = CapturePacketCopies.of(budget, 0);
        int large = CapturePacketCopies.MAX_PACKET_BYTES + 1;
        ByteBuf source = Unpooled.buffer(large).writeZero(large);
        try {
            Copy one = copy(copies, source, 0);
            Copy two = copy(copies, source, 0);
            assertNotSame(one.array(), two.array());
            assertEquals(large, one.array().length);
            assertEquals(0, one.offset());
            assertEquals(0, copies.retainedBytes());
            var held = new ArrayList<Copy>();
            for (int length : new int[] {256, 1, 0, 37, CapturePacketCopies.MAX_PACKET_BYTES}) {
                source.writerIndex(length);
                for (int i = 0; i < 512; i++) {
                    if (length >= 2) source.setShort(0, held.size());
                    held.add(copy(copies, source, 0));
                }
            }
            assertTrue(copies.retainedBytes() <= CapturePacketCopies.MAX_RETAINED_BYTES);
            assertEquals(CapturePacketCopies.POOL_BYTES + copies.retainedBytes(), budget.bytes());
            for (int i = 0; i < held.size(); i++) {
                Copy copy = held.get(i);
                assertTrue(copy.offset() + copy.length() <= copy.array().length);
                byte[] bytes = copy.bytes();
                if (bytes.length >= 2) assertEquals(i & 0xFFFF, ((bytes[0] & 255) << 8) | (bytes[1] & 255));
            }
        } finally {
            source.release();
            copies.release();
        }
        assertEquals(0, budget.bytes());
    }

    @Test
    void aBurstPastItsChunksCopiesIntoFreshArraysAndTheChunksAreReusedInOrder() {
        CapturePacketCopies copies = CapturePacketCopies.of(new CapturePacketCopies.Budget(), 0);
        ByteBuf source = Unpooled.buffer(100).writeZero(100);
        int perChunk = CapturePacketCopies.CHUNK_BYTES / 100;
        try {
            List<byte[]> chunks = new ArrayList<>();
            List<Copy> burst = new ArrayList<>();
            for (int i = 0; i < CapturePacketCopies.MAX_CHUNKS * perChunk + 5; i++) {
                source.setInt(0, i);
                Copy copy = copy(copies, source, 0);
                burst.add(copy);
                if (copy.array().length == CapturePacketCopies.CHUNK_BYTES && !chunks.contains(copy.array()))
                    chunks.add(copy.array());
            }
            assertEquals(CapturePacketCopies.MAX_CHUNKS, chunks.size());
            Copy last = burst.getLast();
            assertEquals(100, last.array().length);
            assertEquals(0, last.offset());
            for (int i = 0; i < burst.size(); i++) {
                byte[] bytes = burst.get(i).bytes();
                assertEquals(
                        i, (bytes[0] & 255) << 24 | (bytes[1] & 255) << 16 | (bytes[2] & 255) << 8 | bytes[3] & 255);
            }
            copies.clear(0);
            for (byte[] chunk : chunks) {
                for (int i = 0; i < perChunk; i++) assertSame(chunk, copy(copies, source, 0).array());
            }
        } finally {
            source.release();
            copies.release();
        }
    }

    @Test
    void chunksIdleForThirtySecondsGiveTheirCapacityBack() {
        CapturePacketCopies.Budget budget = new CapturePacketCopies.Budget();
        CapturePacketCopies copies = CapturePacketCopies.of(budget, 0);
        int length = CapturePacketCopies.MAX_PACKET_BYTES;
        ByteBuf source = Unpooled.buffer(length).writeZero(length);
        try {
            byte[] kept = copies.copy(source, 0);
            for (int i = 0; i < CapturePacketCopies.CHUNK_BYTES / length; i++) copies.copy(source, 0);
            copies.clear(0);
            long warmed = budget.bytes();
            assertEquals(CapturePacketCopies.POOL_BYTES + 2L * CHUNK_COST, warmed);
            assertSame(kept, copies.copy(source, 20 * SECOND));
            copies.clear(20 * SECOND);
            assertEquals(warmed, budget.bytes());

            copies.clear(40 * SECOND);

            assertEquals(warmed - CHUNK_COST, budget.bytes());
            assertSame(kept, copies.copy(source, 40 * SECOND));
            copies.clear(80 * SECOND);
            assertEquals(CapturePacketCopies.POOL_BYTES, budget.bytes());
            assertEquals(0, copies.retainedBytes());
        } finally {
            source.release();
            copies.release();
        }
        assertEquals(0, budget.bytes());
    }

    @Test
    void aPoolWithoutRoomCopiesIntoFreshArrays() {
        CapturePacketCopies.Budget budget = new CapturePacketCopies.Budget();
        var pools = new ArrayList<CapturePacketCopies>();
        CapturePacketCopies pool;
        while ((pool = CapturePacketCopies.of(budget, 0)) != null) pools.add(pool);
        assertTrue(budget.bytes() <= CapturePacketCopies.PLAYER_BYTES);
        assertNull(CapturePacketCopies.of(budget, 0));
        long rest = CapturePacketCopies.PLAYER_BYTES - budget.bytes();
        assertTrue(budget.take(rest));
        CapturePacketCopies last = pools.getLast();
        ByteBuf source = Unpooled.buffer(4).writeInt(7);
        try {
            Copy first = copy(last, source, 0);
            last.clear(0);
            Copy second = copy(last, source, 0);
            assertArrayEquals(new byte[] {0, 0, 0, 7}, first.array());
            assertArrayEquals(new byte[] {0, 0, 0, 7}, second.array());
            assertEquals(0, first.offset());
            assertNotSame(first.array(), second.array());
            assertEquals(0, last.retainedBytes());
            assertEquals(CapturePacketCopies.PLAYER_BYTES, budget.bytes());
        } finally {
            source.release();
            budget.give(rest);
            pools.forEach(CapturePacketCopies::release);
        }
        assertEquals(0, budget.bytes());
    }

    @Test
    void anEmptyPoolIsChargedAtLeastTheBytesItAllocates() {
        var platformBean = ManagementFactory.getThreadMXBean();
        assumeTrue(platformBean instanceof ThreadMXBean);
        ThreadMXBean bean = (ThreadMXBean) platformBean;
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        CapturePacketCopies.Budget budget = new CapturePacketCopies.Budget();
        long thread = Thread.currentThread().getId();
        for (int i = 0; i < 20_000; i++) (kept = CapturePacketCopies.of(budget, 0)).release();
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 1_000; i++) (kept = CapturePacketCopies.of(budget, 0)).release();
        double perPool = (bean.getThreadAllocatedBytes(thread) - before) / 1_000.0;
        String report = perPool + " B allocated per empty pool, " + CapturePacketCopies.POOL_BYTES + " B charged";
        System.out.println("CAPTURE-POOL-BYTES " + report);
        assertTrue(perPool <= CapturePacketCopies.POOL_BYTES + 1, report);
        assertTrue(CapturePacketCopies.POOL_BYTES - perPool <= 16, report);
        assertEquals(0, budget.bytes());
    }

    @Test
    void aSecondReleaseGivesNothingBack() {
        CapturePacketCopies.Budget budget = new CapturePacketCopies.Budget();
        long process = CapturePacketCopies.processBytes();
        CapturePacketCopies copies = CapturePacketCopies.of(budget, 0);
        ByteBuf source = Unpooled.buffer(4).writeInt(7);
        try {
            copies.copy(source, 0);
        } finally {
            source.release();
        }
        copies.release();
        copies.release();
        assertEquals(0, budget.bytes());
        assertEquals(process, CapturePacketCopies.processBytes());
    }

    @Test
    void anAllocationFailureLeavesBothBudgetsAsTheyWere() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path log = Files.createTempFile("capture-copies-oom", ".log");
        Process child = null;
        try {
            child = new ProcessBuilder(
                            java,
                            "-Xmx24m",
                            "-XX:+UseSerialGC",
                            "-cp",
                            System.getProperty("java.class.path"),
                            AllocationFailure.class.getName())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            boolean exited = child.waitFor(60, TimeUnit.SECONDS);
            if (!exited) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            assertTrue(exited, "the child ran past its deadline: " + output);
            assertEquals(0, child.exitValue(), output);
            assertTrue(output.contains("constructed=false grown=false"), output);
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(log);
        }
    }

    private static CapturePacketCopies kept;

    /** Exhausts its own heap, then fails a pool construction and a bucket growth. */
    static final class AllocationFailure {
        private static Object[] pressure;

        public static void main(String[] args) {
            CapturePacketCopies.Budget budget = new CapturePacketCopies.Budget();
            ByteBuf source = Unpooled.buffer(200).writeZero(200);
            CapturePacketCopies pool = CapturePacketCopies.of(budget, 0);
            pool.copy(source, 0);
            pool.clear(TimeUnit.SECONDS.toNanos(60));
            long player = budget.bytes();
            long process = CapturePacketCopies.processBytes();
            boolean constructed = true;
            boolean grown = true;
            fill();
            try {
                CapturePacketCopies.of(budget, 0);
            } catch (OutOfMemoryError expected) {
                constructed = false;
            }
            fill();
            try {
                pool.copy(source, 0);
            } catch (OutOfMemoryError expected) {
                grown = false;
            }
            pressure = null;
            System.gc();
            System.out.println("constructed=" + constructed + " grown=" + grown);
            boolean restored = budget.bytes() == player
                    && CapturePacketCopies.processBytes() == process
                    && pool.retainedBytes() == 0;
            System.out.println("player " + budget.bytes() + " of " + player + ", process "
                    + CapturePacketCopies.processBytes() + " of " + process + ", retained " + pool.retainedBytes());
            pool.copy(source, 0);
            pool.release();
            restored &= budget.bytes() == 0 && CapturePacketCopies.processBytes() == 0;
            System.exit(restored ? 0 : 1);
        }

        private static void fill() {
            for (int size : new int[] {1 << 20, 1 << 14, 1 << 10, 64, 0}) {
                try {
                    while (true) pressure = new Object[] {pressure, new byte[size]};
                } catch (OutOfMemoryError full) {
                }
            }
        }
    }
}
