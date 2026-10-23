package ac.cult.cultac.platform.velocity.bridge.command;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Command;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CommandDone;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CommandOutput;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Suggest;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Suggestions;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.platform.velocity.bridge.BridgeMessageListener;
import ac.cult.cultac.platform.velocity.bridge.BridgeService;
import ac.cult.cultac.platform.velocity.bridge.BridgeSession;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.Nullable;

/** Runs /cult commands forwarded by a backend companion through the proxy's command manager. */
final class BridgeCommandHandler implements BridgeMessageListener {
    /** Bukkit's wording; the proxy refuses unknown roots and commands the sender lacks permission for alike. */
    static final Component UNKNOWN_COMMAND = Component.text("Unknown command. Type \"/help\" for help.");

    static final Component NOT_CULTAC = Component.text("Only CultAC commands can be forwarded", NamedTextColor.RED);

    static final Component RATE_LIMITED =
            Component.text("Too many CultAC commands from this server; try again in a moment", NamedTextColor.RED);

    /** The loader runs these before CultAC sees them, with the sender's own proxy permissions. */
    private static final List<String> LOADER_COMMANDS = List.of("update", "devswap", "runtimes");

    /** These act with the proxy's cloud key and data, which no backend may reach on behalf of anyone. */
    private static final List<String> CLOUD_COMMANDS = List.of("cloud", "findings", "top", "label");

    private static final Pattern WORD_BREAK = Pattern.compile("[\\p{javaWhitespace}\\p{Z}]+");

    private final CommandManager commands;
    private final Supplier<? extends Collection<String>> roots;
    private final Predicate<String> ownedRoot;
    private final Function<UUID, Player> players;
    private final LongSupplier clock;
    private final Logger logger;
    private volatile boolean closed;

    /** @param ownedRoot whether a lower-case root is still registered by CultAC on the proxy */
    BridgeCommandHandler(
            CommandManager commands,
            Supplier<? extends Collection<String>> roots,
            Predicate<String> ownedRoot,
            Function<UUID, Player> players,
            LongSupplier clock,
            Logger logger) {
        this.commands = commands;
        this.roots = roots;
        this.ownedRoot = ownedRoot;
        this.players = players;
        this.clock = clock;
        this.logger = logger;
    }

    void close() {
        closed = true;
    }

    @Override
    public void onMessage(BridgeSession session, BridgeMessage message) {
        if (closed || !session.has(ServerCapability.COMMANDS)) return;
        switch (message) {
            case Command command -> execute(session, command);
            case Suggest suggest -> suggest(session, suggest);
            default -> {}
        }
    }

    private void execute(BridgeSession session, Command command) {
        int requestId = command.requestId();
        String line = stripSlash(command.line());
        if (!forwardable(line, roots.get(), ownedRoot)) {
            refuse(session, requestId, NOT_CULTAC);
            return;
        }
        CommandSource source;
        RelayCommandSource relay = null;
        if (Command.CONSOLE.equals(command.sender())) {
            relay = new RelayCommandSource(message -> relay(session, requestId, message), clock);
            source = relay;
        } else {
            Player player = connectedPlayer(session, command.sender());
            if (player == null) {
                refuse(
                        session,
                        requestId,
                        Component.text(
                                printable(command.senderName()) + " is not connected to the CultAC proxy on "
                                        + session.serverName() + ".",
                                NamedTextColor.RED));
                return;
            }
            source = player;
        }
        if (!session.admitControl(relay != null)) {
            refuse(session, requestId, RATE_LIMITED);
            return;
        }
        RelayCommandSource lingering = relay;
        commands.executeAsync(source, line).whenComplete((executed, failure) -> {
            if (failure != null) {
                logger.log(
                        Level.WARNING,
                        "Command forwarded from " + session.serverName() + " by " + printable(command.senderName())
                                + " failed: " + printable(line),
                        failure);
            } else if (Boolean.FALSE.equals(executed)) {
                relay(session, requestId, UNKNOWN_COMMAND);
            }
            if (lingering != null) lingering.done();
            session.send(new CommandDone(requestId));
        });
    }

    private void suggest(BridgeSession session, Suggest suggest) {
        int requestId = suggest.requestId();
        String line = stripSlash(suggest.line());
        CommandSource source = Command.CONSOLE.equals(suggest.sender())
                ? new RelayCommandSource(message -> {}, clock)
                : connectedPlayer(session, suggest.sender());
        if (source == null
                || !forwardable(line, roots.get(), ownedRoot)
                || !session.admitControl(source instanceof RelayCommandSource)) {
            session.send(new Suggestions(requestId, List.of()));
            return;
        }
        commands.offerSuggestions(source, line).whenComplete((suggestions, failure) -> {
            List<String> offered = failure != null || suggestions == null ? List.of() : suggestions;
            session.send(new Suggestions(requestId, CommandFrames.suggestions(offered)));
        });
    }

    /** The online proxy player {@code id}, only while connected to the session's backend server. */
    private @Nullable Player connectedPlayer(BridgeSession session, UUID id) {
        Player player = players.apply(id);
        if (player == null) return null;
        String server = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
        return session.serverName().equals(server) ? player : null;
    }

    /**
     * Whether a line without its slash has a CultAC root CultAC still owns on the proxy, no loader or cloud command and
     * no control characters, which Velocity trims before the loader reads its arguments. Words split on any Unicode
     * whitespace or space, as CultAC's command parser skips them.
     */
    static boolean forwardable(String line, Collection<String> roots, Predicate<String> ownedRoot) {
        if (line.chars().anyMatch(Character::isISOControl)) return false;
        String root = root(line);
        if (!roots.contains(root) || !ownedRoot.test(root)) return false;
        String[] words = WORD_BREAK.split(line, 3);
        return words.length < 2
                || (LOADER_COMMANDS.stream().noneMatch(words[1]::equalsIgnoreCase)
                        && CLOUD_COMMANDS.stream().noneMatch(words[1]::equalsIgnoreCase));
    }

    private static String root(String line) {
        int space = line.indexOf(' ');
        return (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    private static void refuse(BridgeSession session, int requestId, Component message) {
        relay(session, requestId, message);
        session.send(new CommandDone(requestId));
    }

    private static void relay(BridgeSession session, int requestId, Component message) {
        for (CommandOutput output : CommandFrames.outputs(requestId, message)) session.send(output);
    }

    private static String printable(String text) {
        return BridgeService.printable(text, BridgeService.MAX_LOGGED_REASON_CHARS);
    }

    private static String stripSlash(String line) {
        return line.startsWith("/") ? line.substring(1) : line;
    }
}
