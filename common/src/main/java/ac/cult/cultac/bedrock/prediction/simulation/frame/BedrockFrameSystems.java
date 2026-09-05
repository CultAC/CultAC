package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.api.BedrockFluidMovementSource;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import ac.cult.cultac.bedrock.prediction.world.JumpPreventionState;

public final class BedrockFrameSystems {
    private BedrockFrameSystems() {
    }

    public static BedrockFrameState prepare(
        BedrockTravelInput input,
        BedrockMobJumpComponentState initialMobJumpComponent
    ) {
        BedrockInputIntent intent = input.inputIntent();
        Vec3d velocity = input.startingVelocity();

        BedrockRiptideMovement.ActorNormalTick spinAttack = BedrockRiptideMovement.tickActorNormal(
            input.previousState(),
            input.inputFrame(),
            intent,
            input.worldSnapshot().movementContext(),
            velocity
        );
        BedrockRiptideMovement.Step riptide = spinAttack.step();
        velocity = spinAttack.velocity();

        // Start/stop spin requests the shape rebuild before water sensing.
        BedrockFrameFacts facts = BedrockFrameFacts.from(input, riptide.spinActive());
        BedrockLiquidJumpContact liquidContact = BedrockLiquidJumpContact.from(
            facts,
            input.previousState().physicalFeetPosition()
        );


        boolean actorSprinting = intent.sprint().currentTickActorSprinting(
            input.previousState().sprinting()
        );
        BedrockTravelInputControl.InputControlState control = BedrockTravelInputControl.resolve(
            input.previousState(),
            input.inputFrame(),
            actorSprinting,
            input.options().sprintTravelSpeedMode(),
            input.options().sprintJumpImpulseMode(),
            intent,
            facts.context(),
            facts.effectState()
        );

        BedrockFluidMovementSource movementSource = BedrockFluidMovementSourceResolver.fromContext(
            facts.context(),
            input.previousState().physicalFeetPosition().y()
        );
        velocity = velocity.add(movementSource.appliedDelta());


        BedrockSwimmingMovement.SwimmingState swimming = facts.swimming().afterActions(intent);
        if (riptide.spinActive()) {

            swimming = BedrockSwimmingMovement.duringSpin(input.previousState());
        }
        facts = facts.withSwimming(swimming);

        BedrockGlideState gliding = BedrockGlidingTravelMovement.resolve(
            input.previousState(),
            intent,
            facts.context()
        );
        facts = facts.withClimb(BedrockClimbMovement.resolveActions(
            input.previousState(),
            input.inputFrame(),
            intent,
            facts.climb(),
            input.scaffoldingVerticalBranch()
        ));

        if (facts.inWater()
            && BedrockLiquidVerticalMovement.descendInput(intent)
            && !facts.context().movementAbilityFlying()) {
            velocity = BedrockLiquidVerticalMovement.waterDescendVelocity(velocity);
        }
        velocity = BedrockGlideInputMovement.apply(
            velocity,
            input.previousState(),
            intent,
            facts.context(),
            gliding
        );
        BedrockScaffoldingAction scaffoldingAction = BedrockScaffoldingAction.resolve(facts.climb());
        if (scaffoldingAction.active()) {
            velocity = new Vec3d(velocity.x(), scaffoldingAction.moveY(), velocity.z());
        }

        BedrockMobJumpInput mobJumpInput = BedrockMobJumpInput.from(input, intent, facts, liquidContact);
        BedrockMobJumpComponentState mobJumpComponent = initialMobJumpComponent;
        BedrockMobJump mobJump = BedrockMobJump.resolve(mobJumpInput, mobJumpComponent);
        mobJumpComponent = mobJump.componentAfterMobJump();
        if (mobJump.active()) {
            velocity = mobJump.applyVelocityMutation(velocity);
        }
        if (mobJump.groundJumpRequest()) {
            JumpPreventionState jumpPreventionState = BedrockJumpPreventionResolver.resolve(
                facts.context(),
                input.previousState().physicalFeetPosition(),
                input.previousState().collisionFlags().onGround()
            );
            double jumpVelocity = BedrockJumpMovement.jumpVelocity(
                facts.effectState(),
                facts.context().attributeState().jumpStrength(),
                jumpPreventionState
            );
            velocity = BedrockJumpMovement.groundLaunchVelocity(
                velocity,
                input.inputFrame(),
                jumpVelocity,
                control.sprintJumpImpulseActive()
            );
        }
        if (BedrockWaterSwimControl.applies(facts, input.inputFrame())) {
            velocity = BedrockWaterSwimControl.lookAdjustedVelocity(
                velocity,
                input.inputFrame(),
                intent,
                facts.context(),
                liquidContact.waterHeadInWater()
            );
        }

        BedrockTravelSelection selection = BedrockTravelTypeResolver.resolve(
            facts.context(),

            facts.inWater(),
            facts.lavaTravelFlag(),
            travelSensingOnGround(input, facts),
            gliding.activeAtTravelSensing(),
            facts.climb().climbing()
        );
        return new BedrockFrameState(
            input,
            intent,
            facts,
            gliding,
            new BedrockTravelBranch(selection),
            actorSprinting,
            control,
            riptide,
            mobJumpComponent,
            velocity,
            mobJump
        );
    }

    private static boolean travelSensingOnGround(BedrockTravelInput input, BedrockFrameFacts facts) {

        return input.previousState().collisionFlags().onGround();
    }
}
