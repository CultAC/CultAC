package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JavaGroundedGlideContactTest {
    /**
     * Real move returns at 14:41:13 (1.21.4) and 14:42:03 (26.3), after the test-map return teleport.
     * A still-set glide flag runs travelFallFlying even on the floor; a nearby pusher cannot cancel its Y.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void horizontalEntityContactCannotEraseADownwardGlideMove(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(0.5019486597283, FLOOR_Y, 0.5);
            f.place(start, new Vec3(0.0019486597283403166, 0, 0));
            f.player.isGliding = true;
            f.player.pose = Pose.FALL_FLYING;
            f.player.boundingBox = GetBoundingBox.getPlayerBoundingBox(f.player, start.x, start.y, start.z);
            f.player.xRot = -90;
            f.player.yRot = 13;
            f.addRemotePlayer(100, start.add(0.5, 0, 0), GameMode.SURVIVAL);
            // Carry the late pusher's existing allowance, as in the captured preceding tick.
            var previous = f.tick(start.add(0.0019486597283403166, 0, 0), true);
            assertTrue(JavaTickFixture.exact(previous), JavaTickFixture.describe(previous));
            start = new Vec3(f.player.x, f.player.y, f.player.z);
            f.player.isGliding = true;
            // RemotePlayer#pushEntities has now added its one exact late impulse to deltaMovement.
            double distance = 0.5 - 0.0019486597283403166;
            double pushedX = 0.0019486597283403166 - Math.sqrt(distance) * (double) 0.05F;
            var next = ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform.getElytraMovement(
                    f.player,
                    new Vec3(pushedX, 0, 0),
                    ac.cult.cultac.utils.nmsutil.ReachUtils.getLook(f.player, -90, 13),
                    13,
                    0.08);
            var result = f.tick(start.add(next.x, 0, next.z), true);
            assertTrue(JavaTickFixture.exact(result), JavaTickFixture.describe(result));
            var move = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
            assertEquals(-0.020432148367421816, move.attempted().y, 1e-13);
            assertTrue(move.verticalCollisionBelow());
            assertTrue(result.getSimulationContext().getWorldData().getNumPushing() > 0);
        }
    }
}
