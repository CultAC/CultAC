package ac.cult.cultac.network;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.reload.api.RuntimeInstance;
import ac.cult.reload.host.Callbacks;
import io.netty.channel.Channel;
import io.netty.util.concurrent.EventExecutor;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;

/** One directory of attached sessions, with a secondary current-account index. */
final class PacketConnections {
    private final Map<Channel, CultConnection> connections = new ConcurrentHashMap<>();
    private final Map<UUID, CultConnection> currentByUuid = new ConcurrentHashMap<>();
    private volatile UserLifecycleHooks hooks = UserLifecycleHooks.NONE;

    void hooks(UserLifecycleHooks hooks) {
        this.hooks = Objects.requireNonNull(hooks);
    }

    CultConnection get(Channel channel) {
        return channel == null ? null : connections.get(channel);
    }

    List<CultConnection> snapshot() {
        return List.copyOf(connections.values());
    }

    void attach(CultConnection session) {
        if (connections.putIfAbsent(session.channel(), session) != null)
            throw new IllegalStateException("Already attached");
        session.initializer(this::prepare);
        session.closeListener(ignored -> disconnect(session));
    }

    private void prepare(CultConnection session) {
        if (!session.channel().isActive() || session.disconnected() || session.excluded()) return;
        try {
            var platform = session.platform();
            var profile = platform == null ? null : platform.authenticatedProfile();
            if (profile == null || profile.getUUID() == null) return;
            if (platform.disconnectOutdatedClient()) {
                session.exclude();
                return;
            }
            var user = authenticate(session, profile.getUUID(), profile.getName());
            if (user != null) completeLogin(user, platform.playerBinding());
        } catch (RuntimeException | Error failure) {
            refuse(session, failure);
        }
    }

    /** Preparation failed, so this connection has no inspected user; the launch policy decides whether it stays. */
    private void refuse(CultConnection session, Throwable failure) {
        session.exclude();
        detach(session);
        // The profile read may be what failed.
        var who = session.channel().remoteAddress();
        if (CultAPI.INSTANCE.getConfigManager().failsOpen()) {
            LogUtil.warn("Not inspecting " + who + "; CultAC could not prepare its session", failure);
            return;
        }
        LogUtil.error("Disconnecting " + who + "; CultAC could not prepare its session", failure);
        var platform = session.platform();
        try {
            if (platform != null) {
                platform.disconnect(Component.text(RuntimeInstance.CONNECTION_REFUSED));
                return;
            }
        } catch (RuntimeException | Error disconnectFailure) {
            failure.addSuppressed(disconnectFailure);
        }
        session.channel().close();
    }

    /** Withdraws a published user's player and index entry; the transport and its packet values stay usable. */
    private void detach(CultConnection session) {
        var user = session.user();
        if (user == null) return;
        currentByUuid.remove(user.getUUID(), session);
        CultAPI.INSTANCE.getPlayerDataManager().remove(user);
    }

    private User authenticate(CultConnection session, UUID uuid, String name) {
        if (!session.channel().isActive() || session.disconnected()) return null;
        if (session.user() != null) return session.user();
        User user = new User(new User.Profile(uuid, name == null ? uuid.toString() : name), session);
        hooks.onAuthenticated(user);
        if (!session.channel().isActive()) return null;
        // Configuration packets already belong to the authenticated player.
        promote(user);
        var continued = session.continuation();
        if (continued != null && session.player() != null) continued.restore(session.player());
        observePlatformBrand(session);
        return user;
    }

    /** The brand packet can pass before Cult's decoder is installed; the platform then still holds what it read. */
    private static void observePlatformBrand(CultConnection session) {
        var player = session.player();
        var platform = session.platform();
        if (player == null || platform == null) return;
        var brand = player.checkManager.getCheck(ac.cult.cultac.checks.impl.misc.ClientBrand.class);
        if (brand != null) brand.observe(platform.clientBrand());
    }

    private void promote(User user) {
        CultAPI.INSTANCE.getPlayerDataManager().addUser(user);
        currentByUuid.put(user.getUUID(), user.getCultConnection());
    }

    User getUser(PlatformPlayer player) {
        if (player == null) return null;
        return getUser(player.getUniqueId(), player.getNative());
    }

    User getUser(UUID uuid, Object nativePlayer) {
        User user = getUser(uuid);
        if (user == null || user.getCultConnection().disconnected()) return null;
        var platform = user.getCultConnection().platform();
        var binding = platform == null ? null : platform.playerBinding();
        return binding != null && binding.isCurrent() && binding.matches(nativePlayer) ? user : null;
    }

    User getUser(UUID uuid) {
        var session = uuid == null ? null : currentByUuid.get(uuid);
        return session == null ? null : session.user();
    }

    void playerJoined(CultConnection session) {
        session.execute(() -> prepare(session));
    }

    private void completeLogin(User user, PlatformConnection.PlayerBinding binding) {
        var session = user.getCultConnection();
        if (binding == null || session.disconnected() || session.loginNotified || !binding.isCurrent()) return;
        PlatformPlayer player = binding.player();
        if (!user.getUUID().equals(player.getUniqueId())) throw new IllegalStateException("Connection account changed");
        // The owner caches the bound player; releaseConnection invalidates it when this connection ends.
        var players = CultAPI.INSTANCE.getPlatformPlayerFactory();
        if (players != null) player = players.attach(player);
        user.bind(player);
        if (currentByUuid.get(user.getUUID()) != session) promote(user);
        var cultPlayer = session.player();
        if (cultPlayer != null) {
            cultPlayer.platformPlayer = player;
            binding.initialize(cultPlayer);
            cultPlayer.updatePermissions();
            var host = RuntimeEnvironment.host();
            if (host != null) host.bindUser(session.channel(), user.getUUID(), player.getNative(), cultPlayer);
        }
        session.loginNotified = true;
        hooks.onLogin(user, player);
    }

    CompletionStage<Void> disconnect(CultConnection session) {
        return afterPackets(session.owner(), () -> session.runInModel(() -> finishDisconnect(session)));
    }

    /** Hands the session to another engine within the caller's task on its packet owner. */
    void release(CultConnection session) {
        if (!session.owner().inEventLoop()) throw new IllegalStateException("Release requires the packet owner");
        session.detachCloseListener();
        session.runInModel(() -> finishDisconnect(session, true));
    }

    private void finishDisconnect(CultConnection session) {
        finishDisconnect(session, false);
    }

    private void finishDisconnect(CultConnection session, boolean transferred) {
        if (!session.markDisconnected()) return;
        session.stopPacketLog("disconnect");
        var user = session.user();
        try {
            if (user != null) {
                currentByUuid.remove(user.getUUID(), session);
                CultAPI.INSTANCE.getPlayerDataManager().onDisconnect(user, transferred);
            }
        } finally {
            // Shutdown must still find a session while owner cleanup is in progress.
            try {
                session.packets().close();
            } finally {
                connections.remove(session.channel(), session);
            }
        }
    }

    CompletionStage<Void> disconnectRemainingUsers() {
        return CompletableFuture.allOf(snapshot().stream()
                .map(session -> disconnect(session).toCompletableFuture())
                .toArray(CompletableFuture[]::new));
    }

    void clearUsers() {
        connections.clear();
        currentByUuid.clear();
        hooks = UserLifecycleHooks.NONE;
    }

    private static CompletionStage<Void> afterPackets(EventExecutor owner, Runnable cleanup) {
        var completion = new CompletableFuture<Void>();
        Runnable task = () -> {
            try {
                cleanup.run();
                completion.complete(null);
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        };
        Runnable accepted = RuntimeEnvironment.once(task);
        try {
            // Even a hook which closes its own channel finishes creation before teardown.
            owner.execute(accepted);
        } catch (RuntimeException rejected) {
            var host = RuntimeEnvironment.host();
            if (host == null) owner.terminationFuture().addListener(done -> accepted.run());
            else owner.terminationFuture().addListener(Callbacks.future(host.resources(), accepted));
        }
        return completion.minimalCompletionStage();
    }
}
