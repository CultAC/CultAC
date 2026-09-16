package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;

public final class BedrockRiptideMovement {
    private static final long RELEASE_CHARGE_THRESHOLD_TICKS = 9L;
    private static final long GROUNDED_SPIN_TICKS = 5L;
    private static final long MAX_SPIN_TICKS = 19L;

    private BedrockRiptideMovement() {
    }

    static ActorNormalTick tickActorNormal(
        BedrockMovementState current,
        BedrockInputFrame frame,
        BedrockInputIntent intent,
        BedrockMovementContext context,
        Vec3d startingVelocity
    ) {
        int level = context.equipmentState().riptideLevel();
        boolean usable = context.riptideAvailable() && level > 0;

        boolean wet = current.wasInWaterFlag() || context.rainContact();

        boolean releaseRequested = usable && intent.itemUse().release();
        boolean releaseStartsSpinAttack = releaseRequested
            && intent.riptide().startSpinAttack()
            && wet
            && current.riptideChargeTicks() > RELEASE_CHARGE_THRESHOLD_TICKS;
        SpinState spin = nextSpinState(current, intent, releaseStartsSpinAttack);
        Vec3d nextVelocity = startingVelocity;
        if (releaseStartsSpinAttack) {
            nextVelocity = nextVelocity.add(BedrockAerialMovement.riptideImpulse(
                frame,
                level,
                current.collisionFlags().onGround(),
                current.wasInWaterFlag(),
                current.cameraWater().headInWater()
            ));
        }

        return new ActorNormalTick(
            new Step(
                nextChargeTicks(current, intent, usable, wet, releaseRequested),
                spin.active(),
                spin.ticks()
            ),
            nextVelocity
        );
    }

    private static long nextChargeTicks(
        BedrockMovementState current,
        BedrockInputIntent intent,
        boolean usable,
        boolean wet,
        boolean releaseRequested
    ) {
        if (releaseRequested || intent.itemUse().stop()) {
            return 0L;
        }
        // Acknowledged USING_ITEM metadata bypasses the wet check, thanks mojang!
        if (usable && (wet || intent.riptide().serverUsingItem()) && intent.riptide().chargeStart()) {
            return 1L;
        }
        if (usable && current.riptideChargeTicks() > 0L) {
            return current.riptideChargeTicks() + 1L;
        }
        return 0L;
    }

    private static SpinState nextSpinState(
        BedrockMovementState current,
        BedrockInputIntent intent,
        boolean releaseStartsSpinAttack
    ) {
        // A spin request cannot create an impulse without a valid charged, wet release.
        if (releaseStartsSpinAttack) {

            return new SpinState(true, 1L);
        }
        if (!current.riptideSpinActive() || intent.riptide().stopSpinAttack()) {
            return SpinState.INACTIVE;
        }
        if (current.collisionFlags().horizontalCollision()
            || current.riptideSpinTicks() >= MAX_SPIN_TICKS
            || current.riptideSpinTicks() >= GROUNDED_SPIN_TICKS
                && current.collisionFlags().onGround()) {
            return SpinState.INACTIVE;
        }
        return new SpinState(true, current.riptideSpinTicks() + 1L);
    }

    record ActorNormalTick(Step step, Vec3d velocity) {
    }

    public record Step(
        long nextChargeTicks,
        boolean spinActive,
        long spinTicks
    ) {
    }

    private record SpinState(boolean active, long ticks) {
        private static final SpinState INACTIVE = new SpinState(false, 0L);
    }
}
