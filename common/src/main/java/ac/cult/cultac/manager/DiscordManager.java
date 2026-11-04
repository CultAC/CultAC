package ac.cult.cultac.manager;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.config.EnglishDefaults;
import ac.cult.cultac.manager.init.ReloadableInitable;
import ac.cult.cultac.manager.init.start.StartableInitable;
import ac.cult.cultac.manager.init.stop.StoppableInitable;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.AlertDetails;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.cult.cultac.utils.common.arguments.CommonCultArguments;
import ac.cult.cultac.utils.data.webhook.discord.CompiledDiscordTemplate;
import ac.cult.cultac.utils.data.webhook.discord.Embed;
import ac.cult.cultac.utils.data.webhook.discord.EmbedField;
import ac.cult.cultac.utils.data.webhook.discord.EmbedFooter;
import ac.cult.cultac.utils.data.webhook.discord.WebhookMessage;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.config.ConfigManager;
import com.google.gson.JsonParser;
import java.awt.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class DiscordManager implements StartableInitable, ReloadableInitable, StoppableInitable {
    private static final Predicate<String> WEBHOOK_REGEX = Pattern.compile(
                    "^https://(?:canary\\.)?discord\\.com/api(?:/v\\d+)?/webhooks/\\d+/[\\w-]+(\\?thread_id=\\d+)?$")
            .asMatchPredicate();
    private static final Predicate<String> HTTPS_URL_REGEX =
            Pattern.compile("^https://[^/\\s]+/\\S+$").asMatchPredicate();
    private static final Duration timeout = Duration.ofMillis(CommonCultArguments.URL_TIMEOUT.value());
    private static final HttpClient client = RuntimeEnvironment.http(timeout);
    private static final WebhookQueue queue =
            new WebhookQueue(DiscordManager::post, System::currentTimeMillis, LogUtil::getLogger);
    private static final AtomicBoolean taskStarted = new AtomicBoolean();
    static final String DEFAULT_TITLE = "%player% has been detected for %check% [%violations%]";
    static final String DEFAULT_FOOTER_TEXT = "v%cult_version%";
    private URI url;
    private int embedColor;
    private CompiledDiscordTemplate compiledContent;
    private char backtickReplacement = '\u02CB';
    private CompiledDiscordTemplate compiledTitle = CompiledDiscordTemplate.compile("");
    private boolean includeTimestamp;
    private boolean includeVerbose;
    private boolean includeState;
    private @Nullable String embedImageUrl;
    private @Nullable String embedThumbnailUrl;
    private @Nullable String embedFooterUrl;
    private String embedFooterText = "";

    private static final Pattern URL_PATTERN = Pattern.compile(
            "^https?://(?:www\\.)?[-a-z0-9@:%._+~#=]{1,256}\\.[a-z0-9()]{1,6}\\b[-a-z0-9()@:%_+.~#?&/=]*$",
            Pattern.CASE_INSENSITIVE);

    private static String validatedConfigURL(String configPath, String defaultURL) {
        String url = CultAPI.INSTANCE.getConfigManager().getConfig().getStringElse(configPath, defaultURL);
        if (url == null || url.isBlank()) return null;
        if (URL_PATTERN.matcher(url).matches()) {
            return url;
        } else {
            LogUtil.warn("Invalid embed url for config path " + configPath + ": " + url);
            return defaultURL;
        }
    }

    @Override
    public void start() {
        reload();
    }

    @Override
    public void reload() {
        queue.forgetRejections();
        try {
            // These fields are not volatile: an HTTP thread may briefly read the previous config after a reload.
            ConfigManager config = CultAPI.INSTANCE.getConfigManager().getConfig();
            if (!config.getBooleanElse("enabled", false)) {
                url = null;
                return;
            }

            String webhook = config.getStringElse("webhook", "");
            boolean strictValidation = !config.getBooleanElse("disable-webhook-validation", false);

            if (webhook.isEmpty()) {
                url = null;
            } else if (strictValidation) {
                if (!WEBHOOK_REGEX.test(webhook)) {
                    LogUtil.error("Discord webhook URL does not match expected format"
                            + " (https://discord.com/api/webhooks/<id>/<token>): " + webhook);
                    LogUtil.error("If you are using a proxy or custom endpoint,"
                            + " set 'disable-webhook-validation: true' in the Discord config.");
                    url = null;
                } else {
                    url = new URI(webhook);
                }
            } else {
                if (!HTTPS_URL_REGEX.test(webhook)) {
                    LogUtil.error("Discord webhook URL is not a valid HTTPS URL: " + webhook);
                    url = null;
                } else {
                    LogUtil.info("Webhook validation disabled — using custom endpoint: "
                            + webhook.substring(0, Math.min(webhook.length(), 40)) + "...");
                    url = new URI(webhook);
                }
            }
            if (url != null) {
                try {
                    HttpRequest.newBuilder(url);
                } catch (IllegalArgumentException e) {
                    LogUtil.error("Discord webhook URL cannot be requested: " + e.getMessage());
                    url = null;
                }
            }
            embedImageUrl = validatedConfigURL("embed-image-url", null);
            embedThumbnailUrl = validatedConfigURL("embed-thumbnail-url", "https://crafthead.net/helm/%uuid%");
            embedFooterUrl = validatedConfigURL("embed-footer-url", null);
            embedFooterText = config.getStringElse("embed-footer-text", DEFAULT_FOOTER_TEXT);
            compiledTitle = CompiledDiscordTemplate.compile(config.getStringElse("embed-title", DEFAULT_TITLE));

            try {
                embedColor = Color.decode(config.getStringElse("embed-color", "#00FFFF"))
                        .getRGB();
            } catch (NumberFormatException e) {
                LogUtil.warn("Discord embed color is invalid");
            }

            StringBuilder sb = new StringBuilder();
            List<String> content = config.getStringListElse(
                    "violation-content", EnglishDefaults.stringList("discord", "violation-content"));
            for (String string : content) {
                sb.append(string).append("\n");
            }
            includeTimestamp = config.getBooleanElse("include-timestamp", true);
            includeVerbose = config.getBooleanElse("include-verbose", true);
            includeState = config.getBooleanElse("include-state", true);
            String btReplace = config.getStringElse("backtick-replacement-char", "\u02CB");
            backtickReplacement = (btReplace.isEmpty()) ? '\u02CB' : btReplace.charAt(0);
            compiledContent = CompiledDiscordTemplate.compile(sb.toString());
        } catch (Exception e) {
            LogUtil.error("Failed to load Discord webhook configuration", e);
        }
    }

    public void sendAlert(@NotNull CultPlayer player, String verbose, String checkName, int violations) {
        if (isDisabled()) {
            return;
        }
        sendWebhookMessage(new WebhookMessage().addEmbeds(alertEmbed(player, verbose, checkName, violations)));
    }

    /** The embed {@link #sendAlert} posts for this flag. */
    public @NotNull Embed alertEmbed(@NotNull CultPlayer player, String verbose, String checkName, int violations) {
        // Per-alert overlay — avoids polluting the global static map
        Map<String, String> statics =
                new HashMap<>(CultAPI.INSTANCE.getExternalAPI().getStaticReplacements());
        statics.put("%check%", checkName);
        statics.put("%violations%", Integer.toString(violations));
        statics.put("%verbose%", verbose);
        statics.put("%world%", AlertDetails.worldLocation(player));

        Map<String, Function<GrimUser, String>> dynamics =
                CultAPI.INSTANCE.getExternalAPI().getVariableReplacements();

        String content = compiledContent.render(player, statics, dynamics, backtickReplacement);
        String title = compiledTitle.render(player, statics, dynamics, backtickReplacement);

        Embed embed = new Embed(truncate(content, Embed.MAX_DESCRIPTION_LENGTH))
                .color(embedColor)
                .title(truncate(title, Embed.MAX_TITLE_LENGTH))
                .imageURL(MessageUtil.replacePlaceholders(player, embedImageUrl))
                .thumbnailURL(MessageUtil.replacePlaceholders(player, embedThumbnailUrl))
                .footer(new EmbedFooter(
                        MessageUtil.replacePlaceholders(player, embedFooterText),
                        MessageUtil.replacePlaceholders(player, embedFooterUrl)));

        if (includeTimestamp) embed.timestamp(Instant.now());

        if (!verbose.isEmpty() && includeVerbose && !compiledContent.uses("%verbose%")) {
            embed.addFields(new EmbedField("Verbose", codeBlock(verbose)));
        }
        if (includeState && !compiledContent.uses("%state%")) {
            embed.addFields(new EmbedField("State", codeBlock(AlertDetails.state(player))));
        }
        return embed;
    }

    /** A code block that fits a field value, cutting the value rather than the fence. */
    private String codeBlock(String value) {
        int room = EmbedField.MAX_VALUE_LENGTH
                - CompiledDiscordTemplate.codeBlock("", backtickReplacement).length();
        String block = CompiledDiscordTemplate.codeBlock(value, backtickReplacement);
        // Zero-width splits can grow the value; shrink until the whole block fits.
        while (block.length() > EmbedField.MAX_VALUE_LENGTH && room > 1) {
            room -= block.length() - EmbedField.MAX_VALUE_LENGTH;
            block = CompiledDiscordTemplate.codeBlock(truncate(value, room), backtickReplacement);
        }
        return block;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    public CompletableFuture<Boolean> sendWebhookMessage(WebhookMessage message) {
        if (isDisabled()) return CompletableFuture.completedFuture(false);

        CompletableFuture<Boolean> future = queue.add(url, message);

        if (!taskStarted.getAndSet(true)) {
            CultAPI.INSTANCE
                    .getScheduler()
                    .getAsyncScheduler()
                    .runAtFixedRate(CultAPI.INSTANCE.getGrimPlugin(), queue::tick, 0, 1);
        }

        return future;
    }

    private static CompletableFuture<HttpResponse<String>> post(URI uri, String body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(timeout)
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    public boolean isDisabled() {
        return url == null;
    }

    @Override
    public void stop() {
        queue.stop();
    }

    static final class WebhookQueue {
        static final int MAX_QUEUED = 50_000;
        private static final int BACKLOG_WARNING = 1_000;
        private static final long MIN_BACKOFF_MS = 1_000;
        private static final long MAX_BACKOFF_MS = 60_000;

        interface Transport {
            CompletableFuture<HttpResponse<String>> post(URI uri, String body);
        }

        private record Alert(URI uri, WebhookMessage message, CompletableFuture<Boolean> future) {}

        private final Transport transport;
        private final LongSupplier clock;
        private final Supplier<Logger> logger;
        private final ConcurrentLinkedDeque<Alert> queue = new ConcurrentLinkedDeque<>();
        private final Set<URI> rejected = ConcurrentHashMap.newKeySet();
        private final AtomicInteger queued = new AtomicInteger();
        private final AtomicInteger backlogWarning = new AtomicInteger(BACKLOG_WARNING);
        private final AtomicInteger overflowed = new AtomicInteger();
        private final AtomicBoolean sending = new AtomicBoolean();
        private volatile @Nullable List<Alert> inFlight;
        private volatile long nextAttemptAt;
        private volatile int failures;
        private volatile boolean failing;
        private volatile boolean stopped;

        WebhookQueue(Transport transport, LongSupplier clock, Supplier<Logger> logger) {
            this.transport = transport;
            this.clock = clock;
            this.logger = logger;
        }

        synchronized CompletableFuture<Boolean> add(URI uri, WebhookMessage message) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            if (stopped || rejected.contains(uri)) {
                future.complete(false);
                return future;
            }
            int size = queued.incrementAndGet();
            if (size > MAX_QUEUED) {
                queued.decrementAndGet();
                if (overflowed.getAndIncrement() == 0) {
                    logger.get()
                            .warning("Discord alert backlog is full (" + MAX_QUEUED
                                    + "); excess alerts cannot be queued");
                }
                future.complete(false);
                return future;
            }
            queue.add(new Alert(uri, message, future));
            int threshold = backlogWarning.get();
            if (size > threshold && backlogWarning.compareAndSet(threshold, threshold * 10)) {
                logger.get()
                        .warning("Discord alert backlog passed " + threshold
                                + " alerts; they are kept and sent as fast as the webhook allows");
            }
            return future;
        }

        synchronized void tick() {
            if (stopped || clock.getAsLong() < nextAttemptAt || (inFlight == null && queue.isEmpty())) return;
            if (!sending.compareAndSet(false, true)) return;
            List<Alert> batch = inFlight;
            if (batch == null) {
                batch = takeBatch();
                if (batch.isEmpty()) {
                    sending.set(false);
                    return;
                }
                inFlight = batch;
            }
            List<Alert> request = batch;
            CompletableFuture<HttpResponse<String>> response;
            try {
                response = transport.post(request.get(0).uri(), body(request));
            } catch (RuntimeException e) {
                inFlight = null;
                try {
                    logger.get()
                            .log(
                                    Level.SEVERE,
                                    "Could not build a Discord request for " + request.size() + " alerts; alert text:\n"
                                            + describe(request),
                                    e);
                } finally {
                    for (Alert alert : request) alert.future().complete(false);
                    sending.set(false);
                }
                return;
            }
            // The runtime's tracked HTTP client is closed when the runtime stops, failing any pending request.
            response.whenComplete((result, throwable) -> {
                try {
                    // stop() already settled this batch; a late answer must not retry it.
                    if (!stopped) complete(request, result, throwable);
                } finally {
                    sending.set(false);
                }
            });
        }

        synchronized void stop() {
            stopped = true;
            List<Alert> unsent = new ArrayList<>();
            List<Alert> batch = inFlight;
            inFlight = null;
            if (batch != null) unsent.addAll(batch);
            for (Alert alert; (alert = poll()) != null; ) unsent.add(alert);
            if (unsent.isEmpty()) return;
            logger.get()
                    .warning("Discord webhook stopped with " + unsent.size() + " unsent alerts:\n" + describe(unsent));
            for (Alert alert : unsent) alert.future().complete(false);
        }

        private synchronized void complete(
                List<Alert> batch, @Nullable HttpResponse<String> response, @Nullable Throwable throwable) {
            if (stopped) return;
            long now = clock.getAsLong();
            if (throwable != null || response == null) {
                retry(now, "Could not send Discord alerts, retrying with backoff", throwable);
                return;
            }
            int status = response.statusCode();
            if (status == 429) {
                long wait = Math.max(rateLimitWait(response.headers(), now), retryAfterBody(response.body()));
                nextAttemptAt = delayed(now, wait > 0 ? wait : backoff());
                return;
            }
            if (status >= 500) {
                retry(
                        now,
                        "Discord returned status " + status + " with body " + response.body()
                                + ", retrying with backoff",
                        null);
                return;
            }
            if (response.headers()
                    .firstValue("X-RateLimit-Remaining")
                    .orElse("")
                    .equals("0")) {
                long wait = rateLimitWait(response.headers(), now);
                nextAttemptAt = delayed(now, wait > 0 ? wait : MIN_BACKOFF_MS);
            }
            inFlight = null;
            if (status >= 400) {
                logger.get()
                        .severe("Discord rejected " + batch.size() + " alerts with status " + status + " and body "
                                + response.body() + "; alert text:\n" + describe(batch));
                for (Alert alert : batch) alert.future().complete(false);
                if (status == 401 || status == 403 || status == 404)
                    reject(batch.get(0).uri(), status);
            } else {
                failures = 0;
                if (failing) {
                    failing = false;
                    logger.get()
                            .info("Discord alert delivery resumed; " + (queued.get() + batch.size())
                                    + " alerts were waiting");
                }
                for (Alert alert : batch) alert.future().complete(true);
            }
            if (queued.get() < BACKLOG_WARNING && backlogWarning.getAndSet(BACKLOG_WARNING) != BACKLOG_WARNING) {
                logger.get()
                        .info("Discord alert backlog is back under " + BACKLOG_WARNING + " alerts; "
                                + overflowed.getAndSet(0) + " alerts past the cap could not be queued");
            }
        }

        /** Discord asks clients to stop calling a webhook that answers 401, 403 or 404 until it is configured again. */
        private void reject(URI uri, int status) {
            if (!rejected.add(uri)) return;
            int dropped = 0;
            for (Iterator<Alert> it = queue.iterator(); it.hasNext(); ) {
                Alert alert = it.next();
                if (!alert.uri().equals(uri)) continue;
                it.remove();
                queued.decrementAndGet();
                alert.future().complete(false);
                dropped++;
            }
            logger.get()
                    .severe("Discord answered status " + status + " for the configured webhook; " + dropped
                            + " queued alerts were dropped and no more are sent to it until the Discord config is"
                            + " reloaded");
        }

        void forgetRejections() {
            rejected.clear();
        }

        private void retry(long now, String message, @Nullable Throwable throwable) {
            nextAttemptAt = now + backoff();
            if (failing) return;
            failing = true;
            logger.get().log(Level.SEVERE, message, throwable);
        }

        private long backoff() {
            int attempt = failures;
            failures = attempt + 1;
            return Math.min(MAX_BACKOFF_MS, MIN_BACKOFF_MS << Math.min(attempt, 6));
        }

        private static long delayed(long now, long wait) {
            return wait >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + wait;
        }

        private static long rateLimitWait(HttpHeaders headers, long now) {
            long wait = secondsHeader(headers, "Retry-After", 0);
            wait = Math.max(wait, secondsHeader(headers, "X-RateLimit-Reset-After", 0));
            if (wait < 0) wait = secondsHeader(headers, "X-RateLimit-Reset", now);
            return wait;
        }

        private static long secondsHeader(HttpHeaders headers, String name, long origin) {
            Optional<String> value = headers.firstValue(name);
            if (value.isEmpty()) return -1;
            try {
                double seconds = Double.parseDouble(value.get().trim());
                if (!Double.isFinite(seconds)) return -1;
                return Math.max(0, (long) Math.ceil(seconds * 1000) - origin);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private @Nullable Alert poll() {
            Alert alert = queue.poll();
            if (alert != null) queued.decrementAndGet();
            return alert;
        }

        private List<Alert> takeBatch() {
            Alert head = poll();
            return head == null ? List.of() : List.of(head);
        }

        private static String body(List<Alert> batch) {
            return batch.get(0).message().toJson().toString();
        }

        private static long retryAfterBody(String body) {
            try {
                double seconds = JsonParser.parseString(body)
                        .getAsJsonObject()
                        .get("retry_after")
                        .getAsDouble();
                if (!Double.isFinite(seconds) || seconds < 0) return -1;
                return (long) Math.ceil(seconds * 1000);
            } catch (RuntimeException e) {
                return -1;
            }
        }

        private static String describe(List<Alert> alerts) {
            StringBuilder text = new StringBuilder();
            for (Alert alert : alerts) {
                if (!text.isEmpty()) text.append('\n');
                text.append(describe(alert.message()));
            }
            return text.toString();
        }

        private static String describe(WebhookMessage message) {
            try {
                return message.toJson().toString();
            } catch (RuntimeException e) {
                return summary(message);
            }
        }

        private static String summary(WebhookMessage message) {
            List<String> parts = new ArrayList<>();
            if (message.content() != null) parts.add(message.content());
            if (message.embeds() != null) {
                for (Embed embed : message.embeds()) {
                    if (embed.timestamp() != null) parts.add(embed.timestamp().toString());
                    if (embed.author() != null) parts.add(embed.author().name());
                    if (embed.title() != null) parts.add(embed.title());
                    parts.add(embed.description());
                    if (embed.fields() != null) {
                        for (EmbedField field : embed.fields()) parts.add(field.name() + ": " + field.value());
                    }
                    if (embed.footer() != null) parts.add(embed.footer().text());
                }
            }
            return String.join("; ", parts).replace("\n", "; ");
        }
    }
}
