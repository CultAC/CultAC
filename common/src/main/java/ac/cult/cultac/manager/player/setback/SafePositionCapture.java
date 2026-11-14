package ac.cult.cultac.manager.player.setback;

import ac.cult.cultac.bedrock.protocol.BedrockTeleportProvenance;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.PredictionSetbackState;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.MovementEngines;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.checks.impl.prediction.stage.UncertaintyPipeline;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHandler;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHelper;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PositionUpdate;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.IsUsingItem;
import ac.cult.cultac.utils.nmsutil.VelocityCandidates;

/**
 * Turns each completed prediction into the safe position it proves. A movement's candidate is the tick's end state:
 * its position, the legal velocity it carries on with after friction, the impulses its travel took and its ground.
 * A teleport answer's candidate is the teleport's position with the velocity the answer leaves.
 */
public final class SafePositionCapture {
    private final CultPlayer player;
    private final SafePositionTracker safePositions;
    private final CorrectionState corrections;
    private int heldUntil = Integer.MIN_VALUE;

    public SafePositionCapture(CultPlayer player, SafePositionTracker safePositions, CorrectionState corrections) {
        this.player = player;
        this.safePositions = safePositions;
        this.corrections = corrections;
    }

    public void capture(final PredictionComplete predictionComplete) {
        // If this is a regular teleport when on a vehicle, we may not set player variables
        // Therefore, read it from the position update given to us.
        Vec3 to;
        final PositionUpdate positionUpdate = predictionComplete.getPositionUpdate();
        if (positionUpdate != null) {
            to = positionUpdate.getTo();
        } else if (player.isBedrockMovement()
                && predictionComplete.getPredictionResult() != null
                && predictionComplete.getPredictionResult().isTeleport()
                && predictionComplete.getPredictionResult().getSetBackData() != null) {
            to = predictionComplete.getPredictionResult().getSetBackData().getLocation();
        } else {
            to = new Vec3(player.x, player.y, player.z);
        }

        Vec3 afterTickFriction = Vec3.ZERO;
        PredictionSetbackState profileState = null;
        if (player.isBedrockMovement()) {
            profileState = MovementEngines.requireForProfile(MovementProfiles.forPlayer(player))
                    .captureSetbackState(predictionComplete.getPreparedCommit());
            if (profileState != null) {
                to = profileState.position();
                afterTickFriction = profileState.velocity();
            }
        } else if (!predictionComplete.isTeleport()) {
            final PredictionResult result = predictionComplete.getPredictionResult();
            PredVector initialVel = result.getInitialStartingVel();
            final SimulationContext context = result.getSimulationContext();
            final PredictionResult previousResult =
                    player.checkManager.getSimulationProcessor().getLastPrediction();

            MovementTrace trace = MovementTrace.start(initialVel);
            for (UncertaintyHandler modifier : UncertaintyPipeline.MODIFIERS_FOR_SETBACKS) {
                trace = modifier.handleMovementTrace(
                        player,
                        result.getValidMovements(),
                        result,
                        context,
                        previousResult,
                        trace,
                        context.getTarget());
            }
            initialVel = trace.position();
            float speed = context.getMaxSpeed(player);
            if (player.usesModernJavaItemUse()) {
                speed *= context.getItemUse().legalMultiplier();
                if (player.isGliding && context.getItemUse().legalMultiplier() >= 1) speed = 0.0F;
            } else if (IsUsingItem.isUsingItem(player)) {
                speed *= 0.2F;
            } else if (player.isGliding) {
                speed = 0.0f;
            }
            initialVel = UncertaintyHelper.handleCircular(initialVel, context.getTarget(), speed);
            if (VelocityCandidates.collidedVertically(result)) {
                initialVel = initialVel.withY(
                        VelocityCandidates.afterVerticalCollision(result, initialVel.y), "vertical restitution");
            }

            afterTickFriction = SetbackPhysics.simulateFriction(
                    player,
                    initialVel,
                    context.getWorldData().getInWater().determineOptimistically(),
                    context.getWorldData().getInLava().determineOptimistically(),
                    context.usesFallFlyingMovement(),
                    context.getLastOnGround().determineOptimistically(),
                    context.getWorldData().getStuckSpeed().getStuckSpeedMultiplier() != null,
                    null);
        } else {
            // CheckManagerListener#applyTeleportResponse completes an answer away from the target without a prediction.
            final PredictionResult answered = predictionComplete.getPredictionResult();
            TeleportData accepted = answered == null ? null : answered.getSetBackData();
            if (isAcceptedCorrection(accepted)) {
                // ClientPacketListener#setValuesFromPositionPacket set the delta movement to the correction's own.
                afterTickFriction = accepted.getDeltaMovement();
            } else {
                final TransactionVel sentKnockback =
                        player.checkManager.getKnockbackHandler().getLastSent();
                if (sentKnockback != null) {
                    afterTickFriction = sentKnockback.getVel();
                }
            }
        }

        // We must first check if the player has accepted their setback
        // If the setback isn't complete, then this position is illegitimate
        // if we are currently blocking offsets, then the player is desync'd in a vehicle and needs to be teleported
        // soon
        final PredictionResult completedPrediction = predictionComplete.getPredictionResult();
        if (completedPrediction != null && completedPrediction.getSetBackData() != null) {
            TeleportData answeredTeleport = completedPrediction.getSetBackData();
            // A GFP rebase moves the client's coordinate origin, not the player.
            if (answeredTeleport.getBedrockProvenance() != BedrockTeleportProvenance.GFP_REBASE) {
                SetbackPosWithVector answered = new SetbackPosWithVector(to, afterTickFriction, profileState)
                        .provenBy(
                                SetbackEntity.of(player.getRidingVehicleId()),
                                SafeProof.answer(answeredTeleport.getTransaction()));
                answered.setSprintOutcomes(SimulationProcessor.sprintOutcomesAfter(
                        player, predictionComplete.isTeleport() ? null : completedPrediction));
                if (isAcceptedCorrection(answeredTeleport))
                    answered.setOnGround(corrections.required().getGroundAfter());
                safePositions.offer(answered, SafePositionTracker.Write.ANSWER);
            }
        } else if (!player.isBedrockMovement() || profileState != null) {
            // TODO: When flagging immediately after a setback, we don't give uncertainty or allow inputs. This is too
            // strict.
            // No simulation... we can do that later. We just need to know the valid position.
            // As we didn't setback here, the new position is safe unless a check that runs later sets it back.
            SetbackPosWithVector safe = new SetbackPosWithVector(to, afterTickFriction, profileState)
                    .provenBy(
                            SetbackEntity.of(player.getRidingVehicleId()),
                            SafeProof.movement(player.lastTransactionReceived.get()));
            if (player.lastTransactionReceived.get() < heldUntil) return;
            if (!safePositions.accepts(safe, false)) return;
            safe.setSprintOutcomes(SimulationProcessor.sprintOutcomesAfter(
                    player, predictionComplete.isTeleport() ? null : completedPrediction));
            if (!player.isBedrockMovement() && !predictionComplete.isTeleport()) {
                // This tick's travel took every velocity the client applied before it, and its friction carries them.
                safe.setCarriedKnockback(
                        player.checkManager.getKnockbackHandler().lastSentAppliedBeforeTick());
                safe.setCarriedExplosion(
                        player.checkManager.getExplosionHandler().lastSentAppliedBeforeTick());
                if (completedPrediction.getSimulationContext().getVehicle() instanceof PacketEntityHorse) {
                    safe.setHorseOnGround(VelocityCandidates.verticalCollisionBelow(completedPrediction));
                } else if (completedPrediction.getSimulationContext().getVehicle() == null) {
                    safe.setOnGround(VelocityCandidates.verticalCollisionBelow(completedPrediction));
                }
            }
            safePositions.stage(safe);
        }
    }

    /** Movements stage no safe position until the client answers {@code transaction}. */
    public void holdUntil(int transaction) {
        heldUntil = Math.max(heldUntil, transaction);
    }

    /** Whether the client just accepted {@code teleport} as a Java correction whose delta movement is absolute. */
    private boolean isAcceptedCorrection(TeleportData teleport) {
        SetBackData setback = corrections.required();
        return teleport != null
                && setback != null
                && setback.isComplete()
                && !setback.isPlugin()
                && setback.getTeleportData().getTransaction() == teleport.getTransaction()
                && !teleport.isRelativeDeltaX()
                && !teleport.isRelativeDeltaY()
                && !teleport.isRelativeDeltaZ();
    }
}
