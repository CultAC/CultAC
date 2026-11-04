package ac.cult.cultac.bedrock.bridge;

import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.entity.PaintingSize;
import ac.cult.blocksim.environment.ClientWorldDefaults;
import ac.cult.blocksim.environment.DimensionData;
import ac.cult.cultac.utils.latency.ClientWorldRegistries;
import ac.cult.cultac.utils.latency.ClientWorldView;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.function.Supplier;

/**
 * What a Bedrock client sees of the Java world registries Geyser (2.10 to 2.12) receives for it. Geyser sends every
 * Java dimension type as one of Bedrock's three dimensions and keeps only the chunk sections within that dimension's
 * heights (JavaLevelChunkWithLightTranslator); it maps Java biomes and painting variants to Bedrock's own.
 * One per Bedrock session: as in GeyserSession, the overworld's heights are fixed at the first login.
 */
public final class GeyserWorldView implements ClientWorldView {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String END = "minecraft:the_end";
    private static final String OCEAN = "minecraft:ocean";
    private static final String NETHER_WASTES = "minecraft:nether_wastes";

    /** BedrockDimension.OVERWORLD, THE_NETHER and THE_END. */
    private static final Heights BEDROCK_OVERWORLD = new Heights(-64, 320);

    private static final Heights BEDROCK_NETHER = new Heights(0, 128);
    private static final Heights BEDROCK_END = new Heights(0, 256);
    /** GeyserSession#connect's bounds for the overworld it extends with DimensionDataPacket. */
    private static final int MIN_OVERWORLD_Y = -512, MAX_OVERWORLD_Y = 512;

    private final Supplier<GeyserWorldRules> source;
    private GeyserWorldRules rules;
    private Heights overworld;

    public GeyserWorldView() {
        this(GeyserWorldRules::current);
    }

    public GeyserWorldView(Supplier<GeyserWorldRules> rules) {
        this.source = rules;
    }

    private GeyserWorldRules rules() {
        if (rules == null) rules = source.get();
        return rules;
    }

    /**
     * BiomeTranslator: a biome Geyser cannot map shows as ocean (Bedrock ID 0); from 2.12, as nether wastes in a
     * nether-like level and as the End's biome in an end-like one.
     */
    @Override
    public String biome(String key, DimensionData dimension) {
        var rules = rules();
        boolean mapped = rules.biomes() == null
                ? modelBiomes().containsKey(key)
                : rules.biomes().contains(key);
        if (mapped) return key;
        if (!rules.biomeFallbackByDimension() || dimension == null) return OCEAN;
        return switch (resembles(dimension)) {
            case NETHER -> NETHER_WASTES;
            case END -> END;
            default -> OCEAN;
        };
    }

    /** Geyser sends Bedrock's own biomes, so a vanilla biome keeps the model's definition. */
    @Override
    public ClientWorldDefaults.Entry biomeDefinition(String key, ClientWorldDefaults.Entry received) {
        var model = modelBiomes().get(key);
        return model != null ? model : received;
    }

    /** PaintingType#getByName: a variant without a Bedrock motive shows as Kebab. */
    @Override
    public PaintingSize painting(String key, PaintingSize received) {
        var rules = rules();
        if (rules.paintings() != null) return rules.paintings().getOrDefault(key, rules.fallbackPainting());
        var model = ClientWorldDefaults.defaults()
                .entries(ClientWorldRegistries.PAINTINGS)
                .get(key);
        if (model == null) return rules.fallbackPainting();
        var fields = model.data().values();
        return new PaintingSize(
                ((NbtValue.Numeric) fields.get("width")).value().intValue(),
                ((NbtValue.Numeric) fields.get("height")).value().intValue());
    }

    /** DimensionUtils#setBedrockDimension: the heights of the Bedrock dimension a level of this type is sent as. */
    @Override
    public Heights heights(DimensionData dimension, List<ClientWorldRegistries.Definition> dimensions) {
        return switch (bedrockDimension(dimension)) {
            case NETHER -> BEDROCK_NETHER;
            case END -> BEDROCK_END;
            default -> overworld(dimensions);
        };
    }

    /**
     * GeyserSession#connect, at the first Java login: the overworld spans every overworld-like dimension type then
     * received, within ±512. Later configurations do not change it.
     */
    private Heights overworld(List<ClientWorldRegistries.Definition> dimensions) {
        if (overworld != null) return overworld;
        int min = BEDROCK_OVERWORLD.min(), max = BEDROCK_OVERWORLD.max();
        for (var definition : dimensions) {
            if (definition.value() == null) continue;
            var type = new DimensionData(
                    definition.key(),
                    definition.value().data(),
                    definition.value().json());
            if (!bedrockDimension(type).equals(OVERWORLD)) continue;
            min = Math.min(min, type.minY());
            max = Math.max(max, type.minY() + type.height());
        }
        overworld = new Heights(Math.max(min, MIN_OVERWORLD_Y), Math.min(max, MAX_OVERWORLD_Y));
        return overworld;
    }

    /** JavaDimension#read and DimensionUtils#javaToBedrock; the nether option sends nether-like levels as the End. */
    private String bedrockDimension(DimensionData dimension) {
        String resembles = resembles(dimension);
        return resembles.equals(NETHER) && rules().netherAsEnd() ? END : resembles;
    }

    /** JavaDimension#read: a minecraft type by its key, any other by its skybox (absent is the overworld's). */
    private static String resembles(DimensionData dimension) {
        String key = dimension.key();
        if (key.startsWith("minecraft:")) return key.equals(NETHER) || key.equals(END) ? key : OVERWORLD;
        var skybox = JsonParser.parseString(dimension.json()).getAsJsonObject().get("skybox");
        return switch (skybox == null ? "" : skybox.getAsString()) {
            case "none" -> NETHER;
            case "end" -> END;
            default -> OVERWORLD;
        };
    }

    private static java.util.Map<String, ClientWorldDefaults.Entry> modelBiomes() {
        return ClientWorldDefaults.defaults().entries(ClientWorldRegistries.BIOMES);
    }
}
