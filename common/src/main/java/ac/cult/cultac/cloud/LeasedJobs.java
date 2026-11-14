package ac.cult.cultac.cloud;

import ac.cult.cultac.cloud.labels.LabelClient;
import ac.cult.cultac.cloud.transport.CloudConnection.CappedBody;
import ac.cult.cultac.utils.anticheat.LogUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Polls one kind of job the cloud service leases out: fetches the pending ones, claims each this instance should run,
 * runs it and reports the result until the service takes it. Only the poller of the latest configure claims and runs;
 * results already owed are delivered whatever configure comes. The key goes only into the {@code Authorization}
 * header.
 *
 * @param <J> one pending job
 */
public abstract class LeasedJobs<J> {
    public static final long DEFAULT_POLL_SECONDS = 10;
    public static final long MIN_POLL_SECONDS = 5;
    public static final long MAX_POLL_SECONDS = 60;
    /** How long to wait before asking a service without this kind of job again. */
    public static final long ABSENT_POLL_SECONDS = 300;
    /** The share of a poll's wait it may be moved by either way. */
    static final double POLL_SPREAD = 0.2;

    public static final long RESULT_RETRY_SECONDS = 10;
    /** How long a result is retried before it is given up. */
    public static final Duration MAX_OWED = Duration.ofHours(24);
    /** The most jobs one round claims; the rest wait for the next. */
    public static final int MAX_CLAIMS_PER_ROUND = 400;

    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    public static final int MAX_BODY = 256 << 10;
    public static final int MAX_HANDLED = 1000;

    public static final Pattern ID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * How one kind of job is named in logs ({@code label}), routed ({@code /api/v1/<route>/...}) and keyed in the
     * service's answers: {@code plural} holds the pending list, {@code single} the job a won claim answers with.
     */
    public record Kind(String label, String route, String plural, String single) {}

    private final Kind kind;
    private final String base;
    private final String apiKey;
    protected final String serverId;
    private final HttpClient http;
    private final Executor executor;
    private final Generation generation;
    private final Handled handled;
    private final LongFunction<Executor> after;
    /** The kinds of problem already logged by this poller. */
    private final Set<String> logged = ConcurrentHashMap.newKeySet();

    private long chain;

    protected LeasedJobs(
            Kind kind,
            String base,
            String apiKey,
            String serverId,
            HttpClient http,
            Executor executor,
            Generation generation,
            Handled handled,
            LongFunction<Executor> after) {
        this.kind = kind;
        this.base = base;
        this.apiKey = apiKey;
        this.serverId = serverId;
        this.http = http;
        this.executor = executor;
        this.generation = generation;
        this.handled = handled;
        this.after = after;
    }

    /** The pending route's query string, from its {@code ?}. */
    protected abstract String pendingQuery();

    /** A pending job; null when it is not usable. */
    protected abstract @Nullable J parse(JsonObject job);

    protected abstract String id(J job);

    /**
     * For each of this round's jobs, given the pending answer: null when it is not this instance's to claim now, else
     * what to run when its claim is not granted.
     */
    protected abstract Function<J, Runnable> due(JsonObject pending);

    /**
     * Runs a won claim: completes once it {@linkplain #report reported} the result or {@linkplain #release let the
     * job go} unrun.
     */
    protected abstract CompletableFuture<Void> run(J job);

    /** Whether the job a won claim answered with is {@code pending}'s. */
    protected boolean answers(J answered, J pending) {
        return id(answered).equals(id(pending));
    }

    /** Starts polling: the first round within {@link #MIN_POLL_SECONDS}, at a random point. */
    public void start() {
        chain = generation.next();
        after.apply((long) (MIN_POLL_SECONDS * ThreadLocalRandom.current().nextDouble())).execute(this::poll);
    }

    private void poll() {
        if (!generation.latest(chain)) return;
        pollOnce()
                .whenComplete((next, error) -> after.apply(spread(
                                error == null ? next : DEFAULT_POLL_SECONDS,
                                ThreadLocalRandom.current().nextDouble()))
                        .execute(this::poll));
    }

    /**
     * {@code seconds} moved by up to {@link #POLL_SPREAD} of itself either way, at {@code random} in [0, 1), rounded
     * to whole seconds and at least one.
     */
    static long spread(long seconds, double random) {
        return Math.max(1, Math.round(seconds * (1 - POLL_SPREAD + 2 * POLL_SPREAD * random)));
    }

    /** One round: fetches the pending jobs and runs each this instance should; completes with the seconds to wait. */
    public CompletableFuture<Long> pollOnce() {
        return send(request("/api/v1/" + kind.route() + "/pending" + pendingQuery())
                        .GET())
                .handle((reply, error) -> {
                    if (!generation.latest(chain)) return CompletableFuture.completedFuture(DEFAULT_POLL_SECONDS);
                    if (error != null) {
                        problem(
                                "pending " + LabelClient.unanswered(error),
                                kind.label() + ": no answer from the cloud service (" + LabelClient.unanswered(error)
                                        + ")");
                        return CompletableFuture.completedFuture(DEFAULT_POLL_SECONDS);
                    }
                    if (reply.status() == 404) {
                        if (logged.add("absent")) {
                            LogUtil.debug(kind.label() + ": the cloud service has none; asking again every "
                                    + ABSENT_POLL_SECONDS / 60 + " minutes");
                        }
                        return CompletableFuture.completedFuture(ABSENT_POLL_SECONDS);
                    }
                    if (reply.status() != 200) {
                        problem(
                                "pending " + reply.status(),
                                kind.label() + ": the cloud service answered HTTP " + reply.status() + " for pending "
                                        + kind.plural());
                        return CompletableFuture.completedFuture(DEFAULT_POLL_SECONDS);
                    }
                    return round(reply.body());
                })
                .thenCompose(Function.identity());
    }

    private CompletableFuture<Long> round(String body) {
        JsonObject pending;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) throw new JsonParseException("not an object");
            pending = parsed.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException unreadable) {
            problem(
                    "pending body",
                    kind.label() + ": the cloud service's pending " + kind.plural() + " are unreadable");
            return CompletableFuture.completedFuture(DEFAULT_POLL_SECONDS);
        }
        long next =
                Math.clamp(number(pending, "pollSeconds", DEFAULT_POLL_SECONDS), MIN_POLL_SECONDS, MAX_POLL_SECONDS);
        Function<J, Runnable> due = due(pending);
        List<CompletableFuture<Void>> runs = new ArrayList<>();
        JsonElement jobs = pending.get(kind.plural());
        if (jobs != null && jobs.isJsonArray()) {
            for (JsonElement item : jobs.getAsJsonArray()) {
                if (runs.size() >= MAX_CLAIMS_PER_ROUND) break;
                J job = item.isJsonObject() ? parse(item.getAsJsonObject()) : null;
                Runnable[] unclaimed = new Runnable[1];
                if (job != null
                        && generation.whileLatest(chain, () -> (unclaimed[0] = take(job, due)) != null)) {
                    runs.add(claim(job, unclaimed[0]));
                }
            }
        }
        return CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).handle((done, error) -> next);
    }

    /** What {@link #due} gave for {@code job} when it is due and not handled yet; it is handled from here on. */
    private @Nullable Runnable take(J job, Function<J, Runnable> due) {
        if (handled.contains(id(job))) return null;
        Runnable unclaimed = due.apply(job);
        if (unclaimed == null || handled.add(id(job))) return unclaimed;
        unclaimed.run();
        return null;
    }

    private CompletableFuture<Void> claim(J job, Runnable unclaimed) {
        JsonObject body = new JsonObject();
        body.addProperty("server", serverId);
        CompletableFuture<LabelClient.Reply> sent;
        try {
            sent = send(request("/api/v1/" + kind.route() + "/" + id(job) + "/claim")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
        } catch (RuntimeException unsent) {
            unclaimed.run();
            release(job);
            problem(
                    "claim unsent",
                    kind.label() + ": a claim was not sent (" + unsent.getClass().getSimpleName() + ")");
            return CompletableFuture.completedFuture(null);
        }
        return sent
                .handle((reply, error) -> {
                    if (error != null || reply.status() != 200) unclaimed.run();
                    if ((error != null || reply.status() != 200) && !generation.latest(chain)) {
                        release(job);
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    if (error != null) {
                        release(job);
                        problem(
                                "claim " + LabelClient.unanswered(error),
                                kind.label() + ": no answer from the cloud service to a claim ("
                                        + LabelClient.unanswered(error) + ")");
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    if (reply.status() != 200) {
                        release(job);
                        if (reply.status() != 409) {
                            problem(
                                    "claim " + reply.status(),
                                    kind.label() + ": the cloud service answered HTTP " + reply.status()
                                            + " to a claim");
                        }
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    return run(claimed(reply.body(), job));
                })
                .thenCompose(Function.identity());
    }

    /** The job a won claim answered with, else {@code pending}. */
    private J claimed(String body, J pending) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has(kind.single())) return pending;
            JsonElement job = parsed.getAsJsonObject().get(kind.single());
            J answered = job.isJsonObject() ? parse(job.getAsJsonObject()) : null;
            return answered != null && answers(answered, pending) ? answered : pending;
        } catch (JsonParseException | IllegalStateException unreadable) {
            return pending;
        }
    }

    /** Owes the service {@code job}'s result until it takes it; completes after the first attempt. */
    protected final CompletableFuture<Void> report(J job, boolean ok, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("server", serverId);
        body.addProperty("ok", ok);
        body.addProperty("message", message);
        handled.owe(id(job));
        return deliver(id(job), body.toString());
    }

    /** Forgets a claim that was never run, so it can be claimed again once its lease runs out. */
    protected final void release(J job) {
        handled.remove(id(job));
    }

    /**
     * Posts a result, again every {@link #RESULT_RETRY_SECONDS} after no answer or a 5xx, whatever configure comes
     * meanwhile, until the service takes it (200) or refuses it for good (any 4xx), or it is no longer
     * {@linkplain Handled#owing owed}.
     */
    private CompletableFuture<Void> deliver(String id, String body) {
        return send(request("/api/v1/" + kind.route() + "/" + id + "/result")
                        .POST(HttpRequest.BodyPublishers.ofString(body)))
                .handle((reply, error) -> {
                    if (error == null && (reply.status() == 200 || reply.status() / 100 == 4)) {
                        handled.paid(id);
                        if (reply.status() != 200) {
                            problem(
                                    "result " + reply.status(),
                                    kind.label() + ": the cloud service answered HTTP " + reply.status()
                                            + " to a result");
                        }
                        return null;
                    }
                    if (error != null) {
                        problem(
                                "result " + LabelClient.unanswered(error),
                                kind.label() + ": no answer from the cloud service to a result ("
                                        + LabelClient.unanswered(error) + "); retrying every "
                                        + RESULT_RETRY_SECONDS + " s");
                    } else {
                        problem(
                                "result " + reply.status(),
                                kind.label() + ": the cloud service answered HTTP " + reply.status()
                                        + " to a result; retrying every " + RESULT_RETRY_SECONDS + " s");
                    }
                    after.apply(RESULT_RETRY_SECONDS).execute(() -> {
                        if (handled.owing(id)) {
                            deliver(id, body);
                        } else {
                            problem(
                                    "result given up",
                                    kind.label() + ": stopped retrying a result after "
                                            + MAX_OWED.toHours() + " h or " + handled.maxOwed() + " owed");
                        }
                    });
                    return null;
                });
    }

    /** Whether this poller's configure is still the latest. */
    protected final boolean latest() {
        return generation.latest(chain);
    }

    /** {@code action}'s answer while this poller's configure is the latest, no configure between; else false. */
    protected final boolean whileLatest(BooleanSupplier action) {
        return generation.whileLatest(chain, action);
    }

    /** Runs what it is given {@code seconds} from now. */
    protected final Executor after(long seconds) {
        return after.apply(seconds);
    }

    /** Logs {@code message} the first time this poller meets a problem of {@code kind}. */
    protected final void problem(String kind, String message) {
        if (logged.add(kind)) LogUtil.warn(message);
    }

    protected static @Nullable String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    protected static long number(JsonObject object, String key, long fallback) {
        JsonElement value = object.get(key);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) return fallback;
        return value.getAsLong();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
    }

    private CompletableFuture<LabelClient.Reply> send(HttpRequest.Builder request) {
        return CompletableFuture.supplyAsync(
                        () -> http.sendAsync(request.build(), info -> new CappedBody(MAX_BODY)), executor)
                .thenCompose(Function.identity())
                .thenApply(response -> new LabelClient.Reply(response.statusCode(), response.body()));
    }

    /**
     * The ids this instance claimed, the oldest forgotten past {@code max}, and those whose result it still owes, kept
     * until the service takes it, for at most {@link #MAX_OWED} and the {@code max} most recent.
     */
    public static final class Handled {
        private final int max;
        private final LongSupplier nanos;
        private final Map<String, Boolean> ids;
        /** By id, when its result was first owed, in {@link #nanos}. */
        private final Map<String, Long> owed;

        public Handled(int max) {
            this(max, System::nanoTime);
        }

        Handled(int max, LongSupplier nanos) {
            this.max = max;
            this.nanos = nanos;
            ids = new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > max;
                }
            };
            owed = new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > max;
                }
            };
        }

        /** Whether {@code id} was not handled yet; it is from here on. */
        public synchronized boolean add(String id) {
            return !owed.containsKey(id) && ids.putIfAbsent(id, Boolean.TRUE) == null;
        }

        public synchronized boolean contains(String id) {
            return ids.containsKey(id) || owed.containsKey(id);
        }

        /** Forgets a claim that was never run. */
        public synchronized void remove(String id) {
            ids.remove(id);
        }

        public synchronized void owe(String id) {
            owed.putIfAbsent(id, nanos.getAsLong());
        }

        public synchronized void paid(String id) {
            owed.remove(id);
        }

        /** Whether {@code id}'s result is still owed; one owed longer than {@link #MAX_OWED} is forgotten. */
        public synchronized boolean owing(String id) {
            Long since = owed.get(id);
            if (since == null) return false;
            if (nanos.getAsLong() - since <= MAX_OWED.toNanos()) return true;
            owed.remove(id);
            return false;
        }

        int maxOwed() {
            return max;
        }
    }
}
