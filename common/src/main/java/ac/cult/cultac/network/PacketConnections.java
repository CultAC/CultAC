package ac.cult.cultac.network;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import io.netty.channel.Channel;
import io.netty.util.concurrent.EventExecutor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/** One directory of attached sessions, with a secondary current-account index. */
final class PacketConnections {
    private final Map<Channel, CultConnection> connections = new ConcurrentHashMap<>();
    private final Map<UUID, CultConnection> currentByUuid = new ConcurrentHashMap<>();
    private volatile UserLifecycleHooks hooks = UserLifecycleHooks.NONE;

    void hooks(UserLifecycleHooks hooks) {
        this.hooks = java.util.Objects.requireNonNull(hooks);
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
        session.channel().closeFuture().addListener(ignored -> disconnect(session));
    }

    private void prepare(CultConnection session) {
        if (!session.channel().isActive() || session.disconnected()) return;
        var platform = session.platform();
        var profile = platform == null ? null : platform.authenticatedProfile();
        if (profile == null || profile.getUUID() == null) return;
        var user = authenticate(session, profile.getUUID(), profile.getName());
        if (user != null) completeLogin(user, platform.playerBinding());
    }

    private User authenticate(CultConnection session, UUID uuid, String name) {
        if (!session.channel().isActive() || session.disconnected()) return null;
        if (session.user() != null) return session.user();
        User user = new User(new User.Profile(uuid, name == null ? uuid.toString() : name), session);
        try {
            hooks.onAuthenticated(user);
            if (!session.channel().isActive()) return null;
            // Configuration packets already belong to the authenticated player.
            promote(user);
            if (ac.cult.cultac.network.protocol.util.viaversion.ViaVersionUtil.isAvailable()) {
                ac.cult.cultac.network.packet.LegacyViaInputBridge.install(user);
            }
            return user;
        } catch (RuntimeException | Error failure) {
            finishDisconnect(session);
            throw failure;
        }
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
        user.bind(player);
        if (currentByUuid.get(user.getUUID()) != session) promote(user);
        var cultPlayer = session.player();
        if (cultPlayer != null) {
            cultPlayer.platformPlayer = player;
            binding.initialize(cultPlayer);
            cultPlayer.updatePermissions();
        }
        session.loginNotified = true;
        hooks.onLogin(user, player);
    }

    CompletionStage<Void> disconnect(CultConnection session) {
        return afterPackets(session.owner(), () -> session.runInModel(() -> finishDisconnect(session)));
    }

    private void finishDisconnect(CultConnection session) {
        if (!session.markDisconnected()) return;
        var user = session.user();
        try {
            if (user != null) {
                currentByUuid.remove(user.getUUID(), session);
                try {
                    CultAPI.INSTANCE.getPlayerDataManager().onDisconnect(user);
                } finally {
                    CultNetworkManager.clearChannelState(user);
                }
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
        try {
            // Even a hook which closes its own channel finishes creation before teardown.
            owner.execute(task);
        } catch (RuntimeException rejected) {
            owner.terminationFuture().addListener(done -> task.run());
        }
        return completion.minimalCompletionStage();
    }
}
