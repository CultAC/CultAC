package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.DumbTimer;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.verdict.FlagOutcome;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.PlayerObserver;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TimerMovementBudgetTest {
    private static final Vec3 AIR = new Vec3(.5, 70, .5);
    private static final Vec3 GROUND = new Vec3(.5, JavaTickFixture.FLOOR_Y, .5);
    private static final double ONE_MOVEMENT = 45e6;
    private static final long ONE_CLIENT_TICK = 50_000_000L;
    /** LivingEntity#travelInAir: (0 - 0.08) * 0.98F after a tick at rest. */
    private static final double FIRST_FALL = -0.0784000015258789;

    @Test
    void rejectedTickEndDoesNotCreateAnIdleSimulationTick() throws Exception {
        try (var f = new JavaTickFixture()) {
            TimerCheck timer = f.player.checkManager.getCheck(TimerCheck.class);
            timer.setEnabled(true);
            JavaTickFixture.setField(
                    AbstractTimerCheck.class, timer, "timerBalanceRealTime", System.nanoTime() + 1_000_000_000L);
            var previous = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            long acceptedTick = f.player.packetStateData.acceptedClientTick;

            var event = f.event(ServerboundPackets.CLIENT_TICK_END, ServerboundPackets.CLIENT_TICK_END.opaqueValue());
            f.listener.processClientTickEndReceive(event, f.player);

            assertTrue(event.isCancelled());
            assertTrue(f.player.packetStateData.acceptedClientTick == acceptedTick);
            assertTrue(f.player.checkManager.getSimulationProcessor().getLastPrediction() == previous);
        }
    }

    /**
     * LocalPlayer#tick sends a tick's movement before Minecraft#tick sends its tick end, so TimerA charges the tick at
     * that movement. A tick within budget is charged once; a tick over budget is rejected before prediction runs its
     * movement, set back, and its tick end neither charges again nor ends a tick.
     */
    @Test
    void timerChargesATickAtItsMovement() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(GROUND, new Vec3(0, FIRST_FALL, 0));
            // The client has the chunk: a setback in a chunk it may not have loaded does not block movement.
            f.pongThrough(f.player.sendTrailingTransaction());
            f.tick(GROUND, true);
            TimerCheck timer = f.player.checkManager.getCheck(TimerCheck.class);
            timer.setEnabled(true);

            // Within budget, above the drift floor: the movement charges the tick and its tick end does not.
            long within = System.nanoTime() - 100_000_000L;
            JavaTickFixture.setField(AbstractTimerCheck.class, timer, "timerBalanceRealTime", within);
            assertFalse(f.move(GROUND, true).isCancelled());
            assertEquals(within + ONE_CLIENT_TICK, timerBalance(timer), "the movement did not charge the tick");
            assertFalse(tickEnd(f).isCancelled());
            assertEquals(within + ONE_CLIENT_TICK, timerBalance(timer), "the charged tick's end charged again");

            // Over budget: the movement is rejected before prediction and refunded; its tick end passes uncharged.
            long ahead = System.nanoTime() + 1_000_000_000L;
            JavaTickFixture.setField(AbstractTimerCheck.class, timer, "timerBalanceRealTime", ahead);
            var previous = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            var rejected = f.move(GROUND.add(.1, 0, 0), true);
            assertTrue(rejected.isCancelled(), "the over-budget movement went through");
            assertTrue(f.player.checkManager.getSimulationProcessor().getLastPrediction() == previous);
            assertTrue(f.player.getSetbackTeleportUtil().isPendingSetback(), "the rejected movement was not set back");
            assertEquals(1, timer.violations);
            assertEquals(ahead, timerBalance(timer), "the rejected tick was not refunded");
            long acceptedTick = f.player.packetStateData.acceptedClientTick;
            assertTrue(tickEnd(f).isCancelled(), "the rejected movement's tick end ended a tick");
            assertEquals(acceptedTick, f.player.packetStateData.acceptedClientTick, "the rejected tick advanced");
            assertEquals(ahead, timerBalance(timer), "the rejected movement's tick end was charged");
            assertEquals(1, timer.violations);

            // The next tick starts fresh: its first movement charges it rather than counting as a second movement.
            Field moves = AbstractTimerCheck.class.getDeclaredField("movePacketsSinceLastClientTickEnd");
            moves.setAccessible(true);
            assertEquals(0, moves.getInt(timer));
        }
    }

    /**
     * Every flag consumer sees Timer's flags with no verbose. Only an observer that records Timer's verbose gets the
     * balance's lead and the age of the last proven clock, or the tick's movement count, and it gets them through a
     * deferred flag's commit too.
     */
    @Test
    void timerGivesItsNumbersOnlyToAnObserverThatRecordsThem() throws Exception {
        for (boolean recording : new boolean[] {false, true}) {
            try (var f = new JavaTickFixture()) {
                f.place(GROUND, new Vec3(0, FIRST_FALL, 0));
                f.pongThrough(f.player.sendTrailingTransaction());
                f.tick(GROUND, true);
                TimerCheck timer = f.player.checkManager.getCheck(TimerCheck.class);
                timer.setEnabled(true);
                Recorder observer = new Recorder(timer, recording);
                f.player.observer = observer;
                List<String> consumers = new ArrayList<>();
                List<FlagOutcome> deferred = new ArrayList<>();
                f.player.setFlagArbiter((check, verbose, prospective, outcome) -> {
                    if (check != timer) return false;
                    consumers.add(verbose.get());
                    deferred.add(outcome);
                    return true;
                });

                JavaTickFixture.setField(
                        AbstractTimerCheck.class, timer, "timerBalanceRealTime", System.nanoTime() + 1_000_000_000L);
                assertTrue(f.move(GROUND.add(.1, 0, 0), true).isCancelled());
                tickEnd(f);
                f.player.getSetbackTeleportUtil().release(GROUND);
                JavaTickFixture.setField(
                        AbstractTimerCheck.class, timer, "timerBalanceRealTime", System.nanoTime() - 100_000_000L);
                f.move(GROUND, true);
                f.move(GROUND, true);
                assertEquals(List.of("", ""), consumers, "a flag consumer saw a Timer verbose");
                assertTrue(observer.verbose.isEmpty(), "a deferred flag reached the observer before its commit");
                for (FlagOutcome outcome : deferred) outcome.commit();

                assertEquals(2, observer.verbose.size());
                if (!recording) {
                    assertEquals(List.of("", ""), observer.verbose);
                    continue;
                }
                Matcher balance =
                        Pattern.compile("ahead=(\\d+)us, clock=(-?\\d+)us").matcher(observer.verbose.get(0));
                assertTrue(balance.matches(), observer.verbose.get(0));
                long ahead = Long.parseLong(balance.group(1));
                assertTrue(ahead > 900_000 && ahead <= 1_050_000, observer.verbose.get(0));
                assertEquals("moves=2", observer.verbose.get(1));
            }
        }
    }

    /** Records the verbose each flag of {@code recorded} gives it, rendering it as a flag consumer would. */
    private static final class Recorder implements PlayerObserver {
        final List<String> verbose = new ArrayList<>();
        private final Check recorded;
        private final boolean recording;

        Recorder(Check recorded, boolean recording) {
            this.recorded = recorded;
            this.recording = recording;
        }

        @Override
        public boolean recordsVerbose(Check check) {
            return recording && check == recorded;
        }

        @Override
        public void flagged(Check check, double violations, Supplier<String> text) {
            if (check == recorded) verbose.add(text.get());
        }
    }

    /**
     * DumbTimer counts a ridden tick once, through the passenger Rot LocalPlayer#tick sends before the vehicle move. A
     * vehicle move accepted without a Rot, during the vehicle-switch buffer, counts as a tick of its own, against the
     * same one-second backlog cap as on-foot movement.
     */
    @Test
    void dumbTimerCountsEveryRiddenTickOnce() throws Exception {
        try (var f = new JavaTickFixture()) {
            mountAirborneHorse(f);
            DumbTimer dumb = f.player.checkManager.getListener(DumbTimer.class);
            JavaTickFixture.setField(DumbTimer.class, dumb, "balance", System.nanoTime() - 10e9);

            double before = balance(dumb);
            passengerRotation(f);
            assertEquals(before + ONE_MOVEMENT, balance(dumb), 0.0, "the Rot did not count the tick");
            var paired = moveHorse(f, AIR);
            assertFalse(paired.isCancelled());
            assertEquals(before + ONE_MOVEMENT, balance(dumb), 0.0, "the Rot's vehicle move counted again");
            f.tickEnd();
            assertEquals(before + ONE_MOVEMENT, balance(dumb), 0.0, "the counted tick's end counted again");

            // The production cap, with a minute of unanswered pings behind it.
            JavaTickFixture.setField(DumbTimer.class, dumb, "limitAbuseOverPing", 1_000_000_000L);
            JavaTickFixture.setField(DumbTimer.class, dumb, "balance", System.nanoTime() - 60e9);
            long start = System.nanoTime();
            var alone = moveHorse(f, AIR.add(0, FIRST_FALL, 0));
            long end = System.nanoTime();
            assertFalse(alone.isCancelled(), "the buffered vehicle move was not accepted");
            assertTrue(balance(dumb) >= start - 1e9 + ONE_MOVEMENT, "the backlog was not capped: " + balance(dumb));
            assertTrue(balance(dumb) <= end - 1e9 + ONE_MOVEMENT, "the vehicle move was not counted once");
        }
    }

    /** A client tick that sends no movement still ends with ServerboundClientTickEnd, which counts it. */
    @Test
    void anIdleTickCountsAtItsTickEnd() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(GROUND, new Vec3(0, FIRST_FALL, 0));
            DumbTimer dumb = f.player.checkManager.getListener(DumbTimer.class);
            JavaTickFixture.setField(DumbTimer.class, dumb, "balance", System.nanoTime() - 10e9);

            double before = balance(dumb);
            assertFalse(tickEnd(f).isCancelled());
            assertEquals(before + ONE_MOVEMENT, balance(dumb), 0.0, "the idle tick did not count");
        }
    }

    /** Idle ticks share the one-second backlog cap, however long the client has withheld its pongs. */
    @Test
    void idleTicksCannotOutrunTheBacklog() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(GROUND, new Vec3(0, FIRST_FALL, 0));
            f.pongThrough(f.player.sendTrailingTransaction());
            DumbTimer dumb = f.player.checkManager.getListener(DumbTimer.class);
            JavaTickFixture.setField(DumbTimer.class, dumb, "limitAbuseOverPing", 1_000_000_000L);
            JavaTickFixture.setField(DumbTimer.class, dumb, "balance", System.nanoTime() - 60e9);

            // 22 ticks of 45 ms stay inside one second of real time.
            for (int i = 0; i < 22; i++) {
                assertFalse(tickEnd(f).isCancelled(), "idle tick " + (i + 1));
            }
            int rejectedAt = -1;
            for (int i = 22; i < 40 && rejectedAt < 0; i++) {
                long acceptedTick = f.player.packetStateData.acceptedClientTick;
                var event = tickEnd(f);
                if (event.isCancelled()) {
                    rejectedAt = i;
                    assertEquals(acceptedTick, f.player.packetStateData.acceptedClientTick, "the rejected tick ran");
                }
            }
            assertTrue(rejectedAt >= 0, "idle ticks outran the backlog cap");
            assertTrue(f.player.getSetbackTeleportUtil().isPendingSetback());
        }
    }

    private static PacketReceiveEvent<Opaque> tickEnd(JavaTickFixture f) {
        var event = f.event(ServerboundPackets.CLIENT_TICK_END, ServerboundPackets.CLIENT_TICK_END.opaqueValue());
        f.listener.processClientTickEndReceive(event, f.player);
        return event;
    }

    private static void mountAirborneHorse(JavaTickFixture f) {
        f.place(AIR, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(100, EntityTypeIds.HORSE, AIR, 0, 0, 0);
        PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(100);
        horse.hasSaddle = true;
        horse.isTame = true;
        horse.onGround = false;
        f.setPassengers(100, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(Vec3.ZERO);
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
        horse.onGround = false;
    }

    private static void passengerRotation(JavaTickFixture f) {
        var rotation = new ServerboundMovePlayer(0, 0, 0, 0, 0, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rotation), f.player, rotation);
    }

    private static PacketReceiveEvent<ServerboundMoveVehicle> moveHorse(JavaTickFixture f, Vec3 to) {
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), 0, 0, false, true);
        var event = f.event(ServerboundPackets.MOVE_VEHICLE, move);
        f.listener.onMoveVehicle(event, f.player, move);
        return event;
    }

    private static long timerBalance(TimerCheck timer) throws Exception {
        Field field = AbstractTimerCheck.class.getDeclaredField("timerBalanceRealTime");
        field.setAccessible(true);
        return field.getLong(timer);
    }

    private static double balance(DumbTimer dumb) throws Exception {
        Field field = DumbTimer.class.getDeclaredField("balance");
        field.setAccessible(true);
        return field.getDouble(dumb);
    }
}
