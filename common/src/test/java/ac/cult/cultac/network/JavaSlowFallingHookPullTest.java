package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHook;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import org.junit.jupiter.api.Test;

/**
 * A slow-falling glider pulled by a BOBBING hook. FishingHook#pullEntity adds (owner - hook) * 0.1, and the client
 * never sends the Entity.random draws that bob the hook, so the pull's Y is a range. LivingEntity#getEffectiveGravity
 * then reads the deltaMovement updateFallFlyingMovement starts from: at or below zero slow falling's 0.01, above it the
 * ordinary 0.08. The glide meets gravity only as gravity * (1 - 0.75 * cos(pitch)^2) off the starting Y, so at a steep
 * pitch the other gravity's glide lies outside the range: each side of zero must glide on its own gravity.
 */
class JavaSlowFallingHookPullTest {
    private static final int OWNER = 80, HOOK = 81;
    private static final Vec3 BOBBER = new Vec3(0.5, 80, 0.5);
    private static final Vec3 OWNER_AT = new Vec3(3.5, 79.85, 0.5);
    private static final float YAW = 0, PITCH = 75;

    @Test
    void aPullJustAboveZeroGlidesOnTheOrdinaryGravity() throws Exception {
        // The hook bobbed 0.3 below the position the model holds: the pull is 0.03 higher.
        assertTrue(exact(pulled(-0.3)), "the rising branch");
    }

    @Test
    void aPullAtOrBelowZeroGlidesOnSlowFallingGravity() throws Exception {
        assertTrue(exact(pulled(0.2)), "the falling branch");
    }

    private static PredictionResult pulled(double bob) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.getSelf().addPotionEffect(MovementEffect.SLOW_FALLING, 0);
            f.player.isGliding = true;
            f.player.refreshPlayerPose();
            f.player.xRot = YAW;
            f.player.yRot = PITCH;
            Vec3 from = new Vec3(0.5, 70, 0.5);
            Vec3 carried = new Vec3(0.02, -0.01, 0.3);
            f.place(from, carried);
            f.addRemotePlayer(OWNER, OWNER_AT, GameMode.SURVIVAL);
            f.player.compensatedEntities.addEntity(HOOK, EntityTypeIds.FISHING_BOBBER, BOBBER, 0, 0, OWNER);
            var hook = assertInstanceOf(PacketEntityHook.class, f.player.compensatedEntities.getEntity(HOOK));
            JavaTickFixture.setField(PacketEntityHook.class, hook, "mayBeBobbing", true);
            hook.setAttached(f.player, f.player.entityID);
            f.player.compensatedEntities.fishingPulls.receive(
                    f.player, HOOK, f.player.lastTransactionReceived.get(), f.player.nextImpulseSequence());

            Vec3 pull = OWNER_AT.subtract(BOBBER.add(0, bob, 0)).scale(0.1);
            Vec3 start = carried.add(pull);
            double gravity = start.y <= 0 ? 0.01 : 0.08;
            Vec3 move = ElytraTransform.getElytraMovement(
                    f.player, start, ReachUtils.getLook(f.player, YAW, PITCH), PITCH, gravity);
            PredictionResult result = f.tick(from.add(move), false);
            assertTrue(result.getSimulationContext().usesFallFlyingMovement(), describe(result));
            assertTrue(exact(result), "start " + start + ": " + describe(result));
            return result;
        }
    }
}
