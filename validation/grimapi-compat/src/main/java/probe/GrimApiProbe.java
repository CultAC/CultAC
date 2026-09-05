package probe;

import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.event.events.GrimReloadEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/** Compiled against the unmodified published GrimAPI, never against CultAC. */
public final class GrimApiProbe extends JavaPlugin implements Listener {
    @Override
    public void onEnable() {
        GrimAbstractAPI api = GrimAPIProvider.get();
        require(api == Bukkit.getServicesManager().load(GrimAbstractAPI.class), "service identity");
        require(api == GrimAPIProvider.getAsync().join(), "async provider identity");
        require(api.getGrimVersion() != null && !api.getGrimVersion().isBlank(), "version");
        require(api.getGrimPlugin(this) != null, "plugin resolver");
        require(api.getAlertManager() != null, "alert manager");
        require(api.getEventBus() != null, "event bus");
        require(api.getBackendRegistry() != null, "backend registry");
        api.getEventBus().get(GrimReloadEvent.class).onReload(api.getGrimPlugin(this), success -> {
            require(success, "reload success");
            getLogger().info("GRIM_API_PROBE_TYPED_RELOAD_PASS");
        });
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("GRIM_API_PROBE_ENABLE_PASS version=" + api.getGrimVersion());
    }

    @EventHandler
    public void onReload(ac.grim.grimac.api.events.GrimReloadEvent event) {
        getLogger().info("GRIM_API_PROBE_BUKKIT_RELOAD_PASS");
    }

    private static void require(boolean condition, String check) {
        if (!condition) throw new IllegalStateException("GRIM_API_PROBE_FAIL: " + check);
    }
}
