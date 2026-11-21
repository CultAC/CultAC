package ac.cult.cultac.network.codec;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.protocol.data.RegistryNames;
import io.netty.buffer.ByteBuf;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/** Older world packets through the shared framing; only IDs and section framing differ. */
final class ObservedWorldValues {
    private static final String BIOME = "minecraft:worldgen/biome";
    private final ObservedPacketValues values;
    private final Supplier<int[]> dimension;
    private final Supplier<String> poolDimension;
    private BiomeIds biomeIds;

    ObservedWorldValues(ObservedPacketValues values, Supplier<int[]> dimension, Supplier<String> poolDimension) {
        this.values = values;
        this.dimension = dimension;
        this.poolDimension = poolDimension;
    }

    Object read(ByteBuf input, String name) {
        var version = values.version();
        IntFunction<String> types = id -> values.names().name("minecraft:block_entity_type", id);
        return switch (name) {
            case "minecraft:block_update" -> ModelWorldValues.blockUpdate(input, values.mappings()::blockState);
            case "minecraft:section_blocks_update" ->
                ModelWorldValues.sectionUpdates(input, values.mappings()::blockState);
            case "minecraft:block_entity_data" -> ModelWorldValues.blockEntity(input, types);
            case "minecraft:level_chunk_with_light" -> {
                var dimension = this.dimension.get();
                if (dimension == null) yield null;
                int count = dimension[1] / 16;
                String pools = poolDimension.get();
                yield ModelWorldValues.chunkDecoded(
                        input,
                        version,
                        types,
                        (sections, x, z) ->
                                SectionProjection.decodedSections(sections, version, count, registries(), pools, x, z));
            }
            case "minecraft:chunks_biomes" -> {
                var dimension = this.dimension.get();
                if (dimension == null) yield null;
                int count = dimension[1] / 16;
                var registries = registries();
                yield ModelWorldValues.biomesDecoded(
                        input, sections -> SectionProjection.decodedBiomes(sections, version, count, registries));
            }
            default -> throw new IllegalArgumentException(name);
        };
    }

    private SectionProjection.Registries registries() {
        var names = values.names();
        return new SectionProjection.Registries(
                ModelRegistryData.load(values.version()).blockStates().size(),
                DataTables.defaults().registry().stateCount(),
                names.size(BIOME),
                names.modelSize(BIOME),
                values.mappings()::blockState,
                id -> {
                    String name = names.name(BIOME, id);
                    var table = biomeIds(names);
                    int[] known = table.ids();
                    boolean inRange = id >= 0 && id < known.length;
                    if (inRange && known[id] != 0) return known[id] - 1;
                    int mapped = table.model().id(BIOME, name);
                    if (mapped < 0)
                        throw new ProtocolResolutionException("Unavailable older biome " + names.name(BIOME, id));
                    if (inRange) known[id] = mapped + 1;
                    return mapped;
                });
    }

    /** Resolved model biome IDs plus one by wire ID, 0 where unresolved, for the names' current answers. */
    private BiomeIds biomeIds(WireRegistryState names) {
        var table = biomeIds;
        Object generation = names.generation();
        Object emptied = names.emptied();
        RegistryNames model = names.modelNames();
        if (table == null || table.generation != generation || table.emptied != emptied || table.model != model)
            biomeIds = table = new BiomeIds(generation, emptied, model, new int[names.size(BIOME)]);
        return table;
    }

    private record BiomeIds(Object generation, Object emptied, RegistryNames model, int[] ids) {}
}
