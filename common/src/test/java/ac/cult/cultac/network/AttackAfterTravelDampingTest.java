package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.SprintingAttacker.TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import org.junit.jupiter.api.Test;

/**
 * Player#causeExtraKnockback damps deltaMovement, which the next travel starts from. Minecraft#tick handles attacks
 * before LocalPlayer#tick sends the movement, so a damping from an attack after the movement lands on the next travel.
 */
class AttackAfterTravelDampingTest {

    @Test
    void anAttackAfterTheMovementRequiresTheDampingOnTheNextTravel() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            s.travel(1.0D);
            s.attack(TARGET);
            s.swing();
            s.tickEnd();
            assertEquals(0.0D, s.keepSprint());
            PredictionResult next = s.move(1.0D);
            assertEquals(1, next.getSimulationContext().getMinAttackSlow(), describe(next));
            assertTrue(s.keepSprint() > 0, describe(next));
        }
    }

    @Test
    void theNextTravelCarryingTheDampingIsExact() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            s.travel(1.0D);
            s.attack(TARGET);
            s.swing();
            s.tickEnd();
            PredictionResult next = s.move(0.6D);
            assertTrue(exact(next), describe(next));
            assertEquals(0.0D, s.keepSprint(), describe(next));
            PredictionResult after = s.move(1.0D);
            assertTrue(exact(after), describe(after));
            assertEquals(0, after.getSimulationContext().getMinAttackSlow(), describe(after));
        }
    }

    @Test
    void anAttackBeforeTheMovementIsNotCarried() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            s.attack(TARGET);
            s.swing();
            PredictionResult hit = s.move(0.6D);
            assertTrue(exact(hit), describe(hit));
            PredictionResult next = s.move(1.0D);
            assertEquals(0, next.getSimulationContext().getMinAttackSlow(), describe(next));
            assertTrue(exact(next), describe(next));
            assertEquals(0.0D, s.keepSprint(), describe(next));
        }
    }

    @Test
    void anAttackAfterATimedOutTickEndIsConsumedByTheNextMovement() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            s.travel(1.0D);
            // ClientTickEndTimeout closes the stalled tick through the check manager alone.
            s.f.tickEnd();
            s.attack(TARGET);
            s.swing();
            PredictionResult hit = s.move(0.6D);
            assertTrue(exact(hit), describe(hit));
            PredictionResult next = s.move(1.0D);
            assertEquals(0, next.getSimulationContext().getMinAttackSlow(), describe(next));
            assertTrue(exact(next), describe(next));
            assertEquals(0.0D, s.keepSprint(), describe(next));
        }
    }

    @Test
    void anAttackAfterATickEndTheTimerRefusedIsNotCarried() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            // The client has the chunk: a setback in a chunk it may not have loaded does not block movement.
            s.f.pongThrough(s.f.player.sendTrailingTransaction());
            TimerCheck timer = s.f.player.checkManager.getCheck(TimerCheck.class);
            timer.setEnabled(true);
            JavaTickFixture.setField(
                    AbstractTimerCheck.class, timer, "timerBalanceRealTime", System.nanoTime() + 1_000_000_000L);
            s.travel(1.0D);
            s.tickEnd();
            assertTrue(s.f.player.packetStateData.receivedMovementThisClientTick, "the tick end was not refused");
            JavaTickFixture.setField(
                    AbstractTimerCheck.class, timer, "timerBalanceRealTime", System.nanoTime() - 10_000_000_000L);
            s.attack(TARGET);
            s.swing();
            s.travel(0.6D);
            s.tickEnd();
            assertEquals(0, s.f.player.minPlayerAttackSlow);
        }
    }
}
