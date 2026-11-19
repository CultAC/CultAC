package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * ServerPlayer#setGameMode sends player_abilities, then the game event, then player_abilities again. A spectator's
 * movement stays unsimulated until the client answers the ping the new mode applies at; left unanswered past
 * max-ping-out-of-flying, the player is set back, without a flag, to where it was when the mode change was sent.
 */
class SpectatorExitLatencyTest {
    private static final Vec3 HOVER = new Vec3(0.5, FLOOR_Y + 80, 0.5);
    private static final Vec3 STEP = new Vec3(20, 0, 0);
    private static final int LIMIT_MS = 1000;

    @Test
    void anUnansweredModeChangeIsSetBackOnceToWhereItWasSent() throws Exception {
        try (var f = spectating()) {
            String violations = violations(f);
            int[] pings = survival(f);
            // The client answers the abilities packet's ping but not the game event's.
            f.pongThrough(pings[0]);
            Vec3 at = HOVER;
            int setbacks = 0;
            SetBackData seen = null;
            for (int tick = 0; tick < 40; tick++) {
                if (!f.player.getSetbackTeleportUtil().isPendingSetback()) {
                    at = at.add(STEP);
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
            assertTrue(f.player.lastTransactionReceived.get() < pings[1]);
            assertEquals(1, setbacks);
            assertNotNull(seen);
            assertEquals(HOVER, seen.getTeleportData().getLocation());
            assertEquals(violations, violations(f), "the setback flags nothing");
        }
    }

    /** The abilities packet and the game event each revoke movement; one stall on both takes one setback. */
    @Test
    void aWhollyUnansweredModeChangeIsSetBackOnce() throws Exception {
        try (var f = spectating()) {
            abilities(f, false, false);
            int first = f.player.lastTransactionSent.get();
            elapse(f, 50);
            gameMode(f, GameMode.SURVIVAL);
            abilities(f, false, false);
            Vec3 at = HOVER;
            int setbacks = 0;
            SetBackData seen = null;
            for (int tick = 0; tick < 40; tick++) {
                if (!f.player.getSetbackTeleportUtil().isPendingSetback()) {
                    at = at.add(STEP);
                    f.tick(at, false);
                }
                elapse(f, 50);
                SetBackData required = f.player.getSetbackTeleportUtil().getRequiredSetBack();
                if (required != null && required != seen) setbacks++;
                seen = required;
            }
            assertTrue(f.player.lastTransactionReceived.get() < first);
            assertEquals(1, setbacks);
        }
    }

    @Test
    void aModeChangeAnsweredWithinEightHundredMsIsNotSetBack() throws Exception {
        for (int rttMs : new int[] {300, 500, 800}) {
            try (var f = spectating()) {
                String violations = violations(f);
                int[] pings = survival(f);
                int elapsed = 0;
                boolean answered = false;
                double y = HOVER.y;
                double velocity = 0;
                for (int tick = 0; tick < 40; tick++) {
                    if (!answered && elapsed + 50 >= rttMs) {
                        elapse(f, rttMs - elapsed);
                        elapsed = rttMs;
                        f.pongThrough(pings[2]);
                        answered = true;
                    }
                    if (!answered) {
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
                    elapse(f, 50);
                    elapsed += 50;
                    assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback(), rttMs + " ms tick " + tick);
                }
                assertEquals(violations, violations(f), rttMs + " ms");
            }
        }
    }

    @Test
    void aStandingCreativePlayerAnsweringWithinEightHundredMsIsNotSetBack() throws Exception {
        for (int rttMs : new int[] {300, 430, 800, 950}) {
            try (var f = new JavaTickFixture()) {
                f.player.nettyScheduler.removeScheduler();
                f.fixture.channel.freezeTime();
                Vec3 standing = new Vec3(0.5, FLOOR_Y, 0.5);
                f.place(standing, Vec3.ZERO);
                abilities(f, true, false);
                gameMode(f, GameMode.CREATIVE);
                abilities(f, true, false);
                f.pongThrough(f.player.lastTransactionSent.get());
                int[] pings = survival(f);
                for (int elapsed = 0; elapsed < 2_000; elapsed += 50) {
                    if (elapsed == (rttMs / 50) * 50) f.pongThrough(pings[2]);
                    f.tickEnd();
                    elapse(f, 50);
                    assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback(), rttMs + " ms at " + elapsed);
                }
            }
        }
    }

    private static JavaTickFixture spectating() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.nettyScheduler.removeScheduler();
        f.fixture.channel.freezeTime();
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
