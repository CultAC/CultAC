package ac.cult.cultac.manager.datastore;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.grim.grimac.api.AbstractCheck;
import java.util.UUID;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Only builds engine metadata; all asynchronous writes execute host-owned code. */
public final class LiveWriteHooksImpl implements LiveWriteHooks {
    private final Supplier<StorageWriteHooks> hooks;

    public LiveWriteHooksImpl(Supplier<StorageWriteHooks> hooks) {
        this.hooks = hooks;
    }

    public void onJoin(UUID uuid, String name, long now, SessionTracker.ClientMeta meta) {
        hooks.get().onJoin(uuid, name, now, meta);
    }

    public void onQuit(UUID uuid, long now, SessionTracker.ClientMeta meta) {
        hooks.get().onQuit(uuid, now, meta);
    }

    public void observeBrand(UUID uuid, long now, SessionTracker.ClientMeta meta) {
        hooks.get().observeBrand(uuid, now, meta);
    }

    public void recordFlag(
            UUID uuid, AbstractCheck check, double vl, String verbose, long now, SessionTracker.ClientMeta meta) {
        hooks.get().recordFlag(uuid, check, vl, verbose, now, meta);
    }

    public void recordFlagData(
            UUID uuid, AbstractCheck check, double vl, byte[] data, long now, SessionTracker.ClientMeta meta) {
        hooks.get().recordFlagData(uuid, check, vl, data, now, meta);
    }

    @Override
    public void onJoinFromUserLogin(@NotNull PlatformPlayer player, @NotNull User user, long now) {
        CultPlayer gp = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(user);
        onJoin(player.getUniqueId(), player.getName(), now, LiveWriteHooks.clientMetaFor(user, gp));
    }

    @Override
    public void onQuitFromUserDisconnect(@NotNull User user, @Nullable CultPlayer cultPlayer, long now) {
        UUID uuid = user.getUUID();
        if (uuid == null) return; // disconnected pre-LOGIN_SUCCESS — no session to close
        onQuit(uuid, now, LiveWriteHooks.clientMetaFor(user, cultPlayer));
    }

    @Override
    public void observeBrandFromCheck(@NotNull CultPlayer cultPlayer) {
        UUID uuid = cultPlayer.user.getUUID();
        if (uuid == null) return;
        observeBrand(uuid, System.currentTimeMillis(), LiveWriteHooks.clientMetaFor(cultPlayer.user, cultPlayer));
    }

    @Override
    public void recordFlagFromCheck(
            @NotNull CultPlayer player, @NotNull AbstractCheck check, double vl, @Nullable String verbose) {
        try {
            recordFlag(
                    player.playerUUID,
                    check,
                    vl,
                    verbose,
                    System.currentTimeMillis(),
                    LiveWriteHooks.clientMetaFor(player.user, player));
        } catch (RuntimeException e) {
            // A datastore failure must not break the alert path: warn and swallow.
            LogUtil.warn("v1 datastore recordFlag failed: " + e.getMessage());
        }
    }

    @Override
    public void recordFlagDataFromCheck(
            @NotNull CultPlayer player, @NotNull AbstractCheck check, double vl, byte @Nullable [] verboseData) {
        try {
            recordFlagData(
                    player.playerUUID,
                    check,
                    vl,
                    verboseData,
                    System.currentTimeMillis(),
                    LiveWriteHooks.clientMetaFor(player.user, player));
        } catch (RuntimeException e) {
            // Don't let a datastore issue break the check path.
            LogUtil.warn("v1 datastore recordFlag failed: " + e.getMessage());
        }
    }
}
