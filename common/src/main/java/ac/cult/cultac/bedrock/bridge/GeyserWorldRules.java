package ac.cult.cultac.bedrock.bridge;

import ac.cult.blocksim.entity.PaintingSize;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.utils.floodgate.GeyserUtil;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.geysermc.geyser.level.BedrockDimension;
import org.geysermc.geyser.level.JavaDimension;
import org.geysermc.geyser.level.PaintingType;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.translator.level.BiomeTranslator;

/**
 * The tables Geyser converts Java world registries with, read from the running Geyser (2.10 to 2.12). Without a
 * local Geyser, as for a Bedrock player recognised only through Floodgate, the model's vanilla entries map.
 *
 * @param netherAsEnd Geyser's above-bedrock-nether-building option, which sends nether-like levels as the End
 * @param biomes the Java biomes Geyser maps to a Bedrock biome; null for the model's
 * @param paintings the Java painting variants Geyser maps to a Bedrock motive, with its size; null for the model's
 * @param fallbackPainting the motive Geyser shows for every other variant
 * @param biomeFallbackByDimension whether an unmapped biome becomes its level's fallback (2.12) rather than ocean
 */
public record GeyserWorldRules(
        boolean netherAsEnd,
        Set<String> biomes,
        Map<String, PaintingSize> paintings,
        PaintingSize fallbackPainting,
        boolean biomeFallbackByDimension) {
    /** PaintingType.KEBAB, PaintingType#getByName's fallback. */
    private static final PaintingSize KEBAB = new PaintingSize(1, 1);

    public static final GeyserWorldRules WITHOUT_GEYSER = new GeyserWorldRules(false, null, null, KEBAB, false);

    public static GeyserWorldRules current() {
        if (CultAPI.INSTANCE.getPluginManager() == null || !GeyserUtil.isGeyserAvailable()) return WITHOUT_GEYSER;
        try {
            return Tables.read();
        } catch (LinkageError failure) {
            throw new IllegalStateException("Unsupported Geyser world conversion API", failure);
        }
    }

    /** Loaded only with a running Geyser. */
    private static final class Tables {
        static GeyserWorldRules read() {
            // BiomeIdentifierRegistryLoader: Java identifier -> Bedrock ID. Read as a plain Map, as Geyser's
            // platform jars may relocate fastutil.
            Object identifiers = Registries.BIOME_IDENTIFIERS.get();
            var biomes = new HashSet<String>();
            for (Object key : ((Map<?, ?>) identifiers).keySet()) biomes.add(String.valueOf(key));
            // PaintingType#getByName matches the minecraft namespace and the lower-case constant name.
            var paintings = new HashMap<String, PaintingSize>();
            for (PaintingType type : PaintingType.values())
                paintings.put("minecraft:" + type.name().toLowerCase(Locale.ROOT), size(type));
            return new GeyserWorldRules(
                    BedrockDimension.isCustomBedrockNetherId(),
                    Set.copyOf(biomes),
                    Map.copyOf(paintings),
                    size(PaintingType.KEBAB),
                    hasDimensionBiomeFallback());
        }

        private static PaintingSize size(PaintingType type) {
            return new PaintingSize(type.getWidth(), type.getHeight());
        }

        /** Geyser 2.12's BiomeTranslator#fallbackBiomeId; older builds map every unmapped biome to ocean (ID 0). */
        private static boolean hasDimensionBiomeFallback() {
            try {
                BiomeTranslator.class.getDeclaredMethod("fallbackBiomeId", JavaDimension.class);
                return true;
            } catch (NoSuchMethodException older) {
                return false;
            }
        }
    }
}
