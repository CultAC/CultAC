package ac.cult.cultac.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Private copies of a frame's readable bytes. A pooled direct frame is read through its internal NIO view: without
 * Unsafe, every bulk read through Netty's public accessors allocates a fresh {@link ByteBuffer} duplicate,
 * and an adaptive frame allocates its internal view on first use. A small big-endian frame is read eight bytes at a
 * time through absolute primitive reads, which need no view. The internal view is borrowed for the duration of one
 * copy on the thread that owns the frame.
 */
public final class FrameCopies {
    /** Frames up to this many bytes are copied without an NIO view. */
    private static final int SCALAR_COPY_BYTES = 1024;

    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    private FrameCopies() {}

    /** The frame's readable bytes; its indices are unchanged. */
    public static byte[] bytes(ByteBuf frame) {
        if (frame.hasArray()) return ByteBufUtil.getBytes(frame);
        int length = frame.readableBytes();
        boolean scalar = scalar(frame, length);
        if (!scalar && frame.nioBufferCount() != 1) return ByteBufUtil.getBytes(frame);
        byte[] bytes = new byte[length];
        copyDirect(frame, bytes, scalar);
        return bytes;
    }

    /** Copies the frame's readable bytes into an array of exactly their length; its indices are unchanged. */
    public static void copyInto(ByteBuf frame, byte[] bytes) {
        if (bytes.length != frame.readableBytes()) throw new IllegalArgumentException("Frame length changed");
        if (frame.hasArray()) {
            frame.getBytes(frame.readerIndex(), bytes);
            return;
        }
        boolean scalar = scalar(frame, bytes.length);
        if (scalar || frame.nioBufferCount() == 1) copyDirect(frame, bytes, scalar);
        else frame.getBytes(frame.readerIndex(), bytes);
    }

    private static boolean scalar(ByteBuf frame, int length) {
        return length <= SCALAR_COPY_BYTES && frame.order() == ByteOrder.BIG_ENDIAN;
    }

    private static void copyDirect(ByteBuf frame, byte[] bytes, boolean scalar) {
        int from = frame.readerIndex();
        if (!scalar) {
            frame.internalNioBuffer(from, bytes.length).get(bytes);
            return;
        }
        int at = 0;
        for (; at + Long.BYTES <= bytes.length; at += Long.BYTES) LONGS.set(bytes, at, frame.getLong(from + at));
        for (; at < bytes.length; at++) bytes[at] = frame.getByte(from + at);
    }
}
