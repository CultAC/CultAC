package ac.cult.cultac.cloud.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.SuperDebug;
import ac.cult.cultac.cloud.intake.v3.DiagCoverage;
import ac.cult.cultac.cloud.intake.v3.DiagPart;
import ac.cult.cultac.cloud.intake.v3.DiagTick;
import ac.cult.cultac.cloud.intake.v3.Intake;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The capture's diagnostics hook on a real player: a watch hangs on their SuperDebug, the checks of a tick end land
 * under that tick's seq, a cut answers from the ring with the player's header, and the end of the capture ends the
 * watch. A Bedrock player is refused, and a cut for them answers NONE.
 */
class PredictionDiagnosticsTest {
    private static final byte[] BUNDLE = {9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 1, 2, 3, 4, 5, 6};
    private static final Limits ON = Limits.of(
            true,
            400,
            1 << 20,
            Limits.MAX_SERVER_RING_BYTES,
            Limits.MAX_PLAYERS,
            0,
            Limits.MAX_UPLOAD_BYTES_PER_SECOND,
            0,
            0);
    private static final PredictionDiagnostics HOOK = PredictionDiagnostics.INSTANCE;

    private final DiagUpload upload = new DiagUpload();
    private final Diagnostics diagnostics = new Diagnostics(upload, "hook-test");
    private final List<CultPlayer> players = new ArrayList<>();

    @BeforeAll
    static void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    @AfterEach
    void close() {
        diagnostics.close();
        for (CultPlayer player : players) {
            EmbeddedChannel channel = (EmbeddedChannel) player.user.getChannel();
            player.onRemove();
            channel.finishAndReleaseAll();
        }
        Diagnostics.resetTrip();
    }

    @Test
    void watchedFlagsLandUnderTheirTick() throws Exception {
        diagnostics.connected(1, ON);
        CultPlayer player = player();
        SuperDebug debug = player.checkManager.getListener(SuperDebug.class);
        AtomicLong seq = new AtomicLong();
        HOOK.watch(player, diagnostics, 1, 60, seq::get);
        DiagWatch watch = (DiagWatch) debug.watch();
        assertNotNull(watch);

        for (int tick = 0; tick < 12; tick++) {
            watch.capture(seq.get(), tick / 1000.0, "tick " + tick, "state " + tick, null, System.nanoTime());
            HOOK.tickEnd(player, seq.get(), tick == 5 ? List.of("Simulation") : List.of());
            seq.incrementAndGet();
        }
        await(() -> watch.ring != null && watch.ring.newest() >= 11);

        HOOK.watch(player, diagnostics, 1, 60, seq::get);
        assertSame(watch, debug.watch(), "a renewal keeps the running watch");

        HOOK.cut(player, diagnostics, new Cut(BUNDLE, 3, 7, 5, 5));
        List<DiagPart> parts = parts();
        DiagPart last = parts.get(parts.size() - 1);
        assertEquals(DiagCoverage.DIAG_COVERAGE_FULL, last.getCoverage());
        assertTrue(parts.get(0).getHeader().contains("client "), parts.get(0).getHeader());
        List<DiagTick> ticks = new ArrayList<>();
        for (DiagPart part : parts) ticks.addAll(part.getTicksList());
        assertEquals(5, ticks.size());
        for (DiagTick tick : ticks) {
            assertEquals("tick " + tick.getSeq(), tick.getTrace());
            assertEquals(tick.getSeq() == 5, !tick.getFull().isEmpty());
            assertEquals(tick.getSeq() == 5 ? List.of("Simulation") : List.of(), tick.getFlagsList());
        }

        HOOK.ended(player);
        assertTrue(watch.ended());
        assertEquals("left", watch.endReason);
        assertNull(debug.watch());
    }

    @Test
    void aLeaseOfZeroEndsTheWatch() {
        diagnostics.connected(1, ON);
        CultPlayer player = player();
        SuperDebug debug = player.checkManager.getListener(SuperDebug.class);
        HOOK.watch(player, diagnostics, 1, 60, () -> 0);
        DiagWatch watch = (DiagWatch) debug.watch();
        assertNotNull(watch);

        HOOK.watch(player, diagnostics, 1, 0, () -> 0);
        assertTrue(watch.ended());
        assertNull(debug.watch());
        HOOK.tickEnd(player, 0, List.of("Simulation"));
        HOOK.ended(player);
    }

    @Test
    void aWatchWithoutDiagnosticsOnIsRefusedAndNothingHangs() throws IOException {
        diagnostics.connected(1, null);
        CultPlayer player = player();
        HOOK.watch(player, diagnostics, 1, 60, () -> 0);
        assertNull(player.checkManager.getListener(SuperDebug.class).watch());
        assertNull(upload.poll(1, 0));
    }

    @Test
    void aBedrockPlayerIsRefusedAndTheirCutAnswersNone() throws Exception {
        diagnostics.connected(1, ON);
        UUID id = new UUID(3, 4);
        CultPlayer player = Mockito.mock(CultPlayer.class);
        Mockito.when(player.isBedrockMovement()).thenReturn(true);
        Mockito.when(player.getUniqueId()).thenReturn(id);

        HOOK.watch(player, diagnostics, 1, 60, () -> 0);
        HOOK.cut(player, diagnostics, new Cut(BUNDLE, 0, 5, 0, 0));
        List<DiagPart> parts = parts();
        assertEquals(DiagCoverage.DIAG_COVERAGE_NONE, parts.get(0).getCoverage());
        assertEquals("bedrock", parts.get(0).getReason());
    }

    private CultPlayer player() {
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), "Diag_Hook"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        players.add(player);
        return player;
    }

    private List<DiagPart> parts() throws IOException {
        List<DiagPart> parts = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long nanos = 0;
        while (System.nanoTime() < deadline) {
            byte[] record = upload.poll(1, nanos += TimeUnit.SECONDS.toNanos(10));
            if (record == null) {
                sleep();
                continue;
            }
            DiagPart part = Intake.parseFrom(record).getMessages(0).getDiag();
            parts.add(part);
            if (part.getLast()) return parts;
        }
        throw new AssertionError("no last part");
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out");
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
