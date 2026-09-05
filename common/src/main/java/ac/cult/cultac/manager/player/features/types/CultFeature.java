package ac.cult.cultac.manager.player.features.types;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.feature.FeatureState;
import ac.cult.cultac.player.CultPlayer;

public interface CultFeature {
    String getName();

    void setState(CultPlayer player, ConfigManager config, FeatureState state);

    boolean isEnabled(CultPlayer player);

    boolean isEnabledInConfig(CultPlayer player, ConfigManager config);
}
