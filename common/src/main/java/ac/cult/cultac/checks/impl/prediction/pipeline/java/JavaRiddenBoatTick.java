package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.FluidTags;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionCarry;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.BoatTransform;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.ClientFluidTagOrder;
import ac.cult.cultac.utils.nmsutil.FluidCurrent;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.List;

/**
 * AbstractBoat#tick of the boat the local player drives while that boat rides another entity. ClientLevel#tickPassenger
 * runs its Entity#rideTick after its vehicle's tick: rideTick zeroes deltaMovement, and the boat still floats, steers
 * and moves, as Entity#isLocalInstanceAuthoritative reads only its controlling passenger, the local player. The
 * vehicle's positionRider then replaces only the position. LocalPlayer#sendChanges sends MoveVehicle only for
 * Entity#getRootVehicle, so no packet reports this tick; the delta, status and steering it leaves start the boat's first
 * tick as a root once its vehicle ejects it.
 */
public final class JavaRiddenBoatTick {
    private JavaRiddenBoatTick() {}

    /** Runs in ClientLevel's recursive entity traversal, immediately before positionRider seats the boat. */
    public static void tick(CultPlayer player, PacketEntity boat) {
        var entities = player.compensatedEntities;
        long phase = entities.passTickedThroughOrder();
        entities.setPassTickedThroughOrder(entities.clientTickOrder(boat));
        try {
            tickBoat(player, boat);
        } finally {
            entities.setPassTickedThroughOrder(phase);
        }
    }

    private static void tickBoat(CultPlayer player, PacketEntity boat) {
        var processor = player.checkManager.getSimulationProcessor();
        var priors = priors(player, boat, processor.getCurrentPredictionCarry());
        player.boatData.tick(player);
        var tickStart = BoatTransform.BoatTickState.capture(player);
        var beforePass = ac.cult.cultac.utils.latency.ClientBlockEntityPass.EMPTY.after(
                player.compensatedEntities.shulkerPeekMoves(true));
        Vec3 seat = BoundingBoxSize.getRidingOffsetFromVehicle(boat.getRiding(), boat, player);
        float yaw = boat.clientPhysicalYaw;
        var starts = new ArrayList<JavaPredictionCarry.State>(priors.size());
        for (var carried : priors) {
            if (carried.movementPatch() != null || player.getClientVersion() != ClientVersion.V_1_21_9)
                starts.add(carried);
            else for (var patch : JavaMovementPatch.PROTOCOL_773) starts.add(carried.withPatch(patch));
        }
        var states = new ArrayList<JavaPredictionCarry.State>(starts.size());
        BoatTransform.BoatTickState firstTick = null;
        SimpleCollisionBox firstFluidBox = null;
        float postYaw = yaw;
        JavaPassState firstMove = null;
        for (var prior : starts) {
            try (var ignored = ClientFluidTagOrder.use(player, prior.fluidTagOrder())) {
                tickStart.apply(player);
                player.boatData.landFriction = tickStart.landFriction();
                Vec3 start = prior.pistonStart() == null
                        ? position(boat)
                        : prior.pistonStart().position();
                var context = context(player, boat, start, yaw, prior);
                JavaPassState before = JavaRiddenBoatPass.start(player, boat, prior, start);
                var early = JavaBlockEntityPass.run(player, context, beforePass, before, Pose.STANDING, false, start);
                before = early.state();
                if (early.boatLastYd() != null) player.boatData.lastYd = early.boatLastYd();
                start = before.position();
                context = context(player, boat, start, yaw, prior);
                SimpleCollisionBox startBox =
                        GetBoundingBox.getPacketEntityBoundingBox(player, start.x, start.y, start.z, boat);
                player.boatData.sampleCurrentPosition(player, startBox);
                // rideTick zeroes the delta AFTER earlier shulker ticks, then baseTick, floatBoat and controlBoat run.
                var boatTick = BoatTransform.unreportedTick(player, context, yaw);
                Vec3 from = start.add(0, boatTick.snapY(), 0);
                Vec3 scanFrom = player.getClientVersion().isOlderThan(ClientVersion.V_1_21_5) ? start : from;
                before = new JavaPassState(
                        from,
                        boatTick.velocity(),
                        before.onGround(),
                        before.support(),
                        before.stuckSpeed(),
                        before.fallDistance(),
                        before.pistonDeltas(),
                        false,
                        touches(player, startBox, FluidTags.WATER),
                        touches(player, startBox, FluidTags.LAVA));
                var env = new JavaBlockEntityMove.Environment(
                        player,
                        context,
                        Pose.STANDING,
                        1,
                        (boat.actionSharedFlags & 2) != 0,
                        start,
                        player.compensatedWorld.endedTickGameTime(),
                        null,
                        prior.movementPatch());
                var moved = JavaBlockEntityMove.move(env, before, JavaBlockEntityMove.Mover.SELF, boatTick.velocity());
                JavaPassState after = moved.state();
                boolean slime = after.onGround()
                        && JavaInsideBlockEffects.carriesStepOn(player, context)
                        && (boat.actionSharedFlags & 2) == 0
                        && BlockIds.is(
                                BlockProperties.getOnPos(player, after.support(), after.position()),
                                BlockIds.SLIME_BLOCK);
                var scan = JavaInsideBlockEffects.scan(player, context, scanFrom, after.position(), moved.delta());
                var effects = JavaInsideBlockEffects.resolveAfterMove(
                        player, context, scan, after.fallDistance(), after.velocity(), after.onGround(), slime);
                var state = new JavaPredictionCarry.State(
                        effects.velocity(),
                        effects.fallDistance(),
                        effects.stuckSpeed() != null ? effects.stuckSpeed() : after.stuckSpeed(),
                        prior.movementPatch(),
                        new JavaPredictionCarry.PistonStart(
                                seat, after.onGround(), after.support(), after.pistonDeltas()),
                        after.powderContact() || effects.powderContact(),
                        0,
                        prior.fluidTagOrder());
                if (!states.contains(state)) states.add(state);
                if (firstTick == null) {
                    if (moved.boatLastYd() != null) player.boatData.lastYd = moved.boatLastYd();
                    firstTick = BoatTransform.BoatTickState.capture(player);
                    firstFluidBox = startBox;
                    postYaw = boatTick.yaw();
                    firstMove = new JavaPassState(
                            seat,
                            state.velocity(),
                            after.onGround(),
                            after.support(),
                            state.stuckSpeed(),
                            state.fallDistance(),
                            after.pistonDeltas(),
                            state.powderContact(),
                            before.inWater(),
                            before.inLava());
                }
            }
        }
        firstTick.apply(player);
        player.boatData.landFriction = firstTick.landFriction();
        player.boatData.fluidInteractionBox = firstFluidBox;
        boat.setClientPhysicalRotation(postYaw, boat.clientPhysicalPitch);
        boat.setPassengerPosition(
                player, GetBoundingBox.getPacketEntityBoundingBox(player, seat.x, seat.y, seat.z, boat));
        boat.blockEntityState = firstMove;
        JavaRiddenBoatPass.commit(player, boat, states);
    }

    /** The states the boat's last tick left; Entity#rideTick zeroes each one's delta before this tick. */
    static List<JavaPredictionCarry.State> priors(CultPlayer player, PacketEntity boat, PredictionCarry carry) {
        if (carry instanceof JavaPredictionCarry java
                && java.actor() == boat
                && !java.states().isEmpty()) return java.states();
        double fallDistance =
                carry instanceof JavaPredictionCarry java && java.actor() == boat ? java.fallDistance() : 0;
        return List.of(new JavaPredictionCarry.State(boat.deltaMovement, fallDistance, null, null, null, false));
    }

    static SimulationContext context(
            CultPlayer player, PacketEntity boat, Vec3 start, float yaw, JavaPredictionCarry.State state) {
        var anchor = state.pistonStart();
        var context = new SimulationContext(
                start,
                start,
                Vec3.ZERO,
                player.getClientVersion(),
                player.trigHandler,
                player.compensatedEntities,
                boat,
                yaw,
                yaw,
                boat.clientPhysicalPitch,
                boat.clientPhysicalPitch,
                anchor == null ? boat.onGround : anchor.onGround(),
                false,
                false,
                false,
                null,
                0,
                0,
                DesyncStatus.FALSE,
                0,
                0,
                0,
                false,
                Vec3.ZERO,
                false,
                Vec3.ZERO,
                anchor == null ? lastSupport(player) : anchor.support(),
                Pose.STANDING,
                1);
        // AbstractBoat#controlBoat reads the keys stored on the boat itself.
        context.setBoatInputs(boat.boatInput);
        context.setMovementPatch(state.movementPatch());
        context.setProfileCarry(new JavaPredictionCarry(boat, state.fallDistance(), List.of(state)));
        return context;
    }

    static Vec3 position(PacketEntity boat) {
        if (boat.clientPhysicalPosition != null && boat.clientPhysicalPositionExact) return boat.clientPhysicalPosition;
        SimpleCollisionBox box = boat.getPossibleCollisionBoxes();
        return new Vec3((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2);
    }

    static MainSupportingBlockData lastSupport(CultPlayer player) {
        var last = player.checkManager.getSimulationProcessor().getLastPrediction();
        return last == null
                        || last.getSimulationContext() == null
                        || last.getSimulationContext().getWorldData() == null
                ? new MainSupportingBlockData(null, false)
                : last.getSimulationContext().getWorldData().getMainSupportingBlockPos();
    }

    /** Entity#updateFluidHeightAndDoFluidPushing's touching result for {@code tag} on {@code box}. */
    private static boolean touches(CultPlayer player, SimpleCollisionBox box, FluidTags tag) {
        var current = new FluidCurrent();
        current.compute(player, box, false, 0, 0, tag, tag == FluidTags.WATER ? .014D : .0023333333333333335D);
        return current.touching;
    }
}
