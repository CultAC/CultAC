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
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jetbrains.annotations.Nullable;

/**
 * CultAC commands run for a sender on another instance, or on another engine generation of this one. {@value #CMD}
 * asks for a command line to run as the sender, {@value #OUT} carries each component the command sends back, numbered
 * from 0 by {@code seq}, and {@value #DONE} says it ran or why it could not, with {@code count}, how many {@value #OUT}
 * went before it, and {@code cut} when the output stopped at the cap. Of an instance's generations, the one holding the
 * target player runs it, else the current one, and only the first of them to claim the request. The sender hears the
 * output in {@code seq} order, at most {@value #MAX_COMPONENTS} components or {@value #MAX_OUTPUT_BYTES} bytes of it,
 * until {@value #LINGER_MILLIS} ms after {@value #DONE}, and is told when part of it never arrived. A request with
 * neither output nor an answer after {@value #ANSWER_MILLIS} ms fails; one whose output went quiet for
 * {@value #QUIET_MILLIS} ms with no answer counts as run.
 */
final class RemoteCommands {
    static final String CMD = "cmd";
    static final String OUT = "cmd.out";
    static final String DONE = "cmd.done";
    static final String LEFT = "left";
    /** The host-shared key of the request ids already taken by a generation here, with when. */
    static final String CLAIMS = "cultac.network.command-claims";

    static final long ANSWER_MILLIS = 10_000;
    static final long QUIET_MILLIS = 60_000;
    static final long LINGER_MILLIS = RemoteCommandSource.LINGER_MILLIS;
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
        service.on(OUT, envelope -> output(envelope.body()));
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
                    clock,
                    service.remoteSenderBase());
            commands.dispatch(() -> {
                try {
                    commands.execute(source, CloudCommandService.onRegisteredRoot(line))
                            .whenComplete((ignored, failure) -> {
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
        if (request != null) request.output(seq, component, clock.getAsLong());
    }

    private void done(JsonObject body) {
        Request request;
        boolean ok;
        String reason;
        int count;
        boolean cut;
        try {
            request = pending.get(UUID.fromString(body.get("id").getAsString()));
            ok = body.get("ok").getAsBoolean();
            JsonElement why = body.get("reason");
            reason = why == null || why.isJsonNull() ? null : why.getAsString();
            count = body.get("count").getAsInt();
            cut = body.has("cut") && body.get("cut").getAsBoolean();
        } catch (RuntimeException malformed) {
            return;
        }
        if (request == null || !request.finish(clock.getAsLong(), count, cut)) return;
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

    /** The receiving side's output, numbered without gaps; a component over the byte cap is replaced by a note. */
    private final class Relay implements Consumer<Component> {
        private final String reply;
        private final UUID id;
        private int seq;
        private long bytes;
        private boolean cut;

        Relay(String reply, UUID id) {
            this.reply = reply;
            this.id = id;
        }

        @Override
        public synchronized void accept(Component component) {
            if (cut) return;
            if (seq >= MAX_COMPONENTS) {
                cut = true;
                return;
            }
            String json = GsonComponentSerializer.gson().serialize(component);
            if (EnvelopeCodec.utf8Length(json) > MAX_OUTPUT_BYTES)
                json = GsonComponentSerializer.gson()
                        .serialize(MessageUtil.getParsedComponent(
                                null,
                                "network-command-line-too-large",
                                "&7One line of output was too large to relay.",
                                Map.of()));
            int size = EnvelopeCodec.utf8Length(json);
            if (bytes + size > MAX_OUTPUT_BYTES) {
                cut = true;
                return;
            }
            bytes += size;
            service.send(reply, OUT, body(json));
            seq++;
        }

        /** Answers with {@value #DONE}, counting the components sent before it. */
        synchronized void done(boolean ok, @Nullable String reason) {
            JsonObject body = new JsonObject();
            body.addProperty("id", id.toString());
            body.addProperty("ok", ok);
            if (reason != null) body.addProperty("reason", reason);
            body.addProperty("count", seq);
            if (cut) body.addProperty("cut", true);
            service.send(reply, DONE, body);
        }

        private JsonObject body(String component) {
            JsonObject body = new JsonObject();
            body.addProperty("id", id.toString());
            body.addProperty("seq", seq);
            body.add("component", JsonParser.parseString(component));
            return body;
        }
    }

    /** A forwarded command awaiting its answer, then late output; it ends once, under its lock. */
    private final class Request {
        final Sender sender;
        final NetworkPlayer where;
        final long sent;
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        private final TreeMap<Integer, String> waiting = new TreeMap<>();
        private int next;
        /** The bytes of the output taken, relayed or waiting. */
        private long bytes;
        /** The first {@code seq} past the byte cap; none from it on is taken. */
        private int cutAt = Integer.MAX_VALUE;

        private boolean cut;
        private boolean heard;
        private long heardAt;
        private boolean finished;
        private long finishedAt;
        private int count;
        private boolean cutThere;
        private boolean over;

        Request(Sender sender, NetworkPlayer where, long sent) {
            this.sender = sender;
            this.where = where;
            this.sent = sent;
        }

        synchronized void output(int seq, String component, long now) {
            if (over || seq < next || waiting.containsKey(seq)) return;
            if (finished && now - finishedAt > LINGER_MILLIS) return;
            heard = true;
            heardAt = now;
            if (seq >= MAX_COMPONENTS) cutAt = Math.min(cutAt, MAX_COMPONENTS);
            if (seq < cutAt) {
                bytes += EnvelopeCodec.utf8Length(component);
                waiting.put(seq, component);
                while (bytes > MAX_OUTPUT_BYTES) {
                    var last = waiting.pollLastEntry();
                    bytes -= EnvelopeCodec.utf8Length(last.getValue());
                    cutAt = last.getKey();
                }
            }
            drain();
        }

        /** Takes the answer, which says {@code count} components went before it; false once none is awaited. */
        synchronized boolean finish(long now, int count, boolean cut) {
            if (finished) return false;
            finished = true;
            finishedAt = now;
            this.count = count;
            cutThere = cut;
            drain();
            return true;
        }

        /** Fails the request unless it was answered or is over. */
        synchronized void fail(Component why) {
            if (finished) return;
            finished = true;
            over = true;
            sender.sendMessage(why);
            result.complete(false);
        }

        /** Ends the request once its time ran out at {@code now}, or at once for {@code all}; true once it is over. */
        synchronized boolean expire(long now, boolean all) {
            if (over) return true;
            if (finished) {
                if (!all && now - finishedAt <= LINGER_MILLIS) return false;
                boolean lost = !waiting.isEmpty() || next < Math.min(count, cutAt);
                flush();
                if (lost && !cut)
                    sender.sendMessage(MessageUtil.getParsedComponent(
                            sender,
                            "network-command-output-lost",
                            "&cSome output from %instance% was lost.",
                            Map.of("instance", where.instance())));
                if (cutThere || cutAt != Integer.MAX_VALUE) cut();
            } else if (heard) {
                if (!all && now - heardAt < QUIET_MILLIS) return false;
                flush();
                if (cutAt != Integer.MAX_VALUE) cut();
                sender.sendMessage(MessageUtil.getParsedComponent(
                        sender,
                        "network-command-unfinished",
                        "&7%instance% did not report that the command finished; its output may be incomplete.",
                        Map.of("instance", where.instance())));
                finished = true;
                result.complete(true);
            } else {
                if (!all && now - sent < ANSWER_MILLIS) return false;
                sender.sendMessage(MessageUtil.getParsedComponent(
                        sender,
                        "network-command-no-answer",
                        "&c%instance% did not answer.",
                        Map.of("instance", where.instance())));
                finished = true;
                result.complete(false);
            }
            over = true;
            return true;
        }

        private void drain() {
            while (!waiting.isEmpty() && waiting.firstKey() == next) {
                relay(waiting.pollFirstEntry().getValue());
                next++;
            }
            if (next >= cutAt || cutThere && next >= count) cut();
        }

        private void flush() {
            while (!waiting.isEmpty()) {
                var first = waiting.pollFirstEntry();
                relay(first.getValue());
                next = first.getKey() + 1;
            }
        }

        private void relay(String json) {
            if (cut) return;
            Component component;
            try {
                component = GsonComponentSerializer.gson().deserialize(json);
            } catch (RuntimeException malformed) {
                return;
            }
            sender.sendMessage(component);
        }

        private void cut() {
            if (cut) return;
            cut = true;
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender,
                    "network-command-output-cut",
                    "&cThe rest of the output from %instance% was cut: it is over %lines% lines or %kb% KB.",
                    Map.of(
                            "instance",
                            where.instance(),
                            "lines",
                            String.valueOf(MAX_COMPONENTS),
                            "kb",
                            String.valueOf(MAX_OUTPUT_BYTES / 1024))));
        }
    }
}
