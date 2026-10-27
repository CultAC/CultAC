package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionCarry;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementPatch;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassengerTravel;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/** Complete local-passenger states after travel, positionRider and the later entity/block-entity ticks. */
public final class PassengerRideTick {
    private record State(
            JavaPassState move,
            int jumpDelay,
            JavaMovementPatch patch,
            ClientFluidTagOrder order,
            ac.cult.cultac.utils.enums.Pose pose) {}

    private List<State> states = List.of();
    private int onFootJumpDelay;
    private PredictionResult observedPrediction;
    private int recordedTick = Integer.MIN_VALUE;
    private boolean inFluid;
    private boolean useSeats;
    private Vec3 movementInput = Vec3.ZERO;
    private boolean jumping;
    private ac.cult.cultac.utils.data.packetentity.PacketEntitySelf observedSelf;

    public void begin(CultPlayer player, PredictionCarry carry, DesyncStatus onGround) {
        if (observedSelf != player.compensatedEntities.getSelf()) {
            onFootJumpDelay = 0;
            movementInput = Vec3.ZERO;
            jumping = false;
            observedSelf = player.compensatedEntities.getSelf();
        }
        var initial = new ArrayList<State>();
        Vec3 start = new Vec3(player.x, player.y, player.z);
        var last = player.checkManager.getSimulationProcessor().getLastPrediction();
        var support = last == null ? new MainSupportingBlockData(null, false)
                : last.getSimulationContext().getWorldData().getMainSupportingBlockPos();
        if (carry instanceof JavaPredictionCarry java && java.actor() == player.compensatedEntities.getSelf()) {
            for (var state : java.states()) {
                var anchor = state.pistonStart();
                initial.add(new State(new JavaPassState(start, Vec3.ZERO,
                        anchor == null ? onGround.determinePessimistically() : anchor.onGround(),
                        anchor == null ? support : anchor.support(), state.stuckSpeed(), state.fallDistance(),
                        anchor == null ? ClientPistonDeltas.NONE : anchor.pistonDeltas(), state.powderContact(),
                        false, false, state.playerEyeFluidContact()), onFootJumpDelay,
                        state.movementPatch(), state.fluidTagOrder(), player.pose));
            }
        }
        if (initial.isEmpty()) initial.add(new State(new JavaPassState(start, Vec3.ZERO,
                onGround.determinePessimistically(), support, null, 0, ClientPistonDeltas.NONE),
                onFootJumpDelay, null, null, player.pose));
        states = initial.stream().map(s -> new State(s.move(), s.jumpDelay(), s.patch(), s.order(),
                ac.cult.cultac.utils.enums.Pose.STANDING)).toList();
        useSeats = false;
    }

    /** Called once at each tick end; repeated notifications for the same boundary are idempotent. */
    public void offVehicle(CultPlayer player, PredictionResult result) {
        if (observedSelf != player.compensatedEntities.getSelf()) {
            observedSelf = player.compensatedEntities.getSelf();
            onFootJumpDelay = 0;
            movementInput = Vec3.ZERO;
            jumping = false;
            observedPrediction = null;
            recordedTick = Integer.MIN_VALUE;
        }
        int tick = player.packetStateData.acceptedClientTick;
        if (recordedTick == tick) return;
        recordedTick = tick;
        if (result != observedPrediction && result != null && result.getSimulationContext() != null)
            updateInput(player, result.getSimulationContext(), false);
        else if (!player.cameraEntity.isSelf()) decayInput();
        if (!jumping || player.isFlying || player.isInBed || !observedSelf.actionAlive) onFootJumpDelay = 0;
        else onFootJumpDelay = result != observedPrediction && result != null && result.getInitialStartingVel() != null
                        && result.getInitialStartingVel().isJump() ? 10 : Math.max(0, onFootJumpDelay - 1);
        observedPrediction = result;
    }

    public void record(CultPlayer player) {
        int tick = player.packetStateData.acceptedClientTick;
        if (recordedTick == tick) return;
        recordedTick = tick;
        if (states.isEmpty()) begin(player, null, DesyncStatus.fromBoolean(player.onGround));
        var riding = player.compensatedEntities.getSelf().getRiding();
        long phase = player.compensatedEntities.passTickedThroughOrder();
        // ClientLevel#tickNonPassenger ticks the riding root before recursing into this passenger's rideTick.
        player.compensatedEntities.setPassTickedThroughOrder(
                player.compensatedEntities.clientTickOrder(player.compensatedEntities.getSelf()));
        try {
            updateInput(player, context(player, states.getFirst().move().position(), states.getFirst()), true);
            var boxes = player.compensatedEntities.ridingPositionCandidates(riding);
            var next = new ArrayList<State>();
            JavaPassState frost = null;
            for (State prior : states) {
                List<ClientFluidTagOrder> orders = prior.order() != null ? List.of(prior.order())
                        : ClientFluidTagOrder.hasOverlap(player)
                                ? ClientFluidTagOrder.candidates(player) : java.util.Collections.singletonList(null);
                List<JavaMovementPatch> patches = player.getClientVersion() == ClientVersion.V_1_21_9
                        ? JavaMovementPatch.PROTOCOL_773 : java.util.Collections.singletonList(prior.patch());
                for (ClientFluidTagOrder order : orders) {
                    try (var ignored = ClientFluidTagOrder.use(player, order)) {
                        for (JavaMovementPatch patch : patches) {
                            var context = context(player, prior.move().position(), prior);
                            context.setMovementPatch(patch);
                            var before = JavaPassengerTravel.beforeTick(player, context, prior.move());
                            context = context(player, before.position(), prior);
                            context.setMovementPatch(patch);
                            for (var box : boxes) {
                                context.setRideTickVehicleBox(box);
                                var replay = JavaPassengerTravel.tick(
                                        player, context, before, prior.jumpDelay(), box, movementInput, jumping);
                                Vec3 end = replay.state().position();
                                var pose = player.canPlayerFitWithinBlocksAndEntitiesWhen(
                                                ac.cult.cultac.utils.enums.Pose.SWIMMING, end.x, end.y, end.z)
                                        ? player.updatedPose(false, false, end.x, end.y, end.z) : prior.pose();
                                Vec3 seat = BoundingBoxSize.getRidingOffsetFromVehicle(riding,
                                        player.compensatedEntities.getSelf(), player, box);
                                if (frost == null) frost = replay.state();
                                for (var moved : JavaPassengerTravel.afterTick(
                                        player, context, replay.state(), seat, pose, before.position())) {
                                    var state = new State(moved, replay.jumpDelay(), patch, order, pose);
                                    if (!next.contains(state)) next.add(state);
                                }
                            }
                        }
                    }
                }
            }
            if (frost != null)
                player.compensatedEntities.getSelf().updateClientFrost(frost.position(), frost.support());
            states = List.copyOf(next);
            inFluid = states.stream().anyMatch(s -> s.move().inWater() || s.move().inLava());
            useSeats = false;
            if (!states.isEmpty()) {
                player.refreshPlayerPose(states.getFirst().pose());
                var positions = new ArrayList<Vec3>();
                for (var state : states) if (!positions.contains(state.move().position())) positions.add(state.move().position());
                player.compensatedEntities.recordRideTickSeats(positions);
            }
        } finally {
            player.compensatedEntities.setPassTickedThroughOrder(phase);
        }
    }

    private void updateInput(CultPlayer player, SimulationContext context, boolean passenger) {
        if (player.cameraEntity.isSelf()) {
            float itemUse = passenger ? 1 : context.getItemUse().legalMultiplier();
            float slow = passenger ? 1 : ClientKeyInput.movingSlowlyFactors(player, context)[0];
            movementInput = ClientKeyInput.movementInput(context, player.packetStateData.knownInput, itemUse, slow);
            jumping = player.packetStateData.knownInput.jump();
        } else decayInput();
        if (!player.compensatedEntities.getSelf().actionAlive || player.isInBed) {
            movementInput = Vec3.ZERO;
            jumping = false;
        }
    }

    private void decayInput() {
        // LocalPlayer#applyInput falls back to LivingEntity#applyInput when another entity is the camera.
        movementInput = new Vec3((float) movementInput.x * .98F, 0, (float) movementInput.z * .98F);
    }

    private static SimulationContext context(CultPlayer player, Vec3 start, State state) {
        var move = state.move();
        float travelYaw = player.xRot;
        var vehicle = player.compensatedEntities.getSelf().getRiding();
        // 26.3 Minecraft#tick calls sendChanges after positionRider; older LocalPlayer#tick reports before it.
        if (vehicle != null && vehicle.isBoat() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)
                && !ClientFluidQueries.clientTags(player).entities()
                        .getOrDefault("minecraft:can_turn_in_boats", List.of()).contains("minecraft:player"))
            travelYaw -= player.boatData.deltaRotationOf(vehicle);
        var context = new SimulationContext(start, start, Vec3.ZERO, player.getClientVersion(), player.trigHandler,
                player.compensatedEntities, null, travelYaw, travelYaw, player.yRot, player.yRot,
                move.onGround(), false, false, false, null, 0, 0, DesyncStatus.fromBoolean(player.sprintState.flag()),
                0, 0, 0, false, Vec3.ZERO, false, Vec3.ZERO, move.support(), state.pose(), player.dimensionScale);
        context.setMovementEfficiency((float) player.compensatedEntities.getSelf().movementEfficiency);
        context.setMovementPatch(state.patch());
        context.setProfileCarry(new JavaPredictionCarry(player.compensatedEntities.getSelf(), move.fallDistance(),
                List.of(new JavaPredictionCarry.State(Vec3.ZERO, move.fallDistance(), move.stuckSpeed(), state.patch(),
                        null, move.powderContact(), move.playerEyeFluidContact(), state.order()))));
        return context;
    }

    public void setMotion(Vec3 velocity) {
        var next = new ArrayList<State>(states.size());
        for (var state : states)
            next.add(new State(state.move().withVelocity(velocity), state.jumpDelay(),
                    state.patch(), state.order(), state.pose()));
        states = List.copyOf(next);
    }

    /** A packet callback overwrites passenger motion without changing any ride-tick state. */
    public void setMotionY(double y) {
        states = states.stream().map(state -> new State(state.move().withVelocity(state.move().velocity().with(ac.cult.cultac.protocol.value.Direction.Axis.Y, y)),
                state.jumpDelay(), state.patch(), state.order(), state.pose())).toList();
    }

    public boolean inFluid() {
        return inFluid;
    }

    public List<Vec3> velocities() {
        useSeats = true;
        var result = new ArrayList<Vec3>();
        for (var state : states) if (!result.contains(state.move().velocity())) result.add(state.move().velocity());
        return result;
    }

    /** Carries exactly the stuck speed left by the callbacks, paired with that same travel's velocity and ground. */
    public JavaPredictionCarry carry(CultPlayer player, java.util.Collection<Vec3> currentVelocities) {
        var next = new ArrayList<JavaPredictionCarry.State>();
        Vec3 reported = new Vec3(player.x, player.y, player.z);
        for (var state : states) {
            var move = state.move();
            Vec3 seat = useSeats ? move.position() : reported;
            for (Vec3 velocity : useSeats ? List.of(move.velocity()) : currentVelocities) {
                var carry = new JavaPredictionCarry.State(velocity, move.fallDistance(), move.stuckSpeed(), state.patch(),
                        new JavaPredictionCarry.PistonStart(seat, move.onGround(), move.support(), move.pistonDeltas()),
                        move.powderContact(), move.playerEyeFluidContact(), state.order());
                if (!next.contains(carry)) next.add(carry);
            }
        }
        onFootJumpDelay = states.isEmpty() ? 0 : states.getFirst().jumpDelay();
        return new JavaPredictionCarry(player.compensatedEntities.getSelf(),
                next.isEmpty() ? 0 : next.getFirst().fallDistance(), next);
    }

    public DesyncStatus onGround() {
        if (states.isEmpty()) return DesyncStatus.FALSE;
        DesyncStatus ground = DesyncStatus.fromBoolean(states.getFirst().move().onGround());
        for (var state : states) ground = ground.addBoolean(state.move().onGround());
        return ground;
    }
}
