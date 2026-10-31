package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFishingHookFlight;
import ac.cult.cultac.events.packets.PacketWorldBorder;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHook;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JavaFishingHookFlightTest {
    /**
     * FishingHook#checkCollision's block hit runs FishingHook#onHitBlock, which scales deltaMovement by
     * HitResult#distanceTo: the squared distance to the hit. A hook falling at 0.316 that meets the floor 0.264 below
     * slows to 0.07 and stays in the air, so it lands over several ticks rather than on the floor at once.
     */
    @Test
    void aBlockHitScalesTheHookBySquaredDistance() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = new Vec3(5.88691485706653, JavaTickFixture.FLOOR_Y + 0.26436902220472, 0.5);
            Vec3 velocity = new Vec3(0.009049252989953202, -0.315969036483181, 0);
            f.player.compensatedEntities.addEntity(90, EntityTypeIds.FISHING_BOBBER, start, 0, 0, 0);
            var hook = (PacketEntityHook) f.player.compensatedEntities.getEntity(90);
            hook.clientPhysicalPosition = start;
            hook.deltaMovement = velocity;

            var result =
                    JavaFishingHookFlight.tick(f.player, hook, null, true, 40).getFirst();

            // The ray meets the floor where Y has fallen 0.264.
            double fraction = (start.y - JavaTickFixture.FLOOR_Y) / -velocity.y;
            Vec3 hit = velocity.scale(fraction);
            Vec3 blocked = velocity.normalize().scale(hit.lengthSqr());
            // 26.3 promotes FishingHook's float gravity before the move.
            Vec3 move = blocked.add(0, -(double) 0.03F, 0);
            Vec3 end = result.state().position();
            assertEquals(start.x + move.x, end.x, 1e-12);
            assertEquals(start.y + move.y, end.y, 1e-12);
            assertFalse(result.state().onGround(), "the scaled hook stops short of the floor");
            assertEquals(move.x * 0.92, result.state().velocity().x, 1e-12);
            assertEquals(move.y * 0.92, result.state().velocity().y, 1e-12);
        }
    }

    /**
     * CollisionGetter#clipIncludingBorder returns a world-border BlockHitResult where the ray leaves the border, and
     * Projectile#hitTargetOrDeflectSelf bounces it for FishingHook#shouldBounceOnWorldBorder instead of calling
     * FishingHook#onHitBlock. Before 26.3 the client runs ProjectileDeflection#REVERSE (x -0.5) and then scales by
     * 0.2; 26.3 passes 0.2 as the power of a server-only deflection, so its client keeps the velocity and its
     * Entity#move stops at the border.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_2", "V_26_3"})
    void aWorldBorderHitBouncesInsteadOfShorteningTheHook(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            PacketWorldBorder border = f.player.checkManager.getWorldBorder();
            JavaTickFixture.setField(PacketWorldBorder.class, border, "oldDiameter", 12.0);
            JavaTickFixture.setField(PacketWorldBorder.class, border, "newDiameter", 12.0);
            Vec3 start = new Vec3(5.5, JavaTickFixture.FLOOR_Y + 1.44, 0.5);
            Vec3 velocity = new Vec3(1, 0, 0);
            f.player.compensatedEntities.addEntity(90, EntityTypeIds.FISHING_BOBBER, start, 0, 0, 0);
            var hook = (PacketEntityHook) f.player.compensatedEntities.getEntity(90);
            hook.clientPhysicalPosition = start;
            hook.deltaMovement = velocity;

            var result =
                    JavaFishingHookFlight.tick(f.player, hook, null, true, 40).getFirst();

            double gravity = version.isNewerThanOrEquals(ClientVersion.V_26_3) ? (double) 0.03F : 0.03;
            Vec3 end = result.state().position();
            assertEquals(start.y - gravity, end.y, 1e-12);
            if (version.isNewerThanOrEquals(ClientVersion.V_26_3)) {
                // The 0.25 wide hook meets the border at X = 6; FishingHook#tick zeroes a horizontal collision.
                assertEquals(6 - 0.125, end.x, 1e-12);
                assertEquals(0.0, result.state().velocity().x, 1e-12);
            } else {
                Vec3 bounced = velocity.scale(-0.5).scale(0.2);
                assertEquals(start.x + bounced.x, end.x, 1e-12);
                assertEquals(bounced.x * 0.92, result.state().velocity().x, 1e-12);
            }
        }
    }
}
