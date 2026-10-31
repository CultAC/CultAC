package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFishingHookFlight;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHook;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

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
}
