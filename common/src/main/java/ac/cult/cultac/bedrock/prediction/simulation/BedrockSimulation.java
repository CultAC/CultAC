package ac.cult.cultac.bedrock.prediction.simulation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockSolidActorOrders;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockSnapshotResolver;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInput;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelOptions;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BedrockSimulation {
    public static final double DEFAULT_MAX_AUTO_STEP = 0.5625D;

    private BedrockSimulation() {}

    /** Runs one movement tick with bounded controls. */
    public static Candidate movementTick(Input input, Vec3d control) {
        return movementTick(input, control, 0);
    }

    private static Candidate movementTick(Input input, Vec3d control, int solidActorOrder) {
        return movementTick(input, control, solidActorOrder, null);
    }

    private static Candidate movementTick(Input input, Vec3d control, int solidActorOrder, Vec3d nominalEnd) {
        if (control.y() != 0.0D
                || !Double.isFinite(control.x())
                || !Double.isFinite(control.z())
                || Math.abs(control.x()) > 1.0D
                || Math.abs(control.z()) > 1.0D) {
            throw new IllegalArgumentException("Invalid movement control");
        }
        if (!input.actorMovementTick()) return candidates(input).getFirst();
        var state = input.previousState();
        if (state.isHorse()) state = state.withHorse(state.horse().withForwardJump(control.z() > 0.0D));
        var options =
                BedrockTravelOptions.vanilla(input.canStep(), input.maxUpStep()).withTickStart(state.tickStart());
        if (solidActorOrder != 0) options = options.withSolidActorOrder(solidActorOrder, nominalEnd);
        var scaffolding = !state.isVehicle() && descendRequested(state, input)
                ? BedrockTravelInput.ScaffoldingVerticalBranch.DESCEND
                : BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE;
        var result = BedrockTravelEngine.INSTANCE.travel(
                new BedrockTravelInput(
                        state,
                        input.frame(),
                        input.intent(),
                        BedrockSnapshotResolver.forState(
                                snapshotForBranch(input.snapshot(), scaffolding), state, input.frame()),
                        scaffolding,
                        state.velocity(),
                        options,
                        control,
                        input.glideBoost()),
                input.mobJumpComponent());
        return new Candidate(result.movementResult(), result.mobJumpComponent());
    }

    public static List<Candidate> candidates(Input input) {
        if (!input.actorMovementTick()) {
            return List.of(new Candidate(
                    BedrockMovementResultBuilder.withoutActorMovementTick(
                            input.previousState(), input.frame(), input.snapshot(), input.canStep(), input.maxUpStep()),
                    input.mobJumpComponent()));
        }
        if (input.control() != null || input.previousState().isBoat()) {
            Vec3d control = input.control() == null ? Vec3d.ZERO : input.control();
            Candidate first = movementTick(input, control, 0);
            int collected = first.movementResult().solidActorOrder();
            if (collected == 0) return List.of(first);
            ArrayList<Candidate> orders = new ArrayList<>(first.movementResult().solidActorOrders());
            orders.add(first);
            Vec3d nominalEnd = first.movementResult()
                    .predictedPosition()
                    .subtract(input.previousState().physicalFeetPosition());
            for (int index = 1; index < first.movementResult().solidActorOrders(); index++) {
                Candidate candidate =
                        movementTick(input, control, BedrockSolidActorOrders.order(collected, index), nominalEnd);
                if (realizesPositionState(candidate.movementResult())) orders.add(candidate);
            }
            return List.copyOf(orders);
        }
        ArrayList<Candidate> candidates = new ArrayList<>();
        if (input.previousState().isHorse()) {
            addHorseCandidates(candidates, input);
            return List.copyOf(candidates);
        }
        addBranchCandidates(
                candidates,
                input,
                descendRequested(input.previousState(), input)
                        ? BedrockTravelInput.ScaffoldingVerticalBranch.DESCEND
                        : BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE);
        return List.copyOf(candidates);
    }

    /**
     * Sneak falls through scaffolding or powder snow whenever the row below the feet holds it,
     * whatever else is held. Nothing else decides the branch, so there is one candidate.
     */
    private static boolean descendRequested(BedrockMovementState state, Input input) {
        return input.intent().vertical().descendInput()
                && state.climbableContact().descendAllowed();
    }

    private static void addHorseCandidates(List<Candidate> candidates, Input input) {
        var horse = input.previousState().horse();
        int branches = horse.canLaunch(input.previousState().collisionFlags().onGround()) ? 2 : 1;
        for (int branch = 0; branch < branches; branch++) {
            var state = input.previousState().withHorse(horse.withForwardJump(branch == 1));
            var options = BedrockTravelOptions.vanilla(input.canStep(), input.maxUpStep())
                    .withTickStart(state.tickStart());
            int collected = 0;
            Vec3d nominalEnd = null;
            for (int index = 0; index < BedrockSolidActorOrders.variants(collected); index++) {
                var result = travel(
                        state,
                        input.frame(),
                        input.intent(),
                        input.snapshot(),
                        BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
                        index == 0
                                ? options
                                : options.withSolidActorOrder(
                                        BedrockSolidActorOrders.order(collected, index), nominalEnd),
                        input.mobJumpComponent());
                if (index == 0) {
                    collected = result.movementResult().solidActorOrder();
                    nominalEnd = result.movementResult().predictedPosition().subtract(state.physicalFeetPosition());
                }
                if (realizesPositionState(result.movementResult()))
                    candidates.add(new Candidate(result.movementResult(), result.mobJumpComponent()));
            }
        }
    }

    static BedrockMovementResult move(
            BedrockMovementState state,
            BedrockInputFrame frame,
            BedrockMovementContext context,
            BedrockTravelInput.ScaffoldingVerticalBranch scaffolding,
            boolean canStep,
            double maxUpStep) {
        BedrockWorldSnapshot snapshot =
                BedrockSnapshotResolver.forState(BedrockWorldSnapshot.fromContext(context), state, frame);
        return travel(
                        state,
                        frame,
                        frame.intent(),
                        snapshot,
                        scaffolding,
                        BedrockTravelOptions.vanilla(canStep, maxUpStep),
                        BedrockMobJumpComponentState.DEFAULT)
                .movementResult();
    }

    private static void addBranchCandidates(
            List<Candidate> candidates, Input input, BedrockTravelInput.ScaffoldingVerticalBranch scaffolding) {
        ArrayList<Candidate> branch = new ArrayList<>();
        BedrockTravelOptions options = BedrockTravelOptions.vanilla(input.canStep(), input.maxUpStep())
                .withTickStart(input.previousState().tickStart());
        for (BedrockTravelOptions.SprintTravelSpeedMode speed : sprintSpeedModes()) {
            for (BedrockTravelOptions.SprintJumpImpulseMode jump : sprintJumpModes(input)) {
                BedrockTravelOptions mode =
                        options.withSprintTravelSpeedMode(speed).withSprintJumpImpulseMode(jump);
                int collected = 0;
                Vec3d nominalEnd = null;
                for (int index = 0; index < BedrockSolidActorOrders.variants(collected); index++) {
                    BedrockTravelResult result = travel(
                            input.previousState(),
                            input.frame(),
                            input.intent(),
                            snapshotForBranch(input.snapshot(), scaffolding),
                            scaffolding,
                            index == 0
                                    ? mode
                                    : mode.withSolidActorOrder(
                                            BedrockSolidActorOrders.order(collected, index), nominalEnd),
                            input.mobJumpComponent());
                    if (index == 0) {
                        collected = result.movementResult().solidActorOrder();
                        nominalEnd = result.movementResult()
                                .predictedPosition()
                                .subtract(input.previousState().physicalFeetPosition());
                    }
                    Candidate candidate = new Candidate(result.movementResult(), result.mobJumpComponent());
                    if (realizesPositionState(result.movementResult()) && !branch.contains(candidate)) {
                        branch.add(candidate);
                    }
                }
            }
        }
        candidates.addAll(branch);
    }

    private static boolean realizesPositionState(BedrockMovementResult result) {
        var flags = result.predictedState().collisionFlags();
        int contacts =
                (flags.xCollision() ? 1 : 0) | (flags.verticalCollision() ? 2 : 0) | (flags.zCollision() ? 4 : 0);
        return BedrockSolidActorOrders.realizesPositionState(result.solidActorOrder(), contacts);
    }

    private static BedrockWorldSnapshot snapshotForBranch(
            BedrockWorldSnapshot snapshot, BedrockTravelInput.ScaffoldingVerticalBranch branch) {
        if (branch == BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE) {
            return snapshot;
        }
        List<PlacedBlockCollision> blocks = snapshot.blockCollisionWorld().blocks().stream()
                .map(block -> descendThroughCollision(block)
                        ? new PlacedBlockCollision(
                                block.position(),
                                block.javaState(),
                                block.javaStateProperties(),
                                block.bedrockIdentifier(),
                                block.bedrockState(),
                                List.of(),
                                block.contactBoxes(),
                                block.insideBlockContactBoxes(),
                                block.contactBehaviors())
                        : block)
                .toList();
        BlockCollisionWorld collisionWorld =
                new BlockCollisionWorld(blocks, snapshot.blockCollisionWorld().coordinateFrame());
        return snapshot.withBlockCollisionWorld(collisionWorld);
    }

    private static boolean descendThroughCollision(PlacedBlockCollision block) {

        return block.hasContactBehavior(PlacedBlockCollision.BlockContactBehavior.SCAFFOLDING)
                || block.hasContactBehavior(PlacedBlockCollision.BlockContactBehavior.POWDER_SNOW);
    }

    private static BedrockTravelResult travel(
            BedrockMovementState state,
            BedrockInputFrame frame,
            BedrockInputIntent intent,
            BedrockWorldSnapshot snapshot,
            BedrockTravelInput.ScaffoldingVerticalBranch scaffolding,
            BedrockTravelOptions options,
            BedrockMobJumpComponentState mobJumpComponent) {
        return BedrockTravelEngine.INSTANCE.travel(
                new BedrockTravelInput(state, frame, intent, snapshot, scaffolding, state.velocity(), options),
                mobJumpComponent);
    }

    private static List<BedrockTravelOptions.SprintTravelSpeedMode> sprintSpeedModes() {
        return List.of(BedrockTravelOptions.SprintTravelSpeedMode.ORDERED_ACTOR_FLAG);
    }

    private static List<BedrockTravelOptions.SprintJumpImpulseMode> sprintJumpModes(Input input) {
        if (!sprintJumpAmbiguous(input)) {
            return List.of(BedrockTravelOptions.SprintJumpImpulseMode.ORDERED_ACTOR_FLAG);
        }
        return List.of(
                BedrockTravelOptions.SprintJumpImpulseMode.ORDERED_ACTOR_FLAG,
                BedrockTravelOptions.SprintJumpImpulseMode.FORCE_NOT_SPRINTING,
                BedrockTravelOptions.SprintJumpImpulseMode.FORCE_SPRINTING);
    }

    private static boolean sprintJumpAmbiguous(Input input) {
        var intent = input.intent();
        var previousSprint = input.previousState().inputFrame().intent().sprint();

        return intent.jump().start()
                && (intent.sprint().sprinting()
                        || input.previousState().sprinting()
                        || previousSprint.sprinting()
                        || intent.sprint().start()
                        || intent.sprint().stop()
                        || previousSprint.stop());
    }

    public record Input(
            BedrockMovementState previousState,
            BedrockInputFrame frame,
            BedrockInputIntent intent,
            BedrockWorldSnapshot snapshot,
            boolean canStep,
            double maxUpStep,
            BedrockMobJumpComponentState mobJumpComponent,
            boolean actorMovementTick,
            boolean acceptedTeleport,
            Vec3d control,
            boolean glideBoost) {
        public Input(
                BedrockMovementState previousState,
                BedrockInputFrame frame,
                BedrockInputIntent intent,
                BedrockWorldSnapshot snapshot,
                boolean canStep,
                double maxUpStep,
                BedrockMobJumpComponentState mobJumpComponent,
                boolean actorMovementTick,
                boolean acceptedTeleport,
                Vec3d control) {
            this(
                    previousState,
                    frame,
                    intent,
                    snapshot,
                    canStep,
                    maxUpStep,
                    mobJumpComponent,
                    actorMovementTick,
                    acceptedTeleport,
                    control,
                    false);
        }

        public Input(
                BedrockMovementState previousState,
                BedrockInputFrame frame,
                BedrockInputIntent intent,
                BedrockWorldSnapshot snapshot,
                boolean canStep,
                double maxUpStep,
                BedrockMobJumpComponentState mobJumpComponent,
                boolean actorMovementTick,
                boolean acceptedTeleport) {
            this(
                    previousState,
                    frame,
                    intent,
                    snapshot,
                    canStep,
                    maxUpStep,
                    mobJumpComponent,
                    actorMovementTick,
                    acceptedTeleport,
                    null);
        }

        public Input(
                BedrockMovementState previousState,
                BedrockInputFrame frame,
                BedrockInputIntent intent,
                BedrockWorldSnapshot snapshot,
                boolean canStep,
                double maxUpStep,
                BedrockMobJumpComponentState mobJumpComponent) {
            this(previousState, frame, intent, snapshot, canStep, maxUpStep, mobJumpComponent, true);
        }

        public Input(
                BedrockMovementState previousState,
                BedrockInputFrame frame,
                BedrockInputIntent intent,
                BedrockWorldSnapshot snapshot,
                boolean canStep,
                double maxUpStep,
                BedrockMobJumpComponentState mobJumpComponent,
                boolean actorMovementTick) {
            this(
                    previousState,
                    frame,
                    intent,
                    snapshot,
                    canStep,
                    maxUpStep,
                    mobJumpComponent,
                    actorMovementTick,
                    false);
        }

        public Input {
            Objects.requireNonNull(previousState, "previousState");
            if (acceptedTeleport) previousState = previousState.withTeleportPending();
            Objects.requireNonNull(frame, "frame");
            intent = intent == null ? frame.intent() : intent;
            Objects.requireNonNull(snapshot, "snapshot");
            mobJumpComponent = mobJumpComponent == null ? BedrockMobJumpComponentState.DEFAULT : mobJumpComponent;
            if (!Double.isFinite(maxUpStep) || maxUpStep < 0.0D) {
                maxUpStep = 0.0D;
            }
        }
    }

    public record Candidate(BedrockMovementResult movementResult, BedrockMobJumpComponentState mobJumpComponent) {
        public Candidate {
            Objects.requireNonNull(movementResult, "movementResult");
            mobJumpComponent = mobJumpComponent == null ? BedrockMobJumpComponentState.DEFAULT : mobJumpComponent;
        }
    }
}
