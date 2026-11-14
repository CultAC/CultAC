package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.movement.timer.DumbTimer;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.math.Vec3;
import ac.grim.grimac.api.AbstractCheck;
import java.lang.reflect.Field;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * A long teleport leaves DumbTimer's one-second backlog cap as it was. Time passes by moving the balance back, which
 * the check compares against the clock alone.
 */
class DumbTimerTeleportTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Vec3 GROUND = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
    private static final Vec3 FAR = new Vec3(100.5, JavaTickFixture.FLOOR_Y, 0.5);

    @Test
    void aBurstAfterALongTeleportStopsNearOneSecond() throws Exception {
        try (var f = teleported()) {
            DumbTimer dumb = f.player.checkManager.getListener(DumbTimer.class);
            setBalance(dumb, System.nanoTime() - 60e9);
            int accepted = 0;
            while (accepted < 200 && tickEnd(f, dumb)) accepted++;
            assertTrue(accepted <= 24, "burst " + accepted);
        }
    }

    @Test
    void steadyTicksAfterALongTeleportAreNeverRejected() throws Exception {
        try (var f = teleported()) {
            DumbTimer dumb = f.player.checkManager.getListener(DumbTimer.class);
            double violations = violations(f);
            for (int tick = 0; tick < 200; tick++) {
                advance(dumb, 50);
                assertTrue(tickEnd(f, dumb), "tick " + tick);
            }
            assertEquals(violations, violations(f));
        }
    }

    @Test
    void jitteryTicksAfterALongTeleportAreNeverRejected() throws Exception {
        for (long seed = 1; seed <= 5; seed++) {
            try (var f = teleported()) {
                DumbTimer dumb = f.player.checkManager.getListener(DumbTimer.class);
                Random random = new Random(seed);
                // Ticks sent every 50 ms arrive in order after 150 to 450 ms.
                long last = 0;
                long now = 0;
                for (int tick = 0; tick < 400; tick++) {
                    long arrival = Math.max(last, tick * 50L + 150 + random.nextInt(301));
                    advance(dumb, arrival - now);
                    now = arrival;
                    last = arrival;
                    assertTrue(tickEnd(f, dumb), "seed " + seed + " tick " + tick);
                }
            }
        }
    }

    @Test
    void aLagSpikeAfterALongTeleportIsSetBackOnceWithoutAFlag() throws Exception {
        for (int spikeMs : new int[] {2_000, 3_000, 5_000}) {
            try (var f = teleported()) {
                DumbTimer dumb = f.player.checkManager.getListener(DumbTimer.class);
                double violations = violations(f);
                for (int tick = 0; tick < 20; tick++) {
                    advance(dumb, 50);
                    tickEnd(f, dumb);
                }
                // Every tick sent during the spike arrives at its end.
                advance(dumb, spikeMs);
                int setbacks = 0;
                SetBackData seen = f.player.getSetbackTeleportUtil().getRequiredSetBack();
                for (int tick = 0; tick < spikeMs / 50; tick++) {
                    tickEnd(f, dumb);
                    SetBackData required = f.player.getSetbackTeleportUtil().getRequiredSetBack();
                    if (required != null && required != seen) setbacks++;
                    seen = required;
                }
                assertTrue(setbacks <= 1, spikeMs + " ms: " + setbacks + " setbacks");
                assertEquals(violations, violations(f), spikeMs + " ms");
            }
        }
    }

    private static JavaTickFixture teleported() throws Exception {
        var f = new JavaTickFixture();
        f.place(GROUND, Vec3.ZERO);
        f.input(NONE);
        f.tick(GROUND, true);
        f.teleport(FAR, new RelativeFlag(0));
        f.pongThrough(f.player.sendTrailingTransaction());
        DumbTimer dumb = f.player.checkManager.getListener(DumbTimer.class);
        JavaTickFixture.setField(DumbTimer.class, dumb, "limitAbuseOverPing", 1_000_000_000L);
        setBalance(dumb, System.nanoTime());
        return f;
    }

    /** A client tick end; whether DumbTimer accepted it. */
    private static boolean tickEnd(JavaTickFixture f, DumbTimer dumb) {
        var opaque = ServerboundPackets.CLIENT_TICK_END.opaqueValue();
        var event = f.event(ServerboundPackets.CLIENT_TICK_END, opaque);
        boolean pending = f.player.getSetbackTeleportUtil().isPendingSetback();
        dumb.onClientTickEnd(event, f.player, opaque);
        return !event.isCancelled() && (pending || !f.player.getSetbackTeleportUtil().isPendingSetback());
    }

    /** {@code ms} of real time pass. */
    private static void advance(DumbTimer dumb, long ms) throws Exception {
        setBalance(dumb, balance(dumb) - ms * 1e6);
    }

    private static double balance(DumbTimer dumb) throws Exception {
        Field balance = DumbTimer.class.getDeclaredField("balance");
        balance.setAccessible(true);
        return balance.getDouble(dumb);
    }

    private static void setBalance(DumbTimer dumb, double value) throws Exception {
        JavaTickFixture.setField(DumbTimer.class, dumb, "balance", value);
    }

    private static double violations(JavaTickFixture f) {
        double total = 0;
        for (AbstractCheck check : f.player.getChecks()) {
            if (check instanceof Check c) total += c.violations;
        }
        return total;
    }
}
