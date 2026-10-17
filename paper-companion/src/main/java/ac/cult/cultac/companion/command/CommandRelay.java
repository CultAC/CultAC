package ac.cult.cultac.companion.command;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Command;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CommandDone;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CommandOutput;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Suggest;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Suggestions;
import ac.cult.cultac.companion.bridge.CompanionBridge;
import ac.cult.cultac.companion.bridge.CompanionMessageHandler;
import ac.cult.cultac.companion.bridge.CompanionSession;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jetbrains.annotations.Nullable;

/** Forwards commands to the proxy and relays their output back to the sender. */
final class CommandRelay implements CompanionMessageHandler {
    static final int MAX_PENDING = 256;
    /** In-flight requests one player may hold; the console is bounded only by {@link #MAX_PENDING}. */
    static final int MAX_PENDING_PER_PLAYER = 8;
    /** Slots of {@link #MAX_PENDING} that only the console may take. */
    static final int CONSOLE_RESERVE = 32;
    /** A request id is forgotten this long after it was sent; async output arriving later is dropped. */
    static final long EXPIRY_MILLIS = 30_000;

    static final Component UNREACHABLE = Component.text(
            "CultAC proxy not reachable: no player with an active bridge is online on this server", NamedTextColor.RED);
    static final Component BUSY =
            Component.text("Too many CultAC commands are waiting on the proxy; try again shortly", NamedTextColor.RED);

    private record Pending(
            @Nullable UUID sender,
            CompanionSession session,
            Consumer<Component> output,
            CompletableFuture<Boolean> done,
            long expiresAt) {}

    private record PendingSuggest(CompanionSession session, CompletableFuture<List<String>> suggestions) {}

    private final CompanionBridge bridge;
    private final LongSupplier clockMillis;
    private final Logger logger;
    private final boolean debug;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    /** Requests past COMMAND_DONE whose late output still reaches the sender until they expire. */
    private final Map<Integer, Pending> finished = new ConcurrentHashMap<>();

    private final Map<Integer, PendingSuggest> suggests = new ConcurrentHashMap<>();

    CommandRelay(CompanionBridge bridge, LongSupplier clockMillis, Logger logger, boolean debug) {
        this.bridge = bridge;
        this.clockMillis = clockMillis;
        this.logger = logger;
        this.debug = debug;
    }

    /**
     * Sends {@code line} to the proxy as {@code sender} (null for the console) over the sender's own session, else any
     * session. Completes true on COMMAND_DONE and false when the proxy is unreachable or never finishes.
     */
    CompletableFuture<Boolean> forward(
            @Nullable UUID sender, String senderName, String line, Consumer<Component> output) {
        return forward(sender, senderName, line, output, output);
    }

    /**
     * {@link #forward(UUID, String, String, Consumer)} with the reason a command cannot be sent going to
     * {@code refused}, on the calling thread before this returns; the proxy's output goes to {@code output}.
     */
    CompletableFuture<Boolean> forward(
            @Nullable UUID sender,
            String senderName,
            String line,
            Consumer<Component> output,
            Consumer<Component> refused) {
        CompanionSession session = sender == null ? null : bridge.session(sender);
        if (session == null) session = bridge.anySession();
        if (session == null) {
            refused.accept(UNREACHABLE);
            return CompletableFuture.completedFuture(false);
        }
        if (pending.size() >= (sender == null ? MAX_PENDING : MAX_PENDING - CONSOLE_RESERVE)
                || (sender != null && pendingFor(sender) >= MAX_PENDING_PER_PLAYER)) {
            refused.accept(BUSY);
            return CompletableFuture.completedFuture(false);
        }
        int requestId = nextId();
        var request = new Pending(
                sender, session, output, new CompletableFuture<>(), clockMillis.getAsLong() + EXPIRY_MILLIS);
        pending.put(requestId, request);
        session.send(new Command(requestId, sender == null ? Command.CONSOLE : sender, senderName, line));
        return request.done();
    }

    /** Console tab completion; blocks up to {@code waitMillis}, null when the proxy is unreachable or slow. */
    @Nullable
    List<String> suggest(String line, long waitMillis) {
        CompanionSession session = bridge.anySession();
        if (session == null) return null;
        int requestId = nextId();
        var request = new PendingSuggest(session, new CompletableFuture<>());
        suggests.put(requestId, request);
        try {
            session.send(new Suggest(requestId, Command.CONSOLE, line));
            return request.suggestions().get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException unanswered) {
            return null;
        } finally {
            suggests.remove(requestId);
        }
    }

    @Override
    public void onMessage(CompanionSession session, BridgeMessage message) {
        switch (message) {
            case CommandOutput output -> {
                Pending request = pending.get(output.requestId());
                if (request == null) request = finished.get(output.requestId());
                if (request == null || request.session() != session) return;
                Component component;
                try {
                    component = GsonComponentSerializer.gson().deserialize(output.componentJson());
                } catch (RuntimeException malformed) {
                    if (debug) logger.info("[debug] Dropping unreadable command output: " + malformed.getMessage());
                    return;
                }
                request.output().accept(component);
            }
            case CommandDone done -> {
                Pending request = pending.get(done.requestId());
                if (request == null || request.session() != session) return;
                if (pending.remove(done.requestId(), request)
                        && finished.size() < (request.sender() == null ? MAX_PENDING : MAX_PENDING - CONSOLE_RESERVE)) {
                    finished.put(done.requestId(), request);
                }
                request.done().complete(true);
            }
            case Suggestions offered -> {
                PendingSuggest request = suggests.get(offered.requestId());
                if (request != null && request.session() == session)
                    request.suggestions().complete(offered.suggestions());
            }
            default -> {}
        }
    }

    @Override
    public void onClosed(CompanionSession session) {
        pending.entrySet().removeIf(entry -> {
            if (entry.getValue().session() != session) return false;
            entry.getValue().done().complete(false);
            return true;
        });
        finished.values().removeIf(request -> request.session() == session);
        suggests.values().removeIf(request -> request.session() == session);
    }

    void closeAll() {
        pending.values().forEach(request -> request.done().complete(false));
        pending.clear();
        finished.clear();
        suggests.clear();
    }

    private int pendingFor(UUID sender) {
        int count = 0;
        for (Pending request : pending.values()) if (sender.equals(request.sender())) count++;
        return count;
    }

    /** Forgets every request past its deadline; a pending one completes false. */
    void sweep() {
        long now = clockMillis.getAsLong();
        pending.entrySet().removeIf(entry -> {
            if (now - entry.getValue().expiresAt() < 0) return false;
            entry.getValue().done().complete(false);
            return true;
        });
        finished.values().removeIf(request -> now - request.expiresAt() >= 0);
    }

    private int nextId() {
        int id;
        do {
            id = ids.incrementAndGet() & Integer.MAX_VALUE;
        } while (id == 0);
        return id;
    }
}
