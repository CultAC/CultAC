package ac.cult.cultac.companion.api;

import ac.grim.grimac.api.feature.FeatureManager;
import ac.grim.grimac.api.feature.FeatureState;
import java.util.Collection;
import java.util.Set;

/** No features: per-player features live on the proxy. Setting one is accepted and ignored. */
final class CompanionFeatureManager implements FeatureManager {
    private final WarnOnce warnings;

    CompanionFeatureManager(WarnOnce warnings) {
        this.warnings = warnings;
    }

    @Override
    public Collection<String> getFeatureKeys() {
        return Set.of();
    }

    @Override
    public FeatureState getFeatureState(String key) {
        return FeatureState.UNSET;
    }

    @Override
    public boolean isFeatureEnabled(String key) {
        return false;
    }

    @Override
    public boolean setFeatureState(String key, FeatureState state) {
        warnings.warn(
                "features",
                "Feature '" + key + "' was set on the CultAC proxy companion and has no effect; per-player features"
                        + " live on the proxy");
        return false;
    }

    @Override
    public void reload() {}
}
