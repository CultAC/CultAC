package ac.cult.cultac.network.codec;

import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import io.netty.buffer.ByteBuf;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/** Older world packets through the shared framing; only IDs and section framing differ. */
final class ObservedWorldValues {
    private static final String BIOME = "minecraft:worldgen/biome";
    private final ObservedPacketValues values;
    private final Supplier<int[]> dimension;

    ObservedWorldValues(ObservedPacketValues values, Supplier<int[]> dimension) {
        this.values = values;
        this.dimension = dimension;
    }

    Object read(ByteBuf input, String name) {
        var version = values.version();
        IntFunction<String> types = id -> values.names().name("minecraft:block_entity_type", id);
        return switch (name) {
            case "minecraft:block_update" -> ModelWorldValues.blockUpdate(input, values.mappings()::blockState);
            case "minecraft:section_blocks_update" ->
                ModelWorldValues.sectionUpdates(input, values.mappings()::blockState);
            case "minecraft:block_entity_data" -> ModelWorldValues.blockEntity(input, types);
            case "minecraft:level_chunk_with_light" ->
                ModelWorldValues.chunk(input, version, types, sections -> SectionProjection.sections(
                        sections, version, sectionCount(), registries()));
            case "minecraft:chunks_biomes" -> {
                int count = sectionCount();
                var registries = registries();
                yield ModelWorldValues.biomes(
                        input, sections -> SectionProjection.biomes(sections, version, count, registries));
            }
            case "minecraft:light_update" -> ModelWorldValues.light(input, version);
            default -> throw new IllegalArgumentException(name);
        };
    }

    private int sectionCount() {
        return dimension.get()[1] / 16;
    }

    private SectionProjection.Registries registries() {
        var names = values.names();
        return new SectionProjection.Registries(
                ModelRegistryData.load(values.version()).blockStates().size(),
                ac.cult.blocksim.data.DataTables.defaults().registry().stateCount(),
                names.size(BIOME),
                names.modelSize(BIOME),
                values.mappings()::blockState,
                id -> {
                    int mapped = names.modelId(BIOME, names.name(BIOME, id));
                    if (mapped < 0) throw new ProtocolResolutionException("Unavailable older biome " + names.name(BIOME, id));
                    return mapped;
                });
    }
}
