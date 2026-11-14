package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.anticheat.NettyScheduler;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.grim.grimac.api.AbstractCheck;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A knockback or explosion the client has not proven within max-knockback-ping-ms of its send sets the player back
 * once, without a flag, though the client keeps moving.
 */
class KnockbackLatencyWatchdogTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Vec3 STANDING = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
    /** max-knockback-ping-ms and the watchdog's 50 ms margin. */
    private static final int LIMIT_MS = 1_050;

    @Test
    void aKnockbackHeldWhileMovingIsSetBackOnce() throws Exception {
        try (var f = standing()) {
            f.velocity(new Vec3(0, 0.4, 0));
            long sent = sentAt(f.player.checkManager.getKnockbackHandler().lastSent);
            double violations = violations(f);
            assertEquals(0, watchdogEvery60Ms(f, sent, sent + (LIMIT_MS - 10) * MS), "within the limit");
            assertEquals(1, watchdogEvery60Ms(f, sent + LIMIT_MS * MS, sent + 5_000 * MS), "past the limit");
            assertEquals(violations, violations(f), "the setback flags nothing");
        }
    }

    @Test
    void aKnockbackProvenWithinEightHundredMsIsNotSetBack() throws Exception {
        try (var f = standing()) {
            int proof = f.velocity(new Vec3(0, 0.4, 0));
            long sent = sentAt(f.player.checkManager.getKnockbackHandler().lastSent);
            assertEquals(0, watchdogEvery60Ms(f, sent, sent + 800 * MS));
            f.pongThrough(proof);
            assertEquals(0, watchdogEvery60Ms(f, sent + 800 * MS, sent + 5_000 * MS));
        }
    }

    @Test
    void aJitteryKnockbackProvenAtTheLimitIsNotSetBack() throws Exception {
        try (var f = standing()) {
            int proof = f.velocity(new Vec3(0, 0.4, 0));
            long sent = sentAt(f.player.checkManager.getKnockbackHandler().lastSent);
            assertEquals(0, watchdogEvery60Ms(f, sent, sent + (LIMIT_MS - 10) * MS));
            f.pongThrough(proof);
            assertEquals(0, watchdogEvery60Ms(f, sent + LIMIT_MS * MS, sent + 3_000 * MS));
        }
    }

    @Test
    void aSpikeWithNoMovesArrivingIsNotSetBackTwice() throws Exception {
        try (var f = standing()) {
            f.velocity(new Vec3(0, 0.4, 0));
            long sent = sentAt(f.player.checkManager.getKnockbackHandler().lastSent);
            int setbacks = 0;
            SetBackData seen = null;
            for (long now = sent; now <= sent + 4_000 * MS; now += 60 * MS) {
                watchdog(f, now);
                SetBackData required = f.player.getSetbackTeleportUtil().getRequiredSetBack();
                if (required != null && required != seen) setbacks++;
                seen = required;
            }
            assertTrue(setbacks <= 1, "setbacks " + setbacks);
        }
    }

    @Test
    void aRidingPlayerIsNotSetBack() throws Exception {
        try (var f = standing()) {
            f.velocity(new Vec3(0, 0.4, 0));
            long sent = sentAt(f.player.checkManager.getKnockbackHandler().lastSent);
            f.player.compensatedEntities.getSelf().mount(new PacketEntity(EntityTypeIds.OAK_BOAT, 99));
            assertEquals(0, watchdogEvery60Ms(f, sent, sent + 3_000 * MS));
        }
    }

    @Test
    void anExplosionHeldWhileMovingIsSetBack() throws Exception {
        try (var f = standing()) {
            f.explode(new Vec3(0, 0.4, 0));
            TransactionVel explosion = f.player.checkManager.getExplosionHandler().lastSent;
            assertNotNull(explosion);
            assertNull(f.player.checkManager.getKnockbackHandler().lastSent);
            long sent = sentAt(explosion);
            assertEquals(0, watchdogEvery60Ms(f, sent, sent + (LIMIT_MS - 10) * MS));
            assertEquals(1, watchdogEvery60Ms(f, sent + LIMIT_MS * MS, sent + 3_000 * MS));
        }
    }

    @Test
    void aBedrockPlayerIsJudgedByTheMovementStallAndTheLastPingsRoundTrip() throws Exception {
        Method exceeded = NettyScheduler.class.getDeclaredMethod("knockbackLatencyExceeded", long.class);
        exceeded.setAccessible(true);
        long limit = LIMIT_MS * MS;
        int acting = 0;
        int idle = 0;
        for (Long age : new Long[] {null, 10 * MS, limit + 100 * MS}) {
            for (boolean knockback : new boolean[] {false, true}) {
                for (boolean explosion : new boolean[] {false, true}) {
                    for (long pingMs : new long[] {0, LIMIT_MS + 100}) {
                        for (boolean riding : new boolean[] {false, true}) {
                            String at = "age " + age + " knockback " + knockback + " explosion " + explosion
                                    + " ping " + pingMs + " riding " + riding;
                            try (var f = standing()) {
                                f.player.movementPlatform = MovementPlatform.BEDROCK;
                                // Both impulses have waited unproven for two limits.
                                long now = System.nanoTime() + 2 * limit;
                                var moves = f.player.checkManager.getSimulationProcessor().getLastMovementTime();
                                moves.clear();
                                if (age != null) moves.add(now - age);
                                f.player.checkManager.getKnockbackHandler().lastSent =
                                        knockback ? new TransactionVel(new Vec3(0, .4, 0), 1_000, true, false) : null;
                                f.player.checkManager.getExplosionHandler().lastSent =
                                        explosion ? new TransactionVel(new Vec3(0, .4, 0), 1_000, true, false) : null;
                                f.player.rawTransactionPing = pingMs * MS;
                                if (riding)
                                    f.player.compensatedEntities.getSelf().mount(
                                            new PacketEntity(EntityTypeIds.OAK_BOAT, 99));
                                boolean expected = knockback
                                        && !riding
                                        && age != null
                                        && Math.max(age / MS, f.player.getTransactionPing()) > LIMIT_MS;
                                boolean actual = (boolean) exceeded.invoke(f.player.nettyScheduler, now);
                                assertEquals(expected, actual, at);
                                if (actual) acting++;
                                else idle++;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(acting > 0, "no case acted");
        assertTrue(idle > 0, "every case acted");
    }

    private static JavaTickFixture standing() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.nettyScheduler.removeScheduler();
        f.place(STANDING, Vec3.ZERO);
        f.input(NONE);
        assertTrue(exact(f.tick(STANDING, true)));
        return f;
    }

    private static long sentAt(TransactionVel velocity) {
        assertNotNull(velocity);
        return velocity.getSentAt();
    }

    /** The watchdog every 60 ms from {@code from} through {@code to}, with a move arriving 10 ms before each fire. */
    private static int watchdogEvery60Ms(JavaTickFixture f, long from, long to) throws Exception {
        int setbacks = 0;
        SetBackData seen = f.player.getSetbackTeleportUtil().getRequiredSetBack();
        for (long now = from; now <= to; now += 60 * MS) {
            f.player.checkManager.getSimulationProcessor().getLastMovementTime().add(now - 10 * MS);
            watchdog(f, now);
            SetBackData required = f.player.getSetbackTeleportUtil().getRequiredSetBack();
            if (required != null && required != seen) setbacks++;
            seen = required;
        }
        return setbacks;
    }

    private static void watchdog(JavaTickFixture f, long now) throws Exception {
        Method idle = NettyScheduler.class.getDeclaredMethod("idle", long.class);
        Method knockback = NettyScheduler.class.getDeclaredMethod("watchdogKnockbackLatency", long.class);
        idle.setAccessible(true);
        knockback.setAccessible(true);
        boolean wasIdle = (boolean) idle.invoke(f.player.nettyScheduler, now);
        SetBackData before = f.player.getSetbackTeleportUtil().getRequiredSetBack();
        knockback.invoke(f.player.nettyScheduler, now);
        f.fixture.channel.runPendingTasks();
        if (wasIdle) assertEquals(before, f.player.getSetbackTeleportUtil().getRequiredSetBack(), "an idle fire acted");
    }

    private static double violations(JavaTickFixture f) {
        double total = 0;
        for (AbstractCheck check : f.player.getChecks()) {
            if (check instanceof Check c) total += c.violations;
        }
        return total;
    }
}
