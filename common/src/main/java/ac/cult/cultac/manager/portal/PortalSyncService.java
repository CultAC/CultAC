package ac.cult.cultac.manager.portal;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.CloudModule;
import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.transport.TeamSocket;
import ac.cult.cultac.manager.init.start.StartableInitable;
import ac.cult.cultac.manager.init.stop.StoppableInitable;
import ac.cult.cultac.manager.portal.PortalSync.OfflineLookup;
import ac.cult.cultac.manager.portal.PortalSync.Online;
import ac.cult.cultac.manager.portal.PortalSync.Role;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.scheduler.TaskHandle;
import ac.cult.cultac.utils.anticheat.LogUtil;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

/**
 * Runs {@link PortalSync} on the async scheduler: the join check, every tick and every sweep run there, never on the
 * main thread or a connection's event loop. The intake sockets offer team sync while {@code cloud.team-sync} (or
 * {@code CULTAC_CLOUD_TEAM_SYNC}) is on; without an answering socket nothing is sent.
 */
public final class PortalSyncService implements StartableInitable, StoppableInitable {
    private volatile @Nullable PortalSync sync;
    private volatile @Nullable OfflineLookup offline;
    private @Nullable TaskHandle tick;
    private final AtomicReference<Runnable> nextJoin = new AtomicReference<>();

    @Override
    public void start() {
        PortalSync started =
                new PortalSync(new Players(), System::currentTimeMillis, () -> offline, LogUtil::info, LogUtil::debug);
        sync = started;
        tick = CultAPI.INSTANCE
                .getScheduler()
                .getAsyncScheduler()
                .runAtFixedRate(
                        CultAPI.INSTANCE.getGrimPlugin(),
                        started::tick,
                        PortalSync.TICK_SECONDS,
                        PortalSync.TICK_SECONDS,
                        TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        TaskHandle handle = tick;
        if (handle != null) handle.cancel();
        tick = null;
        sync = null;
        nextJoin.set(null);
    }

    /** Runs {@code task} once on the async scheduler at the next player join, whether team sync is on or not. */
    public void onNextJoin(Runnable task) {
        nextJoin.set(task);
    }

    /** Schedules the join check; called on the connection's event loop, so it only schedules. */
    public void joined(PlatformPlayer player) {
        Runnable once = nextJoin.getAndSet(null);
        if (once != null)
            CultAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(CultAPI.INSTANCE.getGrimPlugin(), once);
        PortalSync current = sync;
        UUID uuid = player.getUniqueId();
        if (current == null || !PortalSync.accepts(uuid) || !configured(CloudModule.settings())) return;
        CultAPI.INSTANCE
                .getScheduler()
                .getAsyncScheduler()
                .runDelayed(
                        CultAPI.INSTANCE.getGrimPlugin(),
                        () -> {
                            if (sync == current) current.checkJoined(uuid);
                        },
                        PortalSync.JOIN_DELAY_SECONDS,
                        TimeUnit.SECONDS);
    }

    public void permissionsChanged(UUID uuid) {
        PortalSync current = sync;
        if (current != null) current.permissionsChanged(uuid);
    }

    /** The permission plugin's store for players who are not online; null makes sweeps ignored. */
    public void offlineLookup(@Nullable OfflineLookup lookup) {
        offline = lookup;
    }

    /** Whether the upload runs and offers team sync; {@code settings} is null until a config names the upload. */
    static boolean configured(@Nullable CloudSettings settings) {
        return settings != null && settings.enabled() && settings.team();
    }

    /** Hears the team sockets for whichever {@link PortalSync} runs; built only once the upload loads. */
    public Team teamListener() {
        return new Team();
    }

    /** Returned as itself: widening it to {@link TeamSocket.Listener} here would load that cloud class with the upload off. */
    public final class Team implements TeamSocket.Listener {
        @Override
        public void opened(TeamSocket socket, @Nullable Boolean enabled) {
            PortalSync current = sync;
            if (current != null) current.opened(socket, enabled);
        }

        @Override
        public void state(TeamSocket socket, boolean enabled) {
            PortalSync current = sync;
            if (current != null) current.state(socket, enabled);
        }

        @Override
        public void sweep(TeamSocket socket, List<UUID> players) {
            PortalSync current = sync;
            if (current == null) return;
            CultAPI.INSTANCE
                    .getScheduler()
                    .getAsyncScheduler()
                    .runNow(CultAPI.INSTANCE.getGrimPlugin(), () -> current.sweep(socket, players));
        }

        @Override
        public void closed(TeamSocket socket) {
            PortalSync current = sync;
            if (current != null) current.closed(socket);
        }
    }

    private static final class Players implements PortalSync.Players {
        @Override
        public boolean isOnline(UUID uuid) {
            return CultAPI.INSTANCE.getPlatformPlayerFactory().getFromUUID(uuid) != null;
        }

        @Override
        public @Nullable Online check(UUID uuid) {
            PlatformPlayer player = CultAPI.INSTANCE.getPlatformPlayerFactory().getFromUUID(uuid);
            if (player == null) return null;
            return new Online(player.getName(), Role.highest(node -> player.hasPermission(node, false)));
        }
    }
}
