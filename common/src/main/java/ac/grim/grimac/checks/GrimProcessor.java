package ac.grim.grimac.checks;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.AbstractProcessor;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.config.ConfigReloadable;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.StringReturner;
import ac.grim.grimac.utils.common.ConfigReloadObserver;

public abstract class GrimProcessor implements AbstractProcessor, ConfigReloadable, ConfigReloadObserver {

    // Not everything has to be a check for it to process packets & be configurable

    protected final GrimPlayer player;

    protected GrimProcessor(GrimPlayer player) {
        this.player = player;
    }

    public GrimPlayer getPlayer() {
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
        return GrimAPI.INSTANCE.getConfigManager().getConfig();
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
        reload(GrimAPI.INSTANCE.getConfigManager().getConfig());
    }

    // GenericReloadable/ConfigReloadObserver contract from the pinned GrimAPI artifact;
    // plain processors are not configurable, so both default to no-ops.
    @Override
    public void reload(ConfigManager config) {
        onReload(config);
    }

    @Override
    public void onReload(ConfigManager config) {
    }

}
