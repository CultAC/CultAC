package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.cloud.CaptureFixture.Host;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Receiver;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** The upload's lifecycle as the service drives it: a service that turns it off takes every capture out. */
class CloudServiceLifecycleTest {
    @AfterEach
    void restore() {
        CloudService.configure(config(Map.of()), false);
        CloudCapture.uplinks(CloudCapture.Uplinks.NONE);
        CloudCapture.segmentBinder(SegmentBinder.NONE);
    }

    private static ConfigManager config(Map<String, String> strings) {
        ConfigManager config = Mockito.mock(ConfigManager.class);
        Mockito.when(config.getStringElse(Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> strings.getOrDefault(invocation.getArgument(0), invocation.getArgument(1)));
        Mockito.when(config.getBooleanElse(Mockito.eq("cloud.enabled"), Mockito.anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return config;
    }

    @Test
    void aRefusedOfferTurnsTheUploadOff() throws Exception {
        try (CaptureFixture fixture = new CaptureFixture(Host.SERVER, false);
                Receiver receiver = new Receiver()) {
            CloudCapture.segmentBinder((player, bind, playing) -> bind.accept("a"));
            CloudService.configure(
                    config(Map.of("cloud.url", receiver.url(), "cloud.api-key", "test-key", "cloud.proxy-id", "a")),
                    false);
            awaitOnLoop(fixture, () -> fixture.channel.pipeline().get(CloudCapture.INBOUND) != null);
            assertTrue(CloudService.status().get(0).startsWith("Cloud upload on"));
            assertTrue(CloudService.enabled());

            receiver.refuseConnect();
            awaitOnLoop(fixture, () -> fixture.channel.pipeline().get(CloudCapture.INBOUND) == null);
            assertEquals("Cloud upload off", CloudService.status().get(0));
            assertFalse(CloudService.enabled());
        }
    }

    @Test
    void aSupersededGenerationStreamsItsPlayersUnderDrainIdsUntilTheyMove() throws Exception {
        try (CaptureFixture fixture = new CaptureFixture(Host.SERVER, false);
                Receiver receiver = new Receiver()) {
            CloudCapture.segmentBinder((player, bind, playing) -> bind.accept("a"));
            Map<String, String> on =
                    Map.of("cloud.url", receiver.url(), "cloud.api-key", "test-key", "cloud.proxy-id", "a");
            CloudService.configure(config(on), false);
            awaitOnLoop(
                    fixture,
                    () -> receiver.connectBodies.stream().anyMatch(body -> body.contains("\"serverId\":\"a\"")));

            CloudService.superseded("-drain-1a2b3c4d");
            awaitOnLoop(
                    fixture,
                    () -> receiver.connectBodies.stream()
                            .anyMatch(body -> body.contains("\"serverId\":\"a-drain-1a2b3c4d\"")));
            assertNotNull(fixture.channel.pipeline().get(CloudCapture.INBOUND), "the player still streams");
            assertTrue(CloudService.status()
                    .get(0)
                    .startsWith("Cloud upload on for draining players under server ids ending -drain-1a2b3c4d"));

            fixture.channel.eventLoop().execute(() -> CloudCapture.release(fixture.player));
            awaitOnLoop(fixture, () -> fixture.channel.pipeline().get(CloudCapture.INBOUND) == null);
            assertTrue(CloudService.enabled(), "a hand-off moves one player and leaves the upload on");

            CloudService.configure(config(Map.of()), false);
            assertEquals("Cloud upload off", CloudService.status().get(0), "a reload still turns it off");
        } finally {
            CloudService.superseded("");
        }
    }

    @Test
    void workQueuedBeforeAHandOffLeavesThePipelineToTheNewerRuntime() throws Exception {
        try (CaptureFixture fixture = new CaptureFixture(Host.SERVER, false);
                Receiver receiver = new Receiver()) {
            CloudCapture.segmentBinder((player, bind, playing) -> bind.accept("a"));
            CloudService.configure(
                    config(Map.of("cloud.url", receiver.url(), "cloud.api-key", "test-key", "cloud.proxy-id", "a")),
                    false);
            awaitOnLoop(fixture, () -> fixture.channel.pipeline().get(CloudCapture.INBOUND) != null);

            // The update thread supersedes this generation while the player's loop is busy with the hand-off.
            CloudService.superseded("-drain-1a2b3c4d");
            CloudCapture.reconfigure(fixture.player);
            CloudCapture.release(fixture.player);
            CultAPI.INSTANCE.getPlayerDataManager().onDisconnect(fixture.user, true);
            fixture.channel.runPendingTasks();

            assertNull(fixture.channel.pipeline().get(CloudCapture.INBOUND), "the newer runtime's capture can attach");
            assertNull(CloudCapture.of(fixture.player), "the released player gets no capture back");
        } finally {
            CloudService.superseded("");
        }
    }

    @Test
    void aReloadReachesADrainingGeneration() throws Exception {
        try (CaptureFixture fixture = new CaptureFixture(Host.SERVER, false);
                Receiver receiver = new Receiver()) {
            CloudCapture.segmentBinder((player, bind, playing) -> bind.accept("a"));
            Map<String, String> on =
                    Map.of("cloud.url", receiver.url(), "cloud.api-key", "test-key", "cloud.proxy-id", "a");
            CloudService.configure(config(on), false);
            awaitOnLoop(fixture, () -> fixture.channel.pipeline().get(CloudCapture.INBOUND) != null);
            CloudService.superseded("-drain-1a2b3c4d");
            CloudService.configure(config(Map.of()), false);
            awaitOnLoop(fixture, () -> fixture.channel.pipeline().get(CloudCapture.INBOUND) == null);
            assertFalse(CloudService.enabled());
        } finally {
            CloudService.superseded("");
        }
    }

    @Test
    void thePlatformHooksRunOnlyWhenAConfigureTurnsTheUploadOnOrOff() {
        int[] ons = {0};
        int[] offs = {0};
        CloudModule.platformHooks(() -> ons[0]++, () -> offs[0]++);
        try {
            Map<String, String> on =
                    Map.of("cloud.url", "http://127.0.0.1:1", "cloud.api-key", "test-key", "cloud.proxy-id", "a");
            CloudService.configure(config(on), false);
            assertEquals(1, ons[0]);
            CloudService.configure(config(on), false);
            assertEquals(1, ons[0], "a reload that keeps it on is not a turn-on");
            assertEquals(0, offs[0]);
            CloudService.configure(config(Map.of()), false);
            assertEquals(1, offs[0]);
            CloudService.configure(config(on), false);
            assertEquals(2, ons[0]);
        } finally {
            CloudModule.platformHooks(() -> {}, () -> {});
        }
    }

    @Test
    void overlappingConfiguresApplyInOrderAndTheLastWins() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        Map<String, String> on =
                Map.of("cloud.url", "http://127.0.0.1:1", "cloud.api-key", "test-key", "cloud.proxy-id", "a");
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ConfigManager older = Mockito.mock(ConfigManager.class);
        Mockito.when(older.getStringElse(Mockito.anyString(), Mockito.any())).thenAnswer(invocation -> {
            if (invocation.getArgument(0).equals("cloud.server-id")) {
                reading.countDown();
                release.await();
            }
            return on.getOrDefault(invocation.getArgument(0), invocation.getArgument(1));
        });
        ConfigManager newer = config(Map.of());
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread first = new Thread(() -> CloudService.configure(older, false), "configure-older");
        Thread second = new Thread(() -> CloudService.configure(newer, false), "configure-newer");
        for (Thread thread : List.of(first, second)) {
            thread.setUncaughtExceptionHandler((failed, error) -> failures.add(error));
        }
        first.start();
        reading.await();
        second.start();
        while (second.getState() != Thread.State.TERMINATED && second.getState() != Thread.State.BLOCKED) {
            Thread.onSpinWait();
        }
        release.countDown();
        first.join();
        second.join();
        assertEquals(List.of(), failures);
        assertEquals(CloudSettings.OFF, CloudService.settings());
        assertFalse(CloudService.enabled());
    }

    @Test
    void aTurnOffThatArrivesAfterAReloadTurnedItBackOnLeavesTheUploadOn() throws Exception {
        int[] ons = {0};
        int[] offs = {0};
        AtomicInteger binds = new AtomicInteger();
        try (CaptureFixture fixture = new CaptureFixture(Host.SERVER, false);
                Receiver receiver = new Receiver()) {
            Map<String, String> on =
                    Map.of("cloud.url", receiver.url(), "cloud.api-key", "test-key", "cloud.proxy-id", "a");
            CloudModule.platformHooks(
                    () -> {
                        ons[0]++;
                        CloudCapture.segmentBinder((player, bind, playing) -> {
                            binds.incrementAndGet();
                            bind.accept("a");
                        });
                    },
                    () -> {
                        offs[0]++;
                        CloudCapture.segmentBinder(SegmentBinder.NONE);
                    });
            CloudService.configure(config(on), false);
            awaitOnLoop(fixture, () -> fixture.channel.pipeline().get(CloudCapture.INBOUND) != null);
            receiver.refuseConnect();
            awaitOnLoop(
                    fixture, () -> offs[0] == 1 && fixture.channel.pipeline().get(CloudCapture.INBOUND) == null);
            int bound = binds.get();

            turnOffDuringConfigure(on);

            awaitOnLoop(fixture, () -> fixture.channel.pipeline().get(CloudCapture.INBOUND) != null);
            assertTrue(CloudService.enabled());
            assertEquals(2, ons[0]);
            assertEquals(1, offs[0], "the late turn-off ran no hook");
            awaitOnLoop(fixture, () -> binds.get() > bound);
        } finally {
            CloudModule.platformHooks(() -> {}, () -> {});
        }
    }

    @Test
    void aTurnOffThatArrivesAfterAReloadTurnedItOffRunsNoSecondHook() throws Exception {
        int[] ons = {0};
        int[] offs = {0};
        CloudModule.platformHooks(() -> ons[0]++, () -> offs[0]++);
        try (CaptureFixture fixture = new CaptureFixture(Host.SERVER, false)) {
            CloudService.configure(
                    config(Map.of(
                            "cloud.url", "http://127.0.0.1:1", "cloud.api-key", "test-key", "cloud.proxy-id", "a")),
                    false);

            turnOffDuringConfigure(Map.of());

            assertFalse(CloudService.enabled());
            assertEquals(1, ons[0]);
            assertEquals(1, offs[0]);
        } finally {
            CloudModule.platformHooks(() -> {}, () -> {});
        }
    }

    /**
     * Holds a configure to {@code strings} inside the service's configure lock while a turn-off notice from the
     * connections arrives on another thread, then lets the configure finish and waits for both.
     */
    private static void turnOffDuringConfigure(Map<String, String> strings) throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ConfigManager held = Mockito.mock(ConfigManager.class);
        Mockito.when(held.getStringElse(Mockito.anyString(), Mockito.any())).thenAnswer(invocation -> {
            if (invocation.getArgument(0).equals("cloud.server-id")) {
                reading.countDown();
                release.await();
            }
            return strings.getOrDefault(invocation.getArgument(0), invocation.getArgument(1));
        });
        Mockito.when(held.getBooleanElse(Mockito.eq("cloud.enabled"), Mockito.anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread configuring = new Thread(() -> CloudService.configure(held, false), "configure-held");
        Thread notice = new Thread(CloudService::turnedOff, "turned-off");
        for (Thread thread : List.of(configuring, notice)) {
            thread.setUncaughtExceptionHandler((failed, error) -> failures.add(error));
        }
        configuring.start();
        assertTrue(reading.await(10, TimeUnit.SECONDS));
        notice.start();
        while (notice.getState() != Thread.State.TERMINATED && notice.getState() != Thread.State.BLOCKED) {
            Thread.onSpinWait();
        }
        release.countDown();
        configuring.join();
        notice.join();
        assertEquals(List.of(), failures);
    }

    /** Runs the channel's queued tasks until {@code condition} holds. */
    private static void awaitOnLoop(CaptureFixture fixture, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            fixture.channel.runPendingTasks();
            if (condition.getAsBoolean()) return;
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(10);
        }
    }
}
