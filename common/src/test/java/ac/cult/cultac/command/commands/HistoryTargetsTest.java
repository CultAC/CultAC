package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import ac.cult.cultac.cloud.labels.LabelClient.Reply;
import ac.cult.cultac.network.NetworkPlayer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HistoryTargetsTest {
    private static final UUID ROSTER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID KNOWN = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID MOJANG = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final String MOJANG_BODY = "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}";
    private static final Executor OFF_THREAD = task -> {
        Thread thread = new Thread(task, "history-lookup-test");
        thread.setDaemon(true);
        thread.start();
    };

    private final List<String> asked = new ArrayList<>();
    private final AtomicReference<Thread> identityThread = new AtomicReference<>();

    private HistoryTargets targets(Map<String, NetworkPlayer> online, Map<String, UUID> known) {
        MojangProfiles mojang = new MojangProfiles(
                url -> {
                    record("mojang");
                    return CompletableFuture.completedFuture(
                            url.toLowerCase(Locale.ROOT).endsWith("/notch")
                                    ? new Reply(200, MOJANG_BODY)
                                    : new Reply(404, ""));
                },
                System::nanoTime);
        return new HistoryTargets(
                name -> {
                    record("roster");
                    return Optional.ofNullable(online.get(name.toLowerCase(Locale.ROOT)));
                },
                name -> {
                    record("identities");
                    identityThread.set(Thread.currentThread());
                    return Optional.ofNullable(known.get(name.toLowerCase(Locale.ROOT)));
                },
                new TargetLookup(name -> null, mojang, OFF_THREAD, Duration.ofSeconds(5)),
                OFF_THREAD);
    }

    private void record(String step) {
        synchronized (asked) {
            asked.add(step);
        }
    }

    private static TargetLookup.Outcome resolve(HistoryTargets targets, String input) throws Exception {
        return targets.resolve(input).get(10, TimeUnit.SECONDS);
    }

    @Test
    void aPlayerOnlineAnywhereIsFoundFirst() throws Exception {
        HistoryTargets targets = targets(
                Map.of("notch", new NetworkPlayer(ROSTER, "Notch", "proxy-2", "survival")), Map.of("notch", KNOWN));

        TargetLookup.Outcome outcome = resolve(targets, "notch");

        assertNotNull(outcome.target());
        assertEquals(ROSTER, outcome.target().id());
        assertEquals("Notch", outcome.target().name());
        assertEquals(List.of("roster"), asked);
    }

    @Test
    void thenAPlayerThisServerHasSeen() throws Exception {
        HistoryTargets targets = targets(Map.of(), Map.of("notch", KNOWN));

        TargetLookup.Outcome outcome = resolve(targets, "Notch");

        assertNotNull(outcome.target());
        assertEquals(KNOWN, outcome.target().id());
        assertEquals("Notch", outcome.target().name());
        assertEquals(List.of("roster", "identities"), asked);
        assertNotSame(Thread.currentThread(), identityThread.get());
    }

    @Test
    void thenMojangForAPlayerNeverSeenHere() throws Exception {
        HistoryTargets targets = targets(Map.of(), Map.of());

        TargetLookup.Outcome outcome = resolve(targets, "Notch");

        assertNotNull(outcome.target());
        assertEquals(MOJANG, outcome.target().id());
        assertEquals("Notch", outcome.target().name());
        assertEquals(List.of("roster", "identities", "mojang"), asked);
    }

    @Test
    void aTypedUuidNeedsNoMojang() throws Exception {
        HistoryTargets targets = targets(Map.of(), Map.of());

        TargetLookup.Outcome outcome = resolve(targets, MOJANG.toString());

        assertNotNull(outcome.target());
        assertEquals(MOJANG, outcome.target().id());
        assertEquals(List.of("roster", "identities"), asked);
    }

    @Test
    void aNameNobodyKnowsSaysWhy() throws Exception {
        HistoryTargets targets = targets(Map.of(), Map.of());

        TargetLookup.Outcome outcome = resolve(targets, "Nobody_42");
        TargetLookup.Outcome invalid = resolve(targets, "not a name");

        assertNull(outcome.target());
        assertNotNull(outcome.problem());
        assertNull(invalid.target());
        assertNotNull(invalid.problem());
    }
}
