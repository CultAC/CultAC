package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import org.junit.jupiter.api.Test;

/**
 * Minecraft#tick sends one ServerboundClientTickEndPacket per client tick. A tick end the timer rejects is cancelled,
 * so the server never sees it: it is no client tick and must not run the tick-end simulation.
 */
class TimerRejectedTickEndTest {
    @Test
    void aRejectedTickEndNeitherAdvancesTheClientTickNorPredicts() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.tickEnd();
            var before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            int tick = f.player.packetStateData.acceptedClientTick;
            aheadOfTheWallClock(f);

            var event = f.event(ServerboundPackets.CLIENT_TICK_END, ServerboundPackets.CLIENT_TICK_END.opaqueValue());
            f.listener.processClientTickEndReceive(event, f.player);

            assertTrue(event.isCancelled(), "the timer accepted a tick end a second ahead of the wall clock");
            assertEquals(tick, f.player.packetStateData.acceptedClientTick);
            assertSame(before, f.player.checkManager.getSimulationProcessor().getLastPrediction());
        }
    }

    @Test
    void anAcceptedIdleTickEndStillPredicts() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.tickEnd();
            var before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            int tick = f.player.packetStateData.acceptedClientTick;

            var event = f.event(ServerboundPackets.CLIENT_TICK_END, ServerboundPackets.CLIENT_TICK_END.opaqueValue());
            f.listener.processClientTickEndReceive(event, f.player);

            assertFalse(event.isCancelled());
            assertEquals(tick + 1, f.player.packetStateData.acceptedClientTick);
            assertTrue(before != f.player.checkManager.getSimulationProcessor().getLastPrediction());
        }
    }

    private static void aheadOfTheWallClock(JavaTickFixture f) throws Exception {
        f.player.checkManager.getCheck(TimerCheck.class).setEnabled(true);
        JavaTickFixture.setField(
                AbstractTimerCheck.class,
                f.player.checkManager.getCheck(TimerCheck.class),
                "timerBalanceRealTime",
                System.nanoTime() + 1_000_000_000L);
    }
}
