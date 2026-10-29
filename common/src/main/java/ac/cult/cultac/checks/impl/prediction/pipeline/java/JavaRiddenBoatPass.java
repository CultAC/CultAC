package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidTagOrder;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.List;

/** Moves after a nested boat's positionRider: later shulker entities, then Level#tickBlockEntities. */
public final class JavaRiddenBoatPass {
    private JavaRiddenBoatPass() {}

    public static void afterTick(CultPlayer player, PacketEntity boat) {
        var pass =
                player.compensatedWorld.lastBlockEntityPass().after(player.compensatedEntities.shulkerPeekMoves(false));
        if (pass.isEmpty()) return;
        var processor = player.checkManager.getSimulationProcessor();
        var priors = JavaRiddenBoatTick.priors(player, boat, processor.getCurrentPredictionCarry());
        var states = new ArrayList<JavaPredictionCarry.State>(priors.size());
        JavaPassState first = null;
        Double lastYd = null;
        for (var prior : priors) {
            try (var ignored = ClientFluidTagOrder.use(player, prior.fluidTagOrder())) {
                Vec3 position = prior.pistonStart() == null
                        ? JavaRiddenBoatTick.position(boat)
                        : prior.pistonStart().position();
                var context = JavaRiddenBoatTick.context(player, boat, position, boat.clientPhysicalYaw, prior);
                var replay = JavaBlockEntityPass.run(
                        player, context, pass, start(player, boat, prior, position), Pose.STANDING, false, position);
                var end = replay.state();
                var state = new JavaPredictionCarry.State(
                        end.velocity(),
                        end.fallDistance(),
                        end.stuckSpeed(),
                        prior.movementPatch(),
                        new JavaPredictionCarry.PistonStart(
                                end.position(), end.onGround(), end.support(), end.pistonDeltas()),
                        end.powderContact(),
                        0,
                        prior.fluidTagOrder());
                if (!states.contains(state)) states.add(state);
                if (first == null) {
                    first = end;
                    lastYd = replay.boatLastYd();
                }
            }
        }
        Vec3 from = JavaRiddenBoatTick.position(boat), to = first.position();
        boat.applyBlockEntityMovement(
                player, GetBoundingBox.getPacketEntityBoundingBox(player, to.x, to.y, to.z, boat), to.subtract(from));
        boat.blockEntityState = first;
        if (lastYd != null) player.boatData.lastYd = lastYd;
        commit(player, boat, states);
    }

    static JavaPassState start(CultPlayer player, PacketEntity boat, JavaPredictionCarry.State state, Vec3 position) {
        var anchor = state.pistonStart();
        return new JavaPassState(
                position,
                state.velocity(),
                anchor == null ? boat.onGround : anchor.onGround(),
                anchor == null ? JavaRiddenBoatTick.lastSupport(player) : anchor.support(),
                state.stuckSpeed(),
                state.fallDistance(),
                anchor == null
                        ? boat.blockEntityState == null ? ClientPistonDeltas.NONE : boat.blockEntityState.pistonDeltas()
                        : anchor.pistonDeltas(),
                state.powderContact(),
                boat.blockEntityState != null && boat.blockEntityState.inWater(),
                boat.blockEntityState != null && boat.blockEntityState.inLava());
    }

    static void commit(CultPlayer player, PacketEntity boat, List<JavaPredictionCarry.State> states) {
        DesyncStatus ground = null;
        for (var state : states) {
            boolean onGround = state.pistonStart().onGround();
            ground = ground == null ? DesyncStatus.fromBoolean(onGround) : ground.addBoolean(onGround);
        }
        boat.onGround = states.getFirst().pistonStart().onGround();
        boat.deltaMovement = states.getFirst().velocity();
        player.checkManager
                .getSimulationProcessor()
                .carryUnreportedVehicleTick(
                        new JavaPredictionCarry(boat, states.getFirst().fallDistance(), states), ground);
    }
}
