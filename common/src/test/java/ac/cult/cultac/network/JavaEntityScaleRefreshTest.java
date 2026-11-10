package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaLivingEntityPushes;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ClientPacketListener#handleUpdateAttributes only marks an entity's scale dirty. LivingEntity#tick runs aiStep, with
 * its pushEntities, before refreshDirtyAttributes resizes the box, so the entity pushes with its previous box on the
 * first tick after the packet and with the new one from the next.
 */
class JavaEntityScaleRefreshTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);
    private static final int ZOMBIE = 100;

    /** The zombie's 0.6 wide box misses a player 0.8 away on both axes; its 1.2 wide box at scale 2 reaches it. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aGrownZombiePushesFromTheTickAfterItsBoxRefreshes(boolean idle) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 feet = new Vec3(0.5, FLOOR_Y, 0.5);
            Vec3 zombieAt = feet.add(0.8, 0, 0.8);
            f.player.compensatedEntities.addEntity(ZOMBIE, EntityTypeIds.ZOMBIE, zombieAt, 0, 0, 0);
            PacketEntity zombie = f.player.compensatedEntities.getEntity(ZOMBIE);
            f.place(feet, GROUNDED);
            f.player.onGround = f.player.lastOnGround = true;
            f.input(NONE);
            still(f, feet, false, "before the attribute");

            f.player.compensatedEntities.updateAttributes(
                    ZOMBIE,
                    List.of(new AttributeSnapshot("minecraft:scale", 2.0, List.of())),
                    f.player.lastTransactionReceived.get());
            assertEquals(0.6, width(zombie), 1.0E-6, "the attribute is only dirty");

            // The zombie's aiStep pushes with its previous box, then its tick refreshes it.
            still(f, feet, idle, "the tick the zombie refreshes its box in");
            assertEquals(1.2, width(zombie), 1.0E-6);
            // Its first push with the new box follows the player's travel, which the next tick carries.
            still(f, feet, idle, "the first tick the new box pushes in");

            Vec3 push = JavaLivingEntityPushes.impulse(zombieAt, feet);
            assertTrue(push.x < 0 && push.z < 0, push.toString());
            PredictionResult pushed = f.tick(feet.add(push), true);
            assertTrue(exact(pushed), describe(pushed));
        }
    }

    /** One client tick in which the player stays at {@code feet}, reported or, when {@code idle}, not. */
    private static void still(JavaTickFixture f, Vec3 feet, boolean idle, String tick) {
        var simulation = f.player.checkManager.getSimulationProcessor();
        if (!idle) {
            PredictionResult result = f.tick(feet, true);
            assertTrue(exact(result), tick + ": " + describe(result));
            return;
        }
        PredictionResult before = simulation.getLastPrediction();
        f.tickEnd();
        PredictionResult result = simulation.getLastPrediction();
        assertTrue(result != before, tick + ": an idle tick commits its own prediction");
        assertFalse(result.isExempt() || result.hasEffectiveFlags(), tick + ": " + describe(result));
    }

    private static double width(PacketEntity entity) {
        var box = entity.getPossibleMovementCollisionBoxes();
        return box.maxX - box.minX;
    }
}
