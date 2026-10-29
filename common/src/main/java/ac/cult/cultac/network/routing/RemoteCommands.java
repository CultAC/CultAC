package ac.cult.cultac.network.routing;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.cult.reload.api.HostContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jetbrains.annotations.Nullable;

/**
 * CultAC commands run for a sender on another instance, or on another engine generation of this one. {@value #CMD}
 * asks for a command line to run as the sender, and {@value #DONE} answers once it ran, or why it could not, with the
 * components the command sent as {@code output}, at most {@value #MAX_COMPONENTS} of them or {@value #MAX_OUTPUT_BYTES}
 * bytes, and {@code cut} when the output stopped at that cap. Of an instance's generations, the one holding the target
 * player runs it, else the current one, and only the first of them to claim the request. A command still running after
 * {@value #RUN_MILLIS} ms answers with the output it sent so far; a request without an answer after
 * {@value #ANSWER_MILLIS} ms fails.
 */
final class RemoteCommands {
    static final String CMD = "cmd";
    static final String DONE = "cmd.done";
    static final String LEFT = "left";
    /** The host-shared key of the request ids already taken by a generation here, with when. */
    static final String CLAIMS = "cultac.network.command-claims";

    static final long RUN_MILLIS = 20_000;
    static final long ANSWER_MILLIS = 30_000;
    static final int MAX_COMPONENTS = 200;
    static final int MAX_OUTPUT_BYTES = 256 * 1024;

    /** How this instance runs its CultAC commands. */
    interface Commands {
        /** Runs {@code line} as {@code sender}; completes once it ran, normally or not. */
        CompletionStage<?> execute(Sender sender, String line);

        Set<String> permissionNodes();

        /** Runs a received command's {@code task} off the network thread. */
        default void dispatch(Runnable task) {
            task.run();
        }
    }

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
    private final SharedClaims claims;
    private final Commands commands;
    /** Guarded by {@code this}. */
    private boolean closed;

    /** Commands for {@code service}; {@code host} shares the claimed request ids with the other generations. */
    RemoteCommands(NetworkService service, LongSupplier clock, Commands commands, @Nullable HostContext host) {
        this.service = service;
        this.clock = clock;
        this.commands = commands;
        this.claims = new SharedClaims(host, CLAIMS, clock);
    }

    void register() {
        service.on(CMD, this::received);
        service.on(DONE, envelope -> done(envelope.body()));
    }

    /** Runs {@code line} here as {@code sender}, once {@code where} is still online here. */
    CompletableFuture<Boolean> runHere(Sender sender, String line, NetworkPlayer where) {
        if (!service.online(where.uuid())) {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender, "player-not-found", "%prefix% &cPlayer is exempt or offline!"));
            return CompletableFuture.completedFuture(false);
        }
        try {
            return commands.execute(sender, line)
                    .handle((ignored, failure) -> true)
                    .toCompletableFuture();
        } catch (RuntimeException unavailable) {
            sender.sendMessage(failed(sender, where.instance(), unavailable.getMessage()));
            return CompletableFuture.completedFuture(false);
        }
    }

    CompletableFuture<Boolean> forward(Sender sender, String line, NetworkPlayer where) {
        UUID id = UUID.randomUUID();
        Request request = new Request(sender, where, clock.getAsLong());
        boolean stopped;
        synchronized (this) {
            pending.put(id, request);
            stopped = closed;
        }
        if (stopped) {
            request.fail(MessageUtil.getParsedComponent(
                    sender,
                    "network-command-stopped",
                    "&cThe command could not be forwarded to %instance%: network routing stopped.",
                    Map.of("instance", where.instance())));
            pending.remove(id, request);
            return request.result;
        }
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
        if (!service.send(where.instance(), CMD, body)) {
            request.fail(MessageUtil.getParsedComponent(
                    sender,
                    "network-command-not-forwarded",
                    "&cThe command could not be forwarded to %instance%.",
                    Map.of("instance", where.instance())));
            pending.remove(id, request);
        }
        return request.result;
    }

    void sweep() {
        sweep(false);
    }

    /** Ends every request as if its time ran out and refuses new ones; the service is stopping. */
    void close() {
        synchronized (this) {
            closed = true;
        }
        sweep(true);
    }

    private void sweep(boolean all) {
        long now = clock.getAsLong();
        for (var entry : pending.entrySet()) {
            if (entry.getValue().expire(now, all)) pending.remove(entry.getKey(), entry.getValue());
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

    private void received(Envelope envelope) {
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
        if (!service.answers(target) || !claims.claim(id.toString())) return;
        Relay relay = new Relay(envelope.from(), id);
        try {
            if (!service.online(target)) {
                relay.done(false, LEFT);
                return;
            }
            RemoteCommandSource source = new RemoteCommandSource(
                    senderId,
                    senderName,
                    console,
                    granted,
                    commands.permissionNodes(),
                    relay,
                    service.remoteSenderBase());
            commands.dispatch(() -> {
                try {
                    commands.execute(source, CloudCommandService.onRegisteredRoot(line))
                            .handle((ignored, failure) -> null)
                            .toCompletableFuture()
                            .completeOnTimeout(null, RUN_MILLIS, TimeUnit.MILLISECONDS)
                            .thenRun(() -> {
                                source.done();
                                boolean left = source.left();
                                relay.done(!left, left ? LEFT : null);
                            });
                } catch (RuntimeException unavailable) {
                    relay.done(false, String.valueOf(unavailable.getMessage()));
                }
            });
        } catch (RuntimeException failure) {
            relay.done(false, String.valueOf(failure.getMessage()));
        }
    }

    private void done(JsonObject body) {
        UUID id;
        boolean ok;
        String reason;
        List<String> output = new ArrayList<>();
        boolean cut;
        try {
            id = UUID.fromString(body.get("id").getAsString());
            ok = body.get("ok").getAsBoolean();
            JsonElement why = body.get("reason");
            reason = why == null || why.isJsonNull() ? null : why.getAsString();
            if (body.get("output") instanceof JsonArray lines) {
                for (JsonElement line : lines) output.add(line.getAsString());
            }
            cut = body.has("cut") && body.get("cut").getAsBoolean();
        } catch (RuntimeException malformed) {
            return;
        }
        Request request = pending.get(id);
        if (request == null || !request.finish()) return;
        pending.remove(id, request);
        for (String json : output) {
            try {
                request.sender.sendMessage(GsonComponentSerializer.gson().deserialize(json));
            } catch (RuntimeException malformed) {
                // One unreadable component; the rest still shows.
            }
        }
        if (cut)
            request.sender.sendMessage(MessageUtil.getParsedComponent(
                    request.sender,
                    "network-command-output-cut",
                    "&cThe rest of the output from %instance% was cut: it is over %lines% lines or %kb% KB.",
                    Map.of(
                            "instance",
                            request.where.instance(),
                            "lines",
                            String.valueOf(MAX_COMPONENTS),
                            "kb",
                            String.valueOf(MAX_OUTPUT_BYTES / 1024))));
        if (!ok) {
            NetworkPlayer where = request.where;
            request.sender.sendMessage(
                    LEFT.equals(reason)
                            ? MessageUtil.getParsedComponent(
                                    request.sender,
                                    "network-command-target-left",
                                    "&c%player% is no longer on %instance%.",
                                    Map.of("player", where.name(), "instance", where.instance()))
                            : failed(request.sender, where.instance(), reason));
        }
        request.result.complete(ok);
    }

    private static Component failed(Sender sender, String instance, @Nullable String reason) {
        return MessageUtil.getParsedComponent(
                sender,
                "network-command-failed",
                "&c%instance% could not run the command: %reason%",
                Map.of("instance", instance, "reason", Objects.requireNonNullElse(reason, "?")));
    }

    /** The receiving side's output, kept for the answer up to the cap. */
    private final class Relay implements Consumer<Component> {
        private final String reply;
        private final UUID id;
        private final JsonArray output = new JsonArray();
        private long bytes;
        private boolean cut;
        private boolean answered;

        Relay(String reply, UUID id) {
            this.reply = reply;
            this.id = id;
        }

        @Override
        public synchronized void accept(Component component) {
            if (answered || cut) return;
            if (output.size() >= MAX_COMPONENTS) {
                cut = true;
                return;
            }
            JsonPrimitive json = new JsonPrimitive(GsonComponentSerializer.gson().serialize(component));
            int size = EnvelopeCodec.utf8Length(json.toString());
            if (bytes + size > MAX_OUTPUT_BYTES) {
                cut = true;
                return;
            }
            bytes += size;
            output.add(json);
        }

        /** Answers with {@value #DONE} and the output kept, once; output after it is dropped. */
        synchronized void done(boolean ok, @Nullable String reason) {
            if (answered) return;
            answered = true;
            JsonObject body = new JsonObject();
            body.addProperty("id", id.toString());
            body.addProperty("ok", ok);
            if (reason != null) body.addProperty("reason", reason);
            body.add("output", output);
            if (cut) body.addProperty("cut", true);
            service.send(reply, DONE, body);
        }
    }

    /** A forwarded command awaiting its answer; it ends once, under its lock. */
    private final class Request {
        final Sender sender;
        final NetworkPlayer where;
        final long sent;
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        private boolean finished;

        Request(Sender sender, NetworkPlayer where, long sent) {
            this.sender = sender;
            this.where = where;
            this.sent = sent;
        }

        /** Takes the answer; false once none is awaited. */
        synchronized boolean finish() {
            if (finished) return false;
            finished = true;
            return true;
        }

        /** Fails the request unless it was answered. */
        synchronized void fail(Component why) {
            if (finished) return;
            finished = true;
            sender.sendMessage(why);
            result.complete(false);
        }

        /** Fails the request once its time ran out at {@code now}, or at once for {@code all}; true once it is over. */
        synchronized boolean expire(long now, boolean all) {
            if (finished) return true;
            if (!all && now - sent < ANSWER_MILLIS) return false;
            finished = true;
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender,
                    "network-command-no-answer",
                    "&c%instance% did not answer.",
                    Map.of("instance", where.instance())));
            result.complete(false);
            return true;
        }
    }
}
