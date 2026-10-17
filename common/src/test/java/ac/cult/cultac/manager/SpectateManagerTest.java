package ac.cult.cultac.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.manager.SpectateManager.Outcome;
import ac.cult.cultac.manager.SpectateManager.ServerState;
import ac.cult.cultac.manager.SpectateManager.SpectateResult;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.math.Location;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class SpectateManagerTest {
    private static final UUID STAFF = UUID.fromString("2b5f1e7a-6a39-4d63-9a3f-0f1e2d3c4b5a");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final PlatformPlayer staff = mock(PlatformPlayer.class);
    private final PlatformPlayer target = mock(PlatformPlayer.class);
    private final PlatformWorld world = mock(PlatformWorld.class);
    private final Location home = new Location(world, 10, 64, -3);
    private final FakeRouter router = new FakeRouter();
    private final SpectateManager manager = new SpectateManager();
    private int returnLinks;

    @BeforeEach
    void setUp() {
        when(staff.getUniqueId()).thenReturn(STAFF);
        when(target.getUniqueId()).thenReturn(TARGET);
        when(staff.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(staff.getLocation()).thenReturn(home);
        when(world.isLoaded()).thenReturn(true);
        capable(staff, true);
        capable(target, true);
        manager.setRouter(router);
    }

    @Test
    void sameServerSetsSpectatorThenTeleportsToTheTarget() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "lobby");

        CompletableFuture<SpectateResult> result = spectate(target);
        verify(staff).setGameMode(GameMode.SPECTATOR);
        assertEquals(List.of(TARGET), router.teleports);
        router.teleport.complete(true);

        assertEquals(new SpectateResult(Outcome.SPECTATING, true), result.join());
        assertTrue(manager.isSpectating(STAFF));
        assertTrue(router.connects.isEmpty());
    }

    @Test
    void spectatingAgainKeepsTheFirstSavedState() {
        router.teleport.complete(true);
        spectate(target).join();

        assertEquals(
                new SpectateResult(Outcome.SPECTATING, false), spectate(target).join());
    }

    @Test
    void failedTeleportIsReported() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "lobby");
        router.teleport.complete(false);

        assertEquals(
                new SpectateResult(Outcome.TELEPORT_FAILED, true),
                spectate(target).join());
        assertTrue(manager.isSpectating(STAFF));
    }

    @Test
    void sameServerWithoutCapabilitiesIsUnsupported() {
        capable(staff, false);

        assertEquals(
                new SpectateResult(Outcome.UNSUPPORTED, false), spectate(target).join());
        assertFalse(manager.isSpectating(STAFF));
        verify(staff, never()).setGameMode(any());
    }

    @Test
    void crossServerConnectsFirstAndGatesOnTheTargetsServer() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        capable(staff, false);

        CompletableFuture<SpectateResult> result = spectate(target);
        assertEquals(List.of("survival"), router.connects);
        verify(staff, never()).setGameMode(any());
        assertTrue(router.teleports.isEmpty());

        router.connect.complete(true);
        verify(staff).setGameMode(GameMode.SPECTATOR);
        router.teleport.complete(true);

        assertEquals(new SpectateResult(Outcome.SPECTATING, true), result.join());
    }

    @Test
    void crossServerTimeoutLeavesNothingBehind() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");

        CompletableFuture<SpectateResult> result = spectate(target);
        router.connect.complete(false);

        assertEquals(new SpectateResult(Outcome.CONNECT_FAILED, false), result.join());
        assertFalse(manager.isSpectating(STAFF));
        verify(staff, never()).setGameMode(any());
        assertTrue(router.teleports.isEmpty());
    }

    @Test
    void stopRestoresTargetThenOriginGameMode() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        when(staff.getGameMode()).thenReturn(GameMode.CREATIVE, GameMode.ADVENTURE);
        router.teleport.complete(true);
        CompletableFuture<SpectateResult> result = spectate(target);
        router.servers.put(STAFF, "survival");
        router.connect.complete(true);
        assertEquals(new SpectateResult(Outcome.SPECTATING, true), result.join());
        router.connect = new CompletableFuture<>();
        when(staff.teleportAsync(home)).thenReturn(CompletableFuture.completedFuture(true));

        manager.disable(staff, true);
        assertEquals(List.of("survival", "lobby"), router.connects);
        verify(staff, never()).teleportAsync(any());
        verify(staff).setGameMode(GameMode.ADVENTURE);
        verify(staff, never()).setGameMode(GameMode.CREATIVE);

        router.connect.complete(true);
        InOrder order = inOrder(staff);
        order.verify(staff).setGameMode(GameMode.ADVENTURE);
        order.verify(staff).teleportAsync(home);
        order.verify(staff).setGameMode(GameMode.CREATIVE);
        assertFalse(manager.isSpectating(STAFF));
    }

    @Test
    void failedReturnConnectPutsTheSpectatorBackInSpectator() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        when(staff.getGameMode()).thenReturn(GameMode.CREATIVE, GameMode.ADVENTURE);
        router.teleport.complete(true);
        CompletableFuture<SpectateResult> result = spectate(target);
        router.servers.put(STAFF, "survival");
        router.connect.complete(true);
        assertEquals(new SpectateResult(Outcome.SPECTATING, true), result.join());
        router.connect = new CompletableFuture<>();

        manager.disable(staff, true);
        router.connect.complete(false);

        InOrder order = inOrder(staff);
        order.verify(staff).setGameMode(GameMode.ADVENTURE);
        order.verify(staff).setGameMode(GameMode.SPECTATOR);
        verify(staff, never()).teleportAsync(any());
        assertTrue(manager.isSpectating(STAFF));
    }

    @Test
    void onwardSpectateRestoresTheLeftServersGameMode() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        when(staff.getGameMode()).thenReturn(GameMode.SURVIVAL, GameMode.CREATIVE, GameMode.ADVENTURE);
        router.teleport.complete(true);
        CompletableFuture<SpectateResult> first = spectate(target);
        router.servers.put(STAFF, "survival");
        router.connect.complete(true);
        assertEquals(new SpectateResult(Outcome.SPECTATING, true), first.join());
        router.connect = new CompletableFuture<>();
        router.servers.put(TARGET, "skyblock");

        CompletableFuture<SpectateResult> second = spectate(target);
        assertEquals(List.of("survival", "skyblock"), router.connects);
        InOrder order = inOrder(staff);
        order.verify(staff).setGameMode(GameMode.SPECTATOR);
        order.verify(staff).setGameMode(GameMode.CREATIVE);

        router.servers.put(STAFF, "skyblock");
        router.connect.complete(true);
        order.verify(staff).setGameMode(GameMode.SPECTATOR);
        assertEquals(new SpectateResult(Outcome.SPECTATING, false), second.join());
        assertTrue(manager.isSpectating(STAFF));
        assertEquals(1, returnLinks);

        manager.disable(staff, false);
        verify(staff).setGameMode(GameMode.ADVENTURE);
        verify(staff, never()).setGameMode(GameMode.SURVIVAL);
    }

    @Test
    void requestsWhileReturningAreRefused() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        when(staff.getGameMode()).thenReturn(GameMode.CREATIVE, GameMode.ADVENTURE);
        router.connect.complete(true);
        router.teleport.complete(true);
        spectate(target).join();
        router.servers.put(STAFF, "survival");
        router.connect = new CompletableFuture<>();
        CompletableFuture<Boolean> back = new CompletableFuture<>();
        when(staff.teleportAsync(home)).thenReturn(back);

        manager.disable(staff, true);
        assertTrue(manager.isConnecting(STAFF));
        assertEquals(
                new SpectateResult(Outcome.ALREADY_CONNECTING, false),
                spectate(target).join());
        manager.disable(staff, true);
        assertEquals(List.of("survival", "lobby"), router.connects);

        router.servers.put(STAFF, "lobby");
        router.connect.complete(true);
        assertTrue(manager.isConnecting(STAFF));
        back.complete(true);

        verify(staff).setGameMode(GameMode.CREATIVE);
        assertFalse(manager.isSpectating(STAFF));
        assertFalse(manager.isConnecting(STAFF));
    }

    @Test
    void lateReturnConnectAfterAQuitSubmitsNoTeleport() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        router.connect.complete(true);
        router.teleport.complete(true);
        spectate(target).join();
        router.servers.put(STAFF, "survival");
        router.connect = new CompletableFuture<>();

        manager.disable(staff, true);
        manager.onQuit(STAFF);
        router.connect.complete(true);

        verify(staff, never()).teleportAsync(any());
        assertFalse(manager.isConnecting(STAFF));
    }

    @Test
    void secondStopWhileReturningIsIgnored() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        router.connect.complete(true);
        router.teleport.complete(true);
        spectate(target).join();
        router.servers.put(STAFF, "survival");
        router.connect = new CompletableFuture<>();

        manager.disable(staff, true);
        manager.disable(staff, true);

        assertEquals(List.of("survival", "lobby"), router.connects);
    }

    @Test
    void quitDuringTheReturnLeavesItsLateTeleportInert() {
        router.servers.put(STAFF, "survival");
        router.servers.put(TARGET, "survival");
        router.reply().complete(new ServerState(GameMode.CREATIVE, home));
        router.teleport.complete(true);
        assertEquals(
                new SpectateResult(Outcome.SPECTATING, true), spectate(target).join());
        CompletableFuture<Boolean> back = new CompletableFuture<>();
        when(staff.teleportAsync(home)).thenReturn(back);

        manager.disable(staff, true);
        assertTrue(manager.isConnecting(STAFF));
        manager.onQuit(STAFF);
        back.complete(true);

        verify(staff, never()).setGameMode(GameMode.CREATIVE);
        assertFalse(manager.isConnecting(STAFF));
    }

    @Test
    void failedReconnectLeavesTheTargetServersGameMode() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        when(staff.getGameMode()).thenReturn(GameMode.CREATIVE, GameMode.SURVIVAL);
        router.connect.complete(true);
        router.teleport.complete(true);
        spectate(target).join();
        router.servers.put(STAFF, "survival");
        router.connect = new CompletableFuture<>();

        manager.disable(staff, true);
        router.connect.complete(false);

        verify(staff).setGameMode(GameMode.SURVIVAL);
        verify(staff, never()).setGameMode(GameMode.CREATIVE);
        assertTrue(manager.isSpectating(STAFF));
    }

    @Test
    void crossServerReturnLinkFollowsTheTeleport() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");

        CompletableFuture<SpectateResult> result = spectate(target);
        router.connect.complete(true);
        assertEquals(0, returnLinks);

        router.teleport.complete(true);
        assertEquals(1, returnLinks);
        assertEquals(new SpectateResult(Outcome.SPECTATING, true), result.join());
    }

    @Test
    void singleServerSendsReturnLinkFirst() {
        manager.setRouter(null);
        Location there = new Location(world, 50, 70, 50);
        when(target.getLocation()).thenReturn(there);
        when(staff.teleportAsync(there)).thenReturn(CompletableFuture.completedFuture(false));
        Runnable onStarted = mock(Runnable.class);

        CompletableFuture<SpectateResult> result = manager.spectate(staff, target, onStarted);

        assertEquals(new SpectateResult(Outcome.SPECTATING, true), result.join());
        InOrder order = inOrder(onStarted, staff);
        order.verify(onStarted).run();
        order.verify(staff).setGameMode(GameMode.SPECTATOR);
        order.verify(staff).teleportAsync(there);
        assertTrue(manager.isSpectating(STAFF));
    }

    @Test
    void singleServerSpectateWhileSpectatingSavesNothing() {
        manager.setRouter(null);
        when(staff.teleportAsync(any())).thenReturn(new CompletableFuture<>());

        spectate(target);
        assertEquals(
                new SpectateResult(Outcome.SPECTATING, false), spectate(target).join());

        assertEquals(1, returnLinks);
        verify(staff, times(1)).getLocation();
        verify(staff, times(2)).setGameMode(GameMode.SPECTATOR);
    }

    @Test
    void singleServerStopKeepsTheWorldLoadedGate() {
        manager.setRouter(null);
        when(staff.teleportAsync(any())).thenReturn(new CompletableFuture<>());
        spectate(target);
        when(world.isLoaded()).thenReturn(false);

        manager.disable(staff, true);

        verify(staff, times(1)).teleportAsync(any());
        verify(staff).setGameMode(GameMode.SURVIVAL);
        assertFalse(manager.isSpectating(STAFF));
    }

    @Test
    void proxyStopOnTheSameServerTeleportsBackAcrossDimensions() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "lobby");
        router.teleport.complete(true);
        spectate(target).join();
        when(world.isLoaded()).thenReturn(false);
        when(staff.teleportAsync(home)).thenReturn(CompletableFuture.completedFuture(true));

        manager.disable(staff, true);

        verify(staff).teleportAsync(home);
        verify(staff).setGameMode(GameMode.SURVIVAL);
        assertFalse(manager.isSpectating(STAFF));
    }

    @Test
    void stopHereOnAnotherServerRestoresThatServersGameMode() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        when(staff.getGameMode()).thenReturn(GameMode.CREATIVE, GameMode.ADVENTURE);
        router.connect.complete(true);
        router.teleport.complete(true);
        spectate(target).join();
        router.servers.put(STAFF, "survival");

        manager.disable(staff, false);

        verify(staff).setGameMode(GameMode.ADVENTURE);
        verify(staff, never()).setGameMode(GameMode.CREATIVE);
        assertFalse(manager.isSpectating(STAFF));
    }

    @Test
    void secondCrossServerSpectateWhileConnectingIsRejected() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");

        CompletableFuture<SpectateResult> first = spectate(target);
        assertEquals(
                new SpectateResult(Outcome.ALREADY_CONNECTING, false),
                spectate(target).join());
        assertEquals(List.of("survival"), router.connects);
        assertTrue(manager.isSpectating(STAFF));

        router.connect.complete(true);
        router.teleport.complete(true);
        assertEquals(new SpectateResult(Outcome.SPECTATING, true), first.join());
        assertTrue(manager.isSpectating(STAFF));
    }

    @Test
    void sameServerSpectateWhileConnectingIsRejected() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        PlatformPlayer neighbour = mock(PlatformPlayer.class);
        when(neighbour.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-00000000000c"));
        router.servers.put(neighbour.getUniqueId(), "lobby");

        spectate(target);

        assertEquals(
                new SpectateResult(Outcome.ALREADY_CONNECTING, false),
                spectate(neighbour).join());
        verify(staff, never()).setGameMode(any());
    }

    @Test
    void failedConnectAllowsANewAttempt() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        spectate(target);
        router.connect.complete(false);
        router.connect = new CompletableFuture<>();

        spectate(target);

        assertEquals(List.of("survival", "survival"), router.connects);
    }

    @Test
    void stopWhileConnectingIsIgnoredAndTheConnectCompletes() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");

        CompletableFuture<SpectateResult> result = spectate(target);
        assertTrue(manager.isConnecting(STAFF));
        manager.disable(staff, true);

        verify(staff, never()).setGameMode(any());
        verify(staff, never()).teleportAsync(any());
        assertEquals(List.of("survival"), router.connects);
        assertTrue(manager.isSpectating(STAFF));

        router.connect.complete(true);
        router.teleport.complete(true);
        assertEquals(new SpectateResult(Outcome.SPECTATING, true), result.join());
        assertFalse(manager.isConnecting(STAFF));
    }

    @Test
    void quitClearsTheConnectAndItsLateSuccessIsInert() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        CompletableFuture<Boolean> stale = router.connect;
        CompletableFuture<SpectateResult> first = spectate(target);

        manager.onQuit(STAFF);
        assertFalse(manager.isConnecting(STAFF));
        assertFalse(manager.isSpectating(STAFF));
        router.connect = new CompletableFuture<>();
        CompletableFuture<SpectateResult> second = spectate(target);
        assertEquals(List.of("survival", "survival"), router.connects);

        stale.complete(true);
        assertEquals(new SpectateResult(Outcome.CONNECT_FAILED, false), first.join());
        verify(staff, never()).setGameMode(any());
        assertTrue(router.teleports.isEmpty());
        assertTrue(manager.isConnecting(STAFF));
        assertTrue(manager.isSpectating(STAFF));

        router.connect.complete(true);
        router.teleport.complete(true);
        assertEquals(new SpectateResult(Outcome.SPECTATING, true), second.join());
        verify(staff).setGameMode(GameMode.SPECTATOR);
    }

    @Test
    void lateFailureOfAConnectClearedByQuitKeepsTheNewerSpectate() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        CompletableFuture<Boolean> stale = router.connect;
        spectate(target);
        manager.onQuit(STAFF);
        router.connect = new CompletableFuture<>();
        spectate(target);

        stale.complete(false);

        assertTrue(manager.isConnecting(STAFF));
        assertTrue(manager.isSpectating(STAFF));
    }

    @Test
    void failedConnectFutureClearsTheConnectAndPropagates() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        CompletableFuture<SpectateResult> result = spectate(target);

        router.connect.completeExceptionally(new IllegalStateException("boom"));

        assertTrue(result.isCompletedExceptionally());
        assertFalse(manager.isConnecting(STAFF));
    }

    @Test
    void stopWhileTheStateAfterTheConnectIsReadIsRefused() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        router.reply().complete(new ServerState(GameMode.CREATIVE, home));
        CompletableFuture<@Nullable ServerState> there = router.reply();
        CompletableFuture<SpectateResult> result = spectate(target);
        router.servers.put(STAFF, "survival");
        router.connect.complete(true);

        assertTrue(manager.isConnecting(STAFF));
        manager.disable(staff, false);
        verify(staff, never()).setGameMode(any());
        assertTrue(manager.isSpectating(STAFF));

        there.complete(new ServerState(GameMode.ADVENTURE, home));
        router.teleport.complete(true);
        assertEquals(new SpectateResult(Outcome.SPECTATING, true), result.join());
        assertFalse(manager.isConnecting(STAFF));
    }

    @Test
    void quitDuringStateReadLeavesLateReplyInert() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        router.reply().complete(new ServerState(GameMode.CREATIVE, home));
        CompletableFuture<@Nullable ServerState> there = router.reply();
        CompletableFuture<SpectateResult> result = spectate(target);
        router.connect.complete(true);

        manager.onQuit(STAFF);
        there.complete(new ServerState(GameMode.ADVENTURE, home));

        assertEquals(new SpectateResult(Outcome.CONNECT_FAILED, false), result.join());
        verify(staff, never()).setGameMode(any());
        assertTrue(router.teleports.isEmpty());
        assertEquals(0, returnLinks);
    }

    @Test
    void stopHereWithoutGameModeSupportKeepsSpectating() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        router.connect.complete(true);
        router.teleport.complete(true);
        spectate(target).join();
        router.servers.put(STAFF, "survival");
        when(staff.hasCapability(ServerCapability.GAMEMODE)).thenReturn(false);

        manager.disable(staff, false);

        verify(staff, times(1)).setGameMode(any());
        verify(staff).sendMessage(any(Component.class));
        assertTrue(manager.isSpectating(STAFF));
    }

    @Test
    void failedConnectAfterTheMoveKeepsTheWayBack() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        CompletableFuture<SpectateResult> result = spectate(target);
        router.servers.put(STAFF, "survival");
        router.connect.complete(false);

        assertEquals(new SpectateResult(Outcome.TELEPORT_FAILED, true), result.join());
        assertTrue(manager.isSpectating(STAFF));
        assertFalse(manager.isConnecting(STAFF));
        assertEquals(1, returnLinks);
        verify(staff, never()).setGameMode(any());
    }

    @Test
    void stopOnTheSameServerTeleportsBack() {
        router.teleport.complete(true);
        spectate(target).join();
        when(staff.teleportAsync(home)).thenReturn(CompletableFuture.completedFuture(true));

        manager.disable(staff, true);

        verify(staff).teleportAsync(home);
        verify(staff).setGameMode(GameMode.SURVIVAL);
        assertTrue(router.connects.isEmpty());
        assertFalse(manager.isSpectating(STAFF));
    }

    @Test
    void stopHereOnlyRestoresTheGameMode() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        router.connect.complete(true);
        router.teleport.complete(true);
        spectate(target).join();
        router.servers.put(STAFF, "survival");

        manager.disable(staff, false);

        verify(staff).setGameMode(GameMode.SURVIVAL);
        verify(staff, never()).teleportAsync(any());
        assertEquals(List.of("survival"), router.connects);
        assertFalse(manager.isSpectating(STAFF));
    }

    @Test
    void sameServerOnAProxySavesTheStateItsServerReports() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "lobby");
        Location reported = new Location(world, 1, 2, 3);
        CompletableFuture<@Nullable ServerState> state = router.reply();
        router.teleport.complete(true);

        CompletableFuture<SpectateResult> result = spectate(target);
        assertTrue(manager.isConnecting(STAFF));
        verify(staff, never()).setGameMode(any());
        state.complete(new ServerState(GameMode.CREATIVE, reported));

        assertEquals(new SpectateResult(Outcome.SPECTATING, true), result.join());
        assertFalse(manager.isConnecting(STAFF));
        when(staff.teleportAsync(reported)).thenReturn(CompletableFuture.completedFuture(true));
        manager.disable(staff, true);
        verify(staff).teleportAsync(reported);
        verify(staff).setGameMode(GameMode.CREATIVE);
        verify(staff, never()).getGameMode();
        verify(staff, never()).getLocation();
    }

    @Test
    void sameServerOnAProxyRefusesWhenItsServerCannotReportTheState() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "lobby");
        router.reply().complete(null);

        assertEquals(
                new SpectateResult(Outcome.STATE_UNAVAILABLE, false),
                spectate(target).join());
        assertFalse(manager.isSpectating(STAFF));
        assertFalse(manager.isConnecting(STAFF));
        verify(staff, never()).setGameMode(any());
        assertTrue(router.teleports.isEmpty());
        assertEquals(0, returnLinks);
    }

    @Test
    void quitWhileTheStateIsReadSavesNothing() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "lobby");
        CompletableFuture<@Nullable ServerState> state = router.reply();
        CompletableFuture<SpectateResult> result = spectate(target);

        manager.onQuit(STAFF);
        state.complete(new ServerState(GameMode.SURVIVAL, home));

        assertEquals(new SpectateResult(Outcome.CONNECT_FAILED, false), result.join());
        assertFalse(manager.isSpectating(STAFF));
        verify(staff, never()).setGameMode(any());
    }

    @Test
    void crossServerRefusesWhenTheOriginCannotReportTheState() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        router.reply().complete(null);

        assertEquals(
                new SpectateResult(Outcome.STATE_UNAVAILABLE, false),
                spectate(target).join());
        assertTrue(router.connects.isEmpty());
        assertFalse(manager.isSpectating(STAFF));
        assertFalse(manager.isConnecting(STAFF));
    }

    @Test
    void crossServerStateFailureAfterTheConnectKeepsTheWayBack() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        router.reply().complete(new ServerState(GameMode.CREATIVE, home));
        router.reply().complete(null);
        router.connect.complete(true);

        assertEquals(
                new SpectateResult(Outcome.TELEPORT_FAILED, true),
                spectate(target).join());
        verify(staff, never()).setGameMode(any());
        assertTrue(router.teleports.isEmpty());
        assertTrue(manager.isSpectating(STAFF));
        assertEquals(1, returnLinks);

        router.servers.put(STAFF, "survival");
        router.connect = new CompletableFuture<>();
        when(staff.teleportAsync(home)).thenReturn(CompletableFuture.completedFuture(true));
        manager.disable(staff, true);
        router.connect.complete(true);

        assertEquals(List.of("survival", "lobby"), router.connects);
        verify(staff).teleportAsync(home);
        verify(staff).setGameMode(GameMode.CREATIVE);
        assertFalse(manager.isSpectating(STAFF));
    }

    @Test
    void failedStateReadAfterTheConnectCountsAsUnreadable() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        router.reply().complete(new ServerState(GameMode.CREATIVE, home));
        router.reply().completeExceptionally(new IllegalStateException("boom"));
        router.connect.complete(true);

        assertEquals(
                new SpectateResult(Outcome.TELEPORT_FAILED, true),
                spectate(target).join());
        assertTrue(manager.isSpectating(STAFF));
        assertFalse(manager.isConnecting(STAFF));
        assertEquals(1, returnLinks);
    }

    @Test
    void retryOnTheTargetServerRecordsItsGameModeBeforeSpectating() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        router.reply().complete(new ServerState(GameMode.CREATIVE, home));
        router.reply().complete(null);
        router.connect.complete(true);
        router.teleport.complete(true);
        spectate(target).join();
        router.servers.put(STAFF, "survival");
        CompletableFuture<@Nullable ServerState> there = router.reply();

        CompletableFuture<SpectateResult> retry = spectate(target);
        assertTrue(manager.isConnecting(STAFF));
        verify(staff, never()).setGameMode(any());
        there.complete(new ServerState(GameMode.ADVENTURE, home));

        assertEquals(new SpectateResult(Outcome.SPECTATING, false), retry.join());
        verify(staff).setGameMode(GameMode.SPECTATOR);
        manager.disable(staff, false);
        verify(staff).setGameMode(GameMode.ADVENTURE);
        verify(staff, never()).setGameMode(GameMode.CREATIVE);
        assertFalse(manager.isSpectating(STAFF));
    }

    @Test
    void chainStateFailureAfterTheConnectStillReturnsToTheOrigin() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        when(staff.getGameMode()).thenReturn(GameMode.SURVIVAL, GameMode.CREATIVE);
        router.teleport.complete(true);
        CompletableFuture<SpectateResult> first = spectate(target);
        router.servers.put(STAFF, "survival");
        router.connect.complete(true);
        assertEquals(new SpectateResult(Outcome.SPECTATING, true), first.join());
        router.connect = new CompletableFuture<>();
        router.servers.put(TARGET, "skyblock");
        router.reply().complete(null);

        CompletableFuture<SpectateResult> second = spectate(target);
        router.servers.put(STAFF, "skyblock");
        router.connect.complete(true);

        assertEquals(new SpectateResult(Outcome.TELEPORT_FAILED, false), second.join());
        assertTrue(manager.isSpectating(STAFF));
        assertEquals(2, returnLinks);
        verify(staff, times(1)).setGameMode(GameMode.SPECTATOR);
        verify(staff, times(1)).setGameMode(GameMode.CREATIVE);

        router.connect = new CompletableFuture<>();
        when(staff.teleportAsync(home)).thenReturn(CompletableFuture.completedFuture(true));
        manager.disable(staff, true);
        router.connect.complete(true);

        assertEquals(List.of("survival", "skyblock", "lobby"), router.connects);
        verify(staff, times(1)).setGameMode(GameMode.CREATIVE);
        verify(staff).setGameMode(GameMode.SURVIVAL);
        assertFalse(manager.isSpectating(STAFF));
    }

    @Test
    void failedOnwardConnectPutsThePlayerBackInSpectator() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "survival");
        when(staff.getGameMode()).thenReturn(GameMode.SURVIVAL, GameMode.CREATIVE);
        router.teleport.complete(true);
        CompletableFuture<SpectateResult> first = spectate(target);
        router.servers.put(STAFF, "survival");
        router.connect.complete(true);
        assertEquals(new SpectateResult(Outcome.SPECTATING, true), first.join());
        router.connect = new CompletableFuture<>();
        router.servers.put(TARGET, "skyblock");

        CompletableFuture<SpectateResult> second = spectate(target);
        router.connect.complete(false);

        assertEquals(new SpectateResult(Outcome.CONNECT_FAILED, false), second.join());
        InOrder order = inOrder(staff);
        order.verify(staff).setGameMode(GameMode.SPECTATOR);
        order.verify(staff).setGameMode(GameMode.CREATIVE);
        order.verify(staff).setGameMode(GameMode.SPECTATOR);
        assertTrue(manager.isSpectating(STAFF));
    }

    @Test
    void failedTeleportBackThroughAProxyStillRestoresTheGameMode() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "lobby");
        when(staff.getGameMode()).thenReturn(GameMode.CREATIVE);
        router.teleport.complete(true);
        spectate(target).join();
        when(staff.teleportAsync(home)).thenReturn(CompletableFuture.completedFuture(false));

        manager.disable(staff, true);

        verify(staff).setGameMode(GameMode.CREATIVE);
        assertFalse(manager.isSpectating(STAFF));
    }

    @Test
    void failedTeleportBackWithoutGameModeSupportKeepsSpectating() {
        router.servers.put(STAFF, "lobby");
        router.servers.put(TARGET, "lobby");
        when(staff.getGameMode()).thenReturn(GameMode.CREATIVE);
        router.teleport.complete(true);
        spectate(target).join();
        when(staff.hasCapability(ServerCapability.GAMEMODE)).thenReturn(false);
        when(staff.teleportAsync(home)).thenReturn(CompletableFuture.completedFuture(false));

        manager.disable(staff, true);

        verify(staff, never()).setGameMode(GameMode.CREATIVE);
        verify(staff).sendMessage(any(Component.class));
        assertTrue(manager.isSpectating(STAFF));
    }

    @Test
    void failedTeleportBackOnASingleServerKeepsSpectating() {
        manager.setRouter(null);
        when(staff.teleportAsync(any())).thenReturn(CompletableFuture.completedFuture(false));
        spectate(target);

        manager.disable(staff, true);

        verify(staff, never()).setGameMode(GameMode.SURVIVAL);
        assertTrue(manager.isSpectating(STAFF));
    }

    private CompletableFuture<SpectateResult> spectate(PlatformPlayer to) {
        return manager.spectate(staff, to, () -> returnLinks++);
    }

    private static void capable(PlatformPlayer player, boolean capable) {
        when(player.hasCapability(ServerCapability.TELEPORT)).thenReturn(capable);
        when(player.hasCapability(ServerCapability.GAMEMODE)).thenReturn(capable);
    }

    private static final class FakeRouter implements SpectateManager.Router {
        final Map<UUID, String> servers = new HashMap<>();
        final List<String> connects = new ArrayList<>();
        final List<UUID> teleports = new ArrayList<>();
        final Deque<CompletableFuture<@Nullable ServerState>> states = new ArrayDeque<>();
        CompletableFuture<Boolean> connect = new CompletableFuture<>();
        final CompletableFuture<Boolean> teleport = new CompletableFuture<>();

        @Override
        public @Nullable String serverName(PlatformPlayer player) {
            return servers.get(player.getUniqueId());
        }

        @Override
        public CompletableFuture<Boolean> connect(PlatformPlayer player, String server) {
            connects.add(server);
            return connect;
        }

        @Override
        public CompletableFuture<Boolean> teleportToPlayer(PlatformPlayer spectator, PlatformPlayer target) {
            teleports.add(target.getUniqueId());
            return teleport;
        }

        /** Queues the reply to the next state read; without one the player's own state answers. */
        CompletableFuture<@Nullable ServerState> reply() {
            CompletableFuture<@Nullable ServerState> reply = new CompletableFuture<>();
            states.addLast(reply);
            return reply;
        }

        @Override
        public CompletableFuture<@Nullable ServerState> playerState(PlatformPlayer player) {
            CompletableFuture<@Nullable ServerState> queued = states.pollFirst();
            if (queued != null) return queued;
            return CompletableFuture.completedFuture(new ServerState(player.getGameMode(), player.getLocation()));
        }
    }
}
