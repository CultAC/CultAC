package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.api.BedrockFluidMovementSource;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import ac.cult.cultac.bedrock.prediction.world.JumpPreventionState;
import ac.cult.cultac.bedrock.prediction.state.BedrockDolphinBoost;

public final class BedrockFrameSystems {
    private BedrockFrameSystems() {
    }

    public static BedrockFrameState prepare(
        BedrockTravelInput input,
        BedrockMobJumpComponentState initialMobJumpComponent
    ) {
        if (input.previousState().isBoat()) return BedrockBoatMovement.prepare(input, initialMobJumpComponent);
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

        // Launch velocity uses carried water state; sensing precedes the requested resize.
        BedrockFrameFacts facts = BedrockFrameFacts.from(input, riptide.spinActive(), spinAttack.spinAttackStarted());
        var cameraWater = BedrockUnderwaterSensing.update(input.previousState(), input.previousState().cameraWater(),
            facts.context(), input.previousState().physicalFeetPosition(), input.previousState().playerDimensions());
        BedrockLiquidJumpContact liquidContact = BedrockLiquidJumpContact.from(
            facts, input.previousState().physicalFeetPosition(), cameraWater.headInWater());


        boolean actorSprinting = intent.sprint().currentTickActorSprinting(
            input.previousState().sprinting()
        );
        if (input.control() != null && !input.previousState().isVehicle()) {
            // Held sprint input does not override a stop action or acknowledged actor state.
            actorSprinting = intent.sprint().afterActionEdges(input.previousState().sprinting());
            float side = (float) input.control().x();
            float forward = (float) input.control().z();
            if (!input.previousState().swimming() && !facts.context().actorSwimming()
                && (forward <= 0.0F || Math.abs(side) > 0.70710677F
                    || (float) Math.sqrt(side * side + forward * forward) < 0.70710677F)) {
                actorSprinting = false;
            }
        }
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
        BedrockSwimmingMovement.SwimmingState swimming = facts.swimming().afterActions(
            intent, facts.context().inWater());
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
        boolean groundJumpApplied = input.options().travelActive() && mobJump.groundJumpRequest();
        if (groundJumpApplied) {
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

        var horse = input.previousState().horse();
        if (horse != null) {
            var jump = BedrockHorseMovement.applyJump(input, facts, velocity);
            horse = jump.state();
            velocity = jump.velocity();
            groundJumpApplied = jump.launched();
        } else {
            cameraWater = BedrockCameraMovement.afterActions(input, cameraWater,
                swimming.actorStateAfterActions(), gliding.activeAfterActions(), riptide.spinActive());
        }

        BedrockDolphinBoost dolphinBoost = input.previousState().dolphinBoost().tick(
            swimming.actorStateAfterActions(), facts.context().dolphinBoostAvailable());
        facts = facts.withContext(BedrockFluidStateResolver.withSwimSpeedMultiplier(
            facts.context(), dolphinBoost.multiplier()));

        // Teleport ticks skip travel selection.
        BedrockTravelSelection selection = !input.options().travelActive()
            ? new BedrockTravelSelection(BedrockTravelType.NONE, input.previousState().movementBranch())
            : BedrockTravelTypeResolver.resolve(
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
            mobJump,
            dolphinBoost,
            groundJumpApplied,
            cameraWater,
            horse, null
        );
    }

    private static boolean travelSensingOnGround(BedrockTravelInput input, BedrockFrameFacts facts) {

        return input.previousState().collisionFlags().onGround();
    }
}
