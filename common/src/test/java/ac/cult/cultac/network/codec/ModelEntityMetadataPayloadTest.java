package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.protocol.wire.Wire;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

/** Decoded metadata entries keep their exact wire bytes as ranges of one copy of the payload. */
class ModelEntityMetadataPayloadTest {
    private static final int ENTRIES = 16;
    private static final int WARMUP = 20_000;
    private static final int MEASURED = 20_000;
    private static final double BUDGET = 170;

    private final ModelEntityMetadataValues values = new ModelEntityMetadataValues(
            ProtocolCodecs.decoder(),
            ProtocolVersion.V26_3,
            new WireRegistryState(ProtocolVersion.V26_3, () -> null),
            name -> null);

    @Test
    void entriesKeepTheirValuesAndExactWireBytesAfterTheSourceIsOverwrittenAndReleased() {
        ByteBuf template = packet();
        byte[] original = ByteBufUtil.getBytes(template);
        template.release();
        for (boolean direct : new boolean[] {false, true}) {
            ByteBuf input = direct
                    ? PooledByteBufAllocator.DEFAULT.directBuffer(original.length + 3)
                    : Unpooled.buffer(original.length + 3);
            input.writeBytes(new byte[] {7, 7, 7}).writeBytes(original).readerIndex(3);
            EntityMetadata metadata = values.read(input);
            assertFalse(input.isReadable());
            input.setBytes(0, new byte[input.capacity()]);
            input.release();

            assertEquals(42, metadata.id());
            assertEquals(ENTRIES, metadata.packedItems().size());
            assertEquals((byte) 2, metadata.packedItems().get(0).value());
            assertEquals(1.5F, metadata.packedItems().get(1).value());
            assertEquals(EntityPose.CROUCHING, metadata.packedItems().get(2).value());
            for (int i = 3; i < ENTRIES; i++)
                assertEquals(300 + 7 + i, metadata.packedItems().get(i).value());

            ByteBuffer first = metadata.packedItems().get(1).bytes();
            assertTrue(first.isReadOnly());
            assertEquals(0, first.position());
            assertEquals(6, first.remaining());
            first.get(new byte[first.remaining()]);
            assertEquals(6, metadata.packedItems().get(1).bytes().remaining(), "a read view moves no other view");

            ByteBuf output = Unpooled.buffer();
            try {
                Wire.writeVarInt(output, metadata.id());
                metadata.packedItems().forEach(entry -> output.writeBytes(entry.bytes()));
                output.writeByte(255);
                assertArrayEquals(original, ByteBufUtil.getBytes(output), direct ? "direct" : "heap");
            } finally {
                output.release();
            }
        }
    }

    @Test
    void anEntryCopiesNoBytesOfItsOwn() {
        var platformBean = ManagementFactory.getThreadMXBean();
        assumeTrue(platformBean instanceof ThreadMXBean);
        ThreadMXBean bean = (ThreadMXBean) platformBean;
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);

        ByteBuf input = packet();
        try {
            Runnable decode = () -> values.read(input.readerIndex(0));
            long thread = Thread.currentThread().getId();
            for (int i = 0; i < WARMUP; i++) decode.run();
            long before = bean.getThreadAllocatedBytes(thread);
            for (int i = 0; i < MEASURED; i++) decode.run();
            double perEntry = (bean.getThreadAllocatedBytes(thread) - before) / (double) MEASURED / ENTRIES;
            String report = String.format("%.1f B per entry", perEntry);
            System.out.println("METADATA-ENTRY-ALLOC " + report);
            assertTrue(perEntry <= BUDGET, report);
        } finally {
            input.release();
        }
    }

    /** Flags, health and pose, then thirteen integers: serializers 0, 3, 20 and 1 on 26.3. */
    private static ByteBuf packet() {
        ByteBuf input = Unpooled.buffer();
        Wire.writeVarInt(input, 42);
        input.writeByte(0);
        Wire.writeVarInt(input, 0);
        input.writeByte(2);
        input.writeByte(9);
        Wire.writeVarInt(input, 3);
        input.writeFloat(1.5F);
        input.writeByte(6);
        Wire.writeVarInt(input, 20);
        Wire.writeVarInt(input, EntityPose.CROUCHING.ordinal());
        for (int index = 10; index < 10 + ENTRIES - 3; index++) {
            input.writeByte(index);
            Wire.writeVarInt(input, 1);
            Wire.writeVarInt(input, 300 + index);
        }
        input.writeByte(255);
        return input;
    }
}
