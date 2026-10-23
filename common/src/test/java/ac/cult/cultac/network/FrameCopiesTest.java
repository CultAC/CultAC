package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FrameCopiesTest {
    private static final ByteBufAllocator POOLED = PooledByteBufAllocator.DEFAULT;

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 7, 8, 300, 1023, 1024, 1025, 4000})
    void copiesMatchNettysOwnCopyForEveryFrameShape(int length) {
        byte[] bytes = new byte[length];
        new Random(length).nextBytes(bytes);
        for (ByteBuf frame : frames(bytes)) {
            int reader = frame.readerIndex();
            int writer = frame.writerIndex();
            assertArrayEquals(bytes, ByteBufUtil.getBytes(frame), frame.toString());
            assertArrayEquals(bytes, FrameCopies.bytes(frame), frame.toString());
            byte[] into = new byte[length];
            FrameCopies.copyInto(frame, into);
            assertArrayEquals(bytes, into, frame.toString());
            assertEquals(reader, frame.readerIndex());
            assertEquals(writer, frame.writerIndex());
            frame.release();
        }
    }

    @SuppressWarnings("deprecation")
    private static List<ByteBuf> frames(byte[] bytes) {
        List<ByteBuf> frames = new ArrayList<>();
        frames.add(Unpooled.wrappedBuffer(bytes.clone()));
        frames.add(POOLED.heapBuffer(bytes.length).writeBytes(bytes));
        frames.add(POOLED.directBuffer(bytes.length).writeBytes(bytes));
        frames.add(Unpooled.directBuffer(bytes.length).writeBytes(bytes));
        frames.add(POOLED.directBuffer(bytes.length).writeBytes(bytes).order(ByteOrder.LITTLE_ENDIAN));
        ByteBuf offset = POOLED.directBuffer(bytes.length + 20)
                .writeZero(10)
                .writeBytes(bytes)
                .writeZero(10);
        offset.readerIndex(10).writerIndex(10 + bytes.length);
        frames.add(offset);
        ByteBuf parent = POOLED.directBuffer(bytes.length + 20)
                .writeZero(7)
                .writeBytes(bytes)
                .writeZero(13);
        frames.add(parent.retainedSlice(7, bytes.length));
        parent.release();
        frames.add(POOLED.directBuffer(bytes.length).writeBytes(bytes).asReadOnly());
        CompositeByteBuf single = Unpooled.compositeBuffer();
        single.addComponent(true, POOLED.directBuffer(bytes.length).writeBytes(bytes));
        frames.add(single);
        CompositeByteBuf split = Unpooled.compositeBuffer();
        int half = bytes.length / 2;
        split.addComponent(true, POOLED.directBuffer(half).writeBytes(bytes, 0, half));
        split.addComponent(true, POOLED.directBuffer(bytes.length - half).writeBytes(bytes, half, bytes.length - half));
        frames.add(split);
        return frames;
    }
}
