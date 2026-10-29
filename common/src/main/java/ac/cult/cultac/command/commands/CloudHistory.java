package ac.cult.cultac.command.commands;

import ac.cult.cultac.cloud.history.HistoryClient;
import ac.cult.cultac.cloud.history.HistoryClient.Finding;
import ac.cult.cultac.cloud.history.HistoryClient.Session;
import ac.cult.cultac.cloud.history.HistoryClient.Sessions;
import ac.cult.cultac.cloud.labels.LabelClient;
import ac.cult.cultac.command.render.HistoryComponentRenderer;
import ac.grim.grimac.api.storage.history.SessionDetail;
import ac.grim.grimac.api.storage.history.SessionSummary;
import ac.grim.grimac.api.storage.history.ViolationEntry;
import ac.grim.grimac.api.storage.model.VerboseFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /cult history} read from the cloud: a player's sessions on every server of the organization and a session's
 * findings, shaped as this server's history so {@link HistoryComponentRenderer} draws them alike, with {@link #ORIGIN}.
 * Ordinals number a player's sessions from 1 for the oldest to the cloud's {@code total} for the newest, as this
 * server's history does. A finding's source is its description.
 */
final class CloudHistory {
    static final String UNAVAILABLE = "Cloud history is unavailable; showing this server's history.";
    static final String REFUSED = "The cloud refused this server's key; showing this server's history.";
    /** The most findings read for one session, as many as this server's history reads. */
    static final int MAX_FINDINGS = 10_000;
    /** The {@code endReason} of a session whose stream ended without a close. */
    static final String LOST = "lost";
    /** How the renderer draws the cloud's records. */
    static final HistoryComponentRenderer.Origin ORIGIN = HistoryComponentRenderer.Origin.CLOUD;

    /** The cloud's two history calls. */
    interface Source {
        CompletableFuture<Sessions> sessions(UUID player, @Nullable String before, int limit);

        CompletableFuture<HistoryClient.Findings> findings(
                UUID player, UUID session, @Nullable String after, int limit);
    }

    /** A page of the session list, clamped to {@code maxPages}; {@code ongoing} is the newest open session on it. */
    record ListPage(
            int page,
            int maxPages,
            List<SessionSummary> sessions,
            @Nullable UUID ongoing) {}

    /** A session's detail, and whether the session is still open. */
    record Shown(SessionDetail detail, boolean ongoing) {}

    private CloudHistory() {}

    static Source of(HistoryClient client) {
        return new Source() {
            @Override
            public CompletableFuture<Sessions> sessions(UUID player, @Nullable String before, int limit) {
                return client.sessions(player, before, limit);
            }

            @Override
            public CompletableFuture<HistoryClient.Findings> findings(
                    UUID player, UUID session, @Nullable String after, int limit) {
                return client.findings(player, session, after, limit);
            }
        };
    }

    /**
     * Page {@code page} of {@code player}'s sessions, newest first, {@code pageSize} to a page; with a {@code filter},
     * only the sessions holding a finding it matches. A session the cloud lacks is left out; any other failure to read
     * a session's findings fails the page.
     */
    static CompletableFuture<ListPage> list(
            Source source, UUID player, int page, int pageSize, @Nullable Predicate<ViolationEntry> filter) {
        int size = Math.clamp(pageSize, 1, HistoryClient.MAX_SESSIONS);
        // A multiple of the page size, so a page never spans two calls.
        int chunk = HistoryClient.MAX_SESSIONS / size * size;
        return source.sessions(player, null, page <= 1 ? size : chunk).thenCompose(first -> {
            int maxPages = Math.clamp((first.total() + size - 1) / size, 1, Integer.MAX_VALUE);
            int shown = Math.clamp(page, 1, maxPages);
            long start = (long) (shown - 1) * size;
            return window(source, player, first, start, size, chunk).thenCompose(sessions -> {
                List<SessionSummary> summaries = new ArrayList<>(sessions.size());
                UUID ongoing = null;
                for (int i = 0; i < sessions.size(); i++) {
                    Session session = sessions.get(i);
                    summaries.add(summary(player, session, ordinal(first.total(), start + i)));
                    if (ongoing == null && session.ended() == null) ongoing = session.id();
                }
                UUID open = ongoing;
                if (filter == null) {
                    return CompletableFuture.completedFuture(new ListPage(shown, maxPages, summaries, open));
                }
                return matching(source, player, sessions, summaries, filter)
                        .thenApply(kept -> new ListPage(shown, maxPages, kept, open));
            });
        });
    }

    /**
     * The session {@code raw} names, a positive ordinal or {@code latest} / {@code last} / {@code l}, with its findings
     * through {@code filter}; null when the player has no such session.
     */
    static CompletableFuture<@Nullable Shown> detail(
            Source source, UUID player, String raw, long bucketMs, @Nullable Predicate<ViolationEntry> filter) {
        boolean latest = isLatest(raw);
        Integer asked = latest ? null : positive(raw);
        if (!latest && asked == null) return CompletableFuture.completedFuture(null);
        return source.sessions(player, null, latest ? 1 : HistoryClient.MAX_SESSIONS)
                .thenCompose(first -> {
                    long ordinal = latest ? first.total() : asked;
                    if (ordinal < 1 || ordinal > first.total()) return CompletableFuture.completedFuture(null);
                    return window(source, player, first, first.total() - ordinal, 1, HistoryClient.MAX_SESSIONS)
                            .thenCompose(found -> {
                                if (found.isEmpty()) return CompletableFuture.completedFuture(null);
                                Session session = found.get(0);
                                return missingAsNull(violations(source, player, session))
                                        .thenApply(violations -> violations == null
                                                ? null
                                                : new Shown(
                                                        detail(
                                                                player,
                                                                session,
                                                                (int) ordinal,
                                                                bucketMs,
                                                                violations,
                                                                filter),
                                                        session.ended() == null));
                            });
                });
    }

    /** Every finding in {@code session}, oldest first, at most {@link #MAX_FINDINGS}. */
    static CompletableFuture<List<ViolationEntry>> violations(Source source, UUID player, Session session) {
        List<ViolationEntry> out = new ArrayList<>();
        return findings(source, player, session, null, out).thenApply(done -> out);
    }

    /** The line telling staff why the cloud gave no history, before this server's is shown. */
    static Component note(Throwable error) {
        HistoryClient.Failure failure = failure(error);
        String why = failure != null ? failure.getMessage() : LabelClient.unanswered(error);
        boolean refused = failure != null && (failure.status() == 401 || failure.status() == 403);
        return Component.text(refused ? REFUSED : UNAVAILABLE, refused ? NamedTextColor.RED : NamedTextColor.GRAY)
                .hoverEvent(HoverEvent.showText(Component.text(why, NamedTextColor.GRAY)));
    }

    /**
     * Sends the lines {@code call} completes with; when it fails, sends {@link #note} instead and then runs
     * {@code local}, which shows this server's history.
     */
    static void answer(CompletableFuture<List<Component>> call, Consumer<List<Component>> send, Runnable local) {
        call.whenComplete((lines, error) -> {
            if (error == null) {
                send.accept(lines);
                return;
            }
            send.accept(List.of(note(error)));
            local.run();
        });
    }

    /** The ordinal of the session at {@code position} in the newest-first list of {@code total}. */
    static int ordinal(long total, long position) {
        return Math.clamp(total - position, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    static SessionSummary summary(UUID player, Session session, int ordinal) {
        long lastActivity = lastActivity(session);
        return new SessionSummary(
                session.id(),
                player,
                ordinal,
                session.started(),
                lastActivity,
                // A summary reads as crashed when it closed at its last activity.
                LOST.equals(session.endReason()) ? lastActivity : 0,
                version(session),
                session.server(),
                session.protocol(),
                session.brand(),
                session.findings(),
                session.checks().size());
    }

    static SessionDetail detail(
            UUID player,
            Session session,
            int ordinal,
            long bucketMs,
            List<ViolationEntry> violations,
            @Nullable Predicate<ViolationEntry> filter) {
        SessionDetail all = new SessionDetail(
                session.id(),
                player,
                ordinal,
                session.started(),
                lastActivity(session),
                version(session),
                session.server(),
                session.protocol(),
                session.brand(),
                Math.max(1, bucketMs),
                0,
                List.of(),
                violations);
        return HistoryComponentRenderer.applyFilter(all, filter == null ? violation -> true : filter);
    }

    /** {@code finding} as a violation, its stable key its source and check. */
    static ViolationEntry violation(Finding finding, long started) {
        return new ViolationEntry(
                0,
                finding.source() + ":" + finding.check(),
                finding.check(),
                finding.source(),
                Math.max(0, finding.at() - started),
                finding.value(),
                finding.detail(),
                VerboseFormat.TEXT);
    }

    private static long lastActivity(Session session) {
        return session.ended() != null ? session.ended() : session.lastSeen();
    }

    private static @Nullable String version(Session session) {
        return session.agentVersion().isBlank() ? null : session.agentVersion();
    }

    /** The sessions at positions {@code [start, start + count)} of the newest-first list that {@code first} opens. */
    private static CompletableFuture<List<Session>> window(
            Source source, UUID player, Sessions first, long start, int count, int limit) {
        List<Session> out = new ArrayList<>(count);
        return walk(source, player, first, null, 0, start, count, limit, out).thenApply(done -> out);
    }

    /**
     * Takes the wanted sessions from {@code page}, which starts at {@code offset} and was asked for with the cursor
     * {@code before}, then follows its {@code next} while more are wanted. A {@code next} equal to {@code before} ends
     * the walk.
     */
    private static CompletableFuture<Void> walk(
            Source source,
            UUID player,
            Sessions page,
            @Nullable String before,
            long offset,
            long start,
            int count,
            int limit,
            List<Session> out) {
        List<Session> sessions = page.sessions();
        for (int i = 0; i < sessions.size(); i++) {
            long position = offset + i;
            if (position >= start && position < start + count) out.add(sessions.get(i));
        }
        long end = offset + sessions.size();
        String next = page.next();
        if (end >= start + count || next == null || next.equals(before) || sessions.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return source.sessions(player, next, limit)
                .thenCompose(following -> walk(source, player, following, next, end, start, count, limit, out));
    }

    private static CompletableFuture<Void> findings(
            Source source, UUID player, Session session, @Nullable String after, List<ViolationEntry> out) {
        return source.findings(player, session.id(), after, HistoryClient.MAX_FINDINGS)
                .thenCompose(page -> {
                    for (Finding finding : page.findings()) {
                        if (out.size() >= MAX_FINDINGS) break;
                        out.add(violation(finding, session.started()));
                    }
                    String next = page.next();
                    if (next == null || next.equals(after) || page.findings().isEmpty() || out.size() >= MAX_FINDINGS) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return findings(source, player, session, next, out);
                });
    }

    /** The summaries whose sessions hold a finding {@code filter} matches; a session the cloud lacks is left out. */
    private static CompletableFuture<List<SessionSummary>> matching(
            Source source,
            UUID player,
            List<Session> sessions,
            List<SessionSummary> summaries,
            Predicate<ViolationEntry> filter) {
        List<CompletableFuture<Boolean>> matches = new ArrayList<>(sessions.size());
        for (Session session : sessions) {
            matches.add(missingAsNull(violations(source, player, session))
                    .thenApply(violations ->
                            violations != null && violations.stream().anyMatch(filter)));
        }
        return CompletableFuture.allOf(matches.toArray(CompletableFuture[]::new))
                .thenApply(done -> {
                    List<SessionSummary> kept = new ArrayList<>();
                    for (int i = 0; i < summaries.size(); i++) {
                        if (matches.get(i).join()) kept.add(summaries.get(i));
                    }
                    return kept;
                });
    }

    /** {@code call}, completing with null when the cloud has no such session (404). */
    private static <T> CompletableFuture<@Nullable T> missingAsNull(CompletableFuture<T> call) {
        return call.handle((value, error) -> {
            if (error == null) return value;
            HistoryClient.Failure failure = failure(error);
            if (failure != null && failure.status() == 404) return null;
            throw error instanceof CompletionException completion ? completion : new CompletionException(error);
        });
    }

    private static HistoryClient.@Nullable Failure failure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HistoryClient.Failure failure) return failure;
            if (cause == cause.getCause()) break;
        }
        return null;
    }

    private static boolean isLatest(String raw) {
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        return trimmed.equals("latest") || trimmed.equals("last") || trimmed.equals("l");
    }

    private static @Nullable Integer positive(String raw) {
        try {
            int n = Integer.parseInt(raw.trim());
            return n >= 1 ? n : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
