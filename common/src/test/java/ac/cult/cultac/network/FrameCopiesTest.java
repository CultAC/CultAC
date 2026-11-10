package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class FrameCopiesTest {
    private static final ByteBufAllocator POOLED = PooledByteBufAllocator.DEFAULT;
    private static final VarHandle BIG_ENDIAN_LONGS =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    static IntStream lengths() {
        return IntStream.concat(IntStream.rangeClosed(0, 40), IntStream.of(300, 1023, 1024, 1025, 4000));
    }

    @ParameterizedTest
    @MethodSource("lengths")
    void copiesMatchNettysOwnCopyForEveryFrameShape(int length) {
        byte[] bytes = new byte[length];
        new Random(length).nextBytes(bytes);
        for (ByteBuf frame : frames(bytes)) {
            List<ByteBuf> views =
                    List.of(frame, frame.order(ByteOrder.BIG_ENDIAN), frame.order(ByteOrder.LITTLE_ENDIAN));
            for (ByteBuf view : views) {
                String shape = view + " " + view.order();
                int reader = view.readerIndex();
                int writer = view.writerIndex();
                assertArrayEquals(bytes, ByteBufUtil.getBytes(view), shape);
                byte[] reference = referenceBytes(view);
                byte[] referenceInto = new byte[length];
                referenceCopyInto(view, referenceInto);
                assertEquals(reader, view.readerIndex(), shape);
                assertEquals(writer, view.writerIndex(), shape);
                assertArrayEquals(bytes, reference, shape);
                assertArrayEquals(reference, FrameCopies.bytes(view), shape);
                byte[] into = new byte[length];
                FrameCopies.copyInto(view, into);
                assertArrayEquals(referenceInto, into, shape);
                assertEquals(reader, view.readerIndex(), shape);
                assertEquals(writer, view.writerIndex(), shape);
            }
            frame.release();
        }
    }

    @SuppressWarnings("deprecation")
    private static List<ByteBuf> frames(byte[] bytes) {
        int length = bytes.length;
        List<ByteBuf> frames = new ArrayList<>();
        frames.add(Unpooled.wrappedBuffer(bytes.clone()));
        frames.add(POOLED.heapBuffer(length).writeBytes(bytes));
        frames.add(POOLED.directBuffer(length).writeBytes(bytes));
        frames.add(Unpooled.directBuffer(length).writeBytes(bytes));
        frames.add(POOLED.directBuffer(length).writeBytes(bytes).order(ByteOrder.LITTLE_ENDIAN));
        frames.add(padded(POOLED.directBuffer(length + 20), bytes, 10, 10));
        frames.add(padded(POOLED.heapBuffer(length + 13), bytes, 9, 4));
        frames.add(padded(Unpooled.directBuffer(length + 8), bytes, 3, 5));
        // A heap frame whose bytes start inside its backing array.
        byte[] backing = new byte[length + 11];
        System.arraycopy(bytes, 0, backing, 5, length);
        frames.add(Unpooled.wrappedBuffer(backing, 5, length));
        ByteBuf parent = padded(POOLED.directBuffer(length + 20), bytes, 7, 13);
        frames.add(parent.retainedSlice(7, length));
        parent.release();
        ByteBuf heapParent = padded(POOLED.heapBuffer(length + 9), bytes, 3, 6);
        frames.add(heapParent.retainedSlice(3, length));
        heapParent.release();
        ByteBuf duplicated = padded(POOLED.directBuffer(length + 6), bytes, 6, 0);
        frames.add(duplicated.retainedDuplicate());
        duplicated.release();
        ByteBuf heapDuplicated = padded(POOLED.heapBuffer(length + 2), bytes, 1, 1);
        frames.add(heapDuplicated.retainedDuplicate());
        heapDuplicated.release();
        frames.add(POOLED.directBuffer(length).writeBytes(bytes).asReadOnly());
        frames.add(POOLED.heapBuffer(length).writeBytes(bytes).asReadOnly());
        frames.add(padded(POOLED.directBuffer(length + 7), bytes, 4, 3).asReadOnly());
        frames.add(POOLED.directBuffer(length)
                .writeBytes(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asReadOnly());
        frames.add(Unpooled.wrappedBuffer(bytes.clone()).order(ByteOrder.LITTLE_ENDIAN));
        // A wrapper of a swapped buffer reports little-endian order without being a swapped buffer itself.
        frames.add(Unpooled.unreleasableBuffer(
                Unpooled.directBuffer(length).writeBytes(bytes).order(ByteOrder.LITTLE_ENDIAN)));
        CompositeByteBuf single = Unpooled.compositeBuffer();
        single.addComponent(true, POOLED.directBuffer(length).writeBytes(bytes));
        frames.add(single);
        CompositeByteBuf heapSingle = Unpooled.compositeBuffer();
        heapSingle.addComponent(true, POOLED.heapBuffer(length).writeBytes(bytes));
        frames.add(heapSingle);
        frames.add(composite(bytes, length / 2));
        frames.add(composite(bytes, Math.min(3, length)).order(ByteOrder.LITTLE_ENDIAN));
        frames.add(composite(bytes, Math.min(5, length), Math.min(13, length)));
        byte[] led = new byte[length + 1];
        led[0] = 0x55;
        System.arraycopy(bytes, 0, led, 1, length);
        CompositeByteBuf ledComposite = composite(led, Math.min(2, length + 1), Math.min(11, length + 1));
        frames.add(ledComposite.retainedSlice(1, length));
        ledComposite.release();
        return frames;
    }

    /** {@code bytes} between {@code before} and {@code after} zero bytes, its readable bytes exactly {@code bytes}. */
    private static ByteBuf padded(ByteBuf buffer, byte[] bytes, int before, int after) {
        buffer.writeZero(before).writeBytes(bytes).writeZero(after);
        return buffer.readerIndex(before).writerIndex(before + bytes.length);
    }

    /** {@code bytes} as direct components split at each of {@code splits}, ascending. */
    private static CompositeByteBuf composite(byte[] bytes, int... splits) {
        CompositeByteBuf composite = Unpooled.compositeBuffer();
        int from = 0;
        for (int split : splits) {
            composite.addComponent(true, POOLED.directBuffer(split - from).writeBytes(bytes, from, split - from));
            from = split;
        }
        int rest = bytes.length - from;
        composite.addComponent(true, POOLED.directBuffer(rest).writeBytes(bytes, from, rest));
        return composite;
    }

    /** Reference copy that reads small big-endian direct frames big-endian and leaves the rest to their NIO view. */
    private static byte[] referenceBytes(ByteBuf frame) {
        if (frame.hasArray()) return ByteBufUtil.getBytes(frame);
        int length = frame.readableBytes();
        boolean scalar = referenceScalar(frame, length);
        if (!scalar && frame.nioBufferCount() != 1) return ByteBufUtil.getBytes(frame);
        byte[] bytes = new byte[length];
        referenceCopyDirect(frame, bytes, scalar);
        return bytes;
    }

    private static void referenceCopyInto(ByteBuf frame, byte[] bytes) {
        if (frame.hasArray()) {
            frame.getBytes(frame.readerIndex(), bytes);
            return;
        }
        boolean scalar = referenceScalar(frame, bytes.length);
        if (scalar || frame.nioBufferCount() == 1) referenceCopyDirect(frame, bytes, scalar);
        else frame.getBytes(frame.readerIndex(), bytes);
    }

    private static boolean referenceScalar(ByteBuf frame, int length) {
        return length <= 1024 && frame.order() == ByteOrder.BIG_ENDIAN;
    }

    private static void referenceCopyDirect(ByteBuf frame, byte[] bytes, boolean scalar) {
        int from = frame.readerIndex();
        if (!scalar) {
            frame.internalNioBuffer(from, bytes.length).get(bytes);
            return;
        }
        int at = 0;
        for (; at + Long.BYTES <= bytes.length; at += Long.BYTES)
            BIG_ENDIAN_LONGS.set(bytes, at, frame.getLong(from + at));
        for (; at < bytes.length; at++) bytes[at] = frame.getByte(from + at);
    }
}
