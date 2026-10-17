package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CommandExecute;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ConsoleExec;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Result;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ValidationBlock;
import ac.cult.cultac.bridge.protocol.ListenerKind;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.platform.api.PlatformServer;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.platform.velocity.bridge.BridgeFrames;
import ac.cult.cultac.platform.velocity.bridge.api.BridgeApiService;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.grim.grimac.api.AbstractCheck;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.ToDoubleFunction;
import org.jetbrains.annotations.Nullable;

final class VelocityServer implements PlatformServer {
    private static final long REQUEST_TIMEOUT_MILLIS = 2000;

    private final ProxyServer proxy;
    private final VelocitySenders senders;
    private final ToDoubleFunction<UUID> backendTps;

    /** @param backendTps the tick rate of a player's backend, or NaN when it is not bridged */
    VelocityServer(ProxyServer proxy, VelocitySenders senders, ToDoubleFunction<UUID> backendTps) {
        this.proxy = proxy;
        this.senders = senders;
        this.backendTps = backendTps;
    }

    @Override
    public String getPlatformImplementationString() {
        return proxy.getVersion().getName() + " " + proxy.getVersion().getVersion();
    }

    @Override
    public void dispatchCommand(Sender sender, String command) {
        proxy.getCommandManager().executeAsync(senders.unwrap(sender), command);
    }

    /**
     * Commands the proxy does not know run on the subject's backend console when its companion allows it; without such
     * a companion every command dispatches on the proxy as on an unbridged proxy, silently.
     */
    void dispatchCommand(Sender sender, String command, @Nullable PlatformPlayer subject) {
        var session = subject instanceof VelocityPlayer player ? player.bridge(ServerCapability.CONSOLE_EXEC) : null;
        if (session == null) {
            dispatchCommand(sender, command);
            return;
        }
        String root = root(command);
        if (!proxy.getCommandManager().hasCommand(root)) {
            if (BridgeFrames.fits(new ConsoleExec(BridgeFrames.WORST_CASE_REQUEST_ID, command))) {
                reportBackend(
                        command,
                        session.serverName(),
                        session.exchange(requestId -> new ConsoleExec(requestId, command), REQUEST_TIMEOUT_MILLIS),
                        () -> LogUtil.warn("Command did not run on " + session.serverName() + ": " + command));
                return;
            }
            LogUtil.error("Punishment command did not run: it is too long to forward to " + session.serverName()
                    + " and the proxy has no /" + root + " command: " + command);
            return;
        }
        dispatchCommand(sender, command);
    }

    /**
     * Punishment commands bound for a backend that has CommandExecuteEvent listeners fire that event there first, so a
     * backend plugin can cancel them; otherwise they take the {@link ConsoleExec} path.
     */
    @Override
    public void dispatchCommand(
            Sender sender,
            String command,
            @Nullable PlatformPlayer subject,
            AbstractCheck check,
            double violations,
            String verbose) {
        var session = subject instanceof VelocityPlayer player ? player.bridge(ServerCapability.CONSOLE_EXEC) : null;
        if (session != null
                && session.has(ServerCapability.FLAG_VERDICT)
                && ListenerKind.has(session.listeners(), ListenerKind.COMMAND_EXECUTE)
                && !proxy.getCommandManager().hasCommand(root(command))) {
            int checkIdx = BridgeApiService.checkIndex(session, check);
            String text = BridgeFrames.capVerbose(verbose);
            if (checkIdx >= 0
                    && BridgeFrames.fits(new CommandExecute(
                            BridgeFrames.WORST_CASE_REQUEST_ID, checkIdx, violations, text, command))) {
                reportBackend(
                        command,
                        session.serverName(),
                        session.exchange(
                                requestId -> new CommandExecute(requestId, checkIdx, violations, text, command),
                                REQUEST_TIMEOUT_MILLIS),
                        () -> LogUtil.info("Command was cancelled by a plugin on " + session.serverName()
                                + " or did not run there: " + command));
                return;
            }
            if (checkIdx >= 0) {
                LogUtil.warn("Command is too long for CommandExecuteEvent on " + session.serverName()
                        + "; dispatching it without the event");
            }
        }
        dispatchCommand(sender, command, subject);
    }

    private static String root(String command) {
        String line = command.startsWith("/") ? command.substring(1) : command;
        int space = line.indexOf(' ');
        return (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    /** A null reply is a timeout, after which the backend may still run the command. */
    private static void reportBackend(
            String command, String server, CompletableFuture<@Nullable BridgeMessage> reply, Runnable refused) {
        reply.whenComplete((answer, failure) -> {
            if (failure != null) {
                LogUtil.warn("Command failed on " + server + ": " + command, failure);
            } else if (answer == null) {
                LogUtil.warn("No answer from " + server + " within " + REQUEST_TIMEOUT_MILLIS / 1000
                        + " s (the command may still run): " + command);
            } else if (!(answer instanceof Result result && result.success())) {
                refused.run();
            }
        });
    }

    @Override
    public Sender getConsoleSender() {
        return senders.wrap(proxy.getConsoleCommandSource());
    }

    @Override
    public void registerOutgoingPluginChannel(String name) {
        proxy.getChannelRegistrar().register(MinecraftChannelIdentifier.from(name));
    }

    @Override
    public boolean isProxyForwardingEnabled() {
        return true;
    }

    /** The mean tick rate measured on connected backends' streams; NaN before any measurement. */
    @Override
    public double getTPS() {
        double total = 0;
        int measured = 0;
        for (var connection :
                ac.cult.cultac.CultAPI.INSTANCE.getNetworkManager().connections()) {
            var ticks = connection.platform().backendTicks();
            double rate = ticks == null ? Double.NaN : ticks.ticksPerSecond();
            if (!Double.isNaN(rate)) {
                total += rate;
                measured++;
            }
        }
        return measured == 0 ? Double.NaN : total / measured;
    }

    /**
     * The tick rate the companion reports for the player's backend, else the estimate from that player's
     * backend stream, else {@link #getTPS()}.
     */
    @Override
    public double getTPS(@Nullable UUID player) {
        if (player != null) {
            double reported = backendTps.applyAsDouble(player);
            if (!Double.isNaN(reported)) return reported;
            CultPlayer cultPlayer = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(player);
            var ticks = cultPlayer == null ? null : cultPlayer.backendTicks();
            double estimated = ticks == null ? Double.NaN : ticks.ticksPerSecond();
            if (!Double.isNaN(estimated)) return estimated;
        }
        return getTPS();
    }

    @Override
    public void forwardAlert(String message) {
        /* AlertManager already reaches every subscribed proxy player. */
    }

    @Override
    public void applyValidationBlock(PlatformPlayer player, int x, int y, int z, String state, Runnable applied) {
        var session =
                player instanceof VelocityPlayer velocity ? velocity.bridge(ServerCapability.VALIDATION_BLOCK) : null;
        if (session == null) throw new UnsupportedOperationException("A proxy cannot mutate server blocks");
        session.request(requestId -> new ValidationBlock(requestId, x, y, z, state, false), REQUEST_TIMEOUT_MILLIS)
                .whenComplete((success, failure) -> {
                    if (Boolean.TRUE.equals(success)) {
                        applied.run();
                    } else {
                        LogUtil.warn("Validation block " + state + " at " + x + "," + y + "," + z
                                + " was not applied on " + session.serverName());
                    }
                });
    }
}
