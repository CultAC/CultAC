package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.VehicleTimer;
import ac.cult.cultac.events.packets.PacketServerTickingState;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTickingState;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Minecraft#getTickTargetMillis is max(50, 1000 / rate) while the level ticks normally and 50 ms otherwise, so no
 * ticking state lets the client tick faster than the timers already charge. The proof of a ticking-state packet
 * arrives after its own upstream delay; a balance restarted from that arrival would reject every later tick that
 * travels faster than that one pong did.
 */
class TickRateTimerBalanceTest {
    @Test
    void aTickRateChangeKeepsTheTimerBalances() throws Exception {
        try (var f = new JavaTickFixture()) {
            AbstractTimerCheck timer = f.player.checkManager.getCheck(TimerCheck.class);
            AbstractTimerCheck vehicleTimer = f.player.checkManager.getCheck(VehicleTimer.class);
            long balance = balance(timer);
            long vehicleBalance = balance(vehicleTimer);

            for (float rate : new float[] {10.0F, 40.0F, 20.0F}) {
                tickingState(f, rate);
                assertEquals(balance, balance(timer), "rate " + rate);
                assertEquals(vehicleBalance, balance(vehicleTimer), "rate " + rate);
            }
        }
    }

    private static void tickingState(JavaTickFixture f, float rate) {
        var packet = new ClientboundTickingState(rate, false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.TICKING_STATE, packet, false);
        new PacketServerTickingState(f.player).onTickingState(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertEquals(rate, f.player.packetStateData.serverTickRate);
    }

    private static long balance(AbstractTimerCheck timer) throws Exception {
        Field field = AbstractTimerCheck.class.getDeclaredField("timerBalanceRealTime");
        field.setAccessible(true);
        return field.getLong(timer);
    }
}
