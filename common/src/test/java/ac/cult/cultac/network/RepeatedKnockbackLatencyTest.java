package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.anticheat.NettyScheduler;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Knockbacks arriving one after another age from the oldest the client has not proven: a later knockback does not
 * restart the wait for an earlier one.
 */
class RepeatedKnockbackLatencyTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Vec3 STANDING = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
    /** max-knockback-ping-ms and the watchdog's 50 ms margin. */
    private static final int LIMIT_MS = 1_050;

    @Test
    void knockbacksLeftUnprovenAreSetBackOnceTheFirstPassesTheLimit() throws Exception {
        for (int gapMs : new int[] {1_000, 900, 500}) {
            try (var f = standing()) {
                long start = System.nanoTime();
                long firstSetback = -1;
                int setbacks = 0;
                SetBackData seen = null;
                long nextHit = start;
                for (long now = start; now <= start + 15_000 * MS; now += 60 * MS) {
                    while (nextHit <= now) {
                        hit(f, nextHit);
                        nextHit += gapMs * MS;
                    }
                    f.player.checkManager.getSimulationProcessor().getLastMovementTime().add(now - 10 * MS);
                    watchdog(f, now);
                    SetBackData required = f.player.getSetbackTeleportUtil().getRequiredSetBack();
                    if (required != null && required != seen) {
                        setbacks++;
                        if (firstSetback < 0) firstSetback = (now - start) / MS;
                    }
                    seen = required;
                }
                assertEquals(1, setbacks, gapMs + " ms apart");
                assertTrue(firstSetback >= LIMIT_MS && firstSetback < LIMIT_MS + 60, gapMs + " ms: " + firstSetback);
            }
        }
    }

    @Test
    void knockbacksEachProvenWithinEightHundredMsAreNotSetBack() throws Exception {
        for (int rttMs : new int[] {300, 430, 800}) {
            for (int gapMs : new int[] {1_000, 500, 250}) {
                try (var f = standing()) {
                    long start = System.nanoTime();
                    ArrayDeque<long[]> inFlight = new ArrayDeque<>();
                    int setbacks = 0;
                    long nextHit = start;
                    for (long now = start; now <= start + 10_000 * MS; now += 20 * MS) {
                        while (!inFlight.isEmpty() && inFlight.peekFirst()[0] + rttMs * MS <= now) {
                            f.pongThrough((int) inFlight.pollFirst()[1]);
                        }
                        if (nextHit <= now) {
                            inFlight.addLast(new long[] {nextHit, hit(f, nextHit)});
                            nextHit += gapMs * MS;
                        }
                        f.player.checkManager.getSimulationProcessor().getLastMovementTime().add(now - 10 * MS);
                        if ((now - start) % (60 * MS) == 0) {
                            SetBackData before = f.player.getSetbackTeleportUtil().getRequiredSetBack();
                            watchdog(f, now);
                            if (f.player.getSetbackTeleportUtil().getRequiredSetBack() != before) setbacks++;
                        }
                    }
                    assertEquals(0, setbacks, rttMs + " ms round trip, " + gapMs + " ms apart");
                }
            }
        }
    }

    /**
     * The setback stays pending behind the held pings, and every move waits on it; knockbacks sent meanwhile are aged
     * from their own sends, so once the client proves and accepts the setback, one it leaves unproven past the limit is
     * set back again.
     */
    @Test
    void aKnockbackLeftUnprovenAfterAnAcceptedSetbackIsSetBackAgain() throws Exception {
        try (var f = standing()) {
            long start = System.nanoTime();
            long now = start;
            hit(f, now);
            SetBackData first = null;
            for (; first == null && now <= start + 3_000 * MS; now += 60 * MS) {
                f.player.checkManager.getSimulationProcessor().getLastMovementTime().add(now - 10 * MS);
                watchdog(f, now);
                first = f.player.getSetbackTeleportUtil().getRequiredSetBack();
            }
            assertNotNull(first);
            long held = now;
            for (long nextHit = held; now <= held + 3_000 * MS; now += 60 * MS) {
                if (nextHit <= now) {
                    hit(f, now);
                    nextHit += 500 * MS;
                }
                f.player.checkManager.getSimulationProcessor().getLastMovementTime().add(now - 10 * MS);
                watchdog(f, now);
                assertSame(first, f.player.getSetbackTeleportUtil().getRequiredSetBack());
                assertTrue(f.player.getSetbackTeleportUtil().isPendingSetback());
            }
            f.pongThrough(first.getTeleportData().getTransaction());
            accept(f, first);
            assertTrue(first.isComplete(), "the setback was not accepted");
            f.pongThrough(f.player.lastTransactionSent.get());
            long later = now;
            hit(f, later);
            int setbacks = 0;
            long secondAt = -1;
            SetBackData seen = first;
            for (; now <= later + 3_000 * MS; now += 60 * MS) {
                f.player.checkManager.getSimulationProcessor().getLastMovementTime().add(now - 10 * MS);
                watchdog(f, now);
                SetBackData required = f.player.getSetbackTeleportUtil().getRequiredSetBack();
                if (required != seen) {
                    setbacks++;
                    if (secondAt < 0) secondAt = (now - later) / MS;
                }
                seen = required;
            }
            assertEquals(1, setbacks);
            assertTrue(secondAt >= LIMIT_MS && secondAt < LIMIT_MS + 60, "set back after " + secondAt + " ms");
        }
    }

    /** ClientPacketListener#handleMovePlayer's 26.3 acceptance of the setback at its target. */
    private static void accept(JavaTickFixture f, SetBackData setback) {
        Vec3 at = setback.getTeleportData().getLocation();
        var packet = new ServerboundAcceptTeleportation(
                setback.getTeleportData().getTeleportId(), new Vec3d(at.x, at.y, at.z), 0, 0);
        new PacketServerTeleport()
                .onAcceptTeleportation(f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet), f.player, packet);
    }

    /**
     * A mount the client proved drops the player's own velocities that follow it (Entity#rideTick zeroes them); the
     * bound does not age one the engine no longer holds, however long its ping stays unanswered.
     */
    @Test
    void aVelocityTheMountDropsIsNotAged() throws Exception {
        try (var f = standing()) {
            long start = System.nanoTime();
            hit(f, start);
            f.player.checkManager.getKnockbackHandler().clearVelocitiesFromSourceEntity(f.player.entityID);
            int setbacks = 0;
            for (long now = start; now <= start + 5_000 * MS; now += 60 * MS) {
                f.player.checkManager.getSimulationProcessor().getLastMovementTime().add(now - 10 * MS);
                SetBackData before = f.player.getSetbackTeleportUtil().getRequiredSetBack();
                watchdog(f, now);
                if (f.player.getSetbackTeleportUtil().getRequiredSetBack() != before) setbacks++;
            }
            assertEquals(0, setbacks);
        }
    }

    /** A knockback sent at {@code sentAt} and its trailing ping; the ping's transaction. */
    private static int hit(JavaTickFixture f, long sentAt) throws Exception {
        int proof = f.velocity(new Vec3(0.3, 0.4, 0));
        TransactionVel sent = f.player.checkManager.getKnockbackHandler().lastSent;
        assertNotNull(sent);
        for (Field field : TransactionVel.class.getDeclaredFields()) {
            if (!field.getName().equals("sentAt")) continue;
            field.setAccessible(true);
            field.setLong(sent, sentAt);
        }
        return Math.max(proof, sent.getProofTransaction());
    }

    private static JavaTickFixture standing() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.nettyScheduler.removeScheduler();
        f.place(STANDING, Vec3.ZERO);
        f.input(NONE);
        assertTrue(exact(f.tick(STANDING, true)));
        return f;
    }

    private static void watchdog(JavaTickFixture f, long now) throws Exception {
        Method knockback = NettyScheduler.class.getDeclaredMethod("watchdogKnockbackLatency", long.class);
        knockback.setAccessible(true);
        knockback.invoke(f.player.nettyScheduler, now);
        f.fixture.channel.runPendingTasks();
    }
}
