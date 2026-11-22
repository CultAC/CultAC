package ac.cult.cultac.events.packets;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.datastore.PlayerToggleStore;
import ac.cult.cultac.network.UserLifecycleHooks;
import ac.cult.cultac.network.netty.channel.ChannelHelper;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import java.util.function.BiConsumer;
import org.jetbrains.annotations.NotNull;

public class PlayerLoginHooks implements UserLifecycleHooks {

    @Override
    public void onLogin(User user, PlatformPlayer platformPlayer) {
        if (platformPlayer == null) {
            return;
        }

        var config = CultAPI.INSTANCE.getConfigManager();

        if (config.getConfig().getBooleanElse("debug-pipeline-on-join", false)) {
            String pipeline = ChannelHelper.pipelineHandlerNamesAsString(user.getChannel());
            LogUtil.info("Pipeline: " + pipeline);
        }

        CultAPI.INSTANCE.getPortalSync().joined(platformPlayer);

        // An adopted connection keeps its alert subscriptions and session; only the session row is extended.
        if (user.getCultConnection().continuation() != null) {
            CultAPI.INSTANCE
                    .getDataStoreLifecycle()
                    .liveWriteHooks()
                    .onJoinFromUserLogin(platformPlayer, user, System.currentTimeMillis());
            return;
        }

        applyStaffState(platformPlayer);

        CultAPI.INSTANCE
                .getDataStoreLifecycle()
                .liveWriteHooks()
                .onJoinFromUserLogin(platformPlayer, user, System.currentTimeMillis());
    }

    /**
     * The login steps of a player joining on a connection CultAC does not inspect, which need no CultAC player: portal
     * sync, the alert scope, the alert, verbose and brand toggles and spectator hiding.
     */
    public static void joinedUninspected(@NotNull PlatformPlayer platformPlayer) {
        CultAPI.INSTANCE.getPortalSync().joined(platformPlayer);
        applyStaffState(platformPlayer);
    }

    /** Loads the alert scope, applies the alert, verbose and brand toggles and hides a spectator. */
    private static void applyStaffState(@NotNull PlatformPlayer platformPlayer) {
        if (platformPlayer.hasPermission("cult.alerts")
                || platformPlayer.hasPermission("cult.verbose")
                || platformPlayer.hasPermission("cult.brand")) {
            CultAPI.INSTANCE.getAlertManager().loadScope(platformPlayer);
        }

        PlayerToggleStore toggles = CultAPI.INSTANCE.getDataStoreLifecycle().playerToggleStore();
        applyToggle(
                platformPlayer,
                toggles,
                PlayerToggleStore.KEY_ALERTS,
                "cult.alerts",
                "cult.alerts.enable-on-join",
                "cult.alerts.enable-on-join.silent",
                (p, silent) -> CultAPI.INSTANCE.getAlertManager().toggleAlerts(p, silent),
                (p, value) -> CultAPI.INSTANCE.getAlertManager().setAlertsEnabled(p, value, true));
        applyToggle(
                platformPlayer,
                toggles,
                PlayerToggleStore.KEY_VERBOSE,
                "cult.verbose",
                "cult.verbose.enable-on-join",
                "cult.verbose.enable-on-join.silent",
                (p, silent) -> CultAPI.INSTANCE.getAlertManager().toggleVerbose(p, silent),
                (p, value) -> CultAPI.INSTANCE.getAlertManager().setVerboseEnabled(p, value, true));
        applyToggle(
                platformPlayer,
                toggles,
                PlayerToggleStore.KEY_BRANDS,
                "cult.brand",
                "cult.brand.enable-on-join",
                "cult.brand.enable-on-join.silent",
                (p, silent) -> CultAPI.INSTANCE.getAlertManager().toggleBrands(p, silent),
                (p, value) -> CultAPI.INSTANCE.getAlertManager().setBrandsEnabled(p, value, true));

        var config = CultAPI.INSTANCE.getConfigManager();
        if (platformPlayer.hasPermission("cult.spectate")
                && config.getConfig().getBooleanElse("spectators.hide-regardless", false)) {
            CultAPI.INSTANCE.getSpectateManager().onLogin(platformPlayer.getUniqueId());
        }
    }

    static void applyToggle(
            @NotNull PlatformPlayer platformPlayer,
            @NotNull PlayerToggleStore toggles,
            @NotNull String key,
            @NotNull String permTogglePath,
            @NotNull String permEnableOnJoin,
            @NotNull String permSilentJoin,
            @NotNull BiConsumer<PlatformPlayer, Boolean> toggle,
            @NotNull BiConsumer<PlatformPlayer, Boolean> applySilent) {
        if (!platformPlayer.hasPermission(permTogglePath)) {
            return;
        }

        Boolean explicit = toggles.current(platformPlayer.getUniqueId(), key);
        if (explicit != null) {
            applySilent.accept(platformPlayer, explicit);
            return;
        }

        // Without an explicit toggle the permission decides at every join and is never stored.
        if (platformPlayer.hasPermission(permEnableOnJoin)) {
            toggle.accept(platformPlayer, platformPlayer.hasPermission(permSilentJoin));
        }
    }
}
