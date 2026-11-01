package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.anticheat.NettyScheduler;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** A watchdog fire the scheduler treats as idle is one whose tick would have changed nothing. */
class SetbackWatchdogTest {
    private static final Vec3 FALLING = new Vec3(.5, JavaTickFixture.FLOOR_Y + 3, .5);
    private static final Vec3 FALL_VELOCITY = new Vec3(0, -.5, 0);

    @Test
    void anIdleFireIsOneWhoseTickChangesNothing() throws Exception {
        Method idle = NettyScheduler.class.getDeclaredMethod("idle", long.class);
        Method knockbackWatchdog = NettyScheduler.class.getDeclaredMethod("watchdogKnockbackLatency", long.class);
        idle.setAccessible(true);
        knockbackWatchdog.setAccessible(true);
        long freezeNanos = 5_000_000_000L;
        long kbNanos = (CultAPI.INSTANCE.getConfigManager().getMaxPingKnockback() + 50) * 1_000_000L;
        Long[] movementAges = {
            null, 10_000_000L, kbNanos, kbNanos + 2_000_000L, freezeNanos, freezeNanos + 1, 6_000_000_000L
        };
        int idleCases = 0;
        int actingCases = 0;
        for (Long age : movementAges) {
            for (boolean knockback : new boolean[] {false, true}) {
                for (long pingMs : new long[] {0, kbNanos / 1_000_000L + 1_000}) {
                    for (boolean dead : new boolean[] {false, true}) {
                        String at = "age " + age + " knockback " + knockback + " ping " + pingMs + " dead " + dead;
                        try (var f = new JavaTickFixture()) {
                            f.player.nettyScheduler.removeScheduler();
                            f.place(FALLING, FALL_VELOCITY);
                            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
                            teleports.lastKnownGoodPosition = new SetbackPosWithVector(FALLING, FALL_VELOCITY, 0);
                            long now = System.nanoTime();
                            if (age != null)
                                f.player
                                        .checkManager
                                        .getSimulationProcessor()
                                        .getLastMovementTime()
                                        .add(now - age);
                            if (knockback)
                                f.player.checkManager.getKnockbackHandler().lastSent =
                                        new TransactionVel(new Vec3(0, .4, 0), 1, true, false);
                            f.player.rawTransactionPing = pingMs * 1_000_000L;
                            f.player.compensatedEntities.getSelf().isDead = dead;
                            f.fixture.channel.runPendingTasks();
                            f.fixture.channel.releaseOutbound();

                            boolean wasIdle = (boolean) idle.invoke(f.player.nettyScheduler, now);
                            List<Object> before = state(f);
                            knockbackWatchdog.invoke(f.player.nettyScheduler, now);
                            teleports.checkIfMustMove(now);
                            f.fixture.channel.runPendingTasks();
                            List<Object> after = state(f);
                            if (wasIdle) {
                                idleCases++;
                                assertEquals(before, after, at);
                            } else if (!before.equals(after)) actingCases++;
                        }
                    }
                }
            }
        }
        assertTrue(idleCases > 0, "no case was idle");
        assertTrue(actingCases > 0, "no case acted");
    }

    @Test
    void aRecentMovementWithoutKnockbackIsIdleAndAStalledOneIsNot() throws Exception {
        Method idle = NettyScheduler.class.getDeclaredMethod("idle", long.class);
        idle.setAccessible(true);
        try (var f = new JavaTickFixture()) {
            f.player.nettyScheduler.removeScheduler();
            long now = System.nanoTime();
            var movementTimes = f.player.checkManager.getSimulationProcessor().getLastMovementTime();
            assertTrue((boolean) idle.invoke(f.player.nettyScheduler, now), "no movement yet");
            movementTimes.add(now - 10_000_000L);
            assertTrue((boolean) idle.invoke(f.player.nettyScheduler, now));
            assertFalse((boolean) idle.invoke(f.player.nettyScheduler, now + 6_000_000_000L));
        }
    }

    @Test
    void aWatchdogFireMatchesTheTaskItReplaced() throws Exception {
        long kbNanos = (CultAPI.INSTANCE.getConfigManager().getMaxPingKnockback() + 50) * 1_000_000L;
        // Ages stay 100 ms clear of both limits so a few microseconds of clock skew cannot flip a case.
        Long[] movementAges = {null, 10_000_000L, kbNanos + 100_000_000L, 4_900_000_000L, 5_100_000_000L};
        int acting = 0;
        for (Long age : movementAges) {
            for (boolean knockback : new boolean[] {false, true}) {
                for (boolean vehicle : new boolean[] {false, true}) {
                    for (long pingMs : new long[] {0, kbNanos / 1_000_000L + 1_000}) {
                        String at =
                                "age " + age + " knockback " + knockback + " vehicle " + vehicle + " ping " + pingMs;
                        var fired = sameOwner(age, knockback, vehicle, pingMs, true);
                        var replaced = sameOwner(age, knockback, vehicle, pingMs, false);
                        assertEquals(replaced, fired, at);
                        if (!replaced.get(0).equals("none")) acting++;
                    }
                }
            }
        }
        assertTrue(acting > 0, "no case set the player back");
    }

    @Test
    void onASeparateOwnerTheTickSeesWritesQueuedAheadOfItAndNotThoseBehind() throws Exception {
        long kbNanos = (CultAPI.INSTANCE.getConfigManager().getMaxPingKnockback() + 50) * 1_000_000L;
        for (boolean knockbackAhead : new boolean[] {false, true}) {
            var fired = separateOwner(kbNanos + 200_000_000L, knockbackAhead, true);
            var replaced = separateOwner(kbNanos + 200_000_000L, knockbackAhead, false);
            assertEquals(replaced, fired, "knockback ahead " + knockbackAhead);
            assertEquals(knockbackAhead, !replaced.get(0).equals("none"), "knockback ahead " + knockbackAhead);
        }
        var fired = separateOwner(5_100_000_000L, false, true);
        var replaced = separateOwner(5_100_000_000L, false, false);
        assertEquals(replaced, fired, "stalled movement with a movement queued behind");
        assertFalse(replaced.get(0).equals("none"), "the tick ran before the queued movement");
    }

    @Test
    void onASeparateOwnerTheTickJudgesMovementAgeByTheClockWhenItRuns() throws Exception {
        // Under the 5 s freeze limit when the watchdog fires, over it once the held owner runs the task.
        long age = 4_925_000_000L;
        var fired = separateOwner(age, false, true, 150);
        var replaced = separateOwner(age, false, false, 150);
        assertEquals(replaced, fired);
        assertFalse(replaced.get(0).equals("none"), "the task saw the age the owner ran it at");
    }

    private static List<Object> sameOwner(Long age, boolean knockback, boolean vehicle, long pingMs, boolean fire)
            throws Exception {
        try (var f = stalled(age)) {
            if (knockback) f.player.checkManager.getKnockbackHandler().lastSent = knockback();
            if (vehicle) f.player.compensatedEntities.getSelf().mount(new PacketEntity(EntityTypeIds.OAK_BOAT, 99));
            f.player.rawTransactionPing = pingMs * 1_000_000L;
            var log = record(f);
            if (fire) fire(f);
            else replaced(f);
            f.fixture.channel.runPendingTasks();
            return transcript(f, log);
        }
    }

    /**
     * A Geyser-style owner with CultEncoder on its executor. A knockback send is queued ahead of the watchdog's task
     * or not, and a movement is queued behind it.
     */
    private static List<Object> separateOwner(long age, boolean knockbackAhead, boolean fire) throws Exception {
        return separateOwner(age, knockbackAhead, fire, 0);
    }

    private static List<Object> separateOwner(long age, boolean knockbackAhead, boolean fire, long holdMs)
            throws Exception {
        var owner = new DefaultEventExecutor(new DefaultThreadFactory("test-watchdog-owner"));
        try (var f = stalled(age)) {
            Field ownerField = CultConnection.class.getDeclaredField("owner");
            ownerField.setAccessible(true);
            ownerField.set(f.fixture.connection, owner);
            CultEncoder.uninstall(f.fixture.channel);
            CultEncoder.install(f.fixture.connection);
            var log = record(f);
            var release = new CountDownLatch(1);
            owner.execute(() -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            if (knockbackAhead) owner.execute(() -> f.player.checkManager.getKnockbackHandler().lastSent = knockback());
            if (fire) fire(f);
            else replaced(f);
            owner.execute(() -> f.player
                    .checkManager
                    .getSimulationProcessor()
                    .getLastMovementTime()
                    .add(System.nanoTime()));
            Thread.sleep(holdMs);
            release.countDown();
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            f.fixture.channel.runPendingTasks();
            return owner.submit(() -> transcript(f, log)).get(5, TimeUnit.SECONDS);
        } finally {
            owner.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        }
    }

    private static JavaTickFixture stalled(Long age) throws Exception {
        var f = new JavaTickFixture();
        f.player.nettyScheduler.removeScheduler();
        f.place(FALLING, FALL_VELOCITY);
        f.player.getSetbackTeleportUtil().lastKnownGoodPosition = new SetbackPosWithVector(FALLING, FALL_VELOCITY, 0);
        if (age != null)
            f.player.checkManager.getSimulationProcessor().getLastMovementTime().add(System.nanoTime() - age);
        f.fixture.channel.runPendingTasks();
        f.fixture.channel.releaseOutbound();
        return f;
    }

    private static TransactionVel knockback() {
        return new TransactionVel(new Vec3(0, .4, 0), 1, true, false);
    }

    private static void fire(JavaTickFixture f) throws ReflectiveOperationException {
        Method watchdog = NettyScheduler.class.getDeclaredMethod("watchdog");
        watchdog.setAccessible(true);
        watchdog.invoke(f.player.nettyScheduler);
    }

    /** Reference implementation: an outbound task running the base tick. */
    private static void replaced(JavaTickFixture f) {
        CultPlayer player = f.player;
        player.user.executeAfterWrites(() -> {
            if (player.checkManager.getKnockbackHandler().lastSent != null && !player.inVehicle()) {
                var movementTimes = player.checkManager.getSimulationProcessor().getLastMovementTime();
                if (!movementTimes.isEmpty()) {
                    long mostRecentMovement = Collections.max(movementTimes);
                    int elapsedMs = (int) ((System.nanoTime() - mostRecentMovement) / 1e6);
                    elapsedMs = Math.max(elapsedMs, player.getTransactionPing());
                    int allowedLatencyMs = CultAPI.INSTANCE.getConfigManager().getMaxPingKnockback() + 50;
                    if (elapsedMs > allowedLatencyMs)
                        player.getSetbackTeleportUtil()
                                .executeTooHighLatencySetback(String.format("kb %dms", elapsedMs));
                }
            }
            player.getSetbackTeleportUtil().checkIfMustMove();
        });
    }

    /** Packet ids and lengths of every write reaching the head, and every flush. */
    private static List<String> record(JavaTickFixture f) {
        var log = Collections.synchronizedList(new ArrayList<String>());
        f.fixture.channel.pipeline().addFirst("recorder", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                if (message instanceof ByteBuf frame) {
                    ByteBuf view = frame.duplicate();
                    log.add("write " + Wire.readVarInt(view) + "/" + frame.readableBytes());
                } else log.add("write " + message.getClass().getSimpleName());
                ctx.write(message, promise);
            }

            @Override
            public void flush(ChannelHandlerContext ctx) {
                log.add("flush");
                ctx.flush();
            }
        });
        return log;
    }

    private static List<Object> transcript(JavaTickFixture f, List<String> log) {
        SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
        SetBackData required = teleports.getRequiredSetBack();
        return List.of(
                required == null
                        ? "none"
                        : required.getTeleportData().getLocation() + " "
                                + required.getTeleportData().getTransaction() + " " + required.getVelocity() + " "
                                + required.isVehicle() + " " + required.isComplete(),
                teleports.isPendingSetback(),
                teleports.blockOffsets,
                f.player.lastTransactionSent.get(),
                List.copyOf(log));
    }

    /** The setback state, transactions and outbound frames a watchdog tick can change. */
    private static List<Object> state(JavaTickFixture f) {
        SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
        return List.of(
                System.identityHashCode(teleports.getRequiredSetBack()),
                teleports.isPendingSetback(),
                teleports.blockOffsets,
                f.player.lastTransactionSent.get(),
                f.fixture.channel.outboundMessages().size());
    }
}
