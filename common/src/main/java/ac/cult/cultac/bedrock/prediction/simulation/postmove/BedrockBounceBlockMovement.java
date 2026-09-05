package ac.cult.cultac.bedrock.prediction.simulation.postmove;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockAerialMovement;
import ac.cult.cultac.bedrock.prediction.world.BounceBlockState;

public final class BedrockBounceBlockMovement {
    private static final float BLOCK_RESTITUTION_MIN_FALL_SPEED = 0.08F;

    private BedrockBounceBlockMovement() {
    }

    public static Result applyAfterVerticalReset(
        Vec3d currentFeetPosition,
        Vec3d resetVelocity,
        double fallingVelocityY,
        BedrockInputFrame frame,
        BounceBlockState bounceBlockState,
        boolean normalGravityAndVerticalDragApply
    ) {
        Restitution restitution = computeRestitutionAfterVerticalReset(
            fallingVelocityY,
            frame,
            bounceBlockState
        );
        if (!restitution.applied()) {
            return Result.NONE;
        }
        if (restitution.landedWithoutBounce()) {
            return new Result(
                new Vec3d(resetVelocity.x(), 0.0D, resetVelocity.z()),
                false,
                true,
                true
            );
        }

        if (!normalGravityAndVerticalDragApply) {

            return new Result(
                new Vec3d(resetVelocity.x(), restitution.restitutionVelocityY(), resetVelocity.z()),
                true,
                false,
                true
            );
        }

        double collisionTravelY = bounceBlockState.surfaceY() - currentFeetPosition.y();
        double correctedVelocityY = bounceGravityCorrectedVelocityY(
            restitution.restitutionVelocityY(),
            fallingVelocityY,
            collisionTravelY
        );
        double postMoveDraggedY = BedrockAerialMovement.airDraggedVelocityWithoutGravity(correctedVelocityY);

        return new Result(
            new Vec3d(resetVelocity.x(), postMoveDraggedY, resetVelocity.z()),
            true,
            false,
            true
        );
    }

    private static Restitution computeRestitutionAfterVerticalReset(
        double fallingVelocityY,
        BedrockInputFrame frame,
        BounceBlockState bounceBlockState
    ) {
        if (frame.sneaking()) {
            return Restitution.LANDED_WITHOUT_BOUNCE;
        }
        if (fallingVelocityY >= 0.0D) {
            return Restitution.NONE;
        }
        if (Math.abs((float) fallingVelocityY) < BLOCK_RESTITUTION_MIN_FALL_SPEED) {
            return Restitution.LANDED_WITHOUT_BOUNCE;
        }
        return new Restitution(
            bounceBlockState.reboundVelocityY(fallingVelocityY),
            true,
            false,
            true
        );
    }

    private static double bounceGravityCorrectedVelocityY(
        double reboundVelocityY,
        double fallingVelocityY,
        double collisionTravelY
    ) {
        float rawGravity = -(float) BedrockAerialMovement.AIR_GRAVITY;
        float absoluteGravity = Math.abs(rawGravity);
        if (absoluteGravity <= 0.0F) {
            return (float) reboundVelocityY;
        }
        float term0 = (float) fallingVelocityY;
        float term1 = Math.abs((float) collisionTravelY);
        float sign = term0 > 0.0F ? 1.0F : term0 < 0.0F ? -1.0F : 0.0F;
        float root = (float) Math.sqrt(term0 * term0 + (absoluteGravity + absoluteGravity) * term1);
        float ratio = Math.abs((Math.abs(term0) - root) / rawGravity);
        float correction = absoluteGravity * sign * (1.0F - ratio);
        return (float) ((float) reboundVelocityY + correction);
    }

    private record Restitution(
        double restitutionVelocityY,
        boolean bounced,
        boolean landedWithoutBounce,
        boolean applied
    ) {
        private static final Restitution NONE =
            new Restitution(0.0D, false, false, false);
        private static final Restitution LANDED_WITHOUT_BOUNCE =
            new Restitution(0.0D, false, true, true);
    }

    public record Result(
        Vec3d velocity,
        boolean bounced,
        boolean landedWithoutBounce,
        boolean applied
    ) {
        static final Result NONE =
            new Result(Vec3d.ZERO, false, false, false);
    }

}
