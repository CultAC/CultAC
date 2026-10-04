package ac.cult.cultac.network.codec;

import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.protocol.wire.*;
import io.netty.buffer.ByteBuf;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.block.Block;

/** Chunk sections and light retain their physical dimension and packet ordering. */
final class ObservedWorldValues {
    private final ObservedPacketValues values;
    private final Supplier<int[]> dimension;

    ObservedWorldValues(ObservedPacketValues values, Supplier<int[]> dimension) {
        this.values = values;
        this.dimension = dimension;
    }

    Object read(ByteBuf input, String name) {
        return switch (name) {
            case "minecraft:block_update" ->
                new WorldPackets.BlockUpdate(
                        BlockPos.of(input.readLong()),
                        Block.BLOCK_STATE_REGISTRY.byId(values.mappings().blockState(Wire.readVarInt(input))));
            case "minecraft:section_blocks_update" -> updates(input);
            case "minecraft:level_chunk_with_light" -> chunk(input);
            case "minecraft:light_update" -> {
                int x = Wire.readVarInt(input), z = Wire.readVarInt(input);
                yield new WorldPackets.LightUpdate(x, z, light(input, x, z));
            }
            default -> throw new IllegalArgumentException(name);
        };
    }

    private WorldPackets.SectionBlocksUpdate updates(ByteBuf input) {
        var section = SectionPos.of(input.readLong());
        int count = Wire.readLength(input, input.readableBytes());
        var entries = new ArrayList<WorldPackets.BlockUpdate>(count);
        for (int index = 0; index < count; index++) {
            long value = Wire.readVarLong(input);
            entries.add(new WorldPackets.BlockUpdate(
                    section.relativeToBlockPos((short) (value & 4095)),
                    Block.BLOCK_STATE_REGISTRY.byId(values.mappings().blockState((int) (value >>> 12)))));
        }
        return new WorldPackets.SectionBlocksUpdate(entries);
    }

    private static int bits(int count) {
        return 32 - Integer.numberOfLeadingZeros(count - 1);
    }

    private WorldPackets.Chunk chunk(ByteBuf input) {
        int x = input.readInt(), z = input.readInt();
        if (!values.version().atLeast(ProtocolVersion.V1_21_5)) NbtSkipper.skip(input, 512);
        else {
            int count = Wire.readLength(input, input.readableBytes());
            for (int index = 0; index < count; index++) {
                Wire.readVarInt(input);
                input.skipBytes(Math.multiplyExact(Wire.readLength(input, input.readableBytes() / 8), 8));
            }
        }
        int length = Wire.readLength(input, 2097152);
        var sections = input.readSlice(length);
        int[] geometry = dimension.get();
        byte[] projected = values.decoder()
                .sections(
                        values.version(),
                        sections,
                        geometry[1] / 16,
                        bits(ModelRegistryData.load(values.version())
                                .blockStates()
                                .size()),
                        bits(values.names().size("minecraft:worldgen/biome")),
                        bits(values.names()
                                .modelRegistry("minecraft:worldgen/biome")
                                .size()),
                        id -> {
                            String name = values.names().name("minecraft:worldgen/biome", id);
                            int mapped = values.names().modelId("minecraft:worldgen/biome", name);
                            if (mapped < 0) throw new ProtocolResolutionException("Unavailable older biome " + name);
                            return mapped;
                        });
        int count = Wire.readLength(input, input.readableBytes());
        var tickers = new ArrayList<BlockPos>();
        for (int index = 0; index < count; index++) {
            int xz = input.readUnsignedByte(), y = input.readShort();
            String type = values.names().name("minecraft:block_entity_type", Wire.readVarInt(input));
            if (type.equals("minecraft:potent_sulfur"))
                tickers.add(new BlockPos((x << 4) + (xz >> 4), y, (z << 4) + (xz & 15)));
            NbtSkipper.skip(input, 512);
        }
        return new WorldPackets.Chunk(x, z, ByteBuffer.wrap(projected), tickers, light(input, x, z));
    }

    private ClientboundLightUpdatePacketData light(ByteBuf input, int x, int z) {
        if (values.version() == ProtocolVersion.V26_3) return NativeValueCodecs.light(new FriendlyByteBuf(input), x, z);
        // Through 26.2 the four masks are long arrays (FriendlyByteBuf.readBitSet).
        // 26.3's record codec uses byte arrays. The update arrays retain their format.
        var masks = new BitSet[4];
        for (int index = 0; index < masks.length; index++) {
            int count = Wire.readLength(input, input.readableBytes() / Long.BYTES);
            long[] words = new long[count];
            for (int word = 0; word < count; word++) words[word] = input.readLong();
            masks[index] = BitSet.valueOf(words);
        }
        return new ClientboundLightUpdatePacketData(
                masks[0], masks[1], masks[2], masks[3], lightArrays(input), lightArrays(input));
    }

    private static List<byte[]> lightArrays(ByteBuf input) {
        int count = Wire.readLength(input, input.readableBytes());
        var arrays = new ArrayList<byte[]>(count);
        for (int index = 0; index < count; index++) {
            byte[] value = new byte[Wire.readLength(input, 2048)];
            input.readBytes(value);
            arrays.add(value);
        }
        return List.copyOf(arrays);
    }
}
