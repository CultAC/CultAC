package ac.cult.cultac.bedrock.prediction.simulation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockStepProjectionResolver;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockSnapshotResolver;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInput;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelOptions;
import ac.cult.cultac.bedrock.prediction.simulation.reconciliation.BedrockNextTickStateDeriver;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class BedrockSimulation {
    public static final double DEFAULT_MAX_AUTO_STEP = 0.5625D;

    private BedrockSimulation() {
    }

    public static List<Candidate> candidates(Input input) {
        if (!input.actorMovementTick()) {
            return List.of(new Candidate(
                BedrockMovementResultBuilder.withoutActorMovementTick(
                    input.previousState(), input.frame(), input.snapshot(),
                    input.canStep(), input.maxUpStep()),
                input.mobJumpComponent()));
        }
        ArrayList<Candidate> candidates = new ArrayList<>();
        if (input.intent().vertical().descendInput()
            && input.previousState().climbableContact().descendAllowed()) {
            addBranchCandidates(candidates, input, BedrockTravelInput.ScaffoldingVerticalBranch.DESCEND);
            addBranchCandidates(candidates, input, BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE);
        } else {
            addBranchCandidates(candidates, input, BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE);
        }
        return List.copyOf(candidates);
    }

    public static List<NextState> deriveNextStates(
        BedrockMovementResult result,
        List<BedrockMovementState> sourceStates,
        Vec3d acceptedDiff
    ) {
        return deriveNextStates(result, sourceStates, acceptedDiff, false, false);
    }

    public static List<NextState> deriveNextStates(
        BedrockMovementResult result,
        List<BedrockMovementState> sourceStates,
        Vec3d acceptedDiff,
        boolean selectedXCollision,
        boolean selectedZCollision
    ) {
        return deriveNextStates(
            result, sourceStates, acceptedDiff, selectedXCollision, selectedZCollision, false);
    }

    public static List<NextState> deriveNextStates(
        BedrockMovementResult result,
        List<BedrockMovementState> sourceStates,
        Vec3d acceptedDiff,
        boolean selectedXCollision,
        boolean selectedZCollision,
        boolean canStep
    ) {
        return BedrockNextTickStateDeriver.withAcceptedDiff(
            result, sourceStates, acceptedDiff, selectedXCollision, selectedZCollision, canStep).stream()
            .map(state -> new NextState(state.state(), state.lineageState()))
            .toList();
    }

    public static Optional<Vec3d> stepCandidate(BedrockMovementResult result) {
        return BedrockStepProjectionResolver.candidatePosition(result);
    }

    public static Optional<Vec3d> stepCandidate(BedrockMovementResult result, Vec3d requestedDelta) {
        return BedrockStepProjectionResolver.candidatePosition(result, requestedDelta);
    }

    static BedrockMovementResult move(
        BedrockMovementState state,
        BedrockInputFrame frame,
        BedrockMovementContext context,
        BedrockTravelInput.ScaffoldingVerticalBranch scaffolding,
        boolean canStep,
        double maxUpStep
    ) {
        BedrockWorldSnapshot snapshot = BedrockSnapshotResolver.forState(
            BedrockWorldSnapshot.fromContext(context), state, frame
        );
        return travel(state, frame, frame.intent(), snapshot, scaffolding,
            BedrockTravelOptions.vanilla(canStep, maxUpStep), BedrockMobJumpComponentState.DEFAULT)
            .movementResult();
    }

    private static void addBranchCandidates(
        List<Candidate> candidates,
        Input input,
        BedrockTravelInput.ScaffoldingVerticalBranch scaffolding
    ) {
        ArrayList<Candidate> branch = new ArrayList<>();
        BedrockTravelOptions options = BedrockTravelOptions.vanilla(input.canStep(), input.maxUpStep())
            .withTravelActive(!input.acceptedTeleport());
        for (BedrockTravelOptions.SprintTravelSpeedMode speed : sprintSpeedModes()) {
            for (BedrockTravelOptions.SprintJumpImpulseMode jump : sprintJumpModes(input)) {
                BedrockTravelResult result = travel(
                    input.previousState(), input.frame(), input.intent(), snapshotForBranch(input.snapshot(), scaffolding), scaffolding,
                    options.withSprintTravelSpeedMode(speed).withSprintJumpImpulseMode(jump),
                    input.mobJumpComponent()
                );
                Candidate candidate = new Candidate(result.movementResult(), result.mobJumpComponent());
                if (!branch.contains(candidate)) {
                    branch.add(candidate);
                }
            }
        }
        candidates.addAll(branch);
    }

    private static BedrockWorldSnapshot snapshotForBranch(
        BedrockWorldSnapshot snapshot,
        BedrockTravelInput.ScaffoldingVerticalBranch branch
    ) {
        if (branch == BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE) {
            return snapshot;
        }
        List<PlacedBlockCollision> blocks = snapshot.blockCollisionWorld().blocks().stream()
            .map(block -> descendThroughCollision(block)
                ? new PlacedBlockCollision(
                    block.position(), block.javaState(), block.javaStateProperties(),
                    block.bedrockIdentifier(), block.bedrockState(), List.of(),
                    block.contactBoxes(), block.insideBlockContactBoxes(), block.contactBehaviors())
                : block)
            .toList();
        BlockCollisionWorld collisionWorld = new BlockCollisionWorld(blocks);
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
        BedrockMobJumpComponentState mobJumpComponent
    ) {
        return BedrockTravelEngine.INSTANCE.travel(new BedrockTravelInput(
            state, frame, intent, snapshot, scaffolding, state.velocity(), options
        ), mobJumpComponent);
    }

    private static List<BedrockTravelOptions.SprintTravelSpeedMode> sprintSpeedModes() {
        return List.of(
            BedrockTravelOptions.SprintTravelSpeedMode.FORCE_NOT_SPRINTING,
            BedrockTravelOptions.SprintTravelSpeedMode.FORCE_SPRINTING
        );
    }

    private static List<BedrockTravelOptions.SprintJumpImpulseMode> sprintJumpModes(Input input) {
        if (!sprintJumpAmbiguous(input)) {
            return List.of(BedrockTravelOptions.SprintJumpImpulseMode.ORDERED_ACTOR_FLAG);
        }
        return List.of(
            BedrockTravelOptions.SprintJumpImpulseMode.ORDERED_ACTOR_FLAG,
            BedrockTravelOptions.SprintJumpImpulseMode.FORCE_NOT_SPRINTING,
            BedrockTravelOptions.SprintJumpImpulseMode.FORCE_SPRINTING
        );
    }

    private static boolean sprintJumpAmbiguous(Input input) {
        var intent = input.intent();
        var previousSprint = input.previousState().inputFrame().intent().sprint();

        return intent.jump().start() && (intent.sprint().sprinting()
            || input.previousState().sprinting() || previousSprint.sprinting()
            || intent.sprint().start() || intent.sprint().stop() || previousSprint.stop());
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
        boolean acceptedTeleport
    ) {
        public Input(
            BedrockMovementState previousState,
            BedrockInputFrame frame,
            BedrockInputIntent intent,
            BedrockWorldSnapshot snapshot,
            boolean canStep,
            double maxUpStep,
            BedrockMobJumpComponentState mobJumpComponent
        ) {
            this(previousState, frame, intent, snapshot, canStep, maxUpStep, mobJumpComponent, true);
        }
        public Input(BedrockMovementState previousState, BedrockInputFrame frame, BedrockInputIntent intent,
                     BedrockWorldSnapshot snapshot, boolean canStep, double maxUpStep,
                     BedrockMobJumpComponentState mobJumpComponent, boolean actorMovementTick) {
            this(previousState, frame, intent, snapshot, canStep, maxUpStep, mobJumpComponent, actorMovementTick, false);
        }

        public Input {
            Objects.requireNonNull(previousState, "previousState");
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

    public record NextState(BedrockMovementState state, BedrockMovementState lineageState) {
        public NextState {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(lineageState, "lineageState");
        }
    }
}
