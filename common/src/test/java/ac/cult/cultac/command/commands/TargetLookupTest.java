package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ac.cult.cultac.cloud.labels.LabelClient.Reply;
import ac.cult.cultac.platform.api.sender.Sender;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

class TargetLookupTest {
    private static final UUID TYPED = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final String TYPED_PLAIN = "069a79f444e94726a5befca90e38aaf5";
    private static final UUID ONLINE = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final String MOJANG_BODY = "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}";
    private static final Executor OFF_THREAD = task -> {
        Thread thread = new Thread(task, "lookup-test");
        thread.setDaemon(true);
        thread.start();
    };

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<Thread> callThread = new AtomicReference<>();
    private final List<String> urls = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong();

    private TargetLookup lookup(
            Function<String, CloudArguments.Target> online,
            Function<String, CompletableFuture<Reply>> http,
            Duration timeout) {
        MojangProfiles mojang = new MojangProfiles(
                url -> {
                    calls.incrementAndGet();
                    callThread.set(Thread.currentThread());
                    synchronized (urls) {
                        urls.add(url);
                    }
                    return http.apply(url);
                },
                clock::get);
        return new TargetLookup(online, mojang, OFF_THREAD, timeout);
    }

    private TargetLookup lookup(Function<String, CompletableFuture<Reply>> http) {
        return lookup(name -> null, http, Duration.ofSeconds(5));
    }

    private static CompletableFuture<Reply> reply(int status, String body) {
        return CompletableFuture.completedFuture(new Reply(status, body));
    }

    @Test
    void aTypedUuidIsUsedWithoutAskingAnyone() {
        TargetLookup lookup = lookup(name -> null, url -> reply(404, ""), Duration.ofSeconds(5));
        assertEquals(
                new CloudArguments.Target(TYPED, TYPED.toString()),
                lookup.resolve(TYPED.toString()).join().target());
        assertEquals(TYPED, lookup.resolve(TYPED_PLAIN).join().target().id());
        assertEquals(0, calls.get());
    }

    @Test
    void anOnlinePlayerIsUsedWithoutAskingMojang() {
        CloudArguments.Target steve = new CloudArguments.Target(ONLINE, "Steve");
        TargetLookup lookup = lookup(name -> steve, url -> reply(404, ""), Duration.ofSeconds(5));
        CompletableFuture<TargetLookup.Outcome> outcome = lookup.resolve("steve");
        assertTrue(outcome.isDone());
        assertEquals(steve, outcome.join().target());
        assertEquals(0, calls.get());
    }

    @Test
    void anyOtherNameGoesToMojangWhichFindsIt() {
        TargetLookup.Outcome outcome =
                lookup(url -> reply(200, MOJANG_BODY)).resolve("notch").join();
        assertEquals(new CloudArguments.Target(TYPED, "Notch"), outcome.target());
        assertEquals(List.of("https://api.mojang.com/users/profiles/minecraft/notch"), urls);
    }

    @Test
    void mojangNotKnowingTheNameIsAClearMiss() {
        TargetLookup.Outcome outcome =
                lookup(url -> reply(404, "{}")).resolve("ghost").join();
        assertNull(outcome.target());
        assertEquals("Mojang has no player named ghost (use their UUID if the name changed).", outcome.problem());
    }

    @Test
    void mojangRateLimitingIsAClearMessage() {
        TargetLookup.Outcome outcome =
                lookup(url -> reply(429, "")).resolve("ghost").join();
        assertNull(outcome.target());
        assertTrue(outcome.problem().contains("rate limiting"), outcome.problem());
    }

    @Test
    void otherMojangFailuresNameTheStatusAndGarbageIsReported() {
        assertEquals(
                "Mojang lookup failed (HTTP 500).",
                lookup(url -> reply(500, "")).resolve("ghost").join().problem());
        assertEquals(
                "Mojang lookup failed: unreadable answer.",
                lookup(url -> reply(200, "{\"id\":\"zz\",\"name\":\"x\"}"))
                        .resolve("ghost")
                        .join()
                        .problem());
    }

    @Test
    void aSlowMojangTimesOutWithAClearMessage() {
        TargetLookup.Outcome outcome = lookup(name -> null, url -> new CompletableFuture<>(), Duration.ofMillis(100))
                .resolve("ghost")
                .join();
        assertNull(outcome.target());
        assertEquals("Looking up ghost with Mojang timed out; try again, or use their UUID.", outcome.problem());
    }

    @Test
    void aTransportFailureIsReportedNotThrown() {
        CompletableFuture<Reply> broken = new CompletableFuture<>();
        broken.completeExceptionally(new ConnectException("refused"));
        TargetLookup.Outcome outcome = lookup(url -> broken).resolve("ghost").join();
        assertNull(outcome.target());
        assertEquals("Mojang did not answer: could not connect.", outcome.problem());
    }

    @Test
    void aFoundNameIsRememberedForTenMinutesThenAskedAgain() {
        TargetLookup lookup = lookup(url -> reply(200, MOJANG_BODY));
        assertEquals("Notch", lookup.resolve("Notch").join().target().name());
        assertEquals("Notch", lookup.resolve("NOTCH").join().target().name());
        assertEquals(1, calls.get());
        clock.set(Duration.ofMinutes(10).toNanos() - 1);
        lookup.resolve("notch").join();
        assertEquals(1, calls.get());
        clock.set(Duration.ofMinutes(10).toNanos());
        lookup.resolve("notch").join();
        assertEquals(2, calls.get());
    }

    @Test
    void theCacheIsBoundedAndDropsExpiredNamesWhenOneIsAdded() {
        MojangProfiles mojang = new MojangProfiles(url -> reply(200, MOJANG_BODY), clock::get);
        for (int i = 0; i < MojangProfiles.MAX_ENTRIES + 10; i++)
            mojang.lookup("n" + i).join();
        assertEquals(MojangProfiles.MAX_ENTRIES, mojang.size());
        assertNull(mojang.cached("n0"));
        assertNotNull(mojang.cached("n" + (MojangProfiles.MAX_ENTRIES + 9)));
        clock.addAndGet(MojangProfiles.TTL.toNanos());
        mojang.lookup("fresh").join();
        assertEquals(1, mojang.size());
    }

    @Test
    void lookupsInFlightAreCapped() {
        List<CompletableFuture<Reply>> pending = new ArrayList<>();
        MojangProfiles mojang = new MojangProfiles(
                url -> {
                    CompletableFuture<Reply> call = new CompletableFuture<>();
                    pending.add(call);
                    return call;
                },
                clock::get);
        List<CompletableFuture<TargetLookup.Outcome>> running = new ArrayList<>();
        for (int i = 0; i < MojangProfiles.MAX_LOOKUPS; i++) running.add(mojang.lookup("n" + i));
        TargetLookup.Outcome refused = mojang.lookup("extra").getNow(null);
        assertNotNull(refused);
        assertNull(refused.target());
        assertNotNull(refused.problem());
        assertEquals(MojangProfiles.MAX_LOOKUPS, pending.size());

        pending.get(0).complete(new Reply(200, MOJANG_BODY));
        assertNotNull(running.get(0).join().target());
        pending.get(1).completeExceptionally(new ConnectException("refused"));
        assertTrue(running.get(1).isCompletedExceptionally());
        mojang.lookup("again");
        mojang.lookup("more");
        assertEquals(MojangProfiles.MAX_LOOKUPS + 2, pending.size());
        assertNull(mojang.lookup("over").getNow(null).target());
    }

    @Test
    void anOversizedMojangAnswerIsCutOff() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        CompletableFuture<Long> sent = new CompletableFuture<>();
        long total = 32 << 20;
        server.createContext("/", exchange -> {
            long written = 0;
            byte[] block = new byte[64 << 10];
            try {
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                while (written < total) {
                    out.write(block);
                    written += block.length;
                }
                out.close();
            } catch (IOException cutOff) {
            } finally {
                sent.complete(written);
                exchange.close();
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/Notch";
            assertThrows(
                    ExecutionException.class,
                    () -> MojangProfiles.get(HttpClient.newHttpClient(), url).get(20, TimeUnit.SECONDS));
            assertTrue(sent.get(20, TimeUnit.SECONDS) < total);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missesAreNotRemembered() {
        TargetLookup lookup = lookup(url -> reply(404, ""));
        lookup.resolve("ghost").join();
        lookup.resolve("ghost").join();
        assertEquals(2, calls.get());
    }

    @Test
    void theNetworkCallNeverRunsOnTheCallingThread() {
        Thread caller = Thread.currentThread();
        lookup(url -> reply(404, "")).resolve("ghost").join();
        assertNotNull(callThread.get());
        assertNotEquals(caller, callThread.get());
    }

    @Test
    void textThatCannotBeANameNeverGoesToMojang() {
        for (String bad : List.of("a b", "name&x=1", "../x", "seventeen_chars_ok", "Alex.1", "")) {
            TargetLookup.Outcome outcome =
                    lookup(url -> reply(200, MOJANG_BODY)).resolve(bad).join();
            assertNull(outcome.target(), bad);
            assertTrue(outcome.problem().contains("is not a player name"), bad);
        }
        assertEquals(0, calls.get());
    }

    @Test
    void aSixteenCharacterNameIsAccepted() {
        assertFalse(TargetLookup.NAME.matcher("a1234567890_bcdef").matches());
        assertTrue(TargetLookup.NAME.matcher("a1234567890_bcde").matches());
    }

    @Test
    void anOfflineModeServerRefusesLabelingAndNeverResolves() {
        Sender sender = mock(Sender.class);
        assertFalse(CloudArguments.labelingAllowed(sender, () -> false));
        verify(sender).sendMessage(Component.text(CloudArguments.CRACKED, NamedTextColor.RED));
        assertEquals("Labeling is disabled on offline-mode servers.", CloudArguments.CRACKED);
    }

    @Test
    void anOnlineModeServerAllowsLabeling() {
        Sender sender = mock(Sender.class);
        assertTrue(CloudArguments.labelingAllowed(sender, () -> true));
        verify(sender, never()).sendMessage(any(Component.class));
    }
}
