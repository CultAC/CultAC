package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/** HappyGhast#sanitizeScale caps the scale attribute at 1, so LivingEntity#getScale never grows a happy ghast. */
class JavaHappyGhastScaleTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final int GHAST = 100;

    @Test
    void aHappyGhastScaledPastOneKeepsItsUnscaledTop() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity ghast = ghast(f);
            double unscaledTop = ghast.getPossibleMovementCollisionBoxes().maxY;
            scale(f, ghast, 4.0F);
            assertEquals(1.0F, ghast.scale);
            assertEquals(unscaledTop, ghast.getPossibleMovementCollisionBoxes().maxY);

            Vec3 top = new Vec3(0.5, unscaledTop, 0.5);
            // LivingEntity#travel's gravity after the last grounded move.
            f.place(top, new Vec3(0, -0.0784000015258789, 0));
            f.player.onGround = f.player.lastOnGround = true;
            f.input(NONE);
            PredictionResult still = f.tick(top, true);
            assertTrue(exact(still), describe(still));
        }
    }

    @Test
    void aPlayerAboveACappedHappyGhastStillFalls() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity ghast = ghast(f);
            double unscaledTop = ghast.getPossibleMovementCollisionBoxes().maxY;
            scale(f, ghast, 4.0F);

            Vec3 above = new Vec3(0.5, FLOOR_Y + 4 * (unscaledTop - FLOOR_Y), 0.5);
            f.place(above, Vec3.ZERO);
            f.input(NONE);
            PredictionResult hover = f.tick(above, true);
            assertFalse(exact(hover), describe(hover));
        }
    }

    @Test
    void aHappyGhastScaledBelowOneShrinks() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity ghast = ghast(f);
            double unscaledHeight = ghast.getPossibleMovementCollisionBoxes().maxY - FLOOR_Y;
            scale(f, ghast, 0.5F);
            assertEquals(0.5F, ghast.scale);
            assertEquals(FLOOR_Y + unscaledHeight * 0.5, ghast.getPossibleMovementCollisionBoxes().maxY, 1.0E-6);
        }
    }

    /** An adult happy ghast centred under (0.5, 0.5) on the floor, not staying still. */
    private static PacketEntity ghast(JavaTickFixture f) {
        f.player.compensatedEntities.addEntity(GHAST, EntityTypeIds.HAPPY_GHAST, new Vec3(0.5, FLOOR_Y, 0.5), 0, 0, 0);
        PacketEntity ghast = f.player.compensatedEntities.getEntity(GHAST);
        assertTrue(ghast instanceof PacketEntityHappyGhast happy && !happy.staysStill && !happy.isBaby);
        return ghast;
    }

    private static void scale(JavaTickFixture f, PacketEntity ghast, float scale) {
        ghast.onScale(scale, 0);
        ghast.onSizeHandled(0);
        ghast.refreshDimensions(f.player);
    }
}
