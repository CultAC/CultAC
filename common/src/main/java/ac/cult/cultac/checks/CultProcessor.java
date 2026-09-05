package ac.cult.cultac.checks;

import ac.cult.cultac.CultAPI;
import ac.grim.grimac.api.AbstractProcessor;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.config.ConfigReloadable;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.StringReturner;
import ac.cult.cultac.utils.common.ConfigReloadObserver;

public abstract class CultProcessor implements AbstractProcessor, ConfigReloadable, ConfigReloadObserver {

    // Not everything has to be a check for it to process packets & be configurable

    protected final CultPlayer player;

    protected CultProcessor(CultPlayer player) {
        this.player = player;
    }

    public CultPlayer getPlayer() {
        return player;
    }

    /**
     * AbstractProcessor contract. Plain processors are not configurable checks, so they
     * have no config name; Check overrides this with its @CheckData-derived name.
     */
    @Override
    public String getConfigName() {
        return null;
    }

    protected ConfigManager getConfig() {
        return CultAPI.INSTANCE.getConfigManager().getConfig();
    }

    /**
     * Emits a lazily rendered diagnostic line to this player's debug listeners.
     * The supplier is only evaluated when someone is actually listening.
     */
    public void debug(StringReturner details) {
        player.checkManager.getDebugHandler().relayDebug(getClass().getSimpleName(), details);
    }

    @Override
    public void reload() {
        reload(CultAPI.INSTANCE.getConfigManager().getConfig());
    }

    // GenericReloadable/ConfigReloadObserver contract from the pinned CultAPI artifact;
    // plain processors are not configurable, so both default to no-ops.
    @Override
    public void reload(ConfigManager config) {
        onReload(config);
    }

    @Override
    public void onReload(ConfigManager config) {
    }

}
