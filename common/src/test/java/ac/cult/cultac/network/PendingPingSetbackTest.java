package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.anticheat.NettyScheduler;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import ac.grim.grimac.api.AbstractCheck;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * With max-pending-ping-ms set, a client whose ticks arrive past it after a ping it has not answered is set back once,
 * without a flag, and accepting the setback applies everything the ping proves.
 */
class PendingPingSetbackTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Vec3 STANDING = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final double GROUNDED_Y = (0.0 - 0.08) * (double) 0.98F;
    private static final BlockPos REFUSED = new BlockPos(1, FLOOR_Y - 1, 0);
    private static final Vec3 ON_REFUSED = new Vec3(1.5, FLOOR_Y, 0.5);
    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final int BOUND_MS = 1000;

    @AfterEach
    void unsetBound() throws Exception {
        maxPendingPingMs(0);
    }

    @Test
    void theBoundIsOffUnlessConfigured() throws Exception {
        try (var f = standing(false)) {
            assertEquals(0, CultAPI.INSTANCE.getConfigManager().getMaxPendingPingMs());
            removeFloor(f);
            long owed = owedSince(f);
            moveAt(f, owed + 59_000 * MS);
            assertFalse(watchdog(f, owed + 59_010 * MS));
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback());
        }
    }

    @Test
    void aClientSendingOnlyTickEndsPastTheBoundIsSetBack() throws Exception {
        try (var f = standing()) {
            removeFloor(f);
            long owed = owedSince(f);
            f.player.lastClientTickArrivedAt = owed + 1_050 * MS;
            assertTrue(watchdog(f, owed + 1_060 * MS));
            SetBackData setback = f.player.getSetbackTeleportUtil().getRequiredSetBack();
            assertNotNull(setback);
            assertEquals(STANDING, setback.getTeleportData().getLocation());
        }
    }

    @Test
    void aClientStandingOnARemovedBlockPastTheBoundIsSetBackOnceAndThenFalls() throws Exception {
        try (var f = standing()) {
            int proof = removeFloor(f);
            long owed = owedSince(f);
            for (int i = 0; i < 20; i++) assertTrue(exact(f.tick(STANDING, true)), "tick " + i);
            double violations = violations(f);

            moveAt(f, owed + 980 * MS);
            assertFalse(watchdog(f, owed + 990 * MS), "within the bound");
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback());

            moveAt(f, owed + 1_050 * MS);
            assertTrue(watchdog(f, owed + 1_060 * MS));
            SetBackData setback = f.player.getSetbackTeleportUtil().getRequiredSetBack();
            assertNotNull(setback, "standing still is set back too");
            assertEquals(STANDING, setback.getTeleportData().getLocation());
            assertEquals(violations, violations(f), "the setback flags nothing");

            moveAt(f, owed + 1_500 * MS);
            assertFalse(watchdog(f, owed + 1_510 * MS), "one setback per stall");
            assertSame(setback, f.player.getSetbackTeleportUtil().getRequiredSetBack());

            accept(f, setback);
            assertTrue(f.player.lastTransactionReceived.get() >= proof);
            assertEquals(BlockIds.AIR.defaultState(), f.player.compensatedWorld.getBlockStateIdAt(new BlockPos(0, FLOOR_Y - 1, 0)));
            boolean flagged = false;
            for (int i = 0; i < 4 && !flagged; i++) flagged = !exact(f.tick(STANDING, true));
            assertTrue(flagged, "standing on the removed block after the catch-up");
        }
    }

    @Test
    void aRefusedPlacementPastTheBoundIsSetBackAndNoLongerHolds() throws Exception {
        try (var f = holeWithRefusedBlock()) {
            long owed = owedSince(f);
            for (int tick = 0; tick < 10; tick++) {
                PredictionResult result = f.tick(ON_REFUSED, true);
                assertTrue(exact(result), "tick " + tick + " " + describe(result));
            }
            moveAt(f, owed + 1_050 * MS);
            assertTrue(watchdog(f, owed + 1_060 * MS));
            SetBackData setback = f.player.getSetbackTeleportUtil().getRequiredSetBack();
            assertNotNull(setback);
            accept(f, setback);
            assertEquals(BlockIds.AIR.defaultState(), f.player.compensatedWorld.getBlockStateIdAt(REFUSED));
            assertFalse(exact(f.tick(ON_REFUSED, true)), "standing on the refused block after the catch-up");
        }
    }

    @Test
    void noMoveArrivingPastTheBoundIsNoSetback() throws Exception {
        try (var f = standing()) {
            removeFloor(f);
            long owed = owedSince(f);
            moveAt(f, owed + 900 * MS);
            assertFalse(watchdog(f, owed + 5_000 * MS));
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback());
        }
    }

    @Test
    void aBoundOfZeroOrLessIsOff() throws Exception {
        for (int bound : new int[] {0, -1}) {
            try (var f = standing(); var ignored = maxPendingPingMs(bound)) {
                removeFloor(f);
                long owed = owedSince(f);
                moveAt(f, owed + 59_000 * MS);
                assertFalse(watchdog(f, owed + 59_010 * MS), "bound " + bound);
                assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback(), "bound " + bound);
            }
        }
    }

    @Test
    void aDeadPlayerIsNotSetBack() throws Exception {
        try (var f = standing()) {
            removeFloor(f);
            long owed = owedSince(f);
            f.player.compensatedEntities.getSelf().isDead = true;
            moveAt(f, owed + 1_050 * MS);
            watchdog(f, owed + 1_060 * MS);
            assertNull(f.player.getSetbackTeleportUtil().getRequiredSetBack());
        }
    }

    @Test
    void anAnsweredPingIsNotOwed() throws Exception {
        try (var f = standing()) {
            answerAll(f);
            assertEquals(0, f.player.oldestPendingPingSince());
            moveAt(f, System.nanoTime() + 5_000 * MS);
            assertFalse(watchdog(f, System.nanoTime() + 5_010 * MS));
        }
    }

    private static JavaTickFixture standing() throws Exception {
        return standing(true);
    }

    /** {@code configured} sets max-pending-ping-ms to {@link #BOUND_MS}. */
    private static JavaTickFixture standing(boolean configured) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.nettyScheduler.removeScheduler();
        if (configured) maxPendingPingMs(BOUND_MS);
        f.place(STANDING, Vec3.ZERO);
        f.input(NONE);
        assertTrue(exact(f.tick(STANDING, true)));
        return f;
    }

    private static JavaTickFixture holeWithRefusedBlock() throws Exception {
        var f = new JavaTickFixture();
        f.player.nettyScheduler.removeScheduler();
        maxPendingPingMs(BOUND_MS);
        int stone = BlockIds.STONE.defaultState();
        int air = BlockIds.AIR.defaultState();
        CompensatedWorld world = f.player.compensatedWorld;
        for (int x = 0; x <= 3; x++) {
            for (int z = -1; z <= 1; z++) world.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), air);
        }
        world.updateBlock(new BlockPos(-1, FLOOR_Y - 1, 0), stone);
        for (int tick = 0; tick < 3; tick++) world.removeInvalidPistonLikeStuff(0);

        world.advanceClientPredictionSequence(world.clientPredictionSequence() + 1);
        world.startPredicting();
        world.updateBlock(REFUSED, stone);
        world.stopPredicting(world.clientPredictionSequence());
        for (int tick = 0; tick < 3; tick++) world.removeInvalidPistonLikeStuff(0);

        answerAll(f);
        f.player.sendTransaction();
        world.queueServerBlockUpdate(REFUSED.x(), REFUSED.y(), REFUSED.z(), air, f.player.lastTransactionSent.get());
        world.handlePredictionConfirmation(world.clientPredictionSequence());
        f.place(ON_REFUSED, new Vec3(0, GROUNDED_Y, 0));
        f.input(NONE);
        return f;
    }

    private static int removeFloor(JavaTickFixture f) {
        answerAll(f);
        f.player.sendTransaction();
        int proof = f.player.lastTransactionSent.get();
        f.player.compensatedWorld.queueServerBlockUpdate(0, FLOOR_Y - 1, 0, BlockIds.AIR.defaultState(), proof);
        return proof;
    }

    private static long owedSince(JavaTickFixture f) {
        written(f);
        long owed = f.player.oldestPendingPingSince();
        assertTrue(owed != 0, "no ping is owed");
        return owed;
    }

    /** A move arriving at {@code at}; the fixture's ticks arrive at the wall clock. */
    private static void moveAt(JavaTickFixture f, long at) {
        f.player.checkManager.getSimulationProcessor().getLastMovementTime().add(at);
    }

    /** The watchdog at {@code now}; whether it took the acting path. */
    private static boolean watchdog(JavaTickFixture f, long now) throws Exception {
        Method idle = NettyScheduler.class.getDeclaredMethod("idle", long.class);
        Method pendingPing = NettyScheduler.class.getDeclaredMethod("watchdogPendingPing", long.class);
        idle.setAccessible(true);
        pendingPing.setAccessible(true);
        boolean acting = !(boolean) idle.invoke(f.player.nettyScheduler, now);
        pendingPing.invoke(f.player.nettyScheduler, now);
        f.fixture.channel.runPendingTasks();
        return acting;
    }

    /** The client answers, in wire order, every ping written so far. */
    private static void answerAll(JavaTickFixture f) {
        written(f);
        int sent = f.player.lastTransactionSent.get();
        while (f.player.lastTransactionReceived.get() < sent) {
            long owed = f.player.oldestPendingPingSince();
            assertTrue(owed != 0, "a written ping is not owed");
            assertTrue(f.player.addTransactionResponse(oldestPendingId(f)));
        }
    }

    /** Every ping CultAC wrote reaches the wire, in transaction order, as the outbound ping listener marks it. */
    @SuppressWarnings("unchecked")
    private static void written(JavaTickFixture f) {
        f.fixture.channel.runPendingTasks();
        try {
            Field pending = f.player.getClass().getDeclaredField("transactionsPendingSend");
            pending.setAccessible(true);
            Map<Integer, ?> byId = new TreeMap<>((Map<Integer, ?>) pending.get(f.player));
            for (int transaction = f.player.lastTransactionReceived.get() + 1;
                    transaction <= f.player.lastTransactionSent.get();
                    transaction++) {
                for (var entry : byId.entrySet()) {
                    Method of = entry.getValue().getClass().getDeclaredMethod("transaction");
                    of.setAccessible(true);
                    if ((int) of.invoke(entry.getValue()) == transaction)
                        f.player.markTrackedPingSent(entry.getKey(), 0);
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static int oldestPendingId(JavaTickFixture f) {
        try {
            Field sent = f.player.getClass().getDeclaredField("transactionsSent");
            sent.setAccessible(true);
            Queue<?> queue = (Queue<?>) sent.get(f.player);
            if (queue == null) {
                Field written = f.player.getClass().getDeclaredField("writtenPings");
                written.setAccessible(true);
                Object pings = written.get(f.player);
                Method headId = pings.getClass().getDeclaredMethod("headId");
                headId.setAccessible(true);
                return (int) headId.invoke(pings);
            }
            Object oldest = queue.peek();
            Method id = oldest.getClass().getDeclaredMethod("id");
            id.setAccessible(true);
            return (int) id.invoke(oldest);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void accept(JavaTickFixture f, SetBackData setback) {
        var teleport = setback.getTeleportData();
        written(f);
        while (f.player.lastTransactionReceived.get() < teleport.getTransaction()) {
            assertTrue(f.player.addTransactionResponse(oldestPendingId(f)));
        }
        var at = teleport.getLocation();
        var packet = new ServerboundAcceptTeleportation(
                teleport.getTeleportId(), JavaTickFixture.vec3d(at), f.player.xRot, f.player.yRot);
        var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
        new PacketServerTeleport().onAcceptTeleportation(event, f.player, packet);
        assertFalse(event.isCancelled(), "the setback's acceptance was rejected");
    }

    private static double violations(JavaTickFixture f) {
        double total = 0;
        for (AbstractCheck check : f.player.getChecks()) {
            if (check instanceof Check c) total += c.violations;
        }
        return total;
    }

    private static AutoCloseable maxPendingPingMs(int bound) throws ReflectiveOperationException {
        BaseConfigManager manager = CultAPI.INSTANCE.getConfigManager();
        Field field = BaseConfigManager.class.getDeclaredField("maxPendingPingMs");
        field.setAccessible(true);
        int original = field.getInt(manager);
        field.setInt(manager, bound);
        return () -> field.setInt(manager, original);
    }
}
