package ac.cult.cultac.network.codec;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.latency.PalettedSection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.function.IntUnaryOperator;

/**
 * Older LevelChunkSection framing rewritten as pinned 26.3 sections. 1.21.2-1.21.4 prefix each
 * packed array with its length, and 26.1 added the fluid count after the non-empty block count.
 */
final class SectionProjection {
    private SectionProjection() {}

    /** Registry sizes and model IDs for one connection's received registries. */
    record Registries(
            int sourceStates, int modelStates, int sourceBiomes, int modelBiomes,
            IntUnaryOperator states, IntUnaryOperator biomes) {}

    static byte[] sections(ByteBuf input, ProtocolVersion version, int count, Registries registries) {
        ByteBuf output = Unpooled.buffer();
        try {
            for (int section = 0; section < count; section++) {
                short nonEmpty = input.readShort();
                short fluids = version.atLeast(ProtocolVersion.V26_1) ? input.readShort() : 0;
                var states = PalettedSection.readBlocks(input, version, registries.sourceStates())
                        .remap(registries.states(), registries.modelStates());
                var biomes = PalettedSection.readBiomes(input, version, registries.sourceBiomes())
                        .remap(registries.biomes(), registries.modelBiomes());
                output.writeShort(nonEmpty);
                output.writeShort(fluids);
                states.write(output);
                biomes.write(output);
            }
            return ByteBufUtil.getBytes(output);
        } finally {
            output.release();
        }
    }

    static byte[] biomes(ByteBuf input, ProtocolVersion version, int count, Registries registries) {
        ByteBuf output = Unpooled.buffer();
        try {
            for (int section = 0; section < count; section++) {
                PalettedSection.readBiomes(input, version, registries.sourceBiomes())
                        .remap(registries.biomes(), registries.modelBiomes())
                        .write(output);
            }
            return ByteBufUtil.getBytes(output);
        } finally {
            output.release();
        }
    }
}
