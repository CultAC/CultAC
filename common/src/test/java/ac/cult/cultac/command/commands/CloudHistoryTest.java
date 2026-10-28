package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.history.HistoryClient;
import ac.cult.cultac.cloud.history.HistoryClient.Check;
import ac.cult.cultac.cloud.history.HistoryClient.Finding;
import ac.cult.cultac.cloud.history.HistoryClient.Findings;
import ac.cult.cultac.cloud.history.HistoryClient.Session;
import ac.cult.cultac.cloud.history.HistoryClient.Sessions;
import ac.grim.grimac.api.storage.history.SessionDetail;
import ac.grim.grimac.api.storage.history.SessionSummary;
import ac.grim.grimac.api.storage.history.ViolationEntry;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CloudHistoryTest {
    private static final UUID PLAYER = UUID.fromString("6a1f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6b");
    private static final long NEWEST_START = 1_793_200_000_000L;
    private static final long HOUR = 3_600_000L;

    /** A cloud holding {@code count} sessions, newest first, the newest still open; pages as the routes do. */
    private static final class Cloud implements CloudHistory.Source {
        final List<Session> sessions = new ArrayList<>();
        final Map<UUID, List<Finding>> findings = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        @Nullable
        RuntimeException sessionsFailure;

        int findingsStatus = 200;

        Cloud(int count) {
            for (int i = 0; i < count; i++) {
                long started = NEWEST_START - i * HOUR;
                Long ended = i == 0 ? null : started + HOUR / 2;
                sessions.add(new Session(
                        new UUID(1, i),
                        started,
                        ended,
                        ended == null ? started + 60_000 : ended,
                        "server-" + i,
                        "Steve",
                        "vanilla",
                        773,
                        i,
                        List.of(new Check("engine", "Reach", i, 1.0, started, started))));
            }
        }

        Session at(int position) {
            return sessions.get(position);
        }

        @Override
        public CompletableFuture<Sessions> sessions(UUID player, @Nullable Long before, int limit) {
            calls.add("sessions " + before + " " + limit);
            if (sessionsFailure != null) return CompletableFuture.failedFuture(sessionsFailure);
            List<Session> eligible = sessions.stream()
                    .filter(s -> before == null || s.started() < before)
                    .toList();
            List<Session> page = eligible.subList(0, Math.min(limit, eligible.size()));
            Long next = eligible.size() > page.size() ? page.getLast().started() : null;
            return CompletableFuture.completedFuture(new Sessions(sessions.size(), page, next));
        }

        @Override
        public CompletableFuture<Findings> findings(UUID player, UUID session, @Nullable String after, int limit) {
            calls.add("findings " + session + " " + after + " " + limit);
            if (findingsStatus != 200) {
                return CompletableFuture.failedFuture(
                        new HistoryClient.Failure(findingsStatus, "HTTP " + findingsStatus));
            }
            List<Finding> all = findings.getOrDefault(session, List.of());
            int from = after == null ? 0 : Integer.parseInt(after);
            int to = Math.min(all.size(), from + limit);
            return CompletableFuture.completedFuture(
                    new Findings(all.subList(from, to), to < all.size() ? Integer.toString(to) : null));
        }
    }

    @Test
    void ordinalsCountFromTheOldestAsThisServersHistoryDoes() throws Exception {
        Cloud cloud = new Cloud(23);

        CloudHistory.ListPage first =
                CloudHistory.list(cloud, PLAYER, 1, 10, null).get();
        CloudHistory.ListPage last =
                CloudHistory.list(cloud, PLAYER, 3, 10, null).get();

        assertEquals(3, first.maxPages());
        assertEquals(List.of(23, 22, 21, 20, 19, 18, 17, 16, 15, 14), ordinals(first.sessions()));
        assertEquals(cloud.at(0).id(), first.sessions().get(0).sessionId());
        assertEquals(List.of(3, 2, 1), ordinals(last.sessions()));
        assertEquals(cloud.at(22).id(), last.sessions().get(2).sessionId());
        assertEquals(23, CloudHistory.ordinal(23, 0));
        assertEquals(1, CloudHistory.ordinal(23, 22));
    }

    @Test
    void aPageBeyondTheLastShowsTheLast() throws Exception {
        CloudHistory.ListPage page =
                CloudHistory.list(new Cloud(23), PLAYER, 9, 10, null).get();

        assertEquals(3, page.page());
        assertEquals(List.of(3, 2, 1), ordinals(page.sessions()));
    }

    @Test
    void noSessionsIsOneEmptyPage() throws Exception {
        CloudHistory.ListPage page =
                CloudHistory.list(new Cloud(0), PLAYER, 4, 10, null).get();

        assertEquals(1, page.page());
        assertEquals(1, page.maxPages());
        assertTrue(page.sessions().isEmpty());
        assertNull(page.ongoing());
    }

    @Test
    void laterPagesWalkTheCursorInWholePages() throws Exception {
        Cloud cloud = new Cloud(120);

        CloudHistory.ListPage page =
                CloudHistory.list(cloud, PLAYER, 7, 15, null).get();

        assertEquals(List.of(30, 29, 28, 27, 26, 25, 24, 23, 22, 21, 20, 19, 18, 17, 16), ordinals(page.sessions()));
        assertEquals(cloud.at(90).id(), page.sessions().get(0).sessionId());
        assertEquals(
                List.of(
                        "sessions null 45",
                        "sessions " + cloud.at(44).started() + " 45",
                        "sessions " + cloud.at(89).started() + " 45"),
                cloud.calls);
    }

    @Test
    void theFirstPageAsksForOnePage() throws Exception {
        Cloud cloud = new Cloud(120);

        CloudHistory.list(cloud, PLAYER, 1, 15, null).get();

        assertEquals(List.of("sessions null 15"), cloud.calls);
    }

    @Test
    void theOpenSessionIsOngoingAndNoSessionReadsAsCrashed() throws Exception {
        Cloud cloud = new Cloud(3);

        CloudHistory.ListPage page =
                CloudHistory.list(cloud, PLAYER, 1, 10, null).get();

        assertEquals(cloud.at(0).id(), page.ongoing());
        SessionSummary closed = page.sessions().get(1);
        assertEquals(cloud.at(1).ended(), closed.lastActivityEpochMs());
        assertFalse(closed.endedUnexpectedly());
        assertEquals("server-1", closed.serverName());
        assertNull(closed.grimVersion());
        assertEquals(773, closed.clientVersion());
        assertEquals("vanilla", closed.clientBrand());
        assertEquals(1, closed.violationCount());
        assertEquals(1, closed.uniqueCheckCount());
        assertEquals(HOUR / 2, closed.durationMs());
    }

    @Test
    void aSessionIsFoundByOrdinalOrLatest() throws Exception {
        Cloud cloud = new Cloud(120);

        CloudHistory.Shown latest =
                CloudHistory.detail(cloud, PLAYER, "latest", 30_000, null).get();
        CloudHistory.Shown oldest =
                CloudHistory.detail(cloud, PLAYER, "1", 30_000, null).get();
        CloudHistory.Shown middle =
                CloudHistory.detail(cloud, PLAYER, "70", 30_000, null).get();

        assertNotNull(latest);
        assertEquals(cloud.at(0).id(), latest.detail().sessionId());
        assertEquals(120, latest.detail().sessionOrdinal());
        assertTrue(latest.ongoing());
        assertNotNull(oldest);
        assertEquals(cloud.at(119).id(), oldest.detail().sessionId());
        assertEquals(1, oldest.detail().sessionOrdinal());
        assertFalse(oldest.ongoing());
        assertNotNull(middle);
        assertEquals(cloud.at(50).id(), middle.detail().sessionId());
        assertEquals("server-50", middle.detail().serverName());
        for (String raw : List.of("L", "last")) {
            assertEquals(
                    cloud.at(0).id(),
                    CloudHistory.detail(cloud, PLAYER, raw, 30_000, null)
                            .get()
                            .detail()
                            .sessionId());
        }
    }

    @Test
    void anOrdinalOutsideTheSessionsIsNotFound() throws Exception {
        Cloud cloud = new Cloud(5);

        for (String raw : List.of("6", "0", "-1", "x", "")) {
            assertNull(CloudHistory.detail(cloud, PLAYER, raw, 30_000, null).get(), raw);
        }
        assertNull(CloudHistory.detail(new Cloud(0), PLAYER, "latest", 30_000, null)
                .get());
    }

    @Test
    void aSessionsFindingsArePagedAndShapedAsViolations() throws Exception {
        Cloud cloud = new Cloud(2);
        Session session = cloud.at(1);
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            String check = i % 2 == 0 ? "Reach" : "killaura";
            String source = i % 2 == 0 ? "engine" : "model";
            findings.add(new Finding(session.started() + i * 1000L, i, source, check, i / 10.0, "d" + i));
        }
        cloud.findings.put(session.id(), findings);

        CloudHistory.Shown shown =
                CloudHistory.detail(cloud, PLAYER, "1", 30_000, null).get();

        assertNotNull(shown);
        SessionDetail detail = shown.detail();
        assertEquals(1200, detail.violations().size());
        ViolationEntry third = detail.violations().get(3);
        assertEquals("killaura", third.displayName());
        assertEquals("model", third.description());
        assertEquals(3000, third.offsetFromSessionStartMs());
        assertEquals(0.3, third.vl(), 1e-9);
        assertEquals("d3", third.verbose());
        assertEquals(2, detail.uniqueCheckCount());
        assertEquals(40, detail.buckets().size());
        assertEquals(30_000, detail.buckets().get(1).bucketStartOffsetMs());
        assertNull(detail.grimVersion());
        assertEquals(session.ended(), detail.lastActivityEpochMs());
        assertEquals(
                List.of(
                        "findings " + session.id() + " null 500",
                        "findings " + session.id() + " 500 500",
                        "findings " + session.id() + " 1000 500"),
                cloud.calls.stream().filter(call -> call.startsWith("findings")).toList());
    }

    @Test
    void aFilterKeepsMatchingViolationsAndSessions() throws Exception {
        Cloud cloud = new Cloud(3);
        cloud.findings.put(
                cloud.at(1).id(), List.of(new Finding(cloud.at(1).started(), 0, "engine", "Reach", 1, "distance=3.2")));
        cloud.findings.put(
                cloud.at(2).id(),
                List.of(
                        new Finding(cloud.at(2).started(), 0, "engine", "Timer", 1, "balance=60"),
                        new Finding(cloud.at(2).started() + 1, 0, "engine", "Reach", 1, "distance=3.1")));

        CloudHistory.ListPage page = CloudHistory.list(
                        cloud, PLAYER, 1, 10, v -> v.verbose().contains("balance"))
                .get();
        CloudHistory.Shown shown = CloudHistory.detail(
                        cloud, PLAYER, "1", 30_000, v -> v.displayName().equals("Reach"))
                .get();

        assertEquals(List.of(1), ordinals(page.sessions()));
        assertNotNull(shown);
        assertEquals(1, shown.detail().violations().size());
        assertEquals("distance=3.1", shown.detail().violations().get(0).verbose());
    }

    @Test
    void aSessionTheCloudDoesNotKnowIsNotFound() throws Exception {
        Cloud cloud = new Cloud(3);
        cloud.findingsStatus = 404;

        assertNull(CloudHistory.detail(cloud, PLAYER, "2", 30_000, null).get());
    }

    @Test
    void aBusyCloudFallsBackToThisServerWithANote() {
        Cloud cloud = new Cloud(3);
        cloud.sessionsFailure = new HistoryClient.Failure(503, "HTTP 503");

        Answered answered = answer(cloud);

        assertTrue(answered.local.get());
        assertEquals(1, answered.sent.size());
        assertNote(answered.sent.get(0), CloudHistory.UNAVAILABLE, NamedTextColor.GRAY);
        assertEquals("Cloud history is unavailable; showing this server's history.", CloudHistory.UNAVAILABLE);
    }

    @Test
    void aRefusedKeyIsToldAndFallsBackToThisServer() {
        for (int status : new int[] {401, 403}) {
            Cloud cloud = new Cloud(3);
            cloud.sessionsFailure = new HistoryClient.Failure(status, "HTTP " + status);

            Answered answered = answer(cloud);

            assertTrue(answered.local.get());
            assertNote(answered.sent.get(0), CloudHistory.REFUSED, NamedTextColor.RED);
        }
    }

    @Test
    void everyOtherFailureFallsBackWithTheNote() {
        List<RuntimeException> failures = List.of(
                new HistoryClient.Failure(500, "HTTP 500"),
                new HistoryClient.Failure(200, "unreadable sessions answer"),
                new RuntimeException(new HttpTimeoutException("request timed out")),
                new RuntimeException(new ConnectException("refused")));
        for (RuntimeException failure : failures) {
            Cloud cloud = new Cloud(3);
            cloud.sessionsFailure = failure;

            Answered answered = answer(cloud);

            assertTrue(answered.local.get(), failure.toString());
            assertNote(answered.sent.get(0), CloudHistory.UNAVAILABLE, NamedTextColor.GRAY);
        }
    }

    @Test
    void anAnswerFromTheCloudDoesNotFallBack() {
        Answered answered = answer(new Cloud(3));

        assertFalse(answered.local.get());
        assertEquals(List.of(List.of(Component.text("3 sessions"))), answered.sent);
    }

    private record Answered(List<List<Component>> sent, AtomicBoolean local) {}

    private static Answered answer(Cloud cloud) {
        Answered answered = new Answered(new ArrayList<>(), new AtomicBoolean());
        CloudHistory.answer(
                CloudHistory.list(cloud, PLAYER, 1, 10, null)
                        .thenApply(
                                page -> List.of(Component.text(page.sessions().size() + " sessions"))),
                answered.sent::add,
                () -> answered.local.set(true));
        return answered;
    }

    private static void assertNote(List<Component> sent, String text, NamedTextColor color) {
        assertEquals(1, sent.size());
        TextComponent note = (TextComponent) sent.get(0);
        assertEquals(text, note.content());
        assertEquals(color, note.color());
        assertNotNull(note.hoverEvent());
    }

    private static List<Integer> ordinals(List<SessionSummary> sessions) {
        return sessions.stream().map(SessionSummary::sessionOrdinal).toList();
    }
}
