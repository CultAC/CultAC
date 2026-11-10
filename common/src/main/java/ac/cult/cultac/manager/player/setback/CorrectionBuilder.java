package ac.cult.cultac.manager.player.setback;

import ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.PredictionSetbackState;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.BoatTransform;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.NextTickVelocityDeriver;
import ac.cult.cultac.utils.nmsutil.VelocityCandidates;
import java.util.Collections;

/**
 * What a correction holds: the target, the velocity the client leaves it with, the ground its stand-in move claims and
 * keeps, the vehicle's rotation and a horse's ground.
 *
 * <ul>
 *   <li>A correction that rejects a movement ticks once from the safe position in its place, as the client's own tick
 *       would; one that rejects nothing, or a timer's, returns to the safe state as it is.
 *   <li>Knockback the client has yet to take replaces the velocity, and a later explosion adds to it in client order.
 *       An impulse the safe position already carries, or one the latest correction replaced, is never sent again; a
 *       vehicle takes none of its rider's.
 *   <li>The stand-in move claims ground only when the correction tick's own move lands, and the client keeps that
 *       ground. A corrected horse leaves with the ground of the move that reached the position.
 *   <li>A ridden vehicle takes its own simulated tick and keeps its own rotation.
 *   <li>A Bedrock on-foot correction targets the position Paper last accepted.
 * </ul>
 */
public final class CorrectionBuilder {
    /** What a setback asks for. {@code flagged} is the rejected movement whose vehicle tick a ridden setback takes. */
    public record Request(boolean simulateNext, boolean ignoreUnchanged, PredictionResult flagged) {}

    /**
     * The setback state a correction starts from.
     *
     * @param paperVisiblePosition the last Bedrock movement Paper accepted, or null
     * @param setbackVehicleId the vehicle the correction moves, or {@code Integer.MIN_VALUE} for the player
     * @param correctionTransaction the ping before the latest Java correction's player_position
     * @param rejectsLatestMovement whether the client's latest movement is not part of the safe position
     * @param playerImpulsesApply whether the player's pending knockback and explosion move what the correction moves
     */
    public record Inputs(
            SetbackPosWithVector safe,
            Vec3 paperVisiblePosition,
            int setbackVehicleId,
            int correctionTransaction,
            boolean rejectsLatestMovement,
            boolean hasFullyLoaded,
            boolean playerImpulsesApply) {}

    private final CultPlayer player;

    public CorrectionBuilder(CultPlayer player) {
        this.player = player;
    }

    /** The correction, or null when the player barely moves and the request ignores that. */
    public SetBackData build(Request request, Inputs inputs) {
        final boolean simulateNext = request.simulateNext();
        final PredictionResult flagged = request.flagged();
        final SetbackPosWithVector safe = inputs.safe();
        Vec3 clientVel = safe.getVector();
        Vec3 position = safe.getPos();
        if (player.isBedrockMovement()
                && inputs.paperVisiblePosition() != null
                && !player.compensatedEntities.vehicles.hasPlayerPassengerState()) {
            // Anchor to the last position packet actually forwarded to Paper
            // (== Paper's lastGood*), never the canonical prediction. A
            // canonical-vs-authored gap would otherwise make the setback echo
            // an upward move and wipe fallDistance (movedUpwards ->
            // resetFallDistance; the anchor-desync NoFall bypass).
            position = inputs.paperVisiblePosition();
        }
        PredictionSetbackState anchorProfileState = safe.getProfileState();
        PredictionSetbackState profileState = player.isBedrockMovement()
                ? player.checkManager.getSimulationProcessor().getBedrockSetbackState()
                : anchorProfileState;
        boolean bedrockVehicle = player.isBedrockMovement()
                && BedrockVehicleControl.isSupported(player.compensatedEntities.getEntity(inputs.setbackVehicleId()));
        if (bedrockVehicle && !simulateNext) {
            profileState = anchorProfileState;
        }
        if (profileState == null) {
            profileState = anchorProfileState;
        }

        TransactionVel sentKnockback = player.checkManager.getKnockbackHandler().getLastSent();
        TransactionVel sentExplosion = player.checkManager.getExplosionHandler().getLastSent();
        if (inputs.playerImpulsesApply() && isPendingImpulse(inputs, sentKnockback)) {
            clientVel = sentKnockback.getVel();
        }
        // Don't apply explosions multiple times when spamming violations. Use the client's order: one flush ping can
        // prove both the motion and a later explosion.
        final boolean explosionIsNewer = inputs.playerImpulsesApply()
                && isPendingImpulse(inputs, sentExplosion)
                && (sentKnockback == null || sentExplosion.getOutboundSequence() > sentKnockback.getOutboundSequence());
        if (explosionIsNewer) {
            clientVel = clientVel.add(sentExplosion.getVel());
        }

        PredictionResult lastPrediction =
                player.checkManager.getSimulationProcessor().getLastPrediction();
        // A Java setback forwards this with its stand-in move, and Paper resets fall distance on a ground claim. Only
        // the correction tick's own Entity#move can put the player on the ground; without one the move only confirms
        // the safe position, like the teleport response ClientPacketListener#handleMovePlayer sends off the ground.
        boolean expectedOnGround = anchorProfileState != null && anchorProfileState.expectedOnGround();

        boolean vehicleSimulated = simulateNext
                && flagged != null
                && !player.isBedrockMovement()
                && flagged.getSimulationContext().getVehicle() != null;
        boolean onFootTick = simulateNext
                && lastPrediction != null
                && !player.compensatedEntities.getSelf().inVehicle();
        // We don't want to make it IMPOSSIBLE for high ping players to play after taking knockback
        // If the player isn't really moving at all, don't bother
        if (onFootTick && request.ignoreUnchanged() && SetbackPhysics.barelyMoves(player, position, clientVel)) {
            return null;
        }
        // Use Java's correction tick for both platforms. The caller owns whether
        // a tick is allowed: timer setbacks must stay on the non-simulating path.
        // The tick stands in for the movement this setback rejects. A setback after the client's latest
        // movement became safe, such as one from a command sent after it, takes the safe state as it is.
        boolean correctionTicked = onFootTick && inputs.rejectsLatestMovement();
        if (correctionTicked) {
            // Block shapes, the step-up and the supporting block read the actor's box and position.
            SimpleCollisionBox oldBB = player.boundingBox;
            double oldX = player.x, oldY = player.y, oldZ = player.z;
            try {
                player.boundingBox = GetBoundingBox.getPlayerBoundingBox(player, position.x, position.y, position.z);

                final PredVector thresholdInput = new PredVector(clientVel);
                clientVel = VelocityTransformer.applyMovementThreshold(
                                Collections.singletonList(thresholdInput), player.getClientVersion())
                        .get(0);

                SimulationContext context = lastPrediction.getSimulationContext();

                Vec3 stuckSpeedMultiplier =
                        context.getWorldData().getStuckSpeed().getStuckSpeedMultiplier();
                // Entity#move multiplies the movement by the stuck speed before Entity#collide.
                if (stuckSpeedMultiplier != null) clientVel = clientVel.multiply(stuckSpeedMultiplier);

                Vec3 collide = Collisions.collide(player, clientVel.x, clientVel.y, clientVel.z);
                // Entity#move's verticalCollisionBelow for the correction tick.
                expectedOnGround = clientVel.y < 0.0D && clientVel.y != collide.y;

                position = new Vec3(position.x + collide.x, position.y, position.z);
                // 1.8 players need the collision epsilon to not phase into blocks when being setback
                // Due to simulation, this will not allow a flight bypass by sending a billion invalid movements
                position = new Vec3(position.x, position.y + collide.y, position.z);
                position = new Vec3(position.x, position.y, position.z + collide.z);

                if (clientVel.x != collide.x) clientVel = new Vec3(0, clientVel.y, clientVel.z);
                if (clientVel.y != collide.y) {
                    boolean landed = clientVel.y < 0.0D;
                    // Entity#checkSupportingBlock ranks candidates by distance from the post-move position.
                    player.x = position.x;
                    player.y = position.y;
                    player.z = position.z;
                    MainSupportingBlockData support = player.checkManager
                            .getSimulationProcessor()
                            .mainSupportingBlockAfter(
                                    GetBoundingBox.getPlayerBoundingBox(player, position.x, position.y, position.z),
                                    collide,
                                    landed);
                    player.x = oldX;
                    player.y = oldY;
                    player.z = oldZ;
                    double restitutedY = VelocityCandidates.afterVerticalCollision(
                            player,
                            BlockProperties.getOnPos(player, support, position),
                            context.isSneaking(),
                            true,
                            clientVel.y,
                            collide.y);
                    clientVel = new Vec3(clientVel.x, restitutedY, clientVel.z);
                }
                if (clientVel.z != collide.z) clientVel = new Vec3(clientVel.x, clientVel.y, 0);

                clientVel = SetbackPhysics.simulateFriction(
                        player,
                        clientVel,
                        context.getWorldData().mayBeInWater(),
                        context.getWorldData().mayBeInLava(),
                        context.usesFallFlyingMovement(),
                        context.getLastOnGround().determineOptimistically(),
                        stuckSpeedMultiplier != null,
                        context);
            } finally {
                player.boundingBox = oldBB;
                player.x = oldX;
                player.y = oldY;
                player.z = oldZ;
            }
        } else if (vehicleSimulated) {
            // The vehicle's simulated tick from the safe state: its Entity#move and the deltaMovement it carries on.
            Vec3 movement = flagged.getAcceptedClosestToTarget();
            position = flagged.getSimulationContext().getStart().add(movement);
            clientVel = NextTickVelocityDeriver.findVelocitiesForNextTick(player, flagged, lastPrediction, null)
                    .iterator()
                    .next();
        }

        if (bedrockVehicle && profileState != null) {
            position = profileState.position();
            clientVel = profileState.velocity();
            expectedOnGround = profileState.expectedOnGround();
        }

        if (!inputs.hasFullyLoaded()) {
            clientVel = null;
        } // if the player hasn't spawned... don't force kb

        SetBackData data = new SetBackData(
                new TeleportData(position, new RelativeFlag(0b11000), player.lastTransactionSent.get(), 0),
                player.xRot,
                player.yRot,
                clientVel,
                player.compensatedEntities.getSelf().getRiding() != null,
                false,
                expectedOnGround,
                profileState);
        if (!player.isBedrockMovement()) {
            data.setGroundAfter(correctionTicked ? DesyncStatus.fromBoolean(expectedOnGround) : safe.getOnGround());
        }
        PacketEntity vehicle = player.compensatedEntities.getEntity(inputs.setbackVehicleId());
        if (vehicle != null && !player.isBedrockMovement()) {
            // The vehicle keeps its own rotation; a boat's is the yaw its simulated tick turned it to.
            float boatYaw =
                    vehicleSimulated && vehicle.isBoat() ? BoatTransform.predictedBoatYaw(player, flagged) : Float.NaN;
            data.setVehicleYaw(Float.isNaN(boatYaw) ? vehicle.clientPhysicalYaw : boatYaw);
            data.setVehiclePitch(vehicle.clientPhysicalPitch);
            if (vehicle instanceof PacketEntityHorse) {
                // ClientPacketListener#handleMoveVehicle leaves the onGround of the client's last, rejected tick,
                // which can be a ground the setback position is far from. The horse leaves the setback with the
                // onGround of the move that reached the position instead, so it never jumps there from the air.
                data.setHorseOnGround(
                        vehicleSimulated
                                ? VelocityCandidates.verticalCollisionBelow(flagged)
                                : safe.getHorseOnGround());
            }
        }
        return data;
    }

    /**
     * Whether the client has yet to take {@code impulse} from the safe state. The safe vector carries one the safe tick
     * took, and the latest Java correction's delta movement replaced one the client applied before that teleport.
     */
    private static boolean isPendingImpulse(Inputs inputs, TransactionVel impulse) {
        if (impulse == null || inputs.safe().carries(impulse)) return false;
        return impulse.getTransaction() >= inputs.correctionTransaction();
    }
}
