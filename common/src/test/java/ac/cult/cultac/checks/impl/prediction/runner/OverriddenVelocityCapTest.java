package ac.cult.cultac.checks.impl.prediction.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.PacketStateData;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/** Velocities confirmed while the client never ends a tick keep only the newest overridden candidates. */
class OverriddenVelocityCapTest {
    @Test
    void stalledClientKeepsTheNewestOverriddenVelocitiesUpToTheCap() {
        CultPlayer player = mock(CultPlayer.class);
        player.packetStateData = new PacketStateData();
        KnockbackHandler knockback = new KnockbackHandler(player);
        int confirmed = 10_000;
        for (int transaction = 1; transaction <= confirmed; transaction++) {
            Vec3 vel = new Vec3(transaction, 0, 0);
            knockback.confirmVelocity(new TransactionVel(vel, transaction, true, false), vel);
        }

        assertEquals(PacketModHandler.MAX_OVERRIDDEN_VELS, knockback.overriddenVels.size());
        assertEquals(
                confirmed - 1,
                knockback
                        .overriddenVels
                        .get(PacketModHandler.MAX_OVERRIDDEN_VELS - 1)
                        .getTransaction());
        assertEquals(
                confirmed - PacketModHandler.MAX_OVERRIDDEN_VELS,
                knockback.overriddenVels.get(0).getTransaction());
        assertEquals(confirmed, knockback.secondBread.getTransaction());
    }
}
