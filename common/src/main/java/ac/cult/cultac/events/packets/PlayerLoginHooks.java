package ac.cult.cultac.events.packets;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.datastore.PlayerToggleStore;
import ac.cult.cultac.network.netty.channel.ChannelHelper;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.jetbrains.annotations.NotNull;

public class PlayerLoginHooks implements ac.cult.cultac.network.UserLifecycleHooks {

    @Override
    public void onLogin(ac.cult.cultac.network.protocol.player.User user, PlatformPlayer platformPlayer) {
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

        if (platformPlayer.hasPermission("cult.spectate")
                && config.getConfig().getBooleanElse("spectators.hide-regardless", false)) {
            CultAPI.INSTANCE.getSpectateManager().onLogin(platformPlayer.getUniqueId());
        }

        CultAPI.INSTANCE
                .getDataStoreLifecycle()
                .liveWriteHooks()
                .onJoinFromUserLogin(platformPlayer, user, System.currentTimeMillis());
    }

    private static void applyToggle(
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

        UUID uuid = platformPlayer.getUniqueId();
        Boolean persisted = toggles.current(uuid, key);
        if (persisted != null) {
            applySilent.accept(platformPlayer, persisted);
            return;
        }

        boolean enableOnJoin = platformPlayer.hasPermission(permEnableOnJoin);
        boolean silent = platformPlayer.hasPermission(permSilentJoin);
        if (enableOnJoin) {
            toggle.accept(platformPlayer, silent);
            toggles.applyPermissionDefault(uuid, key, true);
        } else {
            toggles.applyPermissionDefault(uuid, key, false);
        }
    }
}
