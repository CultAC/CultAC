package ac.cult.cultac.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CommandExecute;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ConsoleExec;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Result;
import ac.cult.cultac.bridge.protocol.ListenerKind;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.platform.velocity.bridge.BridgeFrames;
import ac.cult.cultac.platform.velocity.bridge.BridgeMessageListener;
import ac.cult.cultac.platform.velocity.bridge.BridgeSession;
import ac.cult.cultac.platform.velocity.bridge.BridgeSessions;
import ac.cult.cultac.platform.velocity.bridge.VelocityBridge;
import ac.cult.cultac.platform.velocity.bridge.api.BridgeApiService;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.grim.grimac.api.AbstractCheck;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class VelocityServerTest {
    private static final String BAN = "ban Notch cheating";

    private final ProxyServer proxy = mock(ProxyServer.class, RETURNS_DEEP_STUBS);
    private final VelocitySenders senders = new VelocitySenders(proxy, new VelocityPermissions());
    private final VelocityServer server = new VelocityServer(proxy, senders, id -> Double.NaN);
    private final BridgeSession session = mock(BridgeSession.class);
    private final Player velocityPlayer = mock(Player.class);
    private final Sender console = senders.wrap(proxy.getConsoleCommandSource());
    private final AbstractCheck check = mock(AbstractCheck.class);
    private VelocityPlayer subject;

    @BeforeEach
    void bridge() {
        UUID id = UUID.randomUUID();
        when(velocityPlayer.getUniqueId()).thenReturn(id);
        subject = new VelocityPlayer(velocityPlayer, senders);
        when(session.isReady()).thenReturn(true);
        when(session.has(ServerCapability.CONSOLE_EXEC)).thenReturn(true);
        when(session.serverName()).thenReturn("survival");
        reply(new Result(1, true));
        when(proxy.getCommandManager().hasCommand(anyString())).thenReturn(false);
        when(proxy.getCommandManager().executeAsync(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(false));
        VelocityBridge.install(new BridgeSessions() {
            @Override
            public @Nullable BridgeSession current(UUID player) {
                return id.equals(player) ? session : null;
            }

            @Override
            public void addListener(BridgeMessageListener listener) {}
        });
    }

    @AfterEach
    void reset() {
        VelocityBridge.install(BridgeSessions.NONE);
    }

    private void reply(@Nullable BridgeMessage message) {
        when(session.exchange(any(), anyLong())).thenReturn(CompletableFuture.completedFuture(message));
    }

    private void listensForCommandExecute() {
        when(session.has(ServerCapability.FLAG_VERDICT)).thenReturn(true);
        when(session.listeners()).thenReturn(ListenerKind.COMMAND_EXECUTE.flag());
    }

    /** The request the session was asked to send, built for {@code requestId}. */
    @SuppressWarnings("unchecked")
    private BridgeMessage sentRequest(int requestId) {
        ArgumentCaptor<IntFunction<BridgeMessage>> request = ArgumentCaptor.forClass(IntFunction.class);
        verify(session).exchange(request.capture(), anyLong());
        return request.getValue().apply(requestId);
    }

    @Test
    void punishmentThatFitsRunsOnTheBackendConsole() {
        try (MockedStatic<LogUtil> log = mockStatic(LogUtil.class)) {
            server.dispatchCommand(console, BAN, subject);

            log.verifyNoInteractions();
        }
        assertEquals(new ConsoleExec(5, BAN), sentRequest(5));
        verify(proxy.getCommandManager(), never()).executeAsync(any(), anyString());
    }

    @Test
    void unansweredConsoleExecSaysTheCommandMayStillRun() {
        reply(null);
        try (MockedStatic<LogUtil> log = mockStatic(LogUtil.class)) {
            server.dispatchCommand(console, BAN, subject);

            log.verify(() -> LogUtil.warn("No answer from survival within 2 s (the command may still run): " + BAN));
            log.verifyNoMoreInteractions();
        }
    }

    @Test
    void refusedConsoleExecSaysTheCommandDidNotRun() {
        reply(new Result(1, false));
        try (MockedStatic<LogUtil> log = mockStatic(LogUtil.class)) {
            server.dispatchCommand(console, BAN, subject);

            log.verify(() -> LogUtil.warn("Command did not run on survival: " + BAN));
            log.verifyNoMoreInteractions();
        }
    }

    @Test
    void oversizedPunishmentWithoutAProxyCommandIsReportedAsNotRun() {
        String command = "ban Notch " + "x".repeat(BridgeFrames.MAX_BACKEND_FRAME_BYTES - 16);

        try (MockedStatic<LogUtil> log = mockStatic(LogUtil.class)) {
            server.dispatchCommand(console, command, subject);

            log.verify(() -> LogUtil.error(startsWith("Punishment command did not run")));
            log.verifyNoMoreInteractions();
        }
        verify(session, never()).exchange(any(), anyLong());
        verify(proxy.getCommandManager(), never()).executeAsync(any(), anyString());
    }

    @Test
    void punishmentsForAnUnbridgedPlayerDispatchSilentlyOnTheProxy() {
        VelocityBridge.install(BridgeSessions.NONE);

        try (MockedStatic<LogUtil> log = mockStatic(LogUtil.class)) {
            server.dispatchCommand(console, "ban Notch one", subject);
            server.dispatchCommand(console, "/BAN Notch two", subject);
            server.dispatchCommand(console, "kick Notch", subject);
            server.dispatchCommand(console, "say hi");

            log.verifyNoInteractions();
        }
        verify(proxy.getCommandManager(), times(4)).executeAsync(any(), anyString());
    }

    @Test
    void punishmentFiresCommandExecuteOnAListeningBackend() {
        listensForCommandExecute();
        String verbose = "v".repeat(BridgeFrames.MAX_VERBOSE_CHARS + 100);
        try (MockedStatic<BridgeApiService> api = mockStatic(BridgeApiService.class);
                MockedStatic<LogUtil> log = mockStatic(LogUtil.class)) {
            api.when(() -> BridgeApiService.checkIndex(session, check)).thenReturn(3);

            server.dispatchCommand(console, BAN, subject, check, 4.0, verbose);

            log.verifyNoInteractions();
        }
        assertEquals(
                new CommandExecute(7, 3, 4.0, verbose.substring(0, BridgeFrames.MAX_VERBOSE_CHARS), BAN),
                sentRequest(7));
        verify(proxy.getCommandManager(), never()).executeAsync(any(), anyString());
    }

    @Test
    void cancelledCommandExecuteRunsNothingElse() {
        listensForCommandExecute();
        reply(new Result(1, false));
        try (MockedStatic<BridgeApiService> api = mockStatic(BridgeApiService.class);
                MockedStatic<LogUtil> log = mockStatic(LogUtil.class)) {
            api.when(() -> BridgeApiService.checkIndex(session, check)).thenReturn(3);

            server.dispatchCommand(console, BAN, subject, check, 4.0, "verbose");

            log.verify(
                    () -> LogUtil.info("Command was cancelled by a plugin on survival or did not run there: " + BAN));
            log.verifyNoMoreInteractions();
        }
        verify(session, times(1)).exchange(any(), anyLong());
        verify(proxy.getCommandManager(), never()).executeAsync(any(), anyString());
    }

    @Test
    void unansweredCommandExecuteSaysTheCommandMayStillRun() {
        listensForCommandExecute();
        reply(null);
        try (MockedStatic<BridgeApiService> api = mockStatic(BridgeApiService.class);
                MockedStatic<LogUtil> log = mockStatic(LogUtil.class)) {
            api.when(() -> BridgeApiService.checkIndex(session, check)).thenReturn(3);

            server.dispatchCommand(console, BAN, subject, check, 4.0, "verbose");

            log.verify(() -> LogUtil.warn("No answer from survival within 2 s (the command may still run): " + BAN));
            log.verifyNoMoreInteractions();
        }
        verify(session, times(1)).exchange(any(), anyLong());
    }

    @Test
    void backendWithoutACommandExecuteListenerGetsConsoleExec() {
        when(session.has(ServerCapability.FLAG_VERDICT)).thenReturn(true);
        when(session.listeners()).thenReturn(ListenerKind.FLAG.flag());

        server.dispatchCommand(console, BAN, subject, check, 4.0, "verbose");

        assertEquals(new ConsoleExec(2, BAN), sentRequest(2));
    }

    @Test
    void proxyKnownRootRunsOnTheProxyWithoutTheEvent() {
        listensForCommandExecute();
        when(proxy.getCommandManager().hasCommand("ban")).thenReturn(true);
        when(proxy.getCommandManager().executeAsync(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        server.dispatchCommand(console, BAN, subject, check, 4.0, "verbose");

        verify(session, never()).exchange(any(), anyLong());
        verify(proxy.getCommandManager()).executeAsync(any(), eq(BAN));
    }

    @Test
    void checkUnknownToTheBackendFallsBackToConsoleExec() {
        listensForCommandExecute();
        try (MockedStatic<BridgeApiService> api = mockStatic(BridgeApiService.class);
                MockedStatic<LogUtil> log = mockStatic(LogUtil.class)) {
            api.when(() -> BridgeApiService.checkIndex(session, check)).thenReturn(-1);

            server.dispatchCommand(console, BAN, subject, check, 4.0, "verbose");

            log.verifyNoInteractions();
        }
        assertEquals(new ConsoleExec(4, BAN), sentRequest(4));
    }

    @Test
    void commandExecuteTooLargeFallsBackToConsoleExec() {
        listensForCommandExecute();
        String command = "ban Notch " + "x".repeat(20000);
        String verbose = "é".repeat(BridgeFrames.MAX_VERBOSE_CHARS);
        try (MockedStatic<BridgeApiService> api = mockStatic(BridgeApiService.class);
                MockedStatic<LogUtil> log = mockStatic(LogUtil.class)) {
            api.when(() -> BridgeApiService.checkIndex(session, check)).thenReturn(3);

            server.dispatchCommand(console, command, subject, check, 4.0, verbose);

            log.verify(() -> LogUtil.warn(
                    "Command is too long for CommandExecuteEvent on survival; dispatching it without the event"));
            log.verifyNoMoreInteractions();
        }
        assertEquals(new ConsoleExec(6, command), sentRequest(6));
    }
}
