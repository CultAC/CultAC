package ac.cult.cultac.manager.player.features;

import ac.cult.cultac.manager.player.features.types.CultFeature;
import ac.cult.cultac.utils.anticheat.LogUtil;
import com.google.common.collect.ImmutableMap;

import java.util.regex.Pattern;

public class FeatureBuilder {

    private static final Pattern VALID = Pattern.compile("[a-zA-Z0-9_]{1,64}");
    private final ImmutableMap.Builder<String, CultFeature> mapBuilder = ImmutableMap.builder();

    public <T extends CultFeature> void register(T feature) {
        if (!VALID.matcher(feature.getName()).matches()) {
            LogUtil.error("Invalid feature name: " + feature.getName());
            return;
        }
        mapBuilder.put(feature.getName(), feature);
    }

    public ImmutableMap<String, CultFeature> buildMap() {
        return mapBuilder.build();
    }

}
