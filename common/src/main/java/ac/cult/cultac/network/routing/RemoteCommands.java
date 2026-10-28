package ac.cult.cultac.network.routing;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.routing.MessageHandler.Origin;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jetbrains.annotations.Nullable;

/**
 * CultAC commands run for a sender on another instance, or on another engine generation of this one. {@value #CMD}
 * asks for a command line to run as the sender, {@value #OUT} carries each component the command sends back, and
 * {@value #DONE} says it ran or why it could not. Of an instance's generations, the one holding the target player runs
 * it, else the current one. The sender hears the output in order, at most {@value #MAX_COMPONENTS} components or
 * {@value #MAX_OUTPUT_BYTES} bytes of it, until {@value #LINGER_MILLIS} ms after the command completed; a request with
 * neither output nor an answer after {@value #ANSWER_MILLIS} ms fails.
 */
final class RemoteCommands {
    static final String CMD = "cmd";
    static final String OUT = "cmd.out";
    static final String DONE = "cmd.done";
    static final String LEFT = "left";
    static final long ANSWER_MILLIS = 10_000;
    static final long LINGER_MILLIS = RemoteCommandSource.LINGER_MILLIS;
    static final int MAX_COMPONENTS = 200;
    static final int MAX_OUTPUT_BYTES = 256 * 1024;

    /** How this instance runs its CultAC commands. */
    interface Commands {
        /** Runs {@code line} as {@code sender}; completes once it ran, normally or not. */
        CompletionStage<?> execute(Sender sender, String line);

        /** Every permission node the registered commands require. */
        Set<String> permissionNodes();

        /** Runs a received command's {@code task} off the network thread. */
        default void dispatch(Runnable task) {
            task.run();
        }
    }

    /** The plugin's own command manager. */
    static final Commands CULT = new Commands() {
        @Override
        public CompletionStage<?> execute(Sender sender, String line) {
            if (!(CultAPI.INSTANCE.getCommandService() instanceof CloudCommandService commands))
                throw new IllegalStateException("CultAC commands are not loaded");
            return commands.execute(sender, line);
        }

        @Override
        public Set<String> permissionNodes() {
            return CultAPI.INSTANCE.getCommandService() instanceof CloudCommandService commands
                    ? commands.permissionNodes()
                    : Set.of();
        }

        @Override
        public void dispatch(Runnable task) {
            CultAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(CultAPI.INSTANCE.getGrimPlugin(), task);
        }
    };

    private final NetworkService service;
    private final LongSupplier clock;
    private final Map<UUID, Request> pending = new ConcurrentHashMap<>();
    private volatile Commands commands;

    RemoteCommands(NetworkService service, LongSupplier clock, Commands commands) {
        this.service = service;
        this.clock = clock;
        this.commands = commands;
    }

    void register() {
        service.on(CMD, this::received);
        service.on(OUT, (envelope, origin) -> output(envelope.body()));
        service.on(DONE, (envelope, origin) -> done(envelope.body()));
    }

    void commands(Commands commands) {
        this.commands = commands;
    }

    /** Runs {@code line} here as {@code sender}, once {@code where} is still online here. */
    CompletableFuture<Boolean> runHere(Sender sender, String line, NetworkPlayer where) {
        if (service.online(where.uuid())) {
            try {
                return commands.execute(sender, line)
                        .handle((ignored, failure) -> true)
                        .toCompletableFuture();
            } catch (RuntimeException unavailable) {
                // Reported as for a player who is not here.
            }
        }
        sender.sendMessage(
                MessageUtil.getParsedComponent(sender, "player-not-found", "%prefix% &cPlayer is exempt or offline!"));
        return CompletableFuture.completedFuture(false);
    }

    /** Sends {@code line} to {@code where}'s instance and relays what it answers to {@code sender}. */
    CompletableFuture<Boolean> forward(Sender sender, String line, NetworkPlayer where) {
        UUID id = UUID.randomUUID();
        Request request = new Request(sender, where, clock.getAsLong());
        pending.put(id, request);
        JsonObject from = new JsonObject();
        from.addProperty("u", sender.getUniqueId().toString());
        from.addProperty("n", sender.getName());
        from.addProperty("console", sender.isConsole());
        JsonArray granted = new JsonArray();
        for (String node : granted(sender)) granted.add(node);
        JsonObject body = new JsonObject();
        body.addProperty("id", id.toString());
        body.addProperty("line", line);
        body.add("sender", from);
        body.add("granted", granted);
        body.addProperty("target", where.uuid().toString());
        if (!service.send(where.instance(), CMD, body) && pending.remove(id, request)) {
            sender.sendMessage(Component.text(
                    "The command could not be forwarded to " + where.instance() + ".", NamedTextColor.RED));
            request.result.complete(false);
        }
        return request.result;
    }

    /** Fails the requests nothing answered in time and forgets those past their late-output window. */
    void sweep() {
        sweep(false);
    }

    /** Ends every request as if its time ran out; the service is stopping. */
    void close() {
        sweep(true);
        pending.clear();
    }

    private void sweep(boolean all) {
        long now = clock.getAsLong();
        for (var entry : pending.entrySet()) {
            Request request = entry.getValue();
            if (request.expired(now)) {
                pending.remove(entry.getKey(), request);
                continue;
            }
            if ((!all && now - request.sent < ANSWER_MILLIS) || !request.finish(now)) continue;
            if (request.answered()) {
                request.result.complete(true);
                continue;
            }
            pending.remove(entry.getKey(), request);
            request.sender.sendMessage(
                    Component.text(request.where.instance() + " did not answer.", NamedTextColor.RED));
            request.result.complete(false);
        }
    }

    /** The command nodes {@code sender} holds; all of them for the console. */
    private List<String> granted(Sender sender) {
        List<String> granted = new ArrayList<>();
        for (String node : commands.permissionNodes()) {
            if (!RemoteCommandSource.DENIED.contains(node) && (sender.isConsole() || sender.hasPermission(node)))
                granted.add(node);
        }
        Collections.sort(granted);
        return granted;
    }

    private void received(Envelope envelope, Origin origin) {
        JsonObject body = envelope.body();
        UUID id;
        UUID target;
        String line;
        UUID senderId;
        String senderName;
        boolean console;
        List<String> granted = new ArrayList<>();
        try {
            id = UUID.fromString(body.get("id").getAsString());
            target = UUID.fromString(body.get("target").getAsString());
            line = body.get("line").getAsString();
            JsonObject from = body.getAsJsonObject("sender");
            senderId = UUID.fromString(from.get("u").getAsString());
            senderName = from.get("n").getAsString();
            console = from.has("console") && from.get("console").getAsBoolean();
            if (body.get("granted") instanceof JsonArray nodes) {
                for (JsonElement node : nodes) granted.add(node.getAsString());
            }
        } catch (RuntimeException malformed) {
            return;
        }
        if (!service.answers(target)) return;
        String reply = envelope.from();
        if (!service.online(target)) {
            finish(reply, id, false, LEFT);
            return;
        }
        Commands commands = this.commands;
        RemoteCommandSource source = new RemoteCommandSource(
                senderId,
                senderName,
                console,
                granted,
                commands.permissionNodes(),
                new Relay(reply, id),
                clock,
                service.remoteSenderBase());
        commands.dispatch(() -> {
            CompletionStage<?> ran;
            try {
                ran = commands.execute(source, CloudCommandService.onRegisteredRoot(line));
            } catch (RuntimeException unavailable) {
                finish(reply, id, false, String.valueOf(unavailable.getMessage()));
                return;
            }
            ran.whenComplete((ignored, failure) -> {
                source.done();
                boolean left = source.left();
                finish(reply, id, !left, left ? LEFT : null);
            });
        });
    }

    private void finish(String reply, UUID id, boolean ok, @Nullable String reason) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id.toString());
        body.addProperty("ok", ok);
        if (reason != null) body.addProperty("reason", reason);
        service.send(reply, DONE, body);
    }

    private void output(JsonObject body) {
        Request request;
        int seq;
        String component;
        try {
            request = pending.get(UUID.fromString(body.get("id").getAsString()));
            seq = body.get("seq").getAsInt();
            component = body.get("component").toString();
        } catch (RuntimeException malformed) {
            return;
        }
        if (request != null && !request.expired(clock.getAsLong())) request.output(seq, component);
    }

    private void done(JsonObject body) {
        Request request;
        boolean ok;
        String reason;
        try {
            request = pending.get(UUID.fromString(body.get("id").getAsString()));
            ok = body.get("ok").getAsBoolean();
            JsonElement why = body.get("reason");
            reason = why == null || why.isJsonNull() ? null : why.getAsString();
        } catch (RuntimeException malformed) {
            return;
        }
        if (request == null || !request.finish(clock.getAsLong())) return;
        if (!ok) {
            NetworkPlayer where = request.where;
            request.sender.sendMessage(Component.text(
                    LEFT.equals(reason)
                            ? where.name() + " is no longer on " + where.instance() + "."
                            : where.instance() + " could not run the command" + (reason == null ? "." : ": " + reason),
                    NamedTextColor.RED));
        }
        request.result.complete(ok);
    }

    /** The receiving side's output: each component sent back in order, until past the sender's cap. */
    private final class Relay implements Consumer<Component> {
        private final String reply;
        private final UUID id;
        private int seq;
        private long bytes;

        Relay(String reply, UUID id) {
            this.reply = reply;
            this.id = id;
        }

        @Override
        public synchronized void accept(Component component) {
            // One component past the cap is sent: it tells the sender the output was cut.
            if (seq > MAX_COMPONENTS || bytes > MAX_OUTPUT_BYTES) return;
            String json = GsonComponentSerializer.gson().serialize(component);
            bytes += EnvelopeCodec.utf8Length(json);
            JsonObject body = new JsonObject();
            body.addProperty("id", id.toString());
            body.addProperty("seq", seq++);
            body.add("component", JsonParser.parseString(json));
            service.send(reply, OUT, body);
        }
    }

    /** A forwarded command awaiting its answer, then taking late output. */
    private final class Request {
        final Sender sender;
        final NetworkPlayer where;
        final long sent;
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        private final TreeMap<Integer, String> waiting = new TreeMap<>();
        private int next;
        private int relayed;
        private long bytes;
        private boolean cut;
        private boolean answered;
        private boolean finished;
        private long finishedAt;

        Request(Sender sender, NetworkPlayer where, long sent) {
            this.sender = sender;
            this.where = where;
            this.sent = sent;
        }

        synchronized void output(int seq, String component) {
            if (seq < next) return;
            answered = true;
            waiting.put(seq, component);
            drain();
        }

        synchronized boolean answered() {
            return answered;
        }

        /** Ends the wait for the answer; false when it had ended. */
        synchronized boolean finish(long now) {
            if (finished) return false;
            finished = true;
            finishedAt = now;
            drain();
            return true;
        }

        synchronized boolean expired(long now) {
            return finished && now - finishedAt > LINGER_MILLIS;
        }

        /** Relays the waiting output in order: up to a gap while the answer is awaited, across gaps after it. */
        private void drain() {
            while (!waiting.isEmpty()) {
                int first = waiting.firstKey();
                if (first != next && !finished) return;
                relay(waiting.pollFirstEntry().getValue());
                next = first + 1;
            }
        }

        private void relay(String json) {
            if (cut) return;
            int size = EnvelopeCodec.utf8Length(json);
            if (relayed >= MAX_COMPONENTS || bytes + size > MAX_OUTPUT_BYTES) {
                cut = true;
                sender.sendMessage(Component.text(
                        "The rest of the output from " + where.instance() + " was cut: it is over " + MAX_COMPONENTS
                                + " lines or " + MAX_OUTPUT_BYTES / 1024 + " KB.",
                        NamedTextColor.RED));
                return;
            }
            Component component;
            try {
                component = GsonComponentSerializer.gson().deserialize(json);
            } catch (RuntimeException malformed) {
                return;
            }
            relayed++;
            bytes += size;
            sender.sendMessage(component);
        }
    }
}
