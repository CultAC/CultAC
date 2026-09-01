package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import java.util.Objects;

public record BedrockMobJump(
    Branch branch,
    double swimUpImpulse,
    BedrockMobJumpComponentState componentAfterMobJump
) {
    public static final BedrockMobJump NONE = new BedrockMobJump(Branch.NONE, BedrockMobJumpComponentState.DEFAULT);

    public BedrockMobJump {
        branch = Objects.requireNonNull(branch, "branch");
        componentAfterMobJump = Objects.requireNonNull(componentAfterMobJump, "componentAfterMobJump");
        if (!Double.isFinite(swimUpImpulse)) {
            throw new IllegalArgumentException("swimUpImpulse must be finite");
        }
    }

    public BedrockMobJump(Branch branch) {
        this(branch, BedrockMobJumpComponentState.DEFAULT);
    }

    public static BedrockMobJump none(BedrockMobJumpComponentState component) {
        return new BedrockMobJump(Branch.NONE, BedrockMobJumpComponentState.NORMAL_SWIM_UP_IMPULSE, component);
    }

    private BedrockMobJump(Branch branch, BedrockMobJumpComponentState component) {
        this(
            branch,
            component.swimUpImpulse(),
            component.afterSwimUpImpulseSelection()
        );
    }

    public static BedrockMobJump resolve(
        BedrockMobJumpInput input,
        BedrockMobJumpComponentState component
    ) {
        if (!input.jumping()) {
            return none(component);
        }
        Branch branch = firstMatchingBranch(input, component);
        return new BedrockMobJump(
            branch,
            component.swimUpImpulse(),
            component.afterSwimUpImpulseSelection()
        );
    }

    public boolean active() {
        return branch != Branch.NONE;
    }

    public boolean zeroedWaterVelocity() {
        return branch == Branch.WATER_AUTO_SURFACE_SWIM
            || branch == Branch.WATER_SWIM_TRANSITION;
    }

    public boolean lavaSwimUpApplied() {
        return branch == Branch.LAVA_SWIM_UP;
    }

    public boolean groundJumpRequest() {
        return branch == Branch.GROUND_JUMP_REQUEST;
    }

    public Vec3d applyVelocityMutation(Vec3d velocity) {
        if (zeroedWaterVelocity()) {
            return new Vec3d(velocity.x(), 0.0D, velocity.z());
        }
        return switch (branch) {
            case SCAFFOLDING_OR_ASCENDABLE_BLOCK -> new Vec3d(
                velocity.x(),
                BedrockClimbMovement.SCAFFOLDING_ASCEND_VELOCITY,
                velocity.z()
            );
            case LADDER_OR_POWDER_SNOW_AT_FEET -> new Vec3d(
                velocity.x(),
                BedrockClimbMovement.LADDER_ASCEND_VELOCITY,
                velocity.z()
            );
            case WATER_NON_SWIMMER_SWIM_UP -> new Vec3d(
                velocity.x(),
                velocity.y() + swimUpImpulse,
                velocity.z()
            );
            case LAVA_SWIM_UP -> new Vec3d(
                velocity.x(),
                BedrockLiquidVerticalMovement.lavaSwimUpVelocityY(velocity.y()),
                velocity.z()
            );
            case NONE, WATER_AUTO_SURFACE_SWIM, WATER_SWIM_TRANSITION, GROUND_JUMP_REQUEST -> velocity;
        };
    }

    private static Branch firstMatchingBranch(
        BedrockMobJumpInput input,
        BedrockMobJumpComponentState component
    ) {
        if (input.autoSurfaceSwim()) {
            return Branch.WATER_AUTO_SURFACE_SWIM;
        }
        if (input.swimTransition()) {
            return Branch.WATER_SWIM_TRANSITION;
        }
        if (input.scaffoldingOrAscendableBlock()) {
            return Branch.SCAFFOLDING_OR_ASCENDABLE_BLOCK;
        }
        if (input.ladderOrPowderSnowAtFeet()) {
            return Branch.LADDER_OR_POWDER_SNOW_AT_FEET;
        }
        if (input.nonSwimmerSwimUp()) {
            return Branch.WATER_NON_SWIMMER_SWIM_UP;
        }
        if (input.lavaSwimUp()) {
            return Branch.LAVA_SWIM_UP;
        }
        if (input.groundJumpRequest()) {
            return Branch.GROUND_JUMP_REQUEST;
        }
        return Branch.NONE;
    }

    public enum Branch {
        NONE,
        WATER_AUTO_SURFACE_SWIM,
        WATER_SWIM_TRANSITION,
        SCAFFOLDING_OR_ASCENDABLE_BLOCK,
        LADDER_OR_POWDER_SNOW_AT_FEET,
        WATER_NON_SWIMMER_SWIM_UP,
        LAVA_SWIM_UP,
        GROUND_JUMP_REQUEST
    }

}
