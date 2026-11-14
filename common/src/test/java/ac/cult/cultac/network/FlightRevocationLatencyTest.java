package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.aim.AimDuplicateLook;
import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.PacketPlayerAbilities;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerAbilities;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.math.Vec3;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A flight revocation the client leaves unanswered past max-ping-out-of-flying sets the player back, without a flag, to
 * where it was when the revocation was sent; movements meanwhile stage no safe position.
 */
class FlightRevocationLatencyTest {
    private static final Vec3 HOVER = new Vec3(0.5, FLOOR_Y + 80, 0.5);
    private static final Vec3 JUMP_20 = new Vec3(20, 0, 0);
    /** max-ping-out-of-flying when the config does not set it. */
    private static final int LIMIT_MS = 1000;

    @Test
    void aHeldRevocationIsSetBackOnceToWhereItWasSent() throws Exception {
        try (var f = flying()) {
            String violations = violations(f);
            int revocation = revoke(f);
            Vec3 at = HOVER;
            int setbacks = 0;
            SetBackData seen = null;
            for (int tick = 0; tick < 40; tick++) {
                if (!f.player.getSetbackTeleportUtil().isPendingSetback()) {
                    f.abilities(true);
                    at = at.add(JUMP_20);
                    PredictionResult r = f.tick(at, false);
                    assertTrue(r.isExempt(), "tick " + tick + " " + describe(r));
                }
                elapse(f, 50);
                SetBackData required = f.player.getSetbackTeleportUtil().getRequiredSetBack();
                if (required != null && required != seen) {
                    setbacks++;
                    assertTrue(tick >= LIMIT_MS / 50 - 1, "set back within the limit at tick " + tick);
                }
                seen = required;
            }
            assertTrue(f.player.lastTransactionReceived.get() < revocation);
            assertEquals(1, setbacks);
            assertNotNull(seen);
            assertEquals(HOVER, seen.getTeleportData().getLocation());
            assertEquals(violations, violations(f), "the setback flags nothing");
        }
    }

    @Test
    void aLimitOfZeroOrLessIsOff() throws Exception {
        for (int limit : new int[] {0, -1}) {
            try (var f = flying()) {
                ConfigManager config = CultAPI.INSTANCE.getConfigManager().getConfig();
                Mockito.when(config.getIntElse("max-ping-out-of-flying", 1000)).thenReturn(limit);
                revoke(f);
                Vec3 at = HOVER;
                for (int tick = 0; tick < 40; tick++) {
                    f.abilities(true);
                    at = at.add(JUMP_20);
                    PredictionResult r = f.tick(at, false);
                    assertTrue(r.isExempt(), limit + ": tick " + tick + " " + describe(r));
                    elapse(f, 50);
                    assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback(), limit + ": tick " + tick);
                }
                assertTrue(f.player.getSetbackTeleportUtil().executeTooHighLatencySetback("probe"));
                SetBackData probe = f.player.getSetbackTeleportUtil().getRequiredSetBack();
                assertNotNull(probe);
                assertNotEquals(HOVER, probe.getTeleportData().getLocation(), limit + ": safe positions held");
            } finally {
                ConfigManager config = CultAPI.INSTANCE.getConfigManager().getConfig();
                Mockito.when(config.getIntElse("max-ping-out-of-flying", 1000)).thenReturn(1000);
            }
        }
    }

    @Test
    void aRevocationAnsweredWithinEightHundredMsIsNotSetBack() throws Exception {
        for (int rttMs : new int[] {300, 500, 800}) {
            try (var f = flying()) {
                answeredAfter(f, rttMs, new Random(rttMs), false);
            }
        }
    }

    @Test
    void aJitteryRevocationAnsweredBeforeTheLimitIsNotSetBack() throws Exception {
        for (long seed = 1; seed <= 5; seed++) {
            Random random = new Random(seed);
            try (var f = flying()) {
                answeredAfter(f, 300 + random.nextInt(651), random, true);
            }
        }
    }

    @Test
    void aSpikeAfterARevocationIsSetBackAtMostOnceWithoutAFlag() throws Exception {
        for (int spikeMs : new int[] {2_000, 3_000, 5_000}) {
            try (var f = flying()) {
                String violations = violations(f);
                int revocation = revoke(f);
                // Nothing arrives during the spike; the client hovers until the revocation reaches it.
                elapse(f, spikeMs);
                int setbacks = f.player.getSetbackTeleportUtil().isPendingSetback() ? 1 : 0;
                SetBackData seen = f.player.getSetbackTeleportUtil().getRequiredSetBack();
                for (int tick = 0; tick < spikeMs / 100; tick++) {
                    f.tickEnd();
                    SetBackData required = f.player.getSetbackTeleportUtil().getRequiredSetBack();
                    if (required != null && required != seen) setbacks++;
                    seen = required;
                }
                f.pongThrough(revocation);
                elapse(f, 2_000);
                SetBackData required = f.player.getSetbackTeleportUtil().getRequiredSetBack();
                if (required != null && required != seen) setbacks++;
                assertTrue(setbacks <= 1, spikeMs + " ms: " + setbacks + " setbacks");
                assertEquals(violations, violations(f), spikeMs + " ms");
            }
        }
    }

    /**
     * The client hovers every tick it runs before the revocation's pong and falls after it; it answers {@code rttMs}
     * after the revocation was sent.
     */
    private static void answeredAfter(JavaTickFixture f, int rttMs, Random random, boolean jitter) {
        String violations = violations(f);
        int revocation = revoke(f);
        int elapsed = 0;
        boolean answered = false;
        double y = HOVER.y;
        double velocity = 0;
        for (int tick = 0; tick < 40; tick++) {
            int step = jitter ? 20 + random.nextInt(61) : 50;
            if (!answered && elapsed + step >= rttMs) {
                elapse(f, rttMs - elapsed);
                elapsed = rttMs;
                f.pongThrough(revocation);
                answered = true;
            }
            if (!answered) {
                // A hovering client sends no position.
                f.tickEnd();
            } else {
                // LivingEntity#travelInAir moves by the velocity, then applies gravity and drag.
                double moved = velocity;
                velocity = (velocity - 0.08) * (double) 0.98F;
                y += moved;
                if (moved == 0) {
                    f.tickEnd();
                } else {
                    PredictionResult r = f.tick(new Vec3(HOVER.x, y, HOVER.z), false);
                    assertFalse(r.hasEffectiveFlags(), rttMs + " ms tick " + tick + " " + describe(r));
                }
            }
            elapse(f, step);
            elapsed += step;
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback(), rttMs + " ms tick " + tick);
        }
        assertEquals(violations, violations(f), rttMs + " ms");
    }

    private static JavaTickFixture flying() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.nettyScheduler.removeScheduler();
        f.place(HOVER, Vec3.ZERO);
        abilities(f, true, true);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.tick(HOVER, false).isExempt());
        return f;
    }

    /** A survival player_abilities after flight; the ping it applies at. */
    private static int revoke(JavaTickFixture f) {
        abilities(f, false, false);
        return f.player.lastTransactionSent.get();
    }

    private static void abilities(JavaTickFixture f, boolean canFly, boolean flying) {
        var packet = new ClientboundPlayerAbilities(false, flying, canFly, false, 0.05F, 0.1F);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_ABILITIES, packet, false);
        f.player.checkManager.getListener(PacketPlayerAbilities.class).onPlayerAbilities(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    /** {@code ms} of real time pass on the player's event loop. */
    private static void elapse(JavaTickFixture f, int ms) {
        f.fixture.channel.advanceTimeBy(ms, TimeUnit.MILLISECONDS);
        f.fixture.channel.runScheduledPendingTasks();
    }

    /**
     * Every check's violations but the timers', which compare client ticks against real time the fixture does not pass,
     * and AimDuplicateLook's, since the fixture repeats one look.
     */
    private static String violations(JavaTickFixture f) {
        Map<String, Double> violations = new TreeMap<>();
        for (AbstractCheck check : f.player.getChecks()) {
            if (check instanceof Check c && !(check instanceof AbstractTimerCheck)
                    && !(check instanceof AimDuplicateLook)
                    && c.violations != 0)
                violations.put(c.getClass().getSimpleName(), c.violations);
        }
        return violations.toString();
    }
}
