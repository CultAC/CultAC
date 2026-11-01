package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.FakePlatformPlayers;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.player.PlatformPlayerCache;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PacketConnectionsTest {
    private final PacketConnections connections = new PacketConnections();

    @BeforeEach
    void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    private CultConnection session(UUID uuid) throws Exception {
        var channel = new EmbeddedChannel();
        var platform = ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.platformConnection();
        when(platform.authenticatedProfile()).thenReturn(new User.Profile(uuid, "ConnectionTest"));
        var session = new CultConnection(
                platform, channel, CultAPI.INSTANCE.getNetworkManager().dispatcher(), ignored -> null);
        session.phase(PacketDirection.SERVERBOUND, ConnectionPhase.CONFIGURATION);
        session.phase(PacketDirection.CLIENTBOUND, ConnectionPhase.CONFIGURATION);
        connections.attach(session);
        return session;
    }

    private static void close(CultConnection session) {
        var channel = (EmbeddedChannel) session.channel();
        channel.close();
        channel.runPendingTasks();
        channel.finishAndReleaseAll();
    }

    @Test
    void oldChannelClosePreservesReplacementAndItsExemption() throws Exception {
        var uuid = UUID.randomUUID();
        var first = session(uuid);
        var second = session(uuid);
        try {
            first.prepare();
            second.prepare();
            var players = CultAPI.INSTANCE.getPlayerDataManager();
            assertNotNull(first.player());
            assertNotNull(second.player());
            assertNotSame(first.player(), second.player());
            assertSame(second.user(), connections.getUser(uuid));
            players.exemptUser(second.user());
            close(first);
            assertTrue(first.disconnected());
            assertNull(first.player());
            assertNull(first.user().getCultPlayer());
            assertSame(second.user(), connections.getUser(uuid));
            assertSame(second.player(), players.getPlayer(second.user()));
            assertTrue(players.isExemptUser(second.user()));
            assertEquals(1, connections.snapshot().size());
        } finally {
            close(first);
            close(second);
        }
        assertTrue(connections.snapshot().isEmpty());
        assertNull(connections.getUser(uuid));
    }

    @Test
    void closingInsideAuthenticationCannotPublishAPartialPlayer() throws Exception {
        var calls = new AtomicInteger();
        connections.hooks(new UserLifecycleHooks() {
            @Override
            public void onAuthenticated(User user) {
                calls.incrementAndGet();
                assertNull(user.getCultPlayer());
                user.getCultConnection().channel().close();
            }
        });
        var session = session(UUID.randomUUID());
        try {
            session.prepare();
            ((EmbeddedChannel) session.channel()).runPendingTasks();
            assertEquals(1, calls.get());
            assertTrue(session.disconnected());
            assertNull(session.player());
            assertNull(connections.getUser(session.user().getUUID()));
            assertTrue(connections.snapshot().isEmpty());
        } finally {
            close(session);
        }
    }

    @Test
    void outdatedClientIsNeverAuthenticated() throws Exception {
        var calls = new AtomicInteger();
        connections.hooks(new UserLifecycleHooks() {
            @Override
            public void onAuthenticated(User user) {
                calls.incrementAndGet();
            }
        });
        var uuid = UUID.randomUUID();
        var session = session(uuid);
        when(session.platform().disconnectOutdatedClient()).thenReturn(true);
        try {
            session.prepare();
            assertEquals(0, calls.get());
            assertNull(session.user());
            assertNull(session.player());
            assertNull(connections.getUser(uuid));
        } finally {
            close(session);
        }
    }

    @Test
    void failedPreparationLeavesFailOpenConnectionsUninspectedOnce() throws Exception {
        var restore = failOpen(true);
        var calls = new AtomicInteger();
        connections.hooks(new UserLifecycleHooks() {
            @Override
            public void onAuthenticated(User user) {
                calls.incrementAndGet();
            }
        });
        var uuid = UUID.randomUUID();
        var session = session(uuid);
        when(session.platform().disconnectOutdatedClient()).thenThrow(new IllegalStateException("broken"));
        try {
            assertDoesNotThrow(session::prepare);
            connections.playerJoined(session);
            ((EmbeddedChannel) session.channel()).runPendingTasks();
            assertTrue(session.channel().isActive());
            assertEquals(0, calls.get());
            assertNull(session.user());
            assertNull(connections.getUser(uuid));
            verify(session.platform(), times(1)).disconnectOutdatedClient();
            verify(session.platform(), never()).disconnect(any());
        } finally {
            restore.run();
            close(session);
        }
    }

    @Test
    void failedPreparationDisconnectsFailClosedConnections() throws Exception {
        var restore = failOpen(false);
        var uuid = UUID.randomUUID();
        var session = session(uuid);
        when(session.platform().disconnectOutdatedClient()).thenThrow(new IllegalStateException("broken"));
        try {
            assertDoesNotThrow(session::prepare);
            assertNull(session.user());
            verify(session.platform()).disconnect(any());
        } finally {
            restore.run();
            close(session);
        }
    }

    @Test
    void failedAuthenticationFollowsTheLaunchPolicy() throws Exception {
        var restore = failOpen(false);
        connections.hooks(new UserLifecycleHooks() {
            @Override
            public void onAuthenticated(User user) {
                throw new IllegalStateException("hook failed");
            }
        });
        var uuid = UUID.randomUUID();
        var session = session(uuid);
        try {
            assertDoesNotThrow(session::prepare);
            assertNull(session.player());
            assertNull(connections.getUser(uuid));
            verify(session.platform()).disconnect(any());
        } finally {
            restore.run();
            close(session);
        }
    }

    @Test
    void failedLoginWithdrawsThePublishedPlayerWhenFailingOpen() throws Exception {
        var restore = failOpen(true);
        var uuid = UUID.randomUUID();
        var session = session(uuid);
        when(session.platform().playerBinding()).thenThrow(new IllegalStateException("binding failed"));
        try {
            assertDoesNotThrow(session::prepare);
            var players = CultAPI.INSTANCE.getPlayerDataManager().getEntries();
            assertNotNull(session.user());
            assertNull(session.player());
            assertTrue(players.stream().noneMatch(player -> player.user == session.user()));
            assertNull(connections.getUser(uuid));
            assertTrue(session.channel().isActive());
            assertFalse(session.disconnected());
            verify(session.platform(), never()).disconnect(any());
        } finally {
            restore.run();
            close(session);
        }
    }

    @Test
    void failedDisconnectStillClosesFailClosedConnections() throws Exception {
        var restore = failOpen(false);
        var session = session(UUID.randomUUID());
        when(session.platform().disconnectOutdatedClient()).thenThrow(new IllegalStateException("broken"));
        doThrow(new IllegalStateException("kick failed"))
                .when(session.platform())
                .disconnect(any());
        try {
            assertDoesNotThrow(session::prepare);
            assertFalse(session.channel().isOpen());
        } finally {
            restore.run();
            close(session);
        }
    }

    private static Runnable failOpen(boolean failOpen) throws ReflectiveOperationException {
        var manager = CultAPI.INSTANCE.getConfigManager();
        var field = BaseConfigManager.class.getDeclaredField("launchFailOpen");
        field.setAccessible(true);
        boolean original = field.getBoolean(manager);
        field.setBoolean(manager, failOpen);
        assertEquals(failOpen, manager.failsOpen());
        return () -> {
            try {
                field.setBoolean(manager, original);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException(failure);
            }
        };
    }

    @Test
    void disconnectAndShutdownShareIdempotentOwnerCleanup() throws Exception {
        var session = session(UUID.randomUUID());
        try {
            session.prepare();
            var user = session.user();
            var player = session.player();
            var first = connections.disconnect(session).toCompletableFuture();
            var shutdown = connections.disconnectRemainingUsers().toCompletableFuture();
            ((EmbeddedChannel) session.channel()).runPendingTasks();
            first.join();
            shutdown.join();
            assertNull(user.getCultPlayer());
            assertFalse(CultAPI.INSTANCE.getPlayerDataManager().getEntries().contains(player));
            assertTrue(connections.snapshot().isEmpty());
            CultAPI.INSTANCE.getPlayerDataManager().addUser(user);
            assertNull(user.getCultPlayer(), "A disconnected session cannot be tracked again");
        } finally {
            close(session);
        }
    }

    @Test
    void removingPlayerTrackingLeavesTheConnectionAvailable() throws Exception {
        var session = session(UUID.randomUUID());
        try {
            session.prepare();
            var user = session.user();
            assertTrue(CultAPI.INSTANCE.getPlayerDataManager().remove(user));
            assertNull(session.player());
            assertNull(user.getCultPlayer());
            assertFalse(session.disconnected());
            assertSame(user, connections.getUser(user.getUUID()));
            assertSame(session, connections.get(session.channel()));
        } finally {
            close(session);
        }
    }

    @Test
    void ownedLoginCachesItsPlayerUntilTheConnectionIsReleased() throws Exception {
        var uuid = UUID.randomUUID();
        var cache = PlatformPlayerCache.getInstance();
        try (var players = new FakePlatformPlayers()) {
            var nativePlayer = new FakePlatformPlayers.Native(uuid);
            PlatformPlayer bound = players.getFromNativePlayerType(nativePlayer);
            assertNull(cache.getPlayer(uuid));
            var session = session(uuid);
            var binding = mock(PlatformConnection.PlayerBinding.class);
            when(binding.player()).thenReturn(bound);
            when(binding.isCurrent()).thenReturn(true);
            when(binding.matches(nativePlayer)).thenReturn(true);
            when(session.platform().playerBinding()).thenReturn(binding);
            try {
                session.prepare();

                assertSame(bound, session.user().getPlayer());
                assertSame(bound, cache.getPlayer(uuid));
            } finally {
                close(session);
            }

            assertNull(cache.getPlayer(uuid));
        } finally {
            cache.removePlayer(uuid);
        }
    }

    @Test
    void platformBindingWaitsForCurrentConnectionAndNotifiesOnce() throws Exception {
        var uuid = UUID.randomUUID();
        var session = session(uuid);
        var player = mock(ac.cult.cultac.platform.api.player.PlatformPlayer.class);
        when(player.getUniqueId()).thenReturn(uuid);
        Object nativePlayer = new Object();
        when(player.getNative()).thenReturn(nativePlayer);
        var binding = mock(PlatformConnection.PlayerBinding.class);
        when(binding.player()).thenReturn(player);
        when(binding.matches(nativePlayer)).thenReturn(true);
        when(session.platform().playerBinding()).thenReturn(binding);
        AtomicInteger joins = new AtomicInteger();
        connections.hooks(new UserLifecycleHooks() {
            @Override
            public void onLogin(User user, ac.cult.cultac.platform.api.player.PlatformPlayer joined) {
                assertSame(player, joined);
                assertSame(player, user.getPlayer());
                assertSame(player, user.getCultPlayer().platformPlayer);
                joins.incrementAndGet();
            }
        });
        try {
            session.prepare();
            assertNotNull(session.player());
            assertNull(session.user().getPlayer());
            assertNull(connections.getUser(uuid, nativePlayer));
            verify(binding, never()).initialize(any());
            when(binding.isCurrent()).thenReturn(true);
            connections.playerJoined(session);
            connections.playerJoined(session);
            ((EmbeddedChannel) session.channel()).runPendingTasks();
            assertEquals(1, joins.get());
            verify(binding).initialize(session.player());
            assertSame(session.user(), connections.getUser(uuid, nativePlayer));
            assertNull(connections.getUser(uuid, new Object()));
            when(binding.isCurrent()).thenReturn(false);
            assertNull(connections.getUser(uuid, nativePlayer));
        } finally {
            close(session);
        }
    }
}
