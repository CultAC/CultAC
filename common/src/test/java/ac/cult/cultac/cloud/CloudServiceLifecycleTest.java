package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.cloud.CaptureFixture.Host;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Receiver;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
