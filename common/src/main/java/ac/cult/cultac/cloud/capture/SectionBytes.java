package ac.cult.cultac.cloud.capture;

import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

/**
 * A simulation-world section's block states as a {@code SectionRecord}'s {@code blocks}: the vanilla network write of
 * its paletted container (bits per entry, the palette, the data array), in the model block-state registry. Each thread
 * writes through one heap buffer of its own, kept between sections.
 */
public final class SectionBytes {
    private static final ThreadLocal<ByteBuf> BUFFER = ThreadLocal.withInitial(() -> Unpooled.buffer(256));
    /** A buffer that grew past this is let go once written, so one huge section is not kept per thread. */
    private static final int KEPT_BYTES = 64 * 1024;

    private static volatile byte[] air;

    private SectionBytes() {}

    /** A section's block states; a section the column lacks is uniform air. */
    public static byte[] of(CachedSection section) {
        if (section == null) return air();
        ByteBuf buffer = BUFFER.get();
        buffer.clear();
        try {
            section.writeStates(buffer);
            return ByteBufUtil.getBytes(buffer);
        } finally {
            if (buffer.capacity() > KEPT_BYTES) {
                buffer.release();
                BUFFER.set(Unpooled.buffer(256));
            }
        }
    }

    private static byte[] air() {
        byte[] bytes = air;
        if (bytes == null) {
            bytes = of(CachedSection.createAirSection());
            air = bytes;
        }
        return bytes;
    }
}
