package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.history.HistoryClient;
import ac.cult.cultac.cloud.history.HistoryClient.Check;
import ac.cult.cultac.cloud.history.HistoryClient.Finding;
import ac.cult.cultac.cloud.history.HistoryClient.Findings;
import ac.cult.cultac.cloud.history.HistoryClient.Session;
import ac.cult.cultac.cloud.history.HistoryClient.Sessions;
import ac.cult.cultac.command.render.HistoryComponentRenderer;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.storage.history.SessionDetail;
import ac.grim.grimac.api.storage.history.SessionSummary;
import ac.grim.grimac.api.storage.history.ViolationEntry;
import ac.grim.grimac.api.storage.query.Page;
import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CloudHistoryTest {
    private static final UUID PLAYER = UUID.fromString("6a1f3c2e-5b4d-4e8f-9a0b-1c2d3e4f5a6b");
    private static final long NEWEST_START = 1_793_200_000_000L;
    private static final long HOUR = 3_600_000L;
    private static final Sender SENDER = mock(Sender.class);
    private static final String HOSTILE_SERVER = "<click:run_command:'/op x'>s</click>";
    private static final String HOSTILE_VERSION = "&k9.9<b>%prefix%";
    private static final String HOSTILE_BRAND = "<red>brand&l";
    private static final String HOSTILE_CHECK = "&cReach<bold>%player%";
    private static final String HOSTILE_DETAIL = "<hover:show_text:'x'><click:run_command:'/op y'>d</click>&l";
    private static final String HOSTILE_SOURCE = "<click:run_command:'/op z'>m</click>";

    /** A cloud holding {@code count} sessions, newest first, the newest still open; pages as the routes do. */
    private static final class Cloud implements CloudHistory.Source {
        final List<Session> sessions = new ArrayList<>();
        final Map<UUID, List<Finding>> findings = new HashMap<>();
        final Map<UUID, RuntimeException> findingsFailures = new HashMap<>();
        final Map<String, Integer> cursors = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        @Nullable
        RuntimeException sessionsFailure;

        Cloud(int count) {
            for (int i = 0; i < count; i++) {
                long started = NEWEST_START - i * HOUR;
                Long ended = i == 0 ? null : started + HOUR / 2;
                sessions.add(new Session(
                        new UUID(1, i),
                        started,
                        ended,
                        ended == null ? "" : "quit",
                        ended == null ? started + 60_000 : ended,
                        "server-" + i,
                        "Steve",
                        "vanilla",
                        773,
                        "2.1." + i,
                        i,
                        List.of(new Check("engine", "Reach", i, 1.0, started, started))));
            }
        }

        /** The opaque cursor this cloud gives for the page after the first {@code end} sessions. */
        static String cursor(int end) {
            return "c/" + Integer.toString(end * 7919, 36) + "+=";
        }

        Session at(int position) {
            return sessions.get(position);
        }

        void replace(int position, String endReason, String server, String brand, String agentVersion) {
            Session s = sessions.get(position);
            sessions.set(
                    position,
                    new Session(
                            s.id(),
                            s.started(),
                            s.ended(),
                            endReason,
                            s.lastSeen(),
                            server,
                            s.name(),
                            brand,
                            s.protocol(),
                            agentVersion,
                            s.findings(),
                            s.checks()));
        }

        @Override
        public CompletableFuture<Sessions> sessions(UUID player, @Nullable String before, int limit) {
            calls.add("sessions " + before + " " + limit);
            if (sessionsFailure != null) return CompletableFuture.failedFuture(sessionsFailure);
            Integer from = before == null ? Integer.valueOf(0) : cursors.get(before);
            if (from == null) return CompletableFuture.failedFuture(new HistoryClient.Failure(400, "HTTP 400"));
            int to = Math.min(sessions.size(), from + limit);
            String next = null;
            if (to < sessions.size()) {
                next = cursor(to);
                cursors.put(next, to);
            }
            return CompletableFuture.completedFuture(new Sessions(sessions.size(), sessions.subList(from, to), next));
        }

        @Override
        public CompletableFuture<Findings> findings(UUID player, UUID session, @Nullable String after, int limit) {
            calls.add("findings " + session + " " + after + " " + limit);
            RuntimeException failure = findingsFailures.get(session);
            if (failure != null) return CompletableFuture.failedFuture(failure);
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
    void laterPagesPassTheOpaqueCursorBackInWholePages() throws Exception {
        Cloud cloud = new Cloud(120);

        CloudHistory.ListPage page =
                CloudHistory.list(cloud, PLAYER, 7, 15, null).get();

        assertEquals(List.of(30, 29, 28, 27, 26, 25, 24, 23, 22, 21, 20, 19, 18, 17, 16), ordinals(page.sessions()));
        assertEquals(cloud.at(90).id(), page.sessions().get(0).sessionId());
        assertEquals(
                List.of(
                        "sessions null 45",
                        "sessions " + Cloud.cursor(45) + " 45",
                        "sessions " + Cloud.cursor(90) + " 45"),
                cloud.calls);
    }

    @Test
    void theFirstPageAsksForOnePage() throws Exception {
        Cloud cloud = new Cloud(120);

        CloudHistory.list(cloud, PLAYER, 1, 15, null).get();

        assertEquals(List.of("sessions null 15"), cloud.calls);
    }

    @Test
    void theOpenSessionIsOngoingAndOnlyALostOneCrashed() throws Exception {
        Cloud cloud = new Cloud(3);
        cloud.replace(2, CloudHistory.LOST, "server-2", "vanilla", "");

        CloudHistory.ListPage page =
                CloudHistory.list(cloud, PLAYER, 1, 10, null).get();

        assertEquals(cloud.at(0).id(), page.ongoing());
        assertFalse(page.sessions().get(0).endedUnexpectedly());
        SessionSummary closed = page.sessions().get(1);
        assertEquals(cloud.at(1).ended(), closed.lastActivityEpochMs());
        assertFalse(closed.endedUnexpectedly());
        assertEquals("server-1", closed.serverName());
        assertEquals("2.1.1", closed.grimVersion());
        assertEquals(773, closed.clientVersion());
        assertEquals("vanilla", closed.clientBrand());
        assertEquals(1, closed.violationCount());
        assertEquals(1, closed.uniqueCheckCount());
        assertEquals(HOUR / 2, closed.durationMs());
        SessionSummary lost = page.sessions().get(2);
        assertTrue(lost.endedUnexpectedly());
        assertEquals(HOUR / 2, lost.durationMs());
        assertNull(lost.grimVersion());
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
        assertEquals("2.1.50", middle.detail().grimVersion());
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
            String source = i % 2 == 0 ? "engine" : "heuristic";
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
        assertEquals("heuristic:killaura", third.stableKey());
        assertEquals("heuristic", third.description());
        assertEquals(3000, third.offsetFromSessionStartMs());
        assertEquals(0.3, third.vl(), 1e-9);
        assertEquals("d3", third.verbose());
        assertEquals(2, detail.uniqueCheckCount());
        assertEquals(40, detail.buckets().size());
        assertEquals(30_000, detail.buckets().get(1).bucketStartOffsetMs());
        assertEquals("2.1.1", detail.grimVersion());
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
    void aFilteredListLeavesOutASessionTheCloudLacks() throws Exception {
        Cloud cloud = new Cloud(3);
        for (Session session : cloud.sessions) {
            cloud.findings.put(session.id(), List.of(new Finding(session.started(), 0, "engine", "Reach", 1, "")));
        }
        cloud.findingsFailures.put(cloud.at(1).id(), new HistoryClient.Failure(404, "HTTP 404"));

        CloudHistory.ListPage page =
                CloudHistory.list(cloud, PLAYER, 1, 10, violation -> true).get();

        assertEquals(List.of(3, 1), ordinals(page.sessions()));
    }

    @Test
    void aFailedFindingsCallFailsAFilteredListOverToThisServer() {
        List<RuntimeException> failures = List.of(
                new HistoryClient.Failure(503, "HTTP 503"),
                new HistoryClient.Failure(500, "HTTP 500"),
                new HistoryClient.Failure(200, "unreadable findings answer"),
                new RuntimeException(new HttpTimeoutException("request timed out")));
        for (RuntimeException failure : failures) {
            Cloud cloud = new Cloud(3);
            cloud.findingsFailures.put(cloud.at(1).id(), failure);

            Answered answered = answer(cloud, violation -> true);

            assertTrue(answered.local.get(), failure.toString());
            assertNote(answered.sent.get(0), CloudHistory.UNAVAILABLE, NamedTextColor.GRAY);
        }
        for (int status : new int[] {401, 403}) {
            Cloud cloud = new Cloud(3);
            cloud.findingsFailures.put(cloud.at(2).id(), new HistoryClient.Failure(status, "HTTP " + status));

            Answered answered = answer(cloud, violation -> true);

            assertTrue(answered.local.get());
            assertNote(answered.sent.get(0), CloudHistory.REFUSED, NamedTextColor.RED);
        }
    }

    @Test
    void aSessionTheCloudDoesNotKnowIsNotFound() throws Exception {
        Cloud cloud = new Cloud(3);
        cloud.findingsFailures.put(cloud.at(1).id(), new HistoryClient.Failure(404, "HTTP 404"));

        assertNull(CloudHistory.detail(cloud, PLAYER, "2", 30_000, null).get());
    }

    @Test
    void aBusyCloudFallsBackToThisServerWithANote() {
        Cloud cloud = new Cloud(3);
        cloud.sessionsFailure = new HistoryClient.Failure(503, "HTTP 503");

        Answered answered = answer(cloud, null);

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

            Answered answered = answer(cloud, null);

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

            Answered answered = answer(cloud, null);

            assertTrue(answered.local.get(), failure.toString());
            assertNote(answered.sent.get(0), CloudHistory.UNAVAILABLE, NamedTextColor.GRAY);
        }
    }

    @Test
    void anAnswerFromTheCloudDoesNotFallBack() {
        Answered answered = answer(new Cloud(3), null);

        assertFalse(answered.local.get());
        assertEquals(List.of(List.of(Component.text("3 sessions"))), answered.sent);
    }

    @Test
    void theCloudsStringsRenderAsPlainText() throws Exception {
        Cloud cloud = new Cloud(2);
        cloud.replace(1, CloudHistory.LOST, HOSTILE_SERVER, HOSTILE_BRAND, HOSTILE_VERSION);
        Session session = cloud.at(1);
        cloud.findings.put(
                session.id(),
                List.of(
                        new Finding(session.started() + 1000, 0, "engine", HOSTILE_CHECK, 2.0, HOSTILE_DETAIL),
                        new Finding(session.started() + 2000, 0, HOSTILE_SOURCE, "Fly", 0.5, "&aok")));

        try (AutoCloseable messages = shippedMessages()) {
            CloudHistory.ListPage page =
                    CloudHistory.list(cloud, PLAYER, 1, 10, null).get();
            List<Component> list = HistoryComponentRenderer.renderSessionList(
                    SENDER,
                    PLAYER,
                    "Steve",
                    page.page(),
                    page.maxPages(),
                    new Page<>(page.sessions(), null),
                    page.ongoing(),
                    CloudHistory.ORIGIN);
            CloudHistory.Shown shown =
                    CloudHistory.detail(cloud, PLAYER, "1", 30_000, null).get();
            List<Component> rows = render(shown, true);
            List<Component> grouped = render(shown, false);

            String row = plain(list.get(2));
            assertTrue(row.contains("[" + HOSTILE_VERSION + "] [" + HOSTILE_SERVER + "]"), row);
            assertTrue(row.contains("(crashed)"), row);
            String detail = text(rows);
            assertTrue(detail.contains("Cult: " + HOSTILE_VERSION + ", Server: " + HOSTILE_SERVER), detail);
            assertTrue(detail.contains("Brand: " + HOSTILE_BRAND), detail);
            assertTrue(detail.contains("- " + HOSTILE_CHECK + " (1s) " + HOSTILE_DETAIL), detail);
            assertTrue(detail.contains("- Fly (2s) &aok"), detail);
            assertTrue(detail.contains("Fly — " + HOSTILE_SOURCE), detail);
            String groups = text(grouped);
            assertTrue(groups.contains("- " + HOSTILE_CHECK + " x1, Fly x1 (0s)"), groups);
            assertTrue(groups.contains(HOSTILE_CHECK + " — engine — " + HOSTILE_DETAIL), groups);
            assertFalse(clickCommands(list).isEmpty());
            for (List<Component> lines : List.of(list, rows, grouped)) {
                for (String command : clickCommands(lines)) {
                    assertFalse(command.contains("/op"), command);
                }
            }
        }
    }

    @Test
    void thisServersRecordsRenderTheirTemplateTextAsBefore() throws Exception {
        SessionSummary local = new SessionSummary(
                new UUID(2, 0),
                PLAYER,
                1,
                NEWEST_START,
                NEWEST_START + HOUR,
                NEWEST_START + HOUR + 5,
                "&a2.0",
                "&aHub",
                773,
                "vanilla",
                4,
                1);

        try (AutoCloseable messages = shippedMessages()) {
            List<Component> lines = HistoryComponentRenderer.renderSessionList(
                    SENDER, PLAYER, "Steve", 1, 1, new Page<>(List.of(local), null), null);

            String row = plain(lines.get(1));
            assertTrue(row.contains("[2.0] [Hub]"), row);
        }
    }

    private record Answered(List<List<Component>> sent, AtomicBoolean local) {}

    private static Answered answer(Cloud cloud, @Nullable Predicate<ViolationEntry> filter) {
        Answered answered = new Answered(new ArrayList<>(), new AtomicBoolean());
        CloudHistory.answer(
                CloudHistory.list(cloud, PLAYER, 1, 10, filter)
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

    private static List<Component> render(CloudHistory.@Nullable Shown shown, boolean detailed) {
        assertNotNull(shown);
        return HistoryComponentRenderer.renderSessionDetail(
                SENDER, "Steve", shown.detail(), detailed, true, null, 10, shown.ongoing(), CloudHistory.ORIGIN);
    }

    /** Serves every message's shipped default, with the prefix written out, until closed. */
    private static AutoCloseable shippedMessages() throws ReflectiveOperationException {
        ConfigManager config = mock(ConfigManager.class);
        when(config.getStringElse(anyString(), anyString()))
                .thenAnswer(call -> call.<String>getArgument(1).replace("%prefix%", "Cult »"));
        BaseConfigManager manager = new BaseConfigManager() {
            @Override
            public ConfigManager getConfig() {
                return config;
            }
        };
        Field field = CultAPI.class.getDeclaredField("configManager");
        field.setAccessible(true);
        Object original = field.get(CultAPI.INSTANCE);
        field.set(CultAPI.INSTANCE, manager);
        return () -> field.set(CultAPI.INSTANCE, original);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Every line's text, then the text of every hover in it, one per line. */
    private static String text(List<Component> lines) {
        StringBuilder out = new StringBuilder();
        for (Component line : lines) {
            out.append(plain(line)).append('\n');
            for (Component part : parts(line)) {
                HoverEvent<?> hover = part.hoverEvent();
                if (hover != null && hover.value() instanceof Component shown)
                    out.append(plain(shown)).append('\n');
            }
        }
        return out.toString();
    }

    private static List<String> clickCommands(List<Component> lines) {
        List<String> out = new ArrayList<>();
        for (Component line : lines) {
            for (Component part : parts(line)) {
                ClickEvent click = part.clickEvent();
                if (click != null) out.add(click.toString());
            }
        }
        return out;
    }

    /** {@code root} and every component under it, through children and shown hovers. */
    private static List<Component> parts(Component root) {
        List<Component> out = new ArrayList<>();
        Deque<Component> todo = new ArrayDeque<>(List.of(root));
        while (!todo.isEmpty()) {
            Component part = todo.pop();
            out.add(part);
            todo.addAll(part.children());
            HoverEvent<?> hover = part.hoverEvent();
            if (hover != null && hover.value() instanceof Component shown) todo.add(shown);
        }
        return out;
    }
}
