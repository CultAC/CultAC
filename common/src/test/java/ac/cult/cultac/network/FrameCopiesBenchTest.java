package ac.cult.cultac.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.management.ManagementFactory;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Thread CPU of copying sliced pooled direct frames, interleaving the view-first copy with {@link FrameCopies};
 * CULT_WIRE_BENCH=1 enables it.
 */
@EnabledIfEnvironmentVariable(named = "CULT_WIRE_BENCH", matches = "1")
class FrameCopiesBenchTest {
    private static final int REPS = 9;
    private static final int FRAMES = 4096;
    private static final int PASSES = 10_000;
    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
    static long sink;

    interface Copy {
        void copy(ByteBuf frame, byte[] bytes);
    }

    @Test
    void copyInto() throws Exception {
        var allocator = new PooledByteBufAllocator(true);
        var random = new SplittableRandom(0xF4A3EL);
        List<ByteBuf> parents = new ArrayList<>();
        ByteBuf[] frames = new ByteBuf[FRAMES];
        byte[][] targets = new byte[FRAMES][];
        ByteBuf parent = null;
        for (int i = 0; i < FRAMES; i++) {
            int length = length(random);
            if (parent == null || parent.writableBytes() < length) {
                parent = allocator.directBuffer(Math.max(length, 16 * 1024));
                parents.add(parent);
            }
            int at = parent.writerIndex();
            for (int b = 0; b < length; b++) parent.writeByte(random.nextInt(256));
            frames[i] = parent.retainedSlice(at, length);
            targets[i] = new byte[length];
        }
        Copy base = FrameCopiesBenchTest::viewFirst;
        Copy branch = FrameCopies::copyInto;
        for (int i = 0; i < FRAMES; i++) {
            byte[] expected = ByteBufUtil.getBytes(frames[i]);
            base.copy(frames[i], targets[i]);
            if (!Arrays.equals(expected, targets[i])) throw new AssertionError("base copy differs");
            branch.copy(frames[i], targets[i]);
            if (!Arrays.equals(expected, targets[i])) throw new AssertionError("branch copy differs");
        }
        for (int warm = 0; warm < 3; warm++) {
            run(base, frames, targets);
            run(branch, frames, targets);
        }
        var bean = ManagementFactory.getThreadMXBean();
        long[] baseNs = new long[REPS], branchNs = new long[REPS];
        for (int rep = 0; rep < REPS; rep++) {
            Copy first = rep % 2 == 0 ? base : branch;
            Copy second = first == base ? branch : base;
            long start = bean.getCurrentThreadCpuTime();
            run(first, frames, targets);
            long middle = bean.getCurrentThreadCpuTime();
            run(second, frames, targets);
            long end = bean.getCurrentThreadCpuTime();
            long firstNs = (middle - start) * 1000 / ((long) FRAMES * PASSES);
            long secondNs = (end - middle) * 1000 / ((long) FRAMES * PASSES);
            baseNs[rep] = first == base ? firstNs : secondNs;
            branchNs[rep] = first == base ? secondNs : firstNs;
        }
        report("frame-copy base", baseNs);
        report("frame-copy branch", branchNs);
        for (ByteBuf frame : frames) frame.release();
        for (ByteBuf owner : parents) owner.release();
    }

    /** Clientbound frame sizes weighted toward delimiters, proof pings and entity updates. */
    private static int length(SplittableRandom random) {
        int roll = random.nextInt(1000);
        if (roll < 300) return 1;
        if (roll < 590) return 5;
        if (roll < 890) return 8 + random.nextInt(40);
        if (roll < 985) return 48 + random.nextInt(250);
        if (roll < 995) return 300 + random.nextInt(724);
        return 1025 + random.nextInt(8000);
    }

    private static void run(Copy copy, ByteBuf[] frames, byte[][] targets) {
        for (int pass = 0; pass < PASSES; pass++) {
            for (int i = 0; i < frames.length; i++) copy.copy(frames[i], targets[i]);
        }
        sink += targets[PASSES % targets.length].length;
    }

    private static void viewFirst(ByteBuf frame, byte[] bytes) {
        if (bytes.length != frame.readableBytes()) throw new IllegalArgumentException("Frame length changed");
        if (!frame.hasArray() && frame.nioBufferCount() == 1) {
            int from = frame.readerIndex();
            if (bytes.length > 1024 || frame.order() != ByteOrder.BIG_ENDIAN) {
                frame.internalNioBuffer(from, bytes.length).get(bytes);
                return;
            }
            int at = 0;
            for (; at + Long.BYTES <= bytes.length; at += Long.BYTES) LONGS.set(bytes, at, frame.getLong(from + at));
            for (; at < bytes.length; at++) bytes[at] = frame.getByte(from + at);
        } else frame.getBytes(frame.readerIndex(), bytes);
    }

    private static void report(String name, long[] perOp) throws Exception {
        long[] sorted = perOp.clone();
        Arrays.sort(sorted);
        String line = name + " ps/frame median=" + sorted[REPS / 2] + " min=" + sorted[0] + " max=" + sorted[REPS - 1]
                + " all=" + Arrays.toString(perOp) + System.lineSeparator();
        System.out.print(line);
        String out = System.getenv("CULT_BENCH_OUT");
        if (out != null)
            Files.writeString(
                    Path.of(out), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
