package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.manager.datastore.PlayerToggleStore;
import ac.grim.grimac.network.event.UserDisconnectEvent;
import ac.grim.grimac.network.event.UserLifecycleListener;
import ac.grim.grimac.network.event.UserLoginEvent;
import ac.grim.grimac.network.netty.channel.ChannelHelper;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.BiConsumer;

public class PacketPlayerJoinQuit extends UserLifecycleListener {

    @Override
    public void onUserLogin(UserLoginEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        PlatformPlayer platformPlayer = GrimAPI.INSTANCE.getPlatformPlayerFactory()
                .getFromNativePlayerType(player);
        var config = GrimAPI.INSTANCE.getConfigManager();

        if (config.getConfig().getBooleanElse("debug-pipeline-on-join", false)) {
            String pipeline = ChannelHelper.pipelineHandlerNamesAsString(event.getUser().getChannel());
            LogUtil.info("Pipeline: " + pipeline);
        }

        PlayerToggleStore toggles = GrimAPI.INSTANCE.getDataStoreLifecycle().playerToggleStore();
        applyToggle(platformPlayer, toggles, PlayerToggleStore.KEY_ALERTS,
                "grim.alerts", "grim.alerts.enable-on-join", "grim.alerts.enable-on-join.silent",
                (p, silent) -> GrimAPI.INSTANCE.getAlertManager().toggleAlerts(p, silent),
                (p, value) -> GrimAPI.INSTANCE.getAlertManager().setAlertsEnabled(p, value, true));
        applyToggle(platformPlayer, toggles, PlayerToggleStore.KEY_VERBOSE,
                "grim.verbose", "grim.verbose.enable-on-join", "grim.verbose.enable-on-join.silent",
                (p, silent) -> GrimAPI.INSTANCE.getAlertManager().toggleVerbose(p, silent),
                (p, value) -> GrimAPI.INSTANCE.getAlertManager().setVerboseEnabled(p, value, true));
        applyToggle(platformPlayer, toggles, PlayerToggleStore.KEY_BRANDS,
                "grim.brand", "grim.brand.enable-on-join", "grim.brand.enable-on-join.silent",
                (p, silent) -> GrimAPI.INSTANCE.getAlertManager().toggleBrands(p, silent),
                (p, value) -> GrimAPI.INSTANCE.getAlertManager().setBrandsEnabled(p, value, true));

        if (platformPlayer.hasPermission("grim.spectate")
                && config.getConfig().getBooleanElse("spectators.hide-regardless", false)) {
            GrimAPI.INSTANCE.getSpectateManager().onLogin(platformPlayer.getUniqueId());
        }

        GrimAPI.INSTANCE.getDataStoreLifecycle().liveWriteHooks()
                .onJoinFromUserLogin(platformPlayer, event.getUser(), System.currentTimeMillis());
    }

    private static void applyToggle(@NotNull PlatformPlayer platformPlayer,
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

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        GrimAPI.INSTANCE.getPlayerDataManager().onDisconnect(event.getUser());
    }
}
