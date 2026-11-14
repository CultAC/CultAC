package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.aim.AimDuplicateLook;
import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.PacketChangeGameState;
import ac.cult.cultac.events.packets.PacketPlayerAbilities;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundGameEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerAbilities;
import ac.cult.cultac.protocol.value.GameEventType;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.math.Vec3;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Creative to survival as ServerPlayer#setGameMode sends it: player_abilities, the game event, player_abilities. The
 * client answers the three pings together one round trip after the change was sent. Below max-ping-out-of-flying that
 * is never set back; past it, or never answered, the player is set back once, without a flag, to where it was when the
 * change was sent.
 */
class ModeChangeRoundTripTest {
    private static final Vec3 HOVER = new Vec3(0.5, FLOOR_Y + 80, 0.5);
    private static final Vec3 STEP = new Vec3(20, 0, 0);
    private static final int DEFAULT_LIMIT_MS = 1000;

    @Test
    void aRoundTripBelowTheLimitIsNotSetBack() throws Exception {
        for (int rttMs : new int[] {300, 430, 800, 950}) {
            try (var f = creativeHovering()) {
                String violations = violations(f);
                int[] pings = survival(f);
                int setbacks = run(f, rttMs, pings[2]);
                assertEquals(0, setbacks, rttMs + " ms");
                assertEquals(violations, violations(f), rttMs + " ms");
            }
        }
    }

    @Test
    void aRoundTripPastTheLimitIsSetBackOnceToWhereTheChangeWasSent() throws Exception {
        for (int[] limitAndRtt : new int[][] {{300, 430}, {1000, 1200}}) {
            int limit = limitAndRtt[0];
            int rttMs = limitAndRtt[1];
            try (var f = creativeHovering()) {
                setLimit(limit);
                String violations = violations(f);
                int[] pings = survival(f);
                SetBackData[] first = new SetBackData[1];
                int[] firstAtMs = {-1};
                int setbacks = run(f, rttMs, pings[2], first, firstAtMs);
                assertEquals(1, setbacks, limit + "/" + rttMs + " ms");
                assertTrue(firstAtMs[0] >= limit && firstAtMs[0] < limit + 50, "set back at " + firstAtMs[0] + " ms");
                assertNotNull(first[0]);
                assertEquals(HOVER, first[0].getTeleportData().getLocation());
                assertEquals(violations, violations(f), "the setback flags nothing");
            } finally {
                setLimit(DEFAULT_LIMIT_MS);
            }
        }
    }

    @Test
    void aWithheldAnswerIsSetBackOnceAtTheLimitToWhereTheChangeWasSent() throws Exception {
        try (var f = creativeHovering()) {
            String violations = violations(f);
            int[] pings = survival(f);
            SetBackData[] first = new SetBackData[1];
            int[] firstAtMs = {-1};
            int setbacks = run(f, Integer.MAX_VALUE, pings[2], first, firstAtMs);
            assertTrue(f.player.lastTransactionReceived.get() < pings[0]);
            assertEquals(1, setbacks);
            assertTrue(
                    firstAtMs[0] >= DEFAULT_LIMIT_MS && firstAtMs[0] < DEFAULT_LIMIT_MS + 50,
                    "set back at " + firstAtMs[0] + " ms");
            assertEquals(HOVER, first[0].getTeleportData().getLocation());
            assertEquals(violations, violations(f), "the setback flags nothing");
        }
    }

    @Test
    void aSpectatorExitWithheldOrAnsweredBehavesTheSame() throws Exception {
        for (int rttMs : new int[] {300, 430, 800, 950, Integer.MAX_VALUE}) {
            try (var f = spectating()) {
                String violations = violations(f);
                int[] pings = survival(f);
                SetBackData[] first = new SetBackData[1];
                int[] firstAtMs = {-1};
                int setbacks = run(f, rttMs, pings[2], first, firstAtMs);
                if (rttMs < DEFAULT_LIMIT_MS) {
                    assertEquals(0, setbacks, rttMs + " ms");
                } else {
                    assertEquals(1, setbacks);
                    assertTrue(
                            firstAtMs[0] >= DEFAULT_LIMIT_MS && firstAtMs[0] < DEFAULT_LIMIT_MS + 50,
                            "set back at " + firstAtMs[0] + " ms");
                    assertEquals(HOVER, first[0].getTeleportData().getLocation());
                }
                assertEquals(violations, violations(f), rttMs + " ms");
            }
        }
    }

    private static int run(JavaTickFixture f, int rttMs, int answer) {
        return run(f, rttMs, answer, new SetBackData[1], new int[] {-1});
    }

    /**
     * Two seconds of 50 ms client ticks: until the answer the client still flies and moves {@link #STEP} a tick; it
     * answers every ping through {@code answer} {@code rttMs} after the change was sent and then falls.
     */
    private static int run(JavaTickFixture f, int rttMs, int answer, SetBackData[] first, int[] firstAtMs) {
        Vec3 at = HOVER;
        boolean answered = false;
        double velocity = 0;
        int setbacks = 0;
        SetBackData seen = null;
        for (int elapsed = 0; elapsed < 2_000; elapsed += 50) {
            if (!answered && elapsed + 50 > rttMs) {
                elapse(f, rttMs - elapsed);
                f.pongThrough(answer);
                answered = true;
                elapse(f, elapsed + 50 - rttMs);
            } else {
                elapse(f, 50);
            }
            SetBackData required = f.player.getSetbackTeleportUtil().getRequiredSetBack();
            if (required != null && required != seen) {
                if (setbacks++ == 0) {
                    first[0] = required;
                    firstAtMs[0] = elapsed + 50;
                }
            }
            seen = required;
            if (f.player.getSetbackTeleportUtil().isPendingSetback()) continue;
            if (!answered) {
                at = at.add(STEP);
                PredictionResult r = f.tick(at, false);
                assertTrue(r.isExempt(), elapsed + " ms " + describe(r));
            } else {
                // LivingEntity#travelInAir moves by the velocity, then applies gravity and drag.
                double moved = velocity;
                velocity = (velocity - 0.08) * (double) 0.98F;
                at = at.add(0, moved, 0);
                if (moved == 0) {
                    f.tickEnd();
                } else {
                    PredictionResult r = f.tick(at, false);
                    assertFalse(r.hasEffectiveFlags(), rttMs + " ms at " + elapsed + " " + describe(r));
                }
            }
        }
        return setbacks;
    }

    private static JavaTickFixture creativeHovering() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.nettyScheduler.removeScheduler();
        f.place(HOVER, Vec3.ZERO);
        abilities(f, true, true);
        gameMode(f, GameMode.CREATIVE);
        abilities(f, true, true);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.tick(HOVER, false).isExempt());
        return f;
    }

    private static JavaTickFixture spectating() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.nettyScheduler.removeScheduler();
        f.place(HOVER, Vec3.ZERO);
        gameMode(f, GameMode.SPECTATOR);
        abilities(f, true, true);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.tick(HOVER, false).isExempt());
        return f;
    }

    /** ServerPlayer#setGameMode(SURVIVAL); the pings the abilities, the game event and the last abilities apply at. */
    private static int[] survival(JavaTickFixture f) {
        abilities(f, false, false);
        int first = f.player.lastTransactionSent.get();
        gameMode(f, GameMode.SURVIVAL);
        int mode = f.player.lastTransactionSent.get();
        abilities(f, false, false);
        return new int[] {first, mode, f.player.lastTransactionSent.get()};
    }

    private static void setLimit(int limitMs) {
        ConfigManager config = CultAPI.INSTANCE.getConfigManager().getConfig();
        Mockito.when(config.getIntElse("max-ping-out-of-flying", 1000)).thenReturn(limitMs);
    }

    private static void gameMode(JavaTickFixture f, GameMode mode) {
        var packet = new ClientboundGameEvent(GameEventType.CHANGE_GAME_MODE, switch (mode) {
            case SPECTATOR -> 3;
            case CREATIVE -> 1;
            default -> 0;
        });
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.GAME_EVENT, packet, false);
        f.player.checkManager.getListener(PacketChangeGameState.class).onGameEvent(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    private static void abilities(JavaTickFixture f, boolean canFly, boolean flying) {
        var packet = new ClientboundPlayerAbilities(false, flying, canFly, false, 0.05F, 0.1F);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_ABILITIES, packet, false);
        f.player.checkManager.getListener(PacketPlayerAbilities.class).onPlayerAbilities(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    private static void elapse(JavaTickFixture f, int ms) {
        if (ms <= 0) return;
        f.fixture.channel.advanceTimeBy(ms, TimeUnit.MILLISECONDS);
        f.fixture.channel.runScheduledPendingTasks();
    }

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
