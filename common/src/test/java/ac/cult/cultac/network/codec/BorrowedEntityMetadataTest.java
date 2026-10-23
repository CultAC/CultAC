package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.FrameLease;
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
import java.util.ArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Entries decoded from a lent frame borrow its bytes until the lease ends, and copy them only when detached. */
class BorrowedEntityMetadataTest {
    private static final int WARMUP = 20_000;
    private static final int MEASURED = 20_000;
    /** One lease per decoded frame: a header and three fields. */
    private static final int LEASE_BYTES = 24;

    private final ModelEntityMetadataValues values = new ModelEntityMetadataValues(
            ProtocolCodecs.decoder(),
            ProtocolVersion.V26_3,
            new WireRegistryState(ProtocolVersion.V26_3, () -> null),
            name -> null);

    @Test
    void borrowedEntriesReEncodeIdenticallyWithoutCopyingThePayload() {
        var platformBean = ManagementFactory.getThreadMXBean();
        assumeTrue(platformBean instanceof ThreadMXBean);
        ThreadMXBean bean = (ThreadMXBean) platformBean;
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);

        for (int entries : new int[] {2, 16}) {
            byte[] original = packet(entries);
            ByteBuf frame = direct(original);
            ByteBuf output = Unpooled.buffer(original.length);
            var lender = new FrameLease.Lender();
            try {
                Function<EntityMetadata, byte[]> encode = metadata -> {
                    output.clear();
                    encodeBorrowed(output, metadata);
                    return ByteBufUtil.getBytes(output);
                };
                FrameLease lease = lender.open(frame);
                EntityMetadata borrowed = values.read(frame.readerIndex(0), lender.lease(frame));
                lender.close(lease);
                assertArrayEquals(original, encode.apply(borrowed), entries + " entries");
                lender.end(lease);

                Runnable copied = () -> {
                    EntityMetadata metadata = values.read(frame.readerIndex(0));
                    output.clear();
                    Wire.writeVarInt(output, metadata.id());
                    for (var entry : metadata.packedItems()) output.writeBytes(entry.bytes());
                    output.writeByte(255);
                };
                Runnable lent = () -> {
                    FrameLease each = lender.open(frame);
                    EntityMetadata metadata = values.read(frame.readerIndex(0), lender.lease(frame));
                    lender.close(each);
                    output.clear();
                    encodeBorrowed(output, metadata);
                    lender.end(each);
                };
                double before = bytesPerRun(bean, copied);
                double after = bytesPerRun(bean, lent);
                String report = String.format(
                        "%d entries, %d B payload: %.1f -> %.1f B per decode and re-encode",
                        entries, original.length, before, after);
                System.out.println("METADATA-BORROW-ALLOC " + report);
                int payloadCopy = (16 + original.length - 3 + 7) & ~7;
                assertTrue(before - after >= payloadCopy - LEASE_BYTES, report);
            } finally {
                output.release();
                frame.release();
            }
        }
    }

    @Test
    void borrowedBytesAreUnreadableOnceTheLeaseEnds() {
        byte[] original = packet(16);
        ByteBuf frame = direct(original);
        var lender = new FrameLease.Lender();
        FrameLease lease = lender.open(frame);
        EntityMetadata metadata = values.read(frame, lender.lease(frame));
        lender.close(lease);
        var entry = metadata.packedItems().get(1);
        var twin = metadata.packedItems().get(1);
        assertEquals(entry.hashCode(), twin.hashCode());
        lender.end(lease);
        frame.release();

        assertEquals(1.5F, entry.value());
        assertEquals(EntityPose.CROUCHING, metadata.packedItems().get(2).value());
        assertThrows(IllegalStateException.class, entry::bytes);
        assertThrows(IllegalStateException.class, entry::detach);
        assertThrows(IllegalStateException.class, entry::hashCode);
        assertThrows(IllegalStateException.class, () -> entry.equals(twin));
        ByteBuf output = Unpooled.buffer();
        try {
            assertThrows(IllegalStateException.class, () -> entry.writeTo(output));
            assertEquals(0, output.writerIndex());
        } finally {
            output.release();
        }
        assertDoesNotThrow(entry::toString);
    }

    @Test
    void detachedEntriesReEncodeIdenticallyAfterTheFrameIsReleased() {
        byte[] original = packet(16);
        ByteBuf frame = direct(original);
        var lender = new FrameLease.Lender();
        FrameLease lease = lender.open(frame);
        EntityMetadata metadata = values.read(frame, lender.lease(frame));
        lender.close(lease);
        var detached = new ArrayList<EntityMetadata.Entry>();
        for (var entry : metadata.packedItems()) detached.add(entry.detach());
        var kept = new EntityMetadata(metadata.id(), detached);
        assertEquals(metadata.packedItems(), detached);
        lender.end(lease);
        frame.setBytes(0, new byte[frame.capacity()]);
        frame.release();

        assertSame(detached.get(0), detached.get(0).detach());
        ByteBuf output = Unpooled.buffer();
        try {
            encodeBorrowed(output, kept);
            assertArrayEquals(original, ByteBufUtil.getBytes(output));
            output.clear();
            Wire.writeVarInt(output, kept.id());
            for (var entry : kept.packedItems()) output.writeBytes(entry.bytes());
            output.writeByte(255);
            assertArrayEquals(original, ByteBufUtil.getBytes(output));
        } finally {
            output.release();
        }
    }

    @Test
    void onlyTheOpenFrameIsLentAndNestedReadsRestoreIt() {
        ByteBuf outer = direct(packet(2));
        ByteBuf inner = direct(packet(2));
        var lender = new FrameLease.Lender();
        try {
            assertNull(lender.lease(outer));
            FrameLease outerLease = lender.open(outer);
            assertNull(lender.lease(inner));
            FrameLease innerLease = lender.open(inner);
            assertNull(lender.lease(outer));
            lender.close(innerLease);
            lender.end(innerLease);
            assertSame(outerLease, lender.lease(outer));
            EntityMetadata metadata = values.read(outer, lender.lease(outer));
            lender.close(outerLease);
            assertNull(lender.lease(outer));

            FrameLease next = lender.open(inner);
            assertSame(innerLease, next, "an untaken lease is reused");
            lender.close(next);
            lender.end(next);
            assertNotNull(metadata.packedItems().get(0).bytes(), "a reused lease leaves taken ones lent");
            lender.end(outerLease);
            assertThrows(
                    IllegalStateException.class,
                    () -> metadata.packedItems().get(0).bytes());
        } finally {
            outer.release();
            inner.release();
        }
    }

    private static void encodeBorrowed(ByteBuf output, EntityMetadata metadata) {
        Wire.writeVarInt(output, metadata.id());
        for (var entry : metadata.packedItems()) entry.writeTo(output);
        output.writeByte(255);
    }

    private static double bytesPerRun(ThreadMXBean bean, Runnable run) {
        long thread = Thread.currentThread().getId();
        for (int i = 0; i < WARMUP; i++) run.run();
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < MEASURED; i++) run.run();
        return (bean.getThreadAllocatedBytes(thread) - before) / (double) MEASURED;
    }

    private static ByteBuf direct(byte[] bytes) {
        return PooledByteBufAllocator.DEFAULT.directBuffer(bytes.length).writeBytes(bytes);
    }

    /** Flags and health, then pose and integers up to {@code entries}: serializers 0, 3, 20 and 1 on 26.3. */
    private static byte[] packet(int entries) {
        ByteBuf input = Unpooled.buffer();
        Wire.writeVarInt(input, 42);
        input.writeByte(0);
        Wire.writeVarInt(input, 0);
        input.writeByte(2);
        input.writeByte(9);
        Wire.writeVarInt(input, 3);
        input.writeFloat(1.5F);
        if (entries > 2) {
            input.writeByte(6);
            Wire.writeVarInt(input, 20);
            Wire.writeVarInt(input, EntityPose.CROUCHING.ordinal());
        }
        for (int index = 10; index < 10 + entries - 3; index++) {
            input.writeByte(index);
            Wire.writeVarInt(input, 1);
            Wire.writeVarInt(input, 300 + index);
        }
        input.writeByte(255);
        byte[] bytes = ByteBufUtil.getBytes(input);
        input.release();
        return bytes;
    }
}
