package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.Riptide;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/** TridentItem#releaseUsing before baseTick: push, then a grounded Entity#move(SELF, (0, 1.1999999F, 0)). */
public final class JavaRiptideRelease {
    private JavaRiptideRelease() {}

    public record Prepared(SimulationContext context, List<PredVector> velocities) {}

    public static List<Prepared> prepare(
            CultPlayer player,
            WorldStageBuilder builder,
            SimulationContext context,
            PredictionResult last,
            List<PredVector> velocities) {
        if (player.isBedrockMovement() || player.packetStateData.riptideLevel == 0 || context.getVehicle() != null)
            return List.of(new Prepared(context, velocities));
        boolean spins = context.getRiptideLevel() != 0;
        ClientBlockEntityPass beforePlayer =
                ClientBlockEntityPass.EMPTY.after(player.compensatedEntities.shulkerPeekMoves(true));
        if (!spins && beforePlayer.isEmpty()) return List.of(new Prepared(context, velocities));
        var outputs = new ArrayList<Prepared>();
        var world = context.getWorldData();
        var carry = context.getProfileCarry() instanceof JavaPredictionCarry javaCarry ? javaCarry : null;
        var previous = last == null ? null : last.getJavaCommittedMove();
        for (boolean grounded : world.getCarriedOnGround().getStates()) {
            for (PredVector pushed : spins ? Riptide.releasePushes(context, velocities) : velocities) {
                boolean lift = spins && grounded;
                JavaPassState start = new JavaPassState(
                        context.getStart(),
                        pushed,
                        grounded,
                        context.getLastTickMainSupportingBlockData(),
                        context.consumesStuckSpeed() ? context.getLastStuckSpeed() : null,
                        carry == null ? 0.0 : carry.fallDistance(),
                        player.checkManager.getSimulationProcessor().getPistonDeltas(),
                        carry != null && carry.powderContact(),
                        previous == null ? world.isInWater() : previous.touchingWater(),
                        previous == null ? world.isInLava() : previous.touchingLava(),
                        world.getEyeFluidContact());
                JavaPassState moved = null;
                JavaBlockEntityMove.Moved lifted = null;
                if (lift) {
                    var env = new JavaBlockEntityMove.Environment(
                            player,
                            context,
                            context.getPose(),
                            context.getScale(),
                            context.isLastSneaking(),
                            context.getStart(),
                            0L,
                            null,
                            context.getMovementPatch());
                    long phase = player.compensatedEntities.passTickedThroughOrder();
                    try {
                        // handleKeybinds precedes ClientLevel#tickEntities, even when LocalPlayer was added last.
                        player.compensatedEntities.setPassTickedThroughOrder(Long.MIN_VALUE + 1);
                        lifted = JavaBlockEntityMove.move(
                                env, start, JavaBlockEntityMove.Mover.SELF, new Vec3(0, 1.1999999F, 0));
                        moved = lifted.state();
                    } finally {
                        player.compensatedEntities.setPassTickedThroughOrder(phase);
                    }
                }
                JavaPassState beforeTick = moved == null ? start : moved;
                if (!beforePlayer.isEmpty() && beforePlayer.mayReach(context.getActualPoseAt(beforeTick.position())))
                    moved = JavaBlockEntityPass.run(
                                    player,
                                    context,
                                    beforePlayer,
                                    beforeTick,
                                    context.getPose(),
                                    context.isLastSneaking(),
                                    context.getStart())
                            .state();
                SimulationContext released = context.afterPreTravelMove(moved);
                released.setMovesBeforeTravel(recordedLift(context, lifted));
                released.setRiptideLiftStuck(lift && context.consumesStuckSpeed() ? context.getLastStuckSpeed() : null);
                if (moved != null) {
                    var state = new JavaPredictionCarry.State(
                            moved.velocity(),
                            moved.fallDistance(),
                            moved.stuckSpeed(),
                            context.getMovementPatch(),
                            null,
                            moved.powderContact(),
                            moved.playerEyeFluidContact(),
                            context.getFluidTagOrder());
                    released.setProfileCarry(new JavaPredictionCarry(
                            player.compensatedEntities.getSelf(), moved.fallDistance(), List.of(state)));
                    released.setWorldData(builder.generateWorldData(
                            player, released, last, DesyncStatus.fromBoolean(moved.onGround())));
                    released.setWaterMovementEfficiency(context.getWaterMovementEfficiency());
                    pushed = pushed.with(moved.velocity(), lift ? Riptide.GROUND_LIFT : "entities before player");
                    // The lift can remove a RemotePlayer from the spin's swept box. Recompute the
                    // post-travel sprint reset exception from this candidate's world.
                    boolean spinPlayer = released.getWorldData().isSpinAttackPlayer();
                    released.setIsSprinting(
                            spinPlayer ? DesyncStatus.UNKNOWN : DesyncStatus.fromBoolean(player.isSprinting));
                    released.setSprintModifier(spinPlayer || player.sprintState.modifier());
                }
                outputs.add(new Prepared(released, List.of(pushed)));
            }
        }
        return outputs;
    }

    /** From 1.21.5 Entity#move records the lift, which the travel's Entity#applyEffectsFromBlocks replays first. */
    private static List<JavaInsideBlockEffects.RecordedMove> recordedLift(
            SimulationContext context, JavaBlockEntityMove.Moved lifted) {
        if (lifted == null || !lifted.recordedMovement() || context.getVersion().isOlderThan(ClientVersion.V_1_21_5))
            return List.of();
        return List.of(new JavaInsideBlockEffects.RecordedMove(
                context.getStart(), lifted.state().position(), lifted.delta()));
    }
}
