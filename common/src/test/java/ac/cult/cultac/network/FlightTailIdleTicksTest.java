package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.PacketPlayerAbilities;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerAbilities;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Client ticks that send no position under the movement exemption run the post-flight tail down like any other. The
 * honest shapes around a revocation, which hover on such ticks, are in FlightRevocationLatencyTest.
 */
class FlightTailIdleTicksTest {
    private static final Vec3 HOVER = new Vec3(0.5, FLOOR_Y + 80, 0.5);
    private static final Vec3 JUMP_20 = new Vec3(20, 0, 0);

    @Test
    void idleTicksAfterAnAnsweredRevocationLeaveNoFreeMove() throws Exception {
        try (var f = flying()) {
            abilities(f, false, false);
            f.pongThrough(f.player.lastTransactionSent.get());
            // LivingEntity#travel falls once the flight is gone, so a client still sending no position is judged
            // from the end of the post-flight tail and set back to where it hovered.
            int idle = 0;
            while (idle < 200 && !f.player.getSetbackTeleportUtil().isPendingSetback()) {
                f.tickEnd();
                idle++;
            }
            assertTrue(idle <= 8, "idle ticks before the setback: " + idle);
            SetBackData setback = f.player.getSetbackTeleportUtil().getRequiredSetBack();
            assertNotNull(setback);
            Vec3 at = setback.getTeleportData().getLocation();
            assertTrue(at.distanceTo(HOVER) < 0.5, "set back to " + at);
            for (int i = idle; i < 200; i++) f.tickEnd();
            PredictionResult r = f.tick(HOVER.add(JUMP_20), false);
            assertSame(setback, f.player.getSetbackTeleportUtil().getRequiredSetBack(), describe(r));
            assertEquals(at, setback.getTeleportData().getLocation(), describe(r));
        }
    }

    @Test
    void idleTicksWhileStillFlyingKeepTheFlightExemption() throws Exception {
        try (var f = flying()) {
            for (int i = 0; i < 200; i++) f.tickEnd();
            PredictionResult r = f.tick(HOVER.add(0, 0.5, 0), false);
            assertTrue(r.isExempt(), describe(r));
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback());
        }
    }

    private static JavaTickFixture flying() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.place(HOVER, Vec3.ZERO);
        abilities(f, true, true);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.tick(HOVER, false).isExempt());
        return f;
    }

    private static void abilities(JavaTickFixture f, boolean canFly, boolean flying) {
        var packet = new ClientboundPlayerAbilities(false, flying, canFly, false, 0.05F, 0.1F);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_ABILITIES, packet, false);
        f.player.checkManager.getListener(PacketPlayerAbilities.class).onPlayerAbilities(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }
}
