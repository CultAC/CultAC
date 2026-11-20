package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * HappyGhast#canBeCollidedWith admits a Player at or above the top of the ghast's own bounding box, so a player
 * standing on a ghast that is not staying still stays on it.
 */
class JavaHappyGhastTopTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final int GHAST = 100;

    @Test
    void aPlayerStandingOnAHappyGhastStaysOnItsTop() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 top = new Vec3(0.5, ghast(f).getPossibleMovementCollisionBoxes().maxY, 0.5);
            // LivingEntity#travel's gravity after the last grounded move.
            f.place(top, new Vec3(0, -0.0784000015258789, 0));
            f.player.onGround = f.player.lastOnGround = true;
            f.input(NONE);

            PredictionResult still = f.tick(top, true);
            assertTrue(exact(still), describe(still));
        }
    }

    @Test
    void aPlayerAboveAHappyGhastStillFalls() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 above = new Vec3(0.5, ghast(f).getPossibleMovementCollisionBoxes().maxY + 0.1, 0.5);
            f.place(above, Vec3.ZERO);
            f.input(NONE);

            PredictionResult hover = f.tick(above, true);
            assertFalse(exact(hover), describe(hover));
        }
    }

    /** An adult happy ghast centred under (0.5, 0.5) on the floor, not staying still. */
    private static PacketEntity ghast(JavaTickFixture f) {
        f.player.compensatedEntities.addEntity(GHAST, EntityTypeIds.HAPPY_GHAST, new Vec3(0.5, FLOOR_Y, 0.5), 0, 0, 0);
        PacketEntity ghast = f.player.compensatedEntities.getEntity(GHAST);
        assertTrue(ghast instanceof PacketEntityHappyGhast happy && !happy.staysStill && !happy.isBaby);
        return ghast;
    }
}
