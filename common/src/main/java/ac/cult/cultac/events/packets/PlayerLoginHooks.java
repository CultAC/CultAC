package ac.cult.cultac.events.packets;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.datastore.PlayerToggleStore;
import ac.cult.cultac.network.netty.channel.ChannelHelper;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.grim.grimac.api.storage.DataStore;
import ac.grim.grimac.api.storage.category.Categories;
import ac.grim.grimac.api.storage.model.SettingRecord;
import ac.grim.grimac.api.storage.model.SettingScope;
import ac.grim.grimac.api.storage.query.Page;
import ac.grim.grimac.api.storage.query.Queries;
import io.netty.channel.Channel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class PlayerLoginHooks implements ac.cult.cultac.network.UserLifecycleHooks {
    private static final List<String> TOGGLES =
            List.of(PlayerToggleStore.KEY_ALERTS, PlayerToggleStore.KEY_VERBOSE, PlayerToggleStore.KEY_BRANDS);

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

        applyStaffState(platformPlayer, user.getCultConnection().channel());

        CultAPI.INSTANCE
                .getDataStoreLifecycle()
                .liveWriteHooks()
                .onJoinFromUserLogin(platformPlayer, user, System.currentTimeMillis());
    }

    /**
     * The login steps of a player joining on {@code channel}, a connection CultAC does not inspect, which need no CultAC
     * player: portal sync, the alert scope, the alert, verbose and brand toggles and spectator hiding.
     */
    public static void joinedUninspected(@NotNull PlatformPlayer platformPlayer, @NotNull Channel channel) {
        CultAPI.INSTANCE.getPortalSync().joined(platformPlayer);
        applyStaffState(platformPlayer, channel);
    }

    /** Loads the alert scope, applies the alert, verbose and brand toggles and hides a spectator. */
    private static void applyStaffState(@NotNull PlatformPlayer platformPlayer, @NotNull Channel channel) {
        if (platformPlayer.hasPermission("cult.alerts")
                || platformPlayer.hasPermission("cult.verbose")
                || platformPlayer.hasPermission("cult.brand")) {
            CultAPI.INSTANCE.getAlertManager().loadScope(platformPlayer);
            applyToggles(platformPlayer, channel);
        }

        var config = CultAPI.INSTANCE.getConfigManager();
        if (platformPlayer.hasPermission("cult.spectate")
                && config.getConfig().getBooleanElse("spectators.hide-regardless", false)) {
            CultAPI.INSTANCE.getSpectateManager().onLogin(platformPlayer.getUniqueId());
        }
    }

    /**
     * Applies the alert, verbose and brand toggles once the player's stored ones are read, on {@code channel}'s event
     * loop while it is open: the toggle store's own read of them, started by the connection's hold, may still be in
     * flight, and the quit of a closed channel may already have released what the toggles subscribe.
     */
    private static void applyToggles(@NotNull PlatformPlayer platformPlayer, @NotNull Channel channel) {
        UUID uuid = platformPlayer.getUniqueId();
        DataStore store = CultAPI.INSTANCE.getDataStoreLifecycle().dataStore();
        Map<String, Boolean> stored = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> reads = new ArrayList<>();
        if (store != null) {
            for (String key : TOGGLES) {
                reads.add(store.query(
                                Categories.SETTING, new Queries.GetSetting(SettingScope.PLAYER, uuid.toString(), key))
                        .thenAccept(page -> {
                            Boolean value = storedToggle(page);
                            if (value != null) stored.put(key, value);
                        })
                        .toCompletableFuture());
            }
        }
        CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            if (failure != null) LogUtil.warn("Could not read the stored alert toggles of " + uuid, failure);
            try {
                channel.eventLoop().execute(RuntimeEnvironment.guard(() -> {
                    if (channel.isActive()) applyToggles(platformPlayer, stored);
                }));
            } catch (RuntimeException ended) {
                // A closed event loop or generation has no connection left to apply them to.
            }
        });
    }

    private static void applyToggles(@NotNull PlatformPlayer platformPlayer, @NotNull Map<String, Boolean> stored) {
        PlayerToggleStore toggles = CultAPI.INSTANCE.getDataStoreLifecycle().playerToggleStore();
        applyToggle(
                platformPlayer,
                toggles,
                PlayerToggleStore.KEY_ALERTS,
                stored.get(PlayerToggleStore.KEY_ALERTS),
                "cult.alerts",
                "cult.alerts.enable-on-join",
                "cult.alerts.enable-on-join.silent",
                (p, silent) -> CultAPI.INSTANCE.getAlertManager().toggleAlerts(p, silent),
                (p, value) -> CultAPI.INSTANCE.getAlertManager().setAlertsEnabled(p, value, true));
        applyToggle(
                platformPlayer,
                toggles,
                PlayerToggleStore.KEY_VERBOSE,
                stored.get(PlayerToggleStore.KEY_VERBOSE),
                "cult.verbose",
                "cult.verbose.enable-on-join",
                "cult.verbose.enable-on-join.silent",
                (p, silent) -> CultAPI.INSTANCE.getAlertManager().toggleVerbose(p, silent),
                (p, value) -> CultAPI.INSTANCE.getAlertManager().setVerboseEnabled(p, value, true));
        applyToggle(
                platformPlayer,
                toggles,
                PlayerToggleStore.KEY_BRANDS,
                stored.get(PlayerToggleStore.KEY_BRANDS),
                "cult.brand",
                "cult.brand.enable-on-join",
                "cult.brand.enable-on-join.silent",
                (p, silent) -> CultAPI.INSTANCE.getAlertManager().toggleBrands(p, silent),
                (p, value) -> CultAPI.INSTANCE.getAlertManager().setBrandsEnabled(p, value, true));
    }

    /** A stored toggle as the toggle store decodes it; null for none. */
    private static @Nullable Boolean storedToggle(@Nullable Page<SettingRecord> page) {
        if (page == null || page.items().isEmpty()) return null;
        byte[] value = page.items().get(0).value();
        if (value == null || value.length == 0) return null;
        if (value.length == 1) return value[0] != 0;
        String text = new String(value, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        return text.equals("1") || text.equals("true");
    }

    /**
     * @param stored the player's stored value of {@code key}, for a toggle slot that may not hold it yet; null for none
     */
    static void applyToggle(
            @NotNull PlatformPlayer platformPlayer,
            @NotNull PlayerToggleStore toggles,
            @NotNull String key,
            @Nullable Boolean stored,
            @NotNull String permTogglePath,
            @NotNull String permEnableOnJoin,
            @NotNull String permSilentJoin,
            @NotNull BiConsumer<PlatformPlayer, Boolean> toggle,
            @NotNull BiConsumer<PlatformPlayer, Boolean> applySilent) {
        if (!platformPlayer.hasPermission(permTogglePath)) {
            return;
        }

        Boolean explicit = toggles.current(platformPlayer.getUniqueId(), key);
        if (explicit == null) explicit = stored;
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
