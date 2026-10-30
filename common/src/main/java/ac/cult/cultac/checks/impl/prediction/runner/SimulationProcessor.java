package ac.cult.cultac.checks.impl.prediction.runner;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionDebug;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.integration.BedrockMovementEngine;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState;
import ac.cult.cultac.bedrock.prediction.integration.BedrockSetbackJumpGuard;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVectorAdapter;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehiclePredictionState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.checks.impl.crash.CrashF;
import ac.cult.cultac.checks.impl.groundspoof.NoFallExecutor;
import ac.cult.cultac.checks.impl.prediction.AuthoredMovementFrame;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionCarry;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.PredictionResult.Flag;
import ac.cult.cultac.checks.impl.prediction.PredictionSetbackState;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.SimulationContext.HardCollidingEntityCollision;
import ac.cult.cultac.checks.impl.prediction.SimulationContext.SpinRebound;
import ac.cult.cultac.checks.impl.prediction.checks.PredCheckRunner;
import ac.cult.cultac.checks.impl.prediction.checks.StepCollisionMemo;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoFallPseudo;
import ac.cult.cultac.checks.impl.prediction.pipeline.MovementEngine;
import ac.cult.cultac.checks.impl.prediction.pipeline.MovementEngines;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaAiStepSprint;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementPatch;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassAfterTravel;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaRemoteBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaRiptideRelease;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaSpinAttack;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaWithheldTravel;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfile;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles;
import ac.cult.cultac.checks.impl.prediction.stage.MovementModifiers;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CarriedPush;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.checks.type.PositionListener;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.update.PositionUpdate;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.anticheat.update.RotationUpdate;
import ac.cult.cultac.utils.anticheat.update.VehiclePositionUpdate;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientFrost;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.ClientSprintState;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.HeadRotation;
import ac.cult.cultac.utils.data.LastInstance;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.data.StuckSpeedData;
import ac.cult.cultac.utils.data.TeleportAcceptData;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.data.packetentity.ClientMovementSpeed;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.lists.EvictingQueue;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.VectorUtils;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.ClientFluidTagOrder;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.FlightToggle;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.NextTickVelocityDeriver;
import ac.cult.cultac.utils.nmsutil.PassengerRideTick;
import ac.cult.cultac.utils.nmsutil.ReusableVelocitySet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Generated;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.jetbrains.annotations.Nullable;

public class SimulationProcessor extends CultProcessor implements PositionListener, ClientTickEndListener {
    static long computeTimeNS = 300000L;
    private static final AtomicInteger flags = new AtomicInteger(0);
    private static final double TELEPORT_GROUND_UNCERTAIN_DISTANCE_SQ = 4.0E-8;
    private static final double TRAVEL_DEBUG_DISTANCE_SQR = 1.0;
    private static final double TRAVEL_FLAG_DISTANCE_SQR = 400.0;
    // LevelLoadTracker stops waiting after CLIENT_WAIT_TIMEOUT_MS (30 s) and LEVEL_LOAD_CLOSE_DELAY_MS (0.5 s).
    private static final int MAX_LOADING_LEVEL_TICKS = 610;
    EvictingQueue<Long> lastMovementTime = new EvictingQueue(3);
    private boolean predictingStationaryTick;
    Set<Vec3> validPlayerStartingVels = new HashSet<>();
    PredictionResult lastPrediction = null;
    /** The committed tick's LivingEntity#checkAutoSpinAttack certainly hit, so the next tick starts from the rebound. */
    private boolean certainSpinHit;
    /**
     * The flags the last pass replayed between client ticks left for LocalPlayer#shouldStopRunSprinting, or null when
     * no such pass moved the local player since the last read.
     */
    private DesyncStatus hardCollisionAfterPass;

    int lastWorldEyeFluidContact = 0;
    PredCheckRunner flagger = new PredCheckRunner(this.player);
    DesyncStatus lastOnGround = DesyncStatus.FALSE;
    LastInstance lastFlying = new LastInstance(this.player);
    boolean lastSneaking = false;
    float lastXRot = 0.0F;
    float lastYRot = 0.0F;
    boolean lastGliding = false;
    boolean lastMovementWasSetback = false;
    CarriedPush carriedPush = CarriedPush.IDENTITY;
    SimpleCollisionBox pushesWhileLoadingLevel = null;
    int ticksWhileLoadingLevel = 0;
    PredictionCarry profileCarry;
    // Entity#pistonDeltas of the local player, which every block-entity pass the model commits updates.
    private ClientPistonDeltas pistonDeltas = ClientPistonDeltas.NONE;
    // Whether the prediction under way runs at a tick end, after the world advanced past that tick's block entities.
    private boolean predictingAtTickEnd;
    // PacketStateData#acceptedClientTick of the client tick whose entity moves before travel have been replayed.
    private int entityMovesTick = Integer.MIN_VALUE;
    private int debugUncertaintyTick = Integer.MIN_VALUE;
    private boolean debugUncertaintyOnTick;
    /** Whether the last client tick ended with the local player a passenger. */
    private boolean rideTicked;
    /** The on-foot candidates a mount temporarily replaces before the first actual passenger tick. */
    @Nullable
    private Set<Vec3> startingVelsBeforeRide;
    /** Whether a dismount after a passenger tick precedes the next prediction. */
    private boolean rideTicksSinceLastPrediction;

    private final PassengerRideTick passengerRideTick = new PassengerRideTick();

    private boolean bedrockSleepingStateObserved;
    private TeleportData bedrockTeleport;
    private PredictionSetbackState activeBedrockSetbackState;
    private final WorldStageBuilder worldStageBuilder = new WorldStageBuilder();
    private final CollisionModifier collisionProbe = new CollisionModifier();
    private final NextTickVelocityDeriver.Scratch velocityScratch = new NextTickVelocityDeriver.Scratch();
    private final StepCollisionMemo stepCollisionMemo = new StepCollisionMemo();

    public SimulationProcessor(CultPlayer playerData) {
        super(playerData);
    }

    public NextTickVelocityDeriver.Scratch velocityScratch() {
        return this.velocityScratch;
    }

    /** Entity#checkSupportingBlock after a move of {@code movement} that ended with the box {@code box}. */
    public ClientPistonDeltas getPistonDeltas() {
        return this.pistonDeltas;
    }

    /** Entity#pistonDeltas belong to the moved actor, including a locally controlled root. */
    public ClientPistonDeltas getPistonDeltas(PacketEntity actor) {
        return actor == this.player.compensatedEntities.getSelf()
                ? this.pistonDeltas
                : actor.blockEntityState == null ? ClientPistonDeltas.NONE : actor.blockEntityState.pistonDeltas();
    }

    /** LivingEntity#canGlide reads the ground state the last block-entity move left, before this tick's travel. */
    public boolean glideStartOnGround() {
        if (this.player.getClientVersion() == ClientVersion.V_1_21_9
                && this.profileCarry instanceof JavaPredictionCarry carry
                && carry.actor() == this.player.compensatedEntities.getSelf()
                && !carry.states().isEmpty()) {
            for (var state : carry.states()) {
                if (state.pistonStart() == null
                        ? !this.player.onGround
                        : !state.pistonStart().onGround()) return false;
            }
            return true;
        }
        return this.player.onGround;
    }

    /** A legal airborne glide command rules out an otherwise compatible patch whose piston left the player grounded. */
    public void commitAirborneGlideStart() {
        if (this.player.getClientVersion() != ClientVersion.V_1_21_9
                || !(this.profileCarry instanceof JavaPredictionCarry carry)
                || carry.actor() != this.player.compensatedEntities.getSelf()
                || carry.states().isEmpty()) return;
        var states = new ArrayList<JavaPredictionCarry.State>();
        for (var state : carry.states()) {
            if (state.pistonStart() == null
                    ? this.player.onGround
                    : state.pistonStart().onGround()) continue;
            states.add(state);
        }
        if (states.isEmpty()) return;
        this.profileCarry =
                new JavaPredictionCarry(carry.actor(), states.getFirst().fallDistance(), states);
        this.validPlayerStartingVels = new HashSet<>();
        for (var state : states) this.validPlayerStartingVels.add(state.velocity());
        var start = states.getFirst().pistonStart();
        if (start == null) return;
        this.pistonDeltas = start.pistonDeltas();
        this.lastOnGround = DesyncStatus.FALSE;
        this.player.onGround = false;
        Vec3 position = start.position();
        this.player.x = position.x;
        this.player.y = position.y;
        this.player.z = position.z;
        this.player.boundingBox =
                GetBoundingBox.getCollisionBoxForPlayer(this.player, position.x, position.y, position.z);
        this.player.packetStateData.clientSidePosition = position;
    }

    /**
     * From 26.3, the moves between this travel and its report: the Shulker#onPeekAmountChange moves of the shulkers
     * ticking after the player in ClientLevel#tickEntities, then the block-entity pass, for a movement packet the pass
     * of the tick in progress, at a tick end the one that tick end recorded. Null when none of its movers reaches where
     * the travel can end.
     */
    private ClientBlockEntityPass passAfterTravel(SimulationContext context) {
        ClientBlockEntityPass pass = (this.predictingAtTickEnd
                        ? this.player.compensatedWorld.lastBlockEntityPass()
                        : this.player.compensatedWorld.nextBlockEntityPass())
                .after(this.player.compensatedEntities.shulkerPeekMoves(false));
        if (pass.isEmpty()) return null;
        SimpleCollisionBox reach = context.getFromMaximumExtent()
                .union(context.getToMaximumExtent())
                .expand(1.0D);
        return pass.mayReach(reach) ? pass : null;
    }

    /**
     * Before 26.3 LocalPlayer#tick sends the position from the player's place in ClientLevel#tickEntities, so the
     * Shulker#onPeekAmountChange moves of the shulkers ticking after it and then the tick's block entities move the
     * player after its report and before the next tick's packets and travel.
     */
    private void applyBlockEntityPassAfterReport() {
        if (this.player.isBedrockMovement() || JavaBlockEntityPass.reportsAfterPass(this.player.getClientVersion()))
            return;
        this.replayPassBeforeTravel(
                this.player
                        .compensatedWorld
                        .lastBlockEntityPass()
                        .after(this.player.compensatedEntities.shulkerPeekMoves(false)),
                this.player.isSneaking);
    }

    /**
     * The Shulker#onPeekAmountChange moves of the shulkers ticking before the local player in ClientLevel#tickEntities,
     * which move it before the client tick's travel, from where its last report and any pass after it left it. Runs
     * once per client tick, before that tick's first prediction; Player#isSuppressingBounce still reads the last
     * tick's shift key.
     */
    public void applyEntityMovesBeforeTravel() {
        if (this.player.isBedrockMovement() || this.entityMovesTick == this.player.packetStateData.acceptedClientTick)
            return;
        this.entityMovesTick = this.player.packetStateData.acceptedClientTick;
        // handleKeybinds releases riptide before any entity ticks. Its possible outcomes must each run this pass
        // after their own lift; replaying it here would push an unlifted player before the release.
        if (this.player.packetStateData.riptideLevel > 0) return;
        this.replayPassBeforeTravel(
                ClientBlockEntityPass.EMPTY.after(this.player.compensatedEntities.shulkerPeekMoves(true)),
                this.lastSneaking);
    }

    /**
     * Replays {@code pass} on every state the next travel may start from, and starts that travel where the pass left
     * the player. {@code sneaking} is the shift key Player#isSuppressingBounce reads during the pass.
     */
    private void replayPassBeforeTravel(ClientBlockEntityPass pass, boolean sneaking) {
        if (this.player.gamemode == GameMode.SPECTATOR
                || this.lastPrediction == null
                || this.lastPrediction.getSimulationContext() == null
                || this.lastPrediction.getSimulationContext().getWorldData() == null) return;
        if (pass.isEmpty()) return;
        SimulationContext context = this.lastPrediction.getSimulationContext();
        PacketEntity vehicle = this.currentPredictionVehicle();
        if (context.getVehicle() != vehicle
                || vehicle != null && !this.player.packetStateData.isVehicleMovementFromClientTick()) return;
        PacketEntity actor = vehicle == null ? this.player.compensatedEntities.getSelf() : vehicle;
        Vec3 position = vehicle == null
                ? new Vec3(this.player.x, this.player.y, this.player.z)
                : vehicle.clientPhysicalPosition;
        WorldData world = context.getWorldData();
        boolean onGround = this.lastOnGround == DesyncStatus.UNKNOWN
                ? this.player.onGround
                : this.lastOnGround == DesyncStatus.TRUE;
        JavaPredictionCarry carry =
                this.profileCarry instanceof JavaPredictionCarry javaCarry && javaCarry.actor() == actor
                        ? javaCarry
                        : null;
        boolean perState = carry != null && !carry.states().isEmpty();
        List<JavaPredictionCarry.State> starts = new ArrayList<>();
        if (perState) {
            starts.addAll(carry.states());
        } else {
            double fallDistance = carry == null ? 0.0D : carry.fallDistance();
            Vec3 stuck =
                    world.getStuckSpeed() == null ? null : world.getStuckSpeed().getStuckSpeedMultiplier();
            for (Vec3 velocity : this.validPlayerStartingVels)
                starts.add(new JavaPredictionCarry.State(
                        velocity,
                        fallDistance,
                        stuck,
                        null,
                        null,
                        carry != null && carry.powderContact(),
                        world.getEyeFluidContact()));
        }
        if (starts.isEmpty()) return;
        boolean reaches = false;
        for (var state : starts) {
            Vec3 from =
                    state.pistonStart() == null ? position : state.pistonStart().position();
            SimpleCollisionBox box = vehicle == null
                    ? GetBoundingBox.getCollisionBoxForPlayer(this.player, from.x, from.y, from.z)
                    : GetBoundingBox.getPacketEntityBoundingBox(this.player, from.x, from.y, from.z, actor);
            if (pass.mayReach(box)) {
                reaches = true;
                break;
            }
        }
        if (!reaches) return;

        Boolean waterAfterTick = JavaMovementEngine.touchingWaterAfterTick(this.lastPrediction);
        Boolean lavaAfterTick = JavaMovementEngine.touchingLavaAfterTick(this.lastPrediction);
        boolean waterBeforePass = waterAfterTick == null ? world.isInWater() : waterAfterTick;
        boolean lavaBeforePass = lavaAfterTick == null ? world.isInLava() : lavaAfterTick;
        JavaBlockEntityPass.Result first = null;
        DesyncStatus passHard = null;
        boolean movedSelf = false;
        List<JavaPredictionCarry.State> after = new ArrayList<>(starts.size());
        for (JavaPredictionCarry.State state : starts) {
            JavaPredictionCarry.PistonStart anchored = state.pistonStart();
            var start = new JavaPassState(
                    anchored == null ? position : anchored.position(),
                    state.velocity(),
                    anchored == null ? onGround : anchored.onGround(),
                    anchored == null ? world.getMainSupportingBlockPos() : anchored.support(),
                    state.stuckSpeed(),
                    state.fallDistance(),
                    anchored == null ? this.getPistonDeltas(actor) : anchored.pistonDeltas(),
                    state.powderContact(),
                    stateFluidContact(state, true, waterBeforePass),
                    stateFluidContact(state, false, lavaBeforePass),
                    state.playerEyeFluidContact());
            JavaBlockEntityPass.Result result;
            try (var ignored = ClientFluidTagOrder.use(this.player, state.fluidTagOrder())) {
                result = JavaBlockEntityPass.run(
                        this.player,
                        context,
                        pass,
                        start,
                        this.player.pose,
                        sneaking,
                        context.getStart(),
                        state.movementPatch());
            }
            if (first == null) first = result;
            if (vehicle == null) {
                // Each Entity#move sets the collision flags the next LocalPlayer#aiStep reads; a state the pass did
                // not move keeps the travel's.
                var last = result.lastMove();
                movedSelf |= last != null;
                DesyncStatus hard = last == null
                        ? DesyncStatus.UNKNOWN
                        : ac.cult.cultac.checks.impl.prediction.ClientHorizontalCollision.afterMove(
                                this.lastPrediction,
                                last.delta(),
                                last.movement());
                passHard = passHard == null || passHard == hard ? hard : DesyncStatus.UNKNOWN;
            }
            JavaPassState end = result.state();
            Vec3 velocity = end.velocity();
            if (state.velocity() instanceof PredVector ranged && ranged.getVelocityRange() != null) {
                // The pass moves every velocity of a carried range: replayed from the range's corners, they bound
                // where it leaves the rest.
                var range = ranged.getVelocityRange();
                var corners = new ArrayList<Vec3>(2);
                for (Vec3 corner : List.of(
                        new Vec3(ranged.x + range.minX, ranged.y + range.minY, ranged.z + range.minZ),
                        new Vec3(ranged.x + range.maxX, ranged.y + range.maxY, ranged.z + range.maxZ))) {
                    try (var ignored = ClientFluidTagOrder.use(this.player, state.fluidTagOrder())) {
                        corners.add(JavaBlockEntityPass.run(
                                        this.player,
                                        context,
                                        pass,
                                        start.withVelocity(corner),
                                        this.player.pose,
                                        sneaking,
                                        context.getStart(),
                                        state.movementPatch())
                                .state()
                                .velocity());
                    }
                }
                velocity = ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaVelocityRange.hull(
                        velocity instanceof PredVector vector ? vector.withVelocityRange(null) : velocity, corners);
            }
            // The pass falls both ends of a carried fall distance range by the same move, or resets both.
            double fallHigh = end.fallDistance() == 0 && state.fallDistance() != 0
                    ? 0
                    : state.fallDistanceMax() + end.fallDistance() - state.fallDistance();
            var next = new JavaPredictionCarry.State(
                            velocity,
                            end.fallDistance(),
                            end.stuckSpeed(),
                            state.movementPatch(),
                            state.movementPatch() == null
                                    ? null
                                    : new JavaPredictionCarry.PistonStart(
                                            end.position(), end.onGround(), end.support(), end.pistonDeltas()),
                            end.powderContact(),
                            end.playerEyeFluidContact(),
                            state.fluidTagOrder(),
                            state.spinRebound())
                    .withFallDistance(end.fallDistance(), fallHigh);
            if (!after.contains(next)) after.add(next);
        }
        if (movedSelf) this.hardCollisionAfterPass = passHard;
        if (!first.moved() && after.equals(starts)) return;

        JavaPassState end = first.state();
        // Each Entity#move of the pass refreshed the fluids a later riptide release and pass start from.
        this.lastPrediction.setJavaReplayedPassState(end);
        if (vehicle == null) this.pistonDeltas = end.pistonDeltas();
        else {
            vehicle.blockEntityState = end;
            if (vehicle instanceof ac.cult.cultac.utils.data.packetentity.PacketEntityStrider strider)
                strider.commitFluidHeights(first.fluidHeights());
            vehicle.deltaMovement = end.velocity();
            vehicle.onGround = end.onGround();
            if (vehicle.isBoat() && first.boatLastYd() != null) this.player.boatData.lastYd = first.boatLastYd();
            if (first.moved())
                vehicle.applyBlockEntityMovement(
                        this.player,
                        GetBoundingBox.getPacketEntityBoundingBox(
                                this.player, end.position().x, end.position().y, end.position().z, vehicle),
                        end.position().subtract(position));
        }
        if (first.moved() && vehicle == null) {
            Vec3 moved = end.position();
            this.player.lastX = this.player.x;
            this.player.lastY = this.player.y;
            this.player.lastZ = this.player.z;
            this.player.x = moved.x;
            this.player.y = moved.y;
            this.player.z = moved.z;
            this.player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(this.player, moved.x, moved.y, moved.z);
            this.player.packetStateData.clientSidePosition = moved;
            this.lastOnGround = DesyncStatus.fromBoolean(end.onGround());
            // LocalPlayer#aiStep and its commands read the state Entity#move(PISTON) left,
            // which can differ from the grounded position packet sent before this pass.
            this.player.onGround = end.onGround();
            // The next context's support comes from the last one's world data, as Entity#checkSupportingBlock's does.
            world.setMainSupportingBlockPos(end.support());
        }
        if (first.moved() && vehicle != null) {
            this.lastOnGround = DesyncStatus.fromBoolean(end.onGround());
            world.setMainSupportingBlockPos(end.support());
        }
        this.validPlayerStartingVels = new HashSet<>(after.size());
        for (JavaPredictionCarry.State state : after) this.validPlayerStartingVels.add(state.velocity());
        if (this.player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1))
            this.lastWorldEyeFluidContact = after.stream()
                    .mapToInt(JavaPredictionCarry.State::playerEyeFluidContact)
                    .reduce(0, (a, b) -> a | b);
        JavaPredictionCarry previous = carry;
        if (perState) {
            this.profileCarry =
                    new JavaPredictionCarry(carry.actor(), after.getFirst().fallDistance(), after);
        } else {
            this.profileCarry = new JavaPredictionCarry(
                    actor,
                    after.getFirst().fallDistance(),
                    List.of(),
                    after.getFirst().powderContact());
            world.setStuckSpeed(new StuckSpeedData(after.getFirst().stuckSpeed(), null));
        }
    }

    /** Late callbacks read the contact cached by the complete travel state that reached them. */
    private boolean stateFluidContact(JavaPredictionCarry.State state, boolean water, boolean fallback) {
        if (state.fluidTagOrder() == null || this.lastPrediction == null) return fallback;
        for (var prediction : this.lastPrediction.getRealities()) {
            if (prediction.getSimulationContext().getFluidTagOrder() != state.fluidTagOrder()) continue;
            Boolean touching = water
                    ? JavaMovementEngine.touchingWaterAfterTick(prediction)
                    : JavaMovementEngine.touchingLavaAfterTick(prediction);
            var world = prediction.getSimulationContext().getWorldData();
            return touching == null ? water ? world.isInWater() : world.isInLava() : touching;
        }
        return fallback;
    }

    /** Entity#checkSupportingBlock after a move that started on {@code previous}'s support. */
    public MainSupportingBlockData mainSupportingBlockAfter(
            MainSupportingBlockData previous, SimpleCollisionBox box, Vec3 movement, boolean onGround) {
        return this.worldStageBuilder.findMainSupportingBlockPos(
                player, previous, movement.x, movement.z, box, onGround);
    }

    /** The same support for a replayed move, scored against that mover's own post-move position. */
    public MainSupportingBlockData mainSupportingBlockAfter(
            MainSupportingBlockData previous,
            SimpleCollisionBox box,
            Vec3 movement,
            boolean onGround,
            Vec3 ownPosition) {
        return this.worldStageBuilder.findMainSupportingBlockPos(
                player, previous, movement.x, movement.z, box, onGround, ownPosition);
    }

    public MainSupportingBlockData mainSupportingBlockAfter(SimpleCollisionBox box, Vec3 movement, boolean onGround) {
        return this.worldStageBuilder.findMainSupportingBlockPos(
                player,
                this.lastPrediction == null ? null : this.lastPrediction.getSimulationContext(),
                movement.x,
                movement.z,
                box,
                onGround);
    }

    public StepCollisionMemo getStepCollisionMemo() {
        return this.stepCollisionMemo;
    }


    public Set<Vec3> getValidPlayerStartingVels() {
        return this.validPlayerStartingVels;
    }

    // Carry-only readers use this to avoid snapshotting the starting velocity set.
    public PredictionCarry getCurrentPredictionCarry() {
        return this.profileCarry;
    }

    /** The accepted relative teleport's echo proves which complete pre-teleport piston states it started from. */
    public void acceptRelativePistonTeleport(JavaPredictionCarry.TeleportMatch match) {
        if (!match.matched()) return;
        this.profileCarry = match.carry();
        this.validPlayerStartingVels = new HashSet<>();
        for (var state : match.carry().states()) this.validPlayerStartingVels.add(state.velocity());
    }

    public PredictionCommit getCurrentPredictionCommit() {
        return new PredictionCommit(this.profileCarry, this.validPlayerStartingVels);
    }

    public void applyInactiveBedrockAttributes(
            ac.cult.cultac.bedrock.prediction.integration.BedrockReplayAttributeEvent event) {
        if (!this.player.isBedrockMovement()) return;
        this.applyProfileCommit(
                ((ac.cult.cultac.bedrock.prediction.integration.BedrockReplayAttributeEvent) event.ordinary())
                        .apply(this.getCurrentPredictionCommit()));
    }

    public void applyAcknowledgedBedrockGliding(boolean gliding) {
        if (this.player.isBedrockMovement() && this.profileCarry != null) {
            this.profileCarry = MovementEngines.requireForProfile(MovementProfiles.forPlayer(this.player))
                    .applyAcknowledgedGlidingToCarry(this.profileCarry, gliding);
        }
    }

    public void handleBedrockActorCreation(long runtimeEntityId) {
        if (!this.player.isBedrockMovement() || this.player.bedrockState == null) return;
        // Start fresh camera history before the new actor's first frame, while
        // retaining metadata already acknowledged for that actor.
        this.player.bedrockState.recordActorCreation(runtimeEntityId);
        this.player.compensatedEntities.getSelf().bedrockRuntimeId = runtimeEntityId;
        this.player.compensatedEntities.getSelf().bedrockAttributes =
                ac.cult.cultac.bedrock.prediction.state.BedrockActorAttributes.EMPTY;
        this.profileCarry = null;
        this.validPlayerStartingVels = new HashSet<>(Set.of(Vec3.ZERO));
    }

    /** Apply on the player's packet thread at the acknowledged clientbound boundary. */
    public void applyAcknowledgedBedrockMetadata(
            Float width, Float height, Boolean gliding, Boolean crawling, Boolean swimming) {
        applyAcknowledgedBedrockMetadata(width, height, gliding, crawling, swimming, null, null, null);
    }

    public void applyAcknowledgedBedrockMetadata(
            Float width,
            Float height,
            Boolean gliding,
            Boolean crawling,
            Boolean swimming,
            Boolean sneaking,
            Boolean spinning,
            Boolean sleeping) {
        applyAcknowledgedBedrockMetadata(
                width, height, gliding, crawling, swimming, sneaking, spinning, sleeping, null);
    }

    public void applyAcknowledgedBedrockMetadata(
            Float width,
            Float height,
            Boolean gliding,
            Boolean crawling,
            Boolean swimming,
            Boolean sneaking,
            Boolean spinning,
            Boolean sleeping,
            Boolean usingItem) {
        applyAcknowledgedBedrockMetadata(
                width, height, gliding, crawling, swimming, sneaking, spinning, sleeping, usingItem, null);
    }

    public void applyAcknowledgedBedrockMetadata(
            Float width,
            Float height,
            Boolean gliding,
            Boolean crawling,
            Boolean swimming,
            Boolean sneaking,
            Boolean spinning,
            Boolean sleeping,
            Boolean usingItem,
            Boolean sprinting) {
        if (!this.player.isBedrockMovement() || this.player.bedrockState == null) {
            return;
        }
        if (sprinting != null) {
            this.player.bedrockState.acknowledgedSprinting = sprinting;
            this.profileCarry =
                    BedrockMovementEngine.INSTANCE.applyAcknowledgedSprintingToCarry(this.profileCarry, sprinting);
        }
        this.player.bedrockState.applyAcknowledgedBoundingBoxMetadata(width, height);
        this.player.bedrockState.applyAcknowledgedItemUseMetadata(usingItem);
        this.player.bedrockState.applyAcknowledgedPoseMetadata(
                crawling, swimming, sneaking, spinning, sleeping, gliding);
        if (gliding != null) {
            this.applyAcknowledgedBedrockGliding(gliding);
        }
        if ((spinning != null || swimming != null || crawling != null) && this.profileCarry != null) {
            this.profileCarry = MovementEngines.requireForProfile(MovementProfiles.forPlayer(this.player))
                    .applyAcknowledgedPoseToCarry(this.profileCarry, crawling, swimming, spinning);
        }
        if (sleeping != null) {
            this.handleBedrockSleepingStateChange(sleeping);
        }
    }

    public Set<Vec3> getLastStartingVelocitiesUsed() {
        return this.validPlayerStartingVels;
    }

    public Set<Vec3> getLastDerivedNextTickVelocities() {
        return this.validPlayerStartingVels;
    }

    public PredictionSetbackState getBedrockSetbackState() {
        if (this.activeBedrockSetbackState != null) {
            return this.activeBedrockSetbackState;
        } else {
            var vehicle = this.player.isBedrockMovement() ? BedrockVehicleControl.controlledVehicle(this.player) : null;
            if (vehicle != null && vehicle.bedrockPrediction != null) {
                return BedrockMovementEngine.INSTANCE.captureSetbackState(vehicle.bedrockPrediction.commit());
            }
            return this.player.isBedrockMovement() && this.profileCarry != null
                    ? MovementEngines.requireForProfile(MovementProfiles.forPlayer(this.player))
                            .captureSetbackState(new PredictionCommit(this.profileCarry, this.validPlayerStartingVels))
                    : null;
        }
    }

    private boolean isPassengerRotationOnlyTick(ServerboundMovePlayer packet) {
        return packet.rotationOnly()
                && (this.player.packetStateData.hasPassengerRotationThisClientTick()
                        || this.player.compensatedEntities.getSelf().inVehicle()
                        || this.player.compensatedEntities.vehicles.hasClientObservedServerVehicle()
                        || this.player.compensatedEntities.vehicles.hasClientPlayerPassengerState());
    }

    private boolean isPacketHandlerRotationTeleport(ServerboundMovePlayer packet) {
        return packet.rotationOnly() && this.player.packetStateData.lastPacketWasTeleport;
    }

    public void onPlayerTickEnd(PacketReceiveEvent event) {
        // ClientLevel no longer holds a player LocalPlayer#tickDeath removed: it travels no more, and no entity
        // pushes it and no block entity moves it.
        if (!this.player.isBedrockMovement()
                && !this.player.compensatedEntities.getSelf().isRemovedFromLevel()) {
            if (!this.player.compensatedEntities.vehicles.hasClientPlayerPassengerState()
                    && !this.player.packetStateData.hasPassengerRotationThisClientTick()) {
                if (!this.player.packetStateData.receivedMovementThisClientTick) {
                    if (CheckManagerListener.localPlayerTicked(this.player)) {
                        this.applyEntityMovesBeforeTravel();
                        this.predictingAtTickEnd = true;
                        // World compensation has advanced to the completed tick. Travel and its input check
                        // still collide before Level#tickBlockEntities, including a tick with no movement packet.
                        try (var ignored = this.player.compensatedWorld.beginBlockEntityReplay(
                                this.player.compensatedWorld.lastBlockEntityPass())) {
                            this.advanceNoPositionClientTick(
                                    new Vec3(this.player.x, this.player.y, this.player.z),
                                    this.player.xRot,
                                    this.player.yRot);
                        } finally {
                            this.predictingAtTickEnd = false;
                        }
                    } else if (this.ticksWhileLoadingLevel < MAX_LOADING_LEVEL_TICKS) {
                        // Entity#push adds to the waiting player's deltaMovement with no travel or friction between.
                        this.ticksWhileLoadingLevel++;
                        if (this.pushesWhileLoadingLevel == null) {
                            this.pushesWhileLoadingLevel = new SimpleCollisionBox(0, 0, 0, 0, 0, 0);
                        }
                        this.worldStageBuilder.addPushesOnStandingPlayer(
                                this.player, this.player.x, this.player.y, this.player.z, this.pushesWhileLoadingLevel);
                    }
                }
            }
            // Entity interpolation has ticked, and even an idle tick now has its own committed prediction.
            // Recounting before that idle prediction writes the previous tick, dropping the next travel's push.
            if (this.lastPrediction != null)
                WorldStageBuilder.recountCollidingTickingAfterPlayer(this.player, this.lastPrediction);
            this.applyEntityVelocityTicksAfterReport();
            // After travel, the block entities move the local player or its locally controlled root.
            this.applyBlockEntityPassAfterReport();
        }
        if (this.player.packetStateData.flyingStopAfterTravel) {
            this.stopFlyingAfterTravel();
        }
        // Foreign entities' piston moves also follow the player's travel, including deferred idle prediction.
        if (!this.player.isBedrockMovement()) this.player.compensatedEntities.fishingPulls.finishClientTick(this.player);
        JavaRemoteBlockEntityPass.tick(this.player);
    }

    /** Through 26.2 the report is inside LocalPlayer#tick; late rockets and boats run before the block entities. */
    private void applyEntityVelocityTicksAfterReport() {
        if (JavaBlockEntityPass.reportsAfterPass(this.player.getClientVersion())
                || !CheckManagerListener.localPlayerTicked(this.player)
                || this.lastPrediction == null
                || !(this.profileCarry instanceof JavaPredictionCarry carry)
                || carry.actor() != this.player.compensatedEntities.getSelf()
                || carry.states().isEmpty()) return;
        var context = this.lastPrediction.getSimulationContext();
        var states = new ArrayList<JavaPredictionCarry.State>();
        Vec3 end = new Vec3(this.player.x, this.player.y, this.player.z);
        var carried = carry.states();
        var velocities = new ArrayList<Vec3>(carried.size());
        for (var state : carried) velocities.add(state.velocity());
        var pushed = ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaEntityVelocityTicks.afterPlayerTick(
                this.player, context, this.lastPrediction.getInitialStartingVel(), end, velocities);
        for (int i = 0; i < carried.size(); i++) {
            var state = carried.get(i);
            for (Vec3 velocity : pushed.get(i)) {
                var next = state.withVelocity(velocity);
                if (!states.contains(next)) states.add(next);
            }
        }
        this.profileCarry = carry.withStates(states);
        this.validPlayerStartingVels = new HashSet<>();
        for (var state : states) this.validPlayerStartingVels.add(state.velocity());
    }

    private void handleMovePlayer(ServerboundMovePlayer packet) {
        if (!this.player.isBedrockMovement()) {
            ServerboundMovePlayer flying = packet;
            if (!flying.hasPosition()) {
                if (!this.isPacketHandlerRotationTeleport(packet)) {
                    if (!this.isPassengerRotationOnlyTick(packet)) {
                        this.player.onGround = flying.onGround();
                        PredictionResult result = this.advanceNoPositionClientTick(
                                new Vec3(this.player.x, this.player.y, this.player.z),
                                this.player.xRot,
                                this.player.yRot);
                        boolean didFlagNoFall = result.hasFlag(NoFallPseudo.class);
                        if (didFlagNoFall) {
                            Boolean desiredOnGround = result.getDesiredOnGround();
                            if (desiredOnGround != null) {
                                this.player.packetStateData.stageDesiredOnGround(desiredOnGround);
                            }

                            if (!result.isExempt()) {
                                ((NoFallExecutor) this.player.checkManager.getListener(NoFallExecutor.class))
                                        .flag("nopos,g=" + flying.onGround());
                            }
                        }

                        if (!didFlagNoFall || result.isExempt()) {
                            this.lastOnGround = DesyncStatus.fromBoolean(flying.onGround());
                        }
                    } else {
                        // PlayerInput precedes the passenger Rot each riding tick, even
                        // when this passenger never commits a vehicle prediction.
                        this.lastSneaking = this.player.isSneaking;
                    }
                }
            }
        }
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        this.handleMovePlayer(packet);
    }

    public void onPositionUpdate(PositionUpdate positionUpdate) {
        if (positionUpdate.getTeleportData().isTeleport()) {
            this.handleTeleport(positionUpdate.getTeleportData());
        } else {
            boolean packetOnGround = positionUpdate.isOnGround();
            PredictionResult result = this.callPrediction(
                    positionUpdate.getTo(),
                    positionUpdate.getFrom(),
                    packetOnGround,
                    this.player.xRot,
                    this.player.yRot,
                    positionUpdate.getAuthoredMovementFrame());
            boolean didFlagNoFall = result != null && !result.isExempt() && result.hasFlag(NoFallPseudo.class);
            if (didFlagNoFall) {
                Boolean desiredOnGround = result.getDesiredOnGround();
                if (desiredOnGround != null) {
                    this.player.packetStateData.stageDesiredOnGround(desiredOnGround);
                    this.lastOnGround = DesyncStatus.fromBoolean(desiredOnGround);
                } else {
                    this.lastOnGround = DesyncStatus.fromBoolean(!positionUpdate.isOnGround());
                }
            } else {
                this.lastOnGround = DesyncStatus.fromBoolean(packetOnGround);
            }

            this.player.onGround = packetOnGround;
        }
    }

    private void handleTeleport(TeleportAcceptData teleportAcceptData) {
        TeleportData teleportData = teleportAcceptData.getTeleportData();
        if (!this.player.isBedrockMovement())
            this.player.compensatedEntities.ravagerRoars.afterTeleport(
                    teleportData, this.player.compensatedEntities.getSelf());
        if (!this.player.isBedrockMovement())
            this.player.compensatedEntities.fishingPulls.afterTeleport(
                    teleportData, this.player.compensatedEntities.getSelf());
        PredictionResult result =
                new PredictionResult(this.player, null, null, null, teleportData, new ArrayList(), new ArrayList());
        result.setTeleport(true);
        Vec3 position = teleportData.getLocation();
        this.player.boundingBox =
                GetBoundingBox.getCollisionBoxForPlayer(this.player, position.x, position.y, position.z);
        if (!this.player.compensatedEntities.getSelf().inVehicle()) {
            this.carriedPush = this.carriedPush.after(teleportData);
            if (this.pushesWhileLoadingLevel != null) {
                this.pushesWhileLoadingLevel = inheritedRange(teleportData, this.pushesWhileLoadingLevel);
                if (this.pushesWhileLoadingLevel == null) this.ticksWhileLoadingLevel = 0;
            }
            Set<Vec3> existingVectors = this.validPlayerStartingVels;
            this.validPlayerStartingVels = new HashSet<>();

            for (Vec3 vector : existingVectors) {
                this.validPlayerStartingVels.add(
                        this.player.isBedrockMovement() ? Vec3.ZERO : teleportData.applyToVelocity(vector));
            }

            if (!this.player.isBedrockMovement()
                    && this.profileCarry instanceof JavaPredictionCarry carry
                    && carry.actor() == this.player.compensatedEntities.getSelf()) {
                this.profileCarry = carry.afterTeleport(teleportData);
            }

            if (!teleportData.isRelativeDeltaX()
                    && !teleportData.isRelativeDeltaY()
                    && !teleportData.isRelativeDeltaZ()) {
                this.lastFlying.setRaw(20);
            }
        }

        PredictionCommit teleportCommit = null;
        if (this.player.isBedrockMovement()) {
            teleportCommit = MovementEngines.requireForProfile(MovementProfiles.forPlayer(this.player))
                    .applyTeleportToCarry(this.profileCarry, teleportData);
        }

        this.callPredictionEndListeners(result, teleportCommit);
        // A transport-only acknowledgement can follow the actual Cult setback
        // before any Bedrock travel. It must not consume that setback's guard.
        if (!this.player.isBedrockMovement()
                || teleportAcceptData.getSetback() != null
                || !teleportData.isBedrockTransportOnly()) {
            this.lastMovementWasSetback = teleportAcceptData.getSetback() != null
                    && !teleportAcceptData.getSetback().isPlugin();
        }
        this.certainSpinHit = false;
        this.player.riptideSpinCertain = false;
        if (!this.player.isBedrockMovement()
                && teleportAcceptData.getSetback() != null
                && teleportAcceptData.getSetback().getVelocity() != null) {
            this.validPlayerStartingVels.clear();
            this.validPlayerStartingVels.add(teleportAcceptData.getSetback().getVelocity());
        }

        if (this.player.isBedrockMovement()) {
            if (teleportCommit != null) {
                this.applyProfileCommit(teleportCommit);
            } else {
                this.profileCarry = null;
            }
        }
    }

    public void applyAcceptedBedrockTeleport(TeleportAcceptData teleportAcceptData) {
        if (teleportAcceptData != null
                && teleportAcceptData.isTeleport()
                && teleportAcceptData.getTeleportData() != null) {
            this.player.bedrockState.movementCorrections.clear();
            this.handleTeleport(teleportAcceptData);
            TeleportData teleport = teleportAcceptData.getTeleportData();
            this.commitPlayerPosition(teleport.getLocation());
        }
    }

    /** A passive root's movement packet can overwrite ground before its rider takes control again. */
    public void applyVehiclePacketGround(PacketEntity vehicle, boolean ground) {
        if (this.player.isBedrockMovement() || this.currentPredictionVehicle() != vehicle) return;
        if (this.lastPrediction != null
                && this.lastPrediction.getSimulationContext() != null
                && this.lastPrediction.getSimulationContext().getVehicle() == vehicle) {
            this.lastOnGround = DesyncStatus.fromBoolean(ground);
        }
        if (this.profileCarry instanceof JavaPredictionCarry carry && carry.actor() == vehicle)
            this.profileCarry = carry.withPacketGround(ground);
    }

    public PredictionResult onVehiclePositionUpdate(VehiclePositionUpdate positionUpdate) {
        if (positionUpdate.getTeleportAcceptData().isTeleport()) {
            this.handleTeleport(positionUpdate.getTeleportAcceptData());
            return null;
        }

        if (!this.player.isBedrockMovement() && !positionUpdate.isHasOnGround()) {
            PacketEntity vehicle = this.currentPredictionVehicle();
            if (vehicle != null
                    && (this.lastPrediction == null
                            || this.lastPrediction.getSimulationContext().getVehicle() != vehicle)) {
                this.lastOnGround = DesyncStatus.fromBoolean(vehicle.onGround);
            }
        }

        PredictionResult result = this.callPrediction(
                positionUpdate.getTo(),
                positionUpdate.getFrom(),
                positionUpdate.isOnGround(),
                positionUpdate.getXRot(),
                positionUpdate.getYRot());
        if (positionUpdate.isHasOnGround()) {
            this.lastOnGround = DesyncStatus.fromBoolean(positionUpdate.isOnGround());
        }

        return result;
    }

    @Nullable
    public PredictionResult processBedrockAuthInputFrame(
            BedrockAuthInputFrame frame, BedrockPredictionTrigger trigger) {
        return processBedrockAuthInputFrame(frame, trigger, null);
    }

    public PredictionResult processBedrockAuthInputFrame(
            BedrockAuthInputFrame frame, BedrockPredictionTrigger trigger, @Nullable TeleportData acceptedTeleport) {
        this.bedrockTeleport = acceptedTeleport;
        try {
            return processBedrockAuthInputTick(frame, trigger);
        } finally {
            this.bedrockTeleport = null;
        }
    }

    private PredictionResult processBedrockAuthInputTick(
            BedrockAuthInputFrame frame, BedrockPredictionTrigger trigger) {
        if (!this.player.isBedrockMovement() || this.player.bedrockState == null || frame == null) {
            return null;
        }
        var vehicle = BedrockVehicleControl.controlledVehicle(this.player);
        this.player.bedrockState.tickRidingJump(frame, vehicle instanceof PacketEntityHorse);
        if (vehicle != null) {
            if (!BedrockVehicleControl.matches(frame, vehicle)) {
                this.player.packetStateData.bedrockTranslatedMovement.reject();
                return null;
            }
            return this.processBedrockVehicleFrame(frame, trigger, vehicle);
        }
        if (this.player.compensatedEntities.vehicles.hasPlayerPassengerState()) {
            // A mount may be pending on the compensated timeline. Reject vehicle movement
            // until control is established, including unmapped or unsupported vehicle IDs.
            if (frame.getPredictedVehicleId() != null && frame.getPredictedVehicleId() != -1L
                    || frame.hasRawInputFlag(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE)) {
                this.player.packetStateData.bedrockTranslatedMovement.reject();
            } else if (this.player.compensatedEntities.getSelf().getRiding() != null) {
                this.recordProcessedBedrockAuthInputFrame(frame, trigger);
                this.advanceBedrockPassenger(frame, this.player.compensatedEntities.getSelf().clientPhysicalPosition);
            }
            return null;
        }
        if (frame.getPredictedVehicleId() != null && frame.getPredictedVehicleId() != -1L) {
            this.player.packetStateData.bedrockTranslatedMovement.reject();
            return null;
        }
        if (this.bedrockSleepingStateObserved) {
            this.recordProcessedBedrockAuthInputFrame(frame, trigger);
            this.applyBedrockImmobileState(frame);
            return null;
        } else {
            return this.processAuthoredInputFrame(frame, trigger);
        }
    }

    private PredictionResult processBedrockVehicleFrame(
            BedrockAuthInputFrame frame, BedrockPredictionTrigger trigger, PacketEntity vehicle) {
        PredictionCommit riderCarry = this.getCurrentPredictionCommit();
        PredictionResult riderPrediction = this.lastPrediction;
        DesyncStatus riderGround = this.lastOnGround;
        boolean riderOnGround = this.player.onGround;
        PredictionResult vehicleResult = null;
        try {
            var previous = vehicle.bedrockPrediction;
            this.applyProfileCommit(
                    previous == null ? new PredictionCommit(null, Set.of(vehicle.deltaMovement)) : previous.commit());
            this.lastPrediction = previous == null ? null : previous.previousPrediction();
            this.lastOnGround = DesyncStatus.fromBoolean(vehicle.onGround);
            this.player.onGround = vehicle.onGround;
            PredictionResult result = this.processAuthoredInputFrame(frame, trigger);
            vehicleResult = result;
            PredictionCommit commit = this.getCurrentPredictionCommit();
            vehicle.bedrockPrediction = new BedrockVehiclePredictionState(commit, this.lastPrediction);
            return result;
        } finally {
            this.applyProfileCommit(riderCarry);
            this.lastPrediction = riderPrediction;
            this.lastOnGround = riderGround;
            this.player.onGround = riderOnGround;
            if (vehicleResult != null) {
                this.advanceBedrockPassenger(frame, vehicleResult);
            }
        }
    }

    private void advanceBedrockPassenger(BedrockAuthInputFrame frame, PredictionResult result) {
        BedrockPredictionResult vehicle = result.getProfileResult(BedrockPredictionResult.class);
        if (vehicle == null || vehicle.movementResult() == null) return;
        Vec3 position = BedrockVehiclePredictionState.passengerPosition(vehicle);
        this.advanceBedrockPassenger(frame, position);
    }

    private void advanceBedrockPassenger(BedrockAuthInputFrame frame, Vec3 position) {
        MovementProfile profile = MovementProfiles.forPlayer(this.player);
        SimulationContext context = this.createSimulationContext(
                this.player,
                this.lastPrediction,
                Vec3.ZERO,
                position,
                position,
                false,
                frame.getYaw(),
                frame.getPitch(),
                frame,
                profile,
                false,
                null,
                null);
        this.applyProfileCommit(
                BedrockMovementEngine.INSTANCE.advancePassenger(this.player, this.profileCarry, context));
        this.commitAuthoredInputPlayerState(frame, position, frame.getYaw(), frame.getPitch());
    }

    public void relocateBedrockPassenger(ac.cult.cultac.bedrock.prediction.state.BedrockMovementState vehicle) {
        Vec3 position = BedrockVehiclePredictionState.passengerPosition(vehicle);
        this.applyProfileCommit(BedrockMovementEngine.INSTANCE.relocatePassenger(
                this.profileCarry, position, vehicle.coordinateFrame()));
        this.commitPlayerPosition(position);
    }

    public void handleBedrockSleepingStateChange(boolean sleeping) {
        if (this.player.isBedrockMovement() && this.bedrockSleepingStateObserved != sleeping) {
            this.bedrockSleepingStateObserved = sleeping;
            this.player.bedrockState.movementCorrections.clear();
            this.player.bedrockState.applyAcknowledgedPoseMetadata(null, null, null, null, sleeping);
            if (sleeping) {
                this.player.packetStateData.bedrockTranslatedMovement.clear();
                this.applyBedrockImmobileState(null);
            }
            // Sleep changes the travel gate, not the already ordered actor actions.
        }
    }

    public boolean isBedrockSleepingStateObserved() {
        return this.bedrockSleepingStateObserved;
    }

    private void applyBedrockImmobileState(@Nullable BedrockAuthInputFrame frame) {
        MovementProfile movementProfile = MovementProfiles.forPlayer(this.player);
        SimulationContext context = null;
        if (frame != null) {
            Vec3 unchangedPosition = movementProfile.authoredPredictionStart(
                    this.player, frame, new Vec3(this.player.x, this.player.y, this.player.z), this.profileCarry);
            float xRot = frame.hasRotation() ? frame.getYaw() : this.player.xRot;
            float yRot = frame.hasRotation() ? frame.getPitch() : this.player.yRot;
            context = this.createSimulationContext(
                    this.player,
                    this.lastPrediction,
                    Vec3.ZERO,
                    unchangedPosition,
                    unchangedPosition,
                    false,
                    xRot,
                    yRot,
                    frame,
                    movementProfile,
                    false);
        }

        PredictionCommit immobileCommit = MovementEngines.requireForProfile(movementProfile)
                .applyImmobileStateToCarry(this.player, this.profileCarry, frame, context);
        if (immobileCommit == null) {
            this.validPlayerStartingVels.clear();
            this.validPlayerStartingVels.add(Vec3.ZERO);
        } else {
            this.applyProfileCommit(immobileCommit);
        }
    }

    @Nullable
    private PredictionResult processAuthoredInputFrame(AuthoredMovementFrame frame, BedrockPredictionTrigger trigger) {
        if (frame == null) {
            return null;
        }

        Vec3 currentPosition = frame.getPosition();
        if (currentPosition == null) {
            return null;
        }

        this.recordProcessedBedrockAuthInputFrame(frame, trigger);
        MovementProfile movementProfile = MovementProfiles.forPlayer(this.player);
        var horse = this.player.isBedrockMovement() ? BedrockVehicleControl.controlledVehicle(this.player) : null;
        if (frame instanceof BedrockAuthInputFrame bedrockFrame) {
            PredictionCommit current = this.getCurrentPredictionCommit();
            PredictionCommit replayed =
                    this.player.bedrockState.movementCorrections.prepareFrame(this.player, bedrockFrame, current);
            if (replayed != current) {
                this.applyProfileCommit(replayed);
                this.lastPrediction = null;
                var state = BedrockProfileState.previousState(replayed.carry());
                if (state != null) {
                    this.lastOnGround = DesyncStatus.fromBoolean(state.movementGrounded());
                    this.player.onGround = state.movementGrounded();
                    if (horse != null) BedrockVehiclePredictionState.commitTransform(horse, replayed.carry());
                    else this.commitPlayerPosition(BedrockVectorAdapter.toJava(state.physicalFeetPosition()));
                }
            }
        }
        Vec3 previousPosition = movementProfile.authoredPredictionStart(
                this.player,
                frame,
                horse != null ? horse.clientPhysicalPosition : new Vec3(this.player.x, this.player.y, this.player.z),
                this.profileCarry);
        if (frame instanceof BedrockAuthInputFrame) {
            previousPosition = this.player.bedrockState.movementCorrections.predictionStart(previousPosition);
        }
        boolean onGround = this.player.onGround;
        float xRot = frame.hasRotation() ? frame.getYaw() : this.player.xRot;
        float yRot = frame.hasRotation() ? frame.getPitch() : this.player.yRot;
        PredictionResult result = frame instanceof BedrockAuthInputFrame bedrock
                ? this.simulateBedrockTick(currentPosition, previousPosition, xRot, yRot, bedrock, movementProfile)
                : this.callPrediction(currentPosition, previousPosition, onGround, xRot, yRot, frame);
        if (result != null
                && (!this.player.isBedrockMovement()
                        || this.bedrockTeleport != null
                        || !this.player.getSetbackTeleportUtil().mustAcknowledgeBedrockTransportTeleport())) {
            var simulated = BedrockProfileState.previousState(this.profileCarry);
            if (simulated != null) currentPosition = BedrockVectorAdapter.toJava(simulated.physicalFeetPosition());
            boolean canonicalGround = canonicalBedrockGround(result, onGround);
            this.lastOnGround = DesyncStatus.fromBoolean(canonicalGround);
            this.player.onGround = canonicalGround;
            if (horse != null) {
                BedrockVehiclePredictionState.commitTransform(horse, this.profileCarry);
            } else {
                this.commitAuthoredInputPlayerState(frame, currentPosition, xRot, yRot);
            }
        }

        return result;
    }

    private PredictionResult simulateBedrockTick(
            Vec3 observed, Vec3 start, float yaw, float pitch, BedrockAuthInputFrame frame, MovementProfile profile) {
        boolean pendingTeleport = this.bedrockTeleport == null
                && this.player.getSetbackTeleportUtil().mustAcknowledgeBedrockTransportTeleport();
        PredictionResult result = this.getPredictionResult(
                this.player,
                this.validPlayerStartingVels,
                this.lastPrediction,
                observed.subtract(start),
                start,
                observed,
                false,
                yaw,
                pitch,
                frame,
                profile);
        if (!pendingTeleport && this.isMovementModeExempt(profile, result.getSimulationContext())) {
            result.exempt();
        }
        PredictionCommit commit = this.prepareBedrockCommit(result, observed.subtract(start), profile);
        if (commit == null || BedrockProfileState.previousState(commit.carry()) == null) {
            this.player.packetStateData.bedrockTranslatedMovement.reject();
            return null;
        }
        if (pendingTeleport) {
            this.applyProfileCommit(commit);
            this.player.bedrockState.movementCorrections.record(
                    this.player, frame, result.getSimulationContext().getVehicle(), result, commit);
            this.player.packetStateData.bedrockTranslatedMovement.reject();
            return null;
        }
        result.getSimulationContext()
                .setEnd(BedrockVectorAdapter.toJava(
                        BedrockProfileState.previousState(commit.carry()).physicalFeetPosition()));
        this.handlePossibleRealities(result);
        boolean verbose = profile.shouldCreateVerboseLog(this.player, result);
        boolean flagged = !result.isExempt() && result.hasEffectiveFlags();
        if (flagged || verbose || BedrockPredictionDebug.shouldRecordMovementDebug(this.player, result)) {
            result.setIdentifier(nextDebugIdentifier());
            result.setProfileVerboseLog(verbose);
        }
        this.callPredictionEndListeners(result, commit);
        PredictionCommit reconciledCommit = this.bedrockTeleport == null
                        && this.player.getSetbackTeleportUtil().mustAcknowledgeBedrockTransportTeleport()
                ? commit
                : BedrockMovementEngine.INSTANCE.reconcileCommittedPosition(
                        commit,
                        frame.getPosition(),
                        CultAPI.INSTANCE.getConfigManager().getBedrockMovementPositionReconciliationStep());
        this.commitPredictionState(result, observed, observed.subtract(start), yaw, pitch, profile, reconciledCommit);
        this.player.bedrockState.movementCorrections.record(
                this.player, frame, result.getSimulationContext().getVehicle(), result, reconciledCommit);
        return result;
    }

    public void resetBedrockVehicle(
            PacketEntity vehicle,
            Vec3 position,
            Vec3 velocity,
            float yaw,
            float pitch,
            boolean onGround,
            PredictionSetbackState setback) {
        PredictionCommit old = setback != null
                ? setback.commit()
                : vehicle.bedrockPrediction == null ? null : vehicle.bedrockPrediction.commit();
        vehicle.clientPhysicalPosition = position;
        vehicle.deltaMovement = velocity;
        vehicle.onGround = onGround;
        if (old == null) return;
        var entries = BedrockProfileState.profileEntries(old.carry()).stream()
                .map(entry -> {
                    var state = entry.state();
                    return entry.withState(
                            state.withPhysicalFeetPosition(BedrockVectorAdapter.toBedrock(position), 0.0D)
                                    .withRotation(yaw + (state.isBoat() ? 90.0F : 0.0F), pitch)
                                    .withVelocityAndCollisionFlags(
                                            BedrockVectorAdapter.toBedrock(velocity),
                                            state.collisionFlags().withTeleportOnGround(onGround)));
                })
                .toList();
        PredictionCommit commit = new PredictionCommit(
                new ac.cult.cultac.bedrock.prediction.integration.BedrockNextTickStates(entries), Set.of(velocity));
        vehicle.bedrockPrediction = new BedrockVehiclePredictionState(commit, null);
        BedrockVehiclePredictionState.commitTransform(vehicle, commit.carry());
    }

    private static boolean canonicalBedrockGround(PredictionResult result, boolean fallback) {
        BedrockPredictionResult bedrockResult =
                (BedrockPredictionResult) result.getProfileResult(BedrockPredictionResult.class);
        return bedrockResult != null && bedrockResult.nextTickBaseState() != null
                ? bedrockResult.nextTickBaseState().movementGrounded()
                : fallback;
    }

    private void recordProcessedBedrockAuthInputFrame(AuthoredMovementFrame frame, BedrockPredictionTrigger trigger) {
        if (this.player.bedrockState != null && frame instanceof BedrockAuthInputFrame bedrockFrame) {
            this.player.bedrockState.recordProcessedAuthInputFrame(bedrockFrame, trigger);
        }
    }

    private void commitAuthoredInputPlayerState(AuthoredMovementFrame frame, Vec3 position, float xRot, float yRot) {
        this.commitPlayerPosition(position);
        if (frame.hasRotation()) {
            HeadRotation from = new HeadRotation(this.player.xRot, this.player.yRot);
            if (this.player.xRot != xRot || this.player.yRot != yRot) {
                this.player.lastTickXRot = this.player.xRot;
                this.player.lastTickYRot = this.player.yRot;
            }

            this.player.xRot = xRot;
            this.player.yRot = yRot;
            RotationUpdate update = new RotationUpdate(
                    from,
                    new HeadRotation(this.player.xRot, this.player.yRot),
                    this.player.xRot - from.yaw(),
                    this.player.yRot - from.pitch());
            if (update.getDeltaXRot() != 0 || update.getDeltaYRot() != 0) {
                this.player.lastRotated = System.currentTimeMillis();
            }
            this.player.checkManager.onRotationUpdate(update);
        }
    }

    private void commitPlayerPosition(Vec3 position) {
        Vec3 clampedPosition = VectorUtils.clampVector(position);
        this.player.lastX = this.player.x;
        this.player.lastY = this.player.y;
        this.player.lastZ = this.player.z;
        this.player.x = clampedPosition.x;
        this.player.y = clampedPosition.y;
        this.player.z = clampedPosition.z;
        this.player.packetStateData.clientSidePosition = clampedPosition;
        this.player.getMovementData().handle(clampedPosition.x, clampedPosition.y, clampedPosition.z);
    }

    private boolean movementStartOnGround(boolean packetOnGround) {
        PacketEntity vehicle = this.player.compensatedEntities.vehicles.getVelocityMovementVehicle();
        if (vehicle == null) {
            return packetOnGround;
        } else if (this.player.packetStateData.isVehicleMovementFromClientTick()) {
            return this.player.packetStateData.vehicleMovementOnGroundPresent
                    ? this.player.packetStateData.vehicleMovementStartOnGround
                    : this.lastOnGround.determineOptimistically();
        } else {
            return packetOnGround;
        }
    }

    @Nullable
    public PredictionResult callPrediction(Vec3 to, Vec3 from, boolean onGround, float xRot, float yRot) {
        return this.callPrediction(to, from, onGround, xRot, yRot, null);
    }

    @Nullable
    public PredictionResult callPrediction(
            Vec3 to, Vec3 from, boolean onGround, float xRot, float yRot, AuthoredMovementFrame authoredMovementFrame) {
        this.activeBedrockSetbackState = null;
        long start = System.nanoTime();
        this.lastMovementTime.add(start);
        Vec3 diff = to.subtract(from);
        double length = diff.lengthSqr();
        if (length > 1.0) {
            Check crashCheck = (Check) this.player.checkManager.getListener(CrashF.class);
            crashCheck.debug(() -> "length=" + length);
        }

        if (length > 400.0) {
            if (this.player.getSetbackTeleportUtil().hasFullyLoaded) {
                Check crashCheck = (Check) this.player.checkManager.getListener(CrashF.class);
                crashCheck.flag("len " + diff.length());
            }

            this.player.getSetbackTeleportUtil().executeViolationSetback();
            return null;
        } else {
            boolean flyingThroughTravel = this.keepsFlyingThroughTravel(onGround);
            boolean predictionOnGround = this.movementStartOnGround(onGround);
            this.player.onGround = predictionOnGround;
            MovementProfile movementProfile = MovementProfiles.forPlayer(this.player);
            PredictionResult result =
                    this.callPrediction(diff, from, to, false, xRot, yRot, authoredMovementFrame, movementProfile);
            if (result.getSimulationContext() != null
                    && result.getSimulationContext().getVehicle() != null) {
                this.player.onGround = onGround;
            }

            this.handlePossibleRealities(result);
            if (this.player.isBedrockMovement()) {
                this.activeBedrockSetbackState =
                        MovementEngines.requireForProfile(movementProfile).prepareSetbackState(this.player, result);
            }

            boolean desync = !result.isExempt() && this.isDesync(result);
            if (desync) {
                SetbackTeleportUtil setbackUtil = this.player.getSetbackTeleportUtil();
                if (setbackUtil.isPendingSetback()) {
                    setbackUtil.blockOffsets = true;
                }

                setbackUtil.executeForceResync("sim desync");
                result.exempt();
                this.lastOnGround = DesyncStatus.fromBoolean(onGround);
            }

            boolean createProfileVerboseLog = movementProfile.shouldCreateVerboseLog(this.player, result);
            boolean recordProfileDebug = BedrockPredictionDebug.shouldRecordMovementDebug(this.player, result);
            this.player.compensatedEntities.vehicles.consumeVehicleSwitchPredictionOffset(result);
            this.prepareFlaggedPrediction(result, createProfileVerboseLog);

            if (result.getIdentifier() == 0 && (createProfileVerboseLog || recordProfileDebug)) {
                result.setIdentifier(nextDebugIdentifier());
                result.setProfileVerboseLog(createProfileVerboseLog);
            }

            PredictionCommit preparedCommit = this.prepareBedrockCommit(result, diff, movementProfile);
            this.callPredictionEndListeners(result, preparedCommit);
            if (!this.player.isBedrockMovement()
                    && this.player.platformPlayer != null
                    && this.player.isGliding
                    && result.getInitialStartingVel().hasReason("Jumping sprinting")
                    && !CultAPI.INSTANCE
                            .getConfigManager()
                            .getConfig()
                            .getBooleanElse("exploit.allow-sprint-jumping-when-using-elytra", true)) {
                SetbackPosWithVector lastKnownGoodPosition = this.player.getSetbackTeleportUtil().lastKnownGoodPosition;
                lastKnownGoodPosition.setVector(
                        lastKnownGoodPosition.getVector().multiply(new Vec3(0.546, 1.0, 0.546)));
                this.player.getSetbackTeleportUtil().executeNonSimulatingSetback();
            }

            this.commitPredictionState(
                    result,
                    to,
                    this.player.compensatedEntities.vehicles.committedVehicleMovement(result, diff),
                    xRot,
                    yRot,
                    movementProfile,
                    preparedCommit);
            if (flyingThroughTravel) {
                this.stopFlyingAfterTravel();
            }
            this.activeBedrockSetbackState = null;
            long took = System.nanoTime() - start;
            computeTimeNS = (long) (computeTimeNS * 499L / 500.0 + took / 500.0);
            return result;
        }
    }

    private void prepareFlaggedPrediction(PredictionResult result, boolean createProfileVerboseLog) {
        if (result.isExempt() || result.getFlagSeverity() <= 0.0) return;
        this.dumpRecentFlagsWhenDebugging(result);
        if (!CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
            this.player.getSetbackTeleportUtil().resyncWorld();
        }
        boolean bedrockMovementFlag = this.player.isBedrockMovement() && result.hasFlag(BedrockMovement.class);
        if (this.player.shouldEnforceMovementSetbacks() || createProfileVerboseLog || bedrockMovementFlag) {
            result.setIdentifier(nextDebugIdentifier());
            result.setProfileVerboseLog(createProfileVerboseLog);
        }
    }

    private PredictionCommit prepareBedrockCommit(
            PredictionResult result, Vec3 acceptedDiff, MovementProfile movementProfile) {
        return this.player.isBedrockMovement()
                        && result != null
                        && result.getSimulationContext() != null
                        && result.getSimulationContext().hasTrustedAuthoredInput()
                ? MovementEngines.requireForProfile(movementProfile)
                        .commitNextTick(this.player, result, this.lastPrediction, acceptedDiff, this.profileCarry)
                : null;
    }

    private void commitPredictionState(
            PredictionResult result, Vec3 to, Vec3 diff, float xRot, float yRot, MovementProfile movementProfile) {
        this.commitPredictionState(result, to, diff, xRot, yRot, movementProfile, null);
    }

    private void commitPredictionState(
            PredictionResult result,
            Vec3 to,
            Vec3 diff,
            float xRot,
            float yRot,
            MovementProfile movementProfile,
            PredictionCommit preparedCommit) {
        if (preparedCommit == null
                || !this.player.isBedrockMovement()
                || this.bedrockTeleport != null
                || !this.player.getSetbackTeleportUtil().mustAcknowledgeBedrockTransportTeleport()) {
            if (!this.player.isBedrockMovement() || BedrockSetbackJumpGuard.advancesTravel(result)) {
                this.lastMovementWasSetback = false;
            }
            this.validPlayerStartingVels.clear();
            PredictionCommit commit = preparedCommit == null
                    ? MovementEngines.requireForProfile(movementProfile)
                            .commitNextTick(this.player, result, this.lastPrediction, diff, this.profileCarry)
                    : preparedCommit;
            this.applyProfileCommit(commit);
            if (!this.player.isBedrockMovement()) this.commitPassAfterTravel(result);
            this.player.checkManager.getDebugHandler().onPredictionCommitted(result, this.validPlayerStartingVels);
            if (!this.player.isBedrockMovement()
                    && result.getSimulationContext().stopsFallFlyingAfterTravel()) {
                this.player.isGliding = false;
            }
            this.lastPrediction = result;
            this.lastWorldEyeFluidContact =
                    result.getSimulationContext().getWorldData().getEyeFluidContact();
            if (!this.player.isBedrockMovement()
                    && result.getSimulationContext().getVersion().isNewerThanOrEquals(ClientVersion.V_26_1)
                    && commit.carry() instanceof JavaPredictionCarry carried
                    && !carried.states().isEmpty())
                this.lastWorldEyeFluidContact = carried.states().stream()
                        .mapToInt(JavaPredictionCarry.State::playerEyeFluidContact)
                        .reduce(0, (a, b) -> a | b);
            this.carriedPush = CarriedPush.IDENTITY;
            this.rideTicksSinceLastPrediction = false;
            this.pushesWhileLoadingLevel = null;
            this.ticksWhileLoadingLevel = 0;
            this.lastXRot = xRot;
            this.lastYRot = yRot;
            this.commitSprintOutcomes(result);
            if (!this.player.isBedrockMovement()) this.commitClientFrost(result, to);
            this.player.minPlayerAttackSlow = 0;
            this.player.maxPlayerAttackSlow = 0;
            if (this.player.isFlying) {
                this.lastFlying.reset();
            }

            this.lastSneaking = this.player.isSneaking;
            this.lastGliding = this.player.isGliding;
            if (!this.player.isBedrockMovement()) this.endSpinAttack(result);
            this.player.packetStateData.riptideLevel = 0;
            this.player.packetStateData.riptideAlternativeLevels = List.of();
            this.player.packetStateData.riptideReleaseCertain = false;
            this.player.riptideSpinAttackTicks = Math.max(0, this.player.riptideSpinAttackTicks - 1);
            this.player.compensatedEntities.fishingRodPulls.clear();
            this.player.compensatedEntities.ravagerRoars.clear();
            this.player.compensatedEntities.fishingPulls.clear();
            if (!this.player.isBedrockMovement()) {
                this.player.swimState.onClientTick(
                        result.getSimulationContext().getWorldData().isSwimming(), this.player.isFlying);
            }
            JavaPassAfterTravel posePass = JavaMovementEngine.passAfterTravel(result);
            if (posePass == null) this.player.refreshPlayerPose();
            else this.player.refreshPlayerPose(posePass.pose());
        } else {
            this.applyProfileCommit(preparedCommit);
        }
    }

    /**
     * LivingEntity#aiStep's removeFrost and tryAddFrost after the committed travel, for the local player or for the
     * vehicle it controls, at the travel's end and supporting block. {@link ClientFrost} has the versions that run it.
     */
    private void commitClientFrost(PredictionResult result, Vec3 to) {
        MainSupportingBlockData support =
                result.getSimulationContext().getWorldData().getMainSupportingBlockPos();
        PacketEntity vehicle = result.getSimulationContext().getVehicle();
        if (vehicle == null) {
            this.player.compensatedEntities.getSelf().updateClientFrost(to, support);
            return;
        }
        ClientMovementSpeed ridden = vehicle.riddenMovementSpeed();
        if (ridden != null
                && ClientFrost.replacedAfterTravel(this.player)
                && ridden.replaceClientFrost(this.player, to, support)) {
            vehicle.refreshRiddenMovementSpeed();
        }
    }

    /** The piston deltas the committed travel's block-entity pass left, from 26.3. */
    private void commitPassAfterTravel(PredictionResult result) {
        if (result.getValidMovements() == null || result.getValidMovements().getClosestTrace() == null) return;
        JavaPassAfterTravel pass = result.getValidMovements().getClosestTrace().passAfterTravel();
        if (pass != null) {
            PacketEntity vehicle = result.getSimulationContext().getVehicle();
            if (vehicle == null) this.pistonDeltas = pass.result().state().pistonDeltas();
            else {
                vehicle.blockEntityState = pass.result().state();
                vehicle.onGround = pass.result().state().onGround();
                if (pass.result().interpolation() != null)
                    vehicle.newPacketLocation = pass.result().interpolation();
            }
        }
    }

    /**
     * Ends the spin where LivingEntity#checkAutoSpinAttack sets autoSpinAttackTicks to 0. A tick that starts from the
     * rebound ran no check, as the last tick's hit ended the spin. A hit on a LivingEntity the travel certainly
     * touched ends it now and leaves the next tick only the rebound.
     */
    private void endSpinAttack(PredictionResult result) {
        this.certainSpinHit = false;
        if (this.player.riptideSpinAttackTicks <= 0) return;
        SimulationContext context = result.getSimulationContext();
        boolean spinStarted = this.player.packetStateData.riptideLevel > 0;
        PredVector start = result.getInitialStartingVel();
        if (!spinStarted
                && (context.getSpinRebound() == SpinRebound.ONLY
                        || start != null && start.hasReason(MovementModifiers.RIPTIDE_ATTACK))) {
            this.player.riptideSpinAttackTicks = 0;
            context.getWorldData().setSpinAttack(false, false);
            return;
        }
        var pass = JavaMovementEngine.passAfterTravel(result);
        var moved = result.getJavaCommittedMove();
        Vec3 travelEnd = pass != null ? pass.travel().position()
                : moved == null ? context.getEnd() : context.getStart().add(moved.movement());
        var entities = this.player.compensatedEntities;
        entities.beginMovementPositionReads();
        JavaSpinAttack.Hit hit;
        try {
            hit = JavaSpinAttack.check(this.player, context, travelEnd);
        } finally {
            entities.endMovementPositionReads();
        }
        var absent = JavaSpinAttack.Presence.ABSENT;
        var certain = JavaSpinAttack.Presence.CERTAIN;
        context.getWorldData().setSpinAttack(hit.living() != absent, hit.player());
        if (this.player.riptideSpinCertain && hit.living() == certain) {
            this.player.riptideSpinAttackTicks = 0;
            this.certainSpinHit = true;
        } else if (hit.living() != absent) {
            this.player.riptideSpinCertain = false;
        } else if (result.getCollideAxisData()
                instanceof ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision move) {
            // A non-living entity in the list suppresses the empty-list horizontal-collision branch too.
            if (move.horizontalCollision() && hit.anyEntity() == absent) this.player.riptideSpinAttackTicks = 0;
            else if (move.horizontalCollision() && hit.anyEntity() != certain) this.player.riptideSpinCertain = false;
        } else if (!this.certainlyMissesWalls(result)) {
            this.player.riptideSpinCertain = false;
        }
    }

    private boolean certainlyMissesWalls(PredictionResult result) {
        CollideAxisData collide = result.getCollideAxisData();
        return !result.getSimulationContext().isWithheldPosition()
                && this.player.isTickingReliablyFor(1)
                && collide != null
                && collide.getX() != null
                && collide.getZ() != null
                && !collide.couldCollideHorizontally();
    }

    /**
     * Keeps the sprint modifiers of the movement this commit carries on; a setback this tick returns to its anchor's
     * instead.
     */
    private void commitSprintOutcomes(PredictionResult result) {
        ClientSprintState state = this.player.sprintState;
        // The client ran this tick's aiStep and spin, whatever a setback restores below.
        switch (aiStepSprintClear(this.player, result)) {
            case TRUE -> state.onModifierCleared();
            case UNKNOWN -> state.onModifierPossiblyCleared();
            case FALSE -> {}
        }
        SetbackTeleportUtil setbacks = this.player.getSetbackTeleportUtil();
        if (setbacks.isPendingSetback() && setbacks.lastKnownGoodPosition != null) {
            state.restore(setbacks.lastKnownGoodPosition.getSprintOutcomes());
            state.settleReset();
            return;
        }
        if (!state.outcomeOpen()) return;
        state.commit(sprintOutcomesAfter(this.player, result));
    }

    /**
     * Whether the committed tick removed the sprint modifier with the flag clear, in aiStep or by the spin after
     * travel, over every accepted candidate's context: they all ran one tick, so one that disagrees leaves it possible.
     */
    private static DesyncStatus aiStepSprintClear(CultPlayer player, PredictionResult result) {
        if (result == null || result.getSimulationContext() == null) return DesyncStatus.FALSE;
        DesyncStatus cleared = null;
        if (result.getRealities() != null) {
            for (PredictionResult reality : result.getRealities()) {
                SimulationContext context = reality.getSimulationContext();
                if (reality.getFlagSeverity() != 0.0 || context == null) continue;
                DesyncStatus verdict = JavaAiStepSprint.clearsModifierThisTick(player, context);
                cleared = cleared == null || cleared == verdict ? verdict : DesyncStatus.UNKNOWN;
            }
        }
        if (cleared != null) return cleared;
        return JavaAiStepSprint.clearsModifierThisTick(player, result.getSimulationContext());
    }

    /**
     * The sprint modifier state after committing {@code result}: the modifiers its accepted candidates moved with, or,
     * when none was accepted, the one the committed candidate moved with.
     */
    public static ClientSprintState.Outcomes sprintOutcomesAfter(CultPlayer player, PredictionResult result) {
        ClientSprintState state = player.sprintState;
        if (result == null || !state.outcomeOpen()) return state.outcomes();
        boolean[] used = new boolean[2];
        for (PredictionResult reality : result.getRealities()) {
            if (reality.getFlagSeverity() == 0.0) addSprintOutcome(player, reality, used);
        }
        if (!used[0] && !used[1]) addSprintOutcome(player, result, used);
        if (!used[0] && !used[1]) return state.outcomes();
        return state.outcomesAfter(used[0], used[1]);
    }

    private static void addSprintOutcome(CultPlayer player, PredictionResult candidate, boolean[] used) {
        if (candidate.getInitialStartingVel() == null || candidate.getSimulationContext() == null) return;
        switch (candidate.getSimulationContext().getSprintOutcome(player, candidate.getInitialStartingVel())) {
            case CANONICAL -> used[0] = true;
            case HELD -> used[1] = true;
            default -> used[0] = used[1] = true;
        }
    }

    /** Each candidate once per sprint modifier it can travel with while a possible attack reset leaves it open. */
    private static List<PredVector> withSprintOutcomes(
            CultPlayer player, SimulationContext context, List<PredVector> candidates) {
        if (context.getVehicle() != null || !player.sprintState.outcomeOpen()) return candidates;
        List<PredVector> split = new ArrayList<>(candidates.size() * 2);
        for (PredVector candidate : candidates) {
            for (ClientSprintState.Outcome outcome : player.sprintState.outcomesFor(candidate.getAttackSlows())) {
                split.add(candidate.withSprintOutcome(outcome));
            }
        }
        return split;
    }

    private void applyProfileCommit(PredictionCommit commit) {
        if (commit == null) {
            this.validPlayerStartingVels.clear();
            this.profileCarry = null;
        } else {
            Set<Vec3> velocities = commit.startingVelocities();
            if (!(this.validPlayerStartingVels instanceof ReusableVelocitySet reusable
                    && reusable.refill(velocities))) {
                this.validPlayerStartingVels = new ReusableVelocitySet(velocities);
            }
            this.profileCarry = commit.carry();
        }
    }

    /**
     * The collision flags a pass replayed since the last read left for the next LocalPlayer#aiStep, or null when none
     * moved the local player; reading clears it.
     */
    public DesyncStatus takeHardCollisionAfterPass() {
        DesyncStatus hard = this.hardCollisionAfterPass;
        this.hardCollisionAfterPass = null;
        return hard;
    }

    /** ClientPacketListener#applyPlayerInfoUpdate -> LocalPlayer#onGameModeChanged, at the packet proof. */
    public void applyPlayerInfoSpectatorReset(long sequence) {
        var self = this.player.compensatedEntities.getSelf();
        // The travel actor may be a vehicle; the packet always writes the local player, never its mount.
        if (self.getRiding() == null) {
            this.validPlayerStartingVels = withZeroY(this.validPlayerStartingVels);
        }
        if (this.profileCarry instanceof JavaPredictionCarry carry && carry.actor() == self)
            this.profileCarry = carry.withVelocityY(0);
        if (this.startingVelsBeforeRide != null) this.startingVelsBeforeRide = withZeroY(this.startingVelsBeforeRide);
        this.passengerRideTick.setMotionY(0);
        self.deltaMovement = self.deltaMovement.with(ac.cult.cultac.protocol.value.Direction.Axis.Y, 0);
        // Pending packet modifiers are a decomposition of that same delta. Zero only the contributions already
        // written, keeping their identities for AntiKB.
        this.player.checkManager.getKnockbackHandler().resetVerticalBefore(sequence);
        this.player.checkManager.getExplosionHandler().resetVerticalBefore(sequence);
        this.player.compensatedEntities.fishingPulls.resetVerticalBefore(self, sequence);
        this.player.compensatedEntities.ravagerRoars.resetVerticalBefore(self, sequence);
    }

    private static Set<Vec3> withZeroY(Set<Vec3> velocities) {
        Set<Vec3> next = new HashSet<>();
        for (var velocity : velocities)
            next.add(velocity instanceof PredVector vector
                    ? vector.withY(0, "PlayerInfo spectator")
                    : velocity.with(ac.cult.cultac.protocol.value.Direction.Axis.Y, 0));
        return next;
    }

    public void seedVehicleStartingVelocity(Vec3 velocity) {
        this.seedStartingVelocity(velocity);
    }

    public void seedStartingVelocity(Vec3 velocity) {
        this.seedStartingVelocities(List.of(velocity));
    }

    public void seedStartingVelocities(Collection<Vec3> velocities) {
        this.validPlayerStartingVels = new HashSet<>(velocities);
    }

    /**
     * Carries a tick the client ran for the vehicle it controls without reporting it (JavaRiddenBoatTick): its
     * complete end states start the next tick, whose ground they state, and its move consumed the stuck speed the
     * last reported tick required.
     */
    public void carryUnreportedVehicleTick(JavaPredictionCarry carry, DesyncStatus onGround) {
        this.profileCarry = carry;
        this.validPlayerStartingVels = new HashSet<>();
        for (var state : carry.states()) this.validPlayerStartingVels.add(state.velocity());
        this.lastOnGround = onGround;
        this.rideTicksSinceLastPrediction = true;
    }

    /** A passenger packet changes the simulated actor, but does not consume the player's on-foot motion. */
    public void beginRide() {
        this.startingVelsBeforeRide = new HashSet<>(this.validPlayerStartingVels);
        this.passengerRideTick.begin(this.player, this.profileCarry, this.lastOnGround);
        // Entity#startRiding changes the pose immediately, before the first passenger tick.
        this.player.refreshPlayerPose(ac.cult.cultac.utils.enums.Pose.STANDING);
    }

    /** Records whether this client tick ran the local player's Entity#rideTick. */
    public void recordRideTick() {
        this.rideTicked =
                !this.player.isBedrockMovement() && this.player.compensatedEntities.playerEntity.getRiding() != null;
        if (!this.rideTicked) {
            if (!this.player.isBedrockMovement()) this.passengerRideTick.offVehicle(this.player, this.lastPrediction);
            return;
        }
        this.startingVelsBeforeRide = null;
        this.player.riptideSpinCertain = false;
        this.passengerRideTick.record(this.player);
    }

    /** Whether the current ride has executed Entity#rideTick, rather than only passenger packet handlers. */
    public boolean hasRideTick() {
        return this.rideTicked;
    }

    /**
     * LocalPlayer#getDeltaMovement while it rides, which Entity#getKnownMovement hands a vehicle it controls: what the
     * last Entity#rideTick left, or before the first one the on-foot motion it mounted with.
     */
    public List<Vec3> riderKnownMovements() {
        if (this.rideTicked) {
            List<Vec3> velocities = this.passengerRideTick.knownMovements();
            if (!velocities.isEmpty()) return velocities;
        }
        if (this.startingVelsBeforeRide != null && !this.startingVelsBeforeRide.isEmpty())
            return List.copyOf(this.startingVelsBeforeRide);
        return List.of(this.player.compensatedEntities.getSelf().deltaMovement);
    }

    /** A set_entity_motion of the local passenger sets the delta it rides with and a dismount keeps. */
    public void replaceRideTickVelocity(Vec3 velocity) {
        if (this.rideTicked) this.passengerRideTick.setMotion(velocity);
        else if (this.startingVelsBeforeRide != null) this.startingVelsBeforeRide = new HashSet<>(List.of(velocity));
    }

    /** Seeds the delta movements the last Entity#rideTick can have left, which a dismount with no velocity resync keeps. */
    public void seedRideTickVelocity() {
        if (!this.rideTicked) {
            if (this.startingVelsBeforeRide != null) this.seedStartingVelocities(this.startingVelsBeforeRide);
            return;
        }
        List<Vec3> velocities = this.passengerRideTick.velocities();
        if (velocities.isEmpty()) return;
        this.player.compensatedEntities.playerEntity.deltaMovement = velocities.get(0);
        this.seedStartingVelocities(velocities);
    }

    /** Carries what the last ride tick can have left once the player leaves its vehicle; with none, the on-foot carry stays. */
    public void endRide() {
        this.startingVelsBeforeRide = null;
        if (!this.rideTicked) return;
        this.rideTicked = false;
        this.rideTicksSinceLastPrediction = true;
        JavaPredictionCarry carry = this.passengerRideTick.carry(this.player, this.validPlayerStartingVels);
        this.profileCarry = carry;
        this.lastWorldEyeFluidContact = carry.states().stream()
                .mapToInt(JavaPredictionCarry.State::playerEyeFluidContact).reduce(0, (a, b) -> a | b);
        this.lastOnGround = this.passengerRideTick.onGround();
        this.player.onGround = this.lastOnGround.determinePessimistically();
    }

    public static int nextDebugIdentifier() {
        return flags.getAndIncrement() % 128 + 1;
    }

    private void handlePossibleRealities(PredictionResult result) {
        SimpleCollisionBox valid = null;
        if (this.lastPrediction != null) {
            valid = this.lastPrediction.getRealitiesExtent();
            if (valid != null) {
                float blockFriction = BlockProperties.getMaterialFriction(
                        result.getSimulationContext().getWorldData().getOnBlock());
                double trueFriction =
                        result.getSimulationContext().getLastOnGround().determinePessimistically()
                                ? blockFriction * 0.91
                                : 0.91;
                if (result.getSimulationContext().getWorldData().isInLava()) {
                    trueFriction = 0.5;
                }

                if (result.getSimulationContext().getWorldData().mayBeInWater()) {
                    trueFriction = 0.96;
                }

                if (result.getSimulationContext().usesFallFlyingMovement()) {
                    trueFriction = 0.99;
                }

                valid = new SimpleCollisionBox(
                        valid.minX * trueFriction,
                        0.0,
                        valid.minZ * trueFriction,
                        valid.maxX * trueFriction,
                        0.0,
                        valid.maxZ * trueFriction);
            }
        }

        for (PredictionResult reality : result.getRealities()) {
            if (reality.getInitialStartingVel().packetModifiersLength() != 0) {
                SimpleCollisionBox knockbackVel =
                        new SimpleCollisionBox(reality.getInitialStartingVel(), reality.getInitialStartingVel());
                if (valid == null) {
                    valid = knockbackVel;
                } else {
                    valid = valid.union(knockbackVel);
                }
            }
        }

        result.setRealitiesExtent(valid);
    }

    /**
     * The accepted travel that ends within LocalPlayer#sendPosition's movement threshold of xLast, the best one if it
     * does, else the one ending nearest; a travel ending farther away would have sent its position.
     */
    static PredictionResult travelWithinMovementThreshold(
            PredictionResult travelled, Vec3 position, Vec3 sent, double threshold) {
        double nearestSqr = threshold * threshold;
        if (sent.distanceToSqr(position.add(travelled.getAcceptedClosestToTarget())) <= nearestSqr) {
            return travelled;
        }
        PredictionResult nearest = null;
        List<PredictionResult> realities = travelled.getRealities();
        for (int i = 0, size = realities.size(); i < size; i++) {
            PredictionResult reality = realities.get(i);
            if (reality.getFlagSeverity() != 0.0) continue;
            double distanceSqr = sent.distanceToSqr(position.add(reality.getAcceptedClosestToTarget()));
            if (distanceSqr <= nearestSqr) {
                nearestSqr = distanceSqr;
                nearest = reality;
            }
        }
        return nearest;
    }

    /** A hypothetical tick that stays at {@code withPos}, testing a player who stopped sending ticks. */
    public PredictionResult predictStationaryTick(Vec3 withPos, float xRot, float yRot) {
        this.predictingStationaryTick = true;
        try {
            return this.callPrediction(
                    Vec3.ZERO, withPos, withPos, true, xRot, yRot, null, MovementProfiles.forPlayer(this.player));
        } finally {
            this.predictingStationaryTick = false;
        }
    }

    /** Whether the prediction running now is {@link #predictStationaryTick}'s, which no client tick ran. */
    public boolean isPredictingStationaryTick() {
        return this.predictingStationaryTick;
    }

    private PredictionResult advanceNoPositionClientTick(Vec3 position, float xRot, float yRot) {
        // sendPosition emits StatusOnly if its cached ground bit changes. With no movement packet,
        // travel's reported ground is still that cache, even if last tick's later piston move cleared
        // the actor's actual onGround. The start of travel remains this.lastOnGround in the context.
        boolean reportedOnGround = this.player.packetStateData.packetPlayerOnGround;
        this.player.onGround = reportedOnGround;
        boolean flyingThroughTravel = this.keepsFlyingThroughTravel(this.player.onGround);
        // LocalPlayer#sendPosition withholds a move within 2.0E-4 of xLast; no travel reaches one past the guard.
        Vec3 sent = this.player.packetStateData.lastSentTickPosition;
        Vec3 end = sent == null || sent.equals(position) || sent.distanceToSqr(position) > TRAVEL_FLAG_DISTANCE_SQR
                ? position
                : sent;
        Vec3 movement = end == position ? Vec3.ZERO : end.subtract(position);
        MovementProfile movementProfile = MovementProfiles.forPlayer(this.player);
        PredictionResult result = this.callPrediction(movement, position, end, true, xRot, yRot, null, movementProfile);
        this.handlePossibleRealities(result);
        this.player.compensatedEntities.vehicles.consumeVehicleSwitchPredictionOffset(result);
        boolean exactWithheldTravel = false;
        if (!result.isExempt() && result.getFlags().isEmpty()) {
            Vec3 physical = JavaWithheldTravel.uniquePosition(
                    this.player, result, sent == null ? position : sent, this.player.getMovementThreshold());
            if (physical != null && !physical.equals(position)) {
                Vec3 physicalMovement = physical.subtract(position);
                PredictionResult travelled = this.callPrediction(
                        physicalMovement, position, physical, false, xRot, yRot, null, movementProfile);
                if (!travelled.isExempt() && travelled.getFlags().isEmpty()) {
                    result = travelled;
                    movement = physicalMovement;
                    end = physical;
                    exactWithheldTravel = true;
                    this.handlePossibleRealities(result);
                }
            }
        }
        if (result.getFlags().isEmpty() && !result.isExempt()) {
            PredictionResult committed = result;
            if (end != position) {
                if (!exactWithheldTravel) {
                    // The withheld position lies anywhere within the threshold; the client is where it travelled.
                    PredictionResult travelled =
                            this.callPrediction(movement, position, end, false, xRot, yRot, null, movementProfile);
                    PredictionResult withheld = travelled.getFlags().isEmpty() && !travelled.isExempt()
                            ? travelWithinMovementThreshold(
                                    travelled, position, sent, this.player.getMovementThreshold())
                            : null;
                    if (withheld != null) {
                        this.handlePossibleRealities(withheld);
                        committed = withheld;
                        movement = withheld.getAcceptedClosestToTarget();
                        end = position.add(movement);
                    }
                }
                this.commitPlayerPosition(end);
            }
            this.callPredictionEndListeners(committed, null);
            this.commitPredictionState(committed, end, movement, xRot, yRot, movementProfile);
            this.lastOnGround = DesyncStatus.fromBoolean(reportedOnGround);
        } else if (!result.isExempt() && result.hasEffectiveFlags()) {
            // LocalPlayer#sendPosition withholds a position only within 2.0E-4 of xLast and before the 20th tick, so
            // this tick ended there. Exact forward input can expose the violation as geometric offset as well
            // as InputKeys; every failed invariant must reach the normal listeners, just like a sent move.
            if (end != position) this.commitPlayerPosition(end);
            this.prepareFlaggedPrediction(result, movementProfile.shouldCreateVerboseLog(this.player, result));
            this.callPredictionEndListeners(result, null);
            this.commitPredictionState(result, end, movement, xRot, yRot, movementProfile);
            this.lastOnGround = DesyncStatus.fromBoolean(
                    result.getDesiredOnGround() == null ? reportedOnGround : result.getDesiredOnGround());
        } else if (result.isExempt()) {
            // An exempt tick still ran LocalPlayer#tick, so it commits its simulated travel as a sent exempt move does.
            if (end != position) this.commitPlayerPosition(end);
            this.callPredictionEndListeners(result, null);
            this.commitPredictionState(result, end, movement, xRot, yRot, movementProfile);
            this.lastOnGround = DesyncStatus.fromBoolean(reportedOnGround);
        }
        if (flyingThroughTravel) {
            this.stopFlyingAfterTravel();
        }

        return result;
    }

    // LocalPlayer#aiStep stops flight after a grounded travel; any other stop is the double-jump toggle before it.
    private boolean keepsFlyingThroughTravel(boolean onGround) {
        if (!this.player.packetStateData.flyingStopAfterTravel) return false;
        if (onGround && !FlightToggle.stopsBeforeTravel(this.player)) return true;
        this.player.packetStateData.flyingStopAfterTravel = false;
        this.player.isFlying = false;
        return false;
    }

    private void stopFlyingAfterTravel() {
        this.player.packetStateData.flyingStopAfterTravel = false;
        this.player.isFlying = false;
        this.player.refreshPlayerPose();
    }

    private PredictionResult callPrediction(
            Vec3 target,
            Vec3 from,
            Vec3 to,
            boolean testing,
            float xRot,
            float yRot,
            AuthoredMovementFrame authoredMovementFrame,
            MovementProfile movementProfile) {
        PredictionResult result = this.getPredictionResult(
                this.player,
                this.validPlayerStartingVels,
                this.lastPrediction,
                target,
                from,
                to,
                testing,
                xRot,
                yRot,
                authoredMovementFrame,
                movementProfile);
        if (this.isExempt(movementProfile, result.getSimulationContext())) {
            result.exempt();
        }

        return result;
    }

    private boolean isDesync(PredictionResult result) {
        if (this.lastMovementWasSetback && result.getInitialStartingVel().isJump()) {
            boolean onGround = this.player.isBedrockMovement()
                    ? BedrockSetbackJumpGuard.hasSupportAtStart(this.player, result)
                    : Collisions.collide(this.player, 0.0, -1.0E-7, 0.0).y == 0.0;
            if (!onGround) {
                this.logDesyncTrace("T0");
                return true;
            }
        }

        if (this.player.getSetbackTeleportUtil().tooFarFromUnloadedChunk()) {
            this.logDesyncTrace("T1");
            return true;
        }

        if (this.player.getSetbackTeleportUtil().blockOffsets) {
            SetbackTeleportUtil setbackUtil = this.player.getSetbackTeleportUtil();
            boolean awaitingSetback = setbackUtil.isPendingSetback();
            if (!awaitingSetback) {
                setbackUtil.executeForceResync("offsets");
                this.logDesyncTrace("T2 | " + awaitingSetback);
            }

            return true;
        } else {
            return false;
        }
    }

    private void logDesyncTrace(String marker) {
        if (this.player.getSetbackTeleportUtil().isDebug()) {
            LogUtil.warn(this.player.getName() + " : " + marker);
        }
    }

    private void dumpRecentFlagsWhenDebugging(PredictionResult prediction) {
        if (this.player.getSetbackTeleportUtil().isDebug()) {
            LogUtil.info("Recent flags: (" + prediction.getFlagSeverity() + ") ");

            for (Flag entry : prediction.getFlags()) {
                String verboseText = entry.getVerbose().getString();
                LogUtil.info(entry.getCheck().getCheckName() + ": severity=" + entry.getSeverity() + " verbose="
                        + verboseText);
            }
        }
    }

    private void callPredictionEndListeners(PredictionResult result) {
        this.callPredictionEndListeners(result, null);
    }

    private void callPredictionEndListeners(PredictionResult result, PredictionCommit preparedCommit) {
        this.player.compensatedEntities.vehicles.consumeVehicleSwitchPredictionOffset(result);
        PredictionComplete complete = new PredictionComplete(result, preparedCommit);
        this.player.checkManager.onPredictionFinish(complete);
    }

    public boolean isExempt() {
        MovementProfile movementProfile = MovementProfiles.forPlayer(this.player);
        return this.isExempt(movementProfile, null);
    }

    private boolean isExempt(MovementProfile movementProfile, SimulationContext context) {
        return this.isMovementModeExempt(movementProfile, context)
                || this.player.getSetbackTeleportUtil().shouldBlockMovement();
    }

    private boolean isMovementModeExempt(MovementProfile movementProfile, SimulationContext context) {
        boolean flyingExempt = movementProfile.usesFlyingExemption(this.player)
                && (this.player.isFlying
                        || this.lastFlying.hasOccurredSince(2)
                        || movementProfile.startsFlyingExemptionThisFrame(this.player, context));
        return flyingExempt || this.player.gamemode == GameMode.SPECTATOR;
    }

    public void handleRespawn() {
        this.lastOnGround = DesyncStatus.FALSE;
        this.lastFlying.setRaw(20);
        this.lastSneaking = false;
        this.lastGliding = false;
        this.player.packetStateData.bedrockTranslatedMovement.clear();
        if (this.player.bedrockState != null) {
            this.lastMovementWasSetback = false;
        }
        // A Java respawn is not a Bedrock LocalPlayer construction boundary.
        // Bedrock metadata and position resets arrive through their own ordered
        // bridge events. The persistent camera and water components survive.
        if (!this.player.isBedrockMovement()) {
            this.bedrockSleepingStateObserved = false;
            this.profileCarry = null;
            // Respawn constructs a fresh LocalPlayer, even when its synchronized entity data is retained.
            this.lastWorldEyeFluidContact = 0;
            // A respawn constructs a new LocalPlayer, whose Entity#pistonDeltas start empty.
            this.pistonDeltas = ClientPistonDeltas.NONE;
        }
    }

    public PredictionResult getPredictionResult(
            CultPlayer player,
            Set<Vec3> startingVel,
            PredictionResult lastPrediction,
            Vec3 target,
            Vec3 start,
            Vec3 end,
            boolean testing,
            float xRot,
            float yRot) {
        return this.getPredictionResult(
                player,
                startingVel,
                lastPrediction,
                target,
                start,
                end,
                testing,
                xRot,
                yRot,
                null,
                MovementProfiles.forPlayer(player));
    }

    public PredictionResult getPredictionResult(
            CultPlayer player,
            Set<Vec3> startingVel,
            PredictionResult lastPrediction,
            Vec3 target,
            Vec3 start,
            Vec3 end,
            boolean testing,
            float xRot,
            float yRot,
            AuthoredMovementFrame authoredMovementFrame,
            MovementProfile movementProfile) {
        var previousFluidOrder = player.clientFluidTagOrder;
        // A time-based border lerp makes every collision read the clock, so a memoised one could go stale.
        stepCollisionMemo.open(!player.isBedrockMovement()
                && player.checkManager.getWorldBorder().hasClockIndependentDiameter());
        player.compensatedEntities.beginMovementPositionReads();
        try {
            return predict(
                    player,
                    startingVel,
                    lastPrediction,
                    target,
                    start,
                    end,
                    testing,
                    xRot,
                    yRot,
                    authoredMovementFrame,
                    movementProfile);
        } finally {
            player.compensatedEntities.endMovementPositionReads();
            stepCollisionMemo.close();
            player.clientFluidTagOrder = previousFluidOrder;
        }
    }

    /** The distinct travels per simulation context whose debug allowance gap is read as velocity too. */
    private static final int DEBUG_READ_TRAVELS = 4;

    /**
     * The debug allowance's gap may have been deltaMovement, and the allowance can make several travels fit. The
     * distinct travels it moved that were nearest the report before it ran, a few per simulation context, read their
     * gap as velocity too, even one a check flagged (a NoFall a landing reading would clear), and those readings
     * compete as candidates. Every family that explains the report as well as the winner is carried with it: the
     * allowance cannot tell them apart.
     */
    private PredictionResult withDebugVelocityReadings(
            MovementEngine engine,
            PredictionResult best,
            PredictionResult lastPrediction,
            List<PredictionResult> alternativeRealities,
            List<PredictionResult> invalidOrValidRealities) {
        var travels = new ArrayList<PredictionResult>();
        for (PredictionResult candidate : invalidOrValidRealities)
            if (candidate.getValidMovements().readsDebugVelocity(invalidOrValidRealities)) travels.add(candidate);
        travels.sort(java.util.Comparator.comparingDouble(PredictionResult::getOffset)
                .thenComparingDouble(PredictionResult::selectionOffset)
                .thenComparingDouble(PredictionResult::getFlagSeverity));
        var perContext = new java.util.IdentityHashMap<SimulationContext, Integer>();
        for (PredictionResult candidate : travels) {
            if (perContext.merge(candidate.getSimulationContext(), 1, Integer::sum) > DEBUG_READ_TRAVELS) continue;
            for (PredictionResult reading : candidate.getValidMovements().debugVelocityReadings()) {
                engine.evaluateCandidate(this.player, reading);
                this.flagger.handleResult(reading, lastPrediction);
                if (reading.getFlagSeverity() == 0.0) alternativeRealities.add(reading);
                invalidOrValidRealities.add(reading);
                if (engine.isBetterCandidate(reading, best)) best = reading;
            }
        }
        if (best.getFlagSeverity() != 0.0) return best;
        // PredictionResult's equality reads its candidate lists, which hold it: collect by identity.
        var carried = new ArrayList<PredictionResult>();
        addAllOnce(carried, best.getValidMovements().debugFamily());
        for (PredictionResult candidate : invalidOrValidRealities) {
            if (candidate.getFlagSeverity() != 0.0
                    || candidate.getOffset() > best.getOffset() + 1.0E-9
                    || candidate.selectionOffset() > best.selectionOffset() + 1.0E-9) continue;
            List<PredictionResult> family = candidate.getValidMovements().debugFamily();
            addAllOnce(carried, family.isEmpty() ? List.of(candidate) : family);
        }
        // Candidates that moved the same travel in the same context commit the same states.
        var distinct = new ArrayList<PredictionResult>(carried.size());
        for (PredictionResult candidate : carried)
            if (candidate == best || !sameTravelEarlier(carried, candidate)) distinct.add(candidate);
        if (distinct.size() > 1) best.getValidMovements().carryDebugReadings(List.copyOf(distinct));
        return best;
    }

    private static boolean sameTravelEarlier(List<PredictionResult> carried, PredictionResult candidate) {
        for (PredictionResult other : carried) {
            if (other == candidate) return false;
            if (other.getSimulationContext() == candidate.getSimulationContext()
                    && other.getValidMovements().sameTravel(candidate.getValidMovements())) return true;
        }
        return false;
    }

    private static void addAllOnce(List<PredictionResult> into, List<PredictionResult> from) {
        for (PredictionResult result : from) {
            boolean present = false;
            for (PredictionResult other : into) present |= other == result;
            if (!present) into.add(result);
        }
    }

    /** Replays and all candidates for one accepted Java/Bedrock tick share the same debug choice. */
    private boolean shouldApplyDebugUncertainty() {
        Vec3 radius = this.player.debugUncertainty;
        if (radius.x == 0 && radius.y == 0 && radius.z == 0) return false;
        double chance = this.player.debugUncertaintyChancePercent;
        if (chance >= 100.0D) return true;
        if (chance <= 0.0D) return false;
        int tick = this.player.packetStateData.acceptedClientTick;
        if (this.debugUncertaintyTick != tick) {
            this.debugUncertaintyTick = tick;
            this.debugUncertaintyOnTick = ThreadLocalRandom.current().nextDouble(100.0D) < chance;
        }
        return this.debugUncertaintyOnTick;
    }

    private PredictionResult predict(
            CultPlayer player,
            Set<Vec3> startingVel,
            PredictionResult lastPrediction,
            Vec3 target,
            Vec3 start,
            Vec3 end,
            boolean testing,
            float xRot,
            float yRot,
            AuthoredMovementFrame authoredMovementFrame,
            MovementProfile movementProfile) {
        List<PredictionResult> alternativeRealities = new ArrayList<>();
        List<PredictionResult> invalidOrValidRealities = new ArrayList<>();
        List<ValidMovements> valid = new ArrayList<>();
        MovementEngine engine = MovementEngines.requireForProfile(movementProfile);
        boolean debugUncertainty = this.shouldApplyDebugUncertainty();

        for (SimulationContext curSimulationContext : this.createReleaseSimulationContexts(
                player,
                lastPrediction,
                target,
                start,
                end,
                testing,
                xRot,
                yRot,
                authoredMovementFrame,
                movementProfile)) {
            player.clientFluidTagOrder = curSimulationContext.getFluidTagOrder();
            List<PredVector> contextPacketStart = engine.applyModifiers(
                    new MovementModifiers(),
                    player,
                    startingVel,
                    curSimulationContext,
                    lastPrediction);
            for (JavaRiptideRelease.Prepared release : JavaSpinAttack.sprintContexts(
                    player,
                    JavaRiptideRelease.prepare(
                            player, this.worldStageBuilder, curSimulationContext, lastPrediction, contextPacketStart))) {
                SimulationContext releaseContext = release.context();
                List<PredVector> validInitialVelocities = withSprintOutcomes(
                        player,
                        releaseContext,
                        engine.startingVelocities(
                                new VelocityTransformer(releaseContext), player, release.velocities(), releaseContext));
                Vec3 sortTarget = releaseContext.getTarget();
                boolean java = !player.isBedrockMovement();
                TransactionVel receivedVelocity =
                        java ? unread(player.checkManager.getKnockbackHandler().secondBread) : null;
                TransactionVel receivedExplosion =
                        java ? unread(player.checkManager.getExplosionHandler().secondBread) : null;
                // Empty or single-candidate lists need no ordering and may be immutable.
                if (validInitialVelocities.size() > 1)
                    validInitialVelocities.sort((first, second) ->
                            compareStartingVelocities(first, second, sortTarget, receivedVelocity, receivedExplosion));

                for (int i = 0, size = validInitialVelocities.size(); i < size; i++) {
                    PredVector initialStartingVel = validInitialVelocities.get(i);
                    PredictionResult thisResult = new PredictionResult(
                            player,
                            initialStartingVel,
                            releaseContext,
                            releaseContext.getTarget(),
                            null,
                            alternativeRealities,
                            invalidOrValidRealities);
                    ValidMovements validMovement = engine.createValidMovements(
                                    thisResult.getInitialStartingVel(), player, thisResult, false)
                            .computeExtents(lastPrediction);
                    valid.add(validMovement);
                }
            }
        }

        PredictionResult bestResult = null;

        for (int i = 0, size = valid.size(); i < size; i++) {
            ValidMovements validMovements = valid.get(i);
            boolean allowStep = false;

            do {
                PredictionResult thisResult = validMovements.getResult();
                player.clientFluidTagOrder = thisResult.getSimulationContext().getFluidTagOrder();
                SimpleCollisionBox attemptedExtents = validMovements.getCollisionIgnoredMaxStartingVelExtents();
                boolean forward = engine == JavaMovementEngine.INSTANCE;
                if (forward) {
                    validMovements.computeCollisionIgnoredMovement(lastPrediction);
                    validMovements.applyForwardMove(lastPrediction);
                } else {
                    thisResult.setCollideAxisData(probeCollisions(
                            player,
                            engine,
                            thisResult,
                            attemptedExtents,
                            thisResult.getSimulationContext().getTarget(),
                            validMovements.isCanStep()));
                    validMovements.computeClosest(lastPrediction);
                }
                if (!player.isBedrockMovement() && !JavaSpinAttack.validSprintContext(player, thisResult)) break;
                if (debugUncertainty) {
                    if (forward) {
                        // Rank by how far off the candidate was before the allowance, which can make many fit.
                        engine.evaluateCandidate(player, thisResult);
                        thisResult.selectionOffset();
                    }
                    validMovements.applyDebugUncertainty();
                }
                engine.evaluateCandidate(player, thisResult);
                this.flagger.handleResult(thisResult, lastPrediction);
                bestResult = engine.isBetterCandidate(thisResult, bestResult) ? thisResult : bestResult;
                if (thisResult.getFlagSeverity() == 0.0) {
                    alternativeRealities.add(thisResult);
                }

                invalidOrValidRealities.add(thisResult);
                if (allowStep || forward) {
                    break;
                }

                CollideAxisData probedCollisions = thisResult.getCollideAxisData();
                allowStep = thisResult.getInitialStartingVel().maxUpStep(player) > 0.0
                        && (thisResult.getSimulationContext().getLastOnGround().determineOptimistically()
                                || probedCollisions.getYNeg() != null
                                        && probedCollisions.getYNeg().isLikelyCollide()
                                        && probedCollisions.getYNeg().getResult() + 0.001
                                                > thisResult
                                                        .getValidMovements()
                                                        .getCollisionIgnoredMaxStartingVelExtents()
                                                        .minY);
                if (allowStep) {
                    CollideAxisData stepEligibility = probedCollisions;
                    allowStep = stepEligibility.couldCollideHorizontally();
                }
                if (allowStep) {
                    thisResult = new PredictionResult(
                            player,
                            thisResult.getInitialStartingVel(),
                            thisResult.getSimulationContext(),
                            thisResult.getSimulationContext().getTarget(),
                            null,
                            alternativeRealities,
                            invalidOrValidRealities);
                    validMovements = engine.createValidMovements(
                                    thisResult.getInitialStartingVel(), player, thisResult, false)
                            .computeExtents(lastPrediction);
                    validMovements.setCanStep(true);
                }
            } while (allowStep);
        }

        if (debugUncertainty && engine == JavaMovementEngine.INSTANCE && bestResult != null)
            bestResult = this.withDebugVelocityReadings(
                    engine, bestResult, lastPrediction, alternativeRealities, invalidOrValidRealities);

        if (!alternativeRealities.contains(bestResult)) {
            alternativeRealities.add(bestResult);
        }

        assert bestResult != null;
        movementProfile.evaluatePredictionResult(player, bestResult);
        return bestResult;
    }


    private static int compareStartingVelocities(
            PredVector first, PredVector second, Vec3 target, TransactionVel velocity, TransactionVel explosion) {
        int order = Integer.compare(missing(first, velocity, explosion), missing(second, velocity, explosion));
        if (order != 0) return order;
        order = Integer.compare(first.packetModifiersLength(), second.packetModifiersLength());
        if (order != 0) return order;
        return Double.compare(first.distanceToSqr(target), second.distanceToSqr(target));
    }

    /** The bundled proof transaction precedes the next client tick, so that tick starts from the confirmed motion. */
    private static TransactionVel unread(TransactionVel confirmed) {
        return confirmed == null || confirmed.isConsumed() ? null : confirmed;
    }

    private static int missing(PredVector vector, TransactionVel velocity, TransactionVel explosion) {
        return (velocity != null && !vector.hasPacketModifier(velocity) ? 1 : 0)
                + (explosion != null && !vector.hasPacketModifier(explosion) ? 1 : 0);
    }

    /** Bedrock alone uses the shared runner collision probe. */
    private CollideAxisData probeCollisions(
            CultPlayer player,
            MovementEngine engine,
            PredictionResult result,
            SimpleCollisionBox attemptedExtents,
            Vec3 collisionTarget,
            boolean canStep) {
        SimulationContext context = result.getSimulationContext();
        return engine.probeCollisions(
                collisionProbe,
                player,
                context,
                attemptedExtents.minY,
                collisionTarget,
                context.getStart(),
                result.getInitialStartingVel(),
                attemptedExtents,
                canStep);
    }

    /**
     * The carried contexts, each split by TridentItem#releaseUsing's outcome on a release tick: the release spins
     * only when Entity#isInWaterOrRain holds, and a spin leaves onGround false for the tick's LivingEntity#aiStep. A
     * release that may hand over tridents of several riptide levels spins once per level.
     */
    private List<SimulationContext> createReleaseSimulationContexts(
            CultPlayer player,
            PredictionResult lastPrediction,
            Vec3 target,
            Vec3 start,
            Vec3 end,
            boolean testing,
            float xRot,
            float yRot,
            AuthoredMovementFrame authoredMovementFrame,
            MovementProfile movementProfile) {
        List<SimulationContext> contexts = this.createSimulationContexts(
                player,
                lastPrediction,
                target,
                start,
                end,
                testing,
                xRot,
                yRot,
                authoredMovementFrame,
                movementProfile);
        if (player.packetStateData.riptideLevel == 0
                || player.isBedrockMovement()
                || this.currentPredictionVehicle() != null) {
            return this.withEndedSpinContexts(
                    contexts,
                    player,
                    lastPrediction,
                    target,
                    start,
                    end,
                    testing,
                    xRot,
                    yRot,
                    authoredMovementFrame,
                    movementProfile);
        }
        boolean certain = player.packetStateData.riptideReleaseCertain;
        List<SimulationContext> released = new ArrayList<>(contexts.size() * 2);
        if (!certain) {
            for (SimulationContext context : contexts) context.setRiptideLevel(0);
            released.addAll(contexts);
        }
        // A spin context builds its own world: the spin leaves onGround false for the tick's LivingEntity#aiStep.
        player.packetStateData.riptideReleaseCertain = true;
        try {
            List<SimulationContext> spins = certain
                    ? contexts
                    : this.createSimulationContexts(
                            player,
                            lastPrediction,
                            target,
                            start,
                            end,
                            testing,
                            xRot,
                            yRot,
                            authoredMovementFrame,
                            movementProfile);
            for (SimulationContext spin : spins) {
                spin.setWaterMovementEfficiency(
                        getWaterMovementEfficiency(player.compensatedEntities.getSelf(), DesyncStatus.FALSE));
                released.add(spin);
            }
            // Each other trident the release may hand TridentItem#releaseUsing pushes at its own riptide level.
            for (int level : player.packetStateData.riptideAlternativeLevels) {
                for (SimulationContext spin : this.createSimulationContexts(
                        player,
                        lastPrediction,
                        target,
                        start,
                        end,
                        testing,
                        xRot,
                        yRot,
                        authoredMovementFrame,
                        movementProfile)) {
                    spin.setRiptideLevel(level);
                    spin.setWaterMovementEfficiency(
                            getWaterMovementEfficiency(player.compensatedEntities.getSelf(), DesyncStatus.FALSE));
                    released.add(spin);
                }
            }
        } finally {
            player.packetStateData.riptideReleaseCertain = certain;
        }
        return released;
    }

    /**
     * The contexts, split where the last tick's LivingEntity#checkAutoSpinAttack may have hit and this tick's may hit
     * too: a hit sets autoSpinAttackTicks to 0, so the rebound tick runs no spin check and travels with the sprint flag
     * the client sent, while a spin that hit nothing starts without the rebound and may hit this tick.
     */
    private List<SimulationContext> withEndedSpinContexts(
            List<SimulationContext> contexts,
            CultPlayer player,
            PredictionResult lastPrediction,
            Vec3 target,
            Vec3 start,
            Vec3 end,
            boolean testing,
            float xRot,
            float yRot,
            AuthoredMovementFrame authoredMovementFrame,
            MovementProfile movementProfile) {
        if (player.isBedrockMovement()
                || this.profileCarry instanceof JavaPredictionCarry carry && !carry.states().isEmpty()
                || lastPrediction == null
                || !lastPrediction.getSimulationContext().getWorldData().isSpinAttackTarget()) return contexts;
        List<SimulationContext> split = new ArrayList<>(contexts.size() * 2);
        boolean needsSplit = false;
        for (SimulationContext context : contexts) {
            if (context.getWorldData().isSpinAttackTarget()) {
                context.setSpinRebound(SpinRebound.NONE);
                needsSplit = true;
            }
            split.add(context);
        }
        if (!needsSplit) return contexts;
        int spinTicks = player.riptideSpinAttackTicks;
        player.riptideSpinAttackTicks = 0;
        try {
            List<SimulationContext> rebounds = this.createSimulationContexts(
                    player,
                    lastPrediction,
                    target,
                    start,
                    end,
                    testing,
                    xRot,
                    yRot,
                    authoredMovementFrame,
                    movementProfile);
            for (SimulationContext context : rebounds) context.setSpinRebound(SpinRebound.ONLY);
            split.addAll(rebounds);
        } finally {
            player.riptideSpinAttackTicks = spinTicks;
        }
        return split;
    }

    private List<SimulationContext> createSimulationContexts(
            CultPlayer player,
            PredictionResult lastPrediction,
            Vec3 target,
            Vec3 start,
            Vec3 end,
            boolean testing,
            float xRot,
            float yRot,
            AuthoredMovementFrame authoredMovementFrame,
            MovementProfile movementProfile) {
        if (!ClientFluidTagOrder.hasOverlap(player)
                || this.profileCarry instanceof JavaPredictionCarry carry
                        && !carry.states().isEmpty())
            return createFluidOrderContexts(
                    player,
                    lastPrediction,
                    target,
                    start,
                    end,
                    testing,
                    xRot,
                    yRot,
                    authoredMovementFrame,
                    movementProfile);
        var contexts = new ArrayList<SimulationContext>();
        for (var order : ClientFluidTagOrder.candidates(player)) {
            try (var ignored = ClientFluidTagOrder.use(player, order)) {
                contexts.addAll(createFluidOrderContexts(
                        player,
                        lastPrediction,
                        target,
                        start,
                        end,
                        testing,
                        xRot,
                        yRot,
                        authoredMovementFrame,
                        movementProfile));
            }
        }
        return contexts;
    }

    private List<SimulationContext> createFluidOrderContexts(
            CultPlayer player,
            PredictionResult lastPrediction,
            Vec3 target,
            Vec3 start,
            Vec3 end,
            boolean testing,
            float xRot,
            float yRot,
            AuthoredMovementFrame authoredMovementFrame,
            MovementProfile movementProfile) {
        if (!player.isBedrockMovement()
                && JavaMovementEngine.supportsExactEffects(player.getClientVersion())
                && this.profileCarry
                        instanceof ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry carry
                && !carry.states().isEmpty()
                && carry.actor()
                        == (currentPredictionVehicle() == null
                                ? player.compensatedEntities.getSelf()
                                : currentPredictionVehicle())) {
            JavaPredictionCarry carried = carry;
            List<SimulationContext> contexts = new ArrayList<>();
            PacketEntity vehicle = this.currentPredictionVehicle();
            var firstAnchor = carried.states().getFirst().pistonStart();
            Vec3 sharedAnchorPosition = firstAnchor == null ? null : firstAnchor.position();
            boolean sharedVehicleStart = vehicle != null
                    && vehicle.clientPhysicalPositionExact
                    && sharedAnchorPosition != null
                    && carried.states().stream()
                            .allMatch(state -> state.pistonStart() != null
                                    && state.pistonStart().position().equals(sharedAnchorPosition));
            // Every state's context is built from the same inputs before the state is applied to it.
            CarryWorld world = null;
            boolean worldSpinRebound = false;
            for (var state :
                    this.fluidOrderStates(this.movementPatchStates(this.fallRangeStates(carried.states(), start)))) {
                JavaPredictionCarry.PistonStart anchored = state.pistonStart();
                // Packet processing/interpolation placed a shared vehicle start before travel.
                // Preserve distinct physical alternatives; a common old anchor must not undo that placement.
                if (sharedVehicleStart)
                    anchored = new JavaPredictionCarry.PistonStart(
                            start, anchored.onGround(), anchored.support(), anchored.pistonDeltas());
                Vec3 stateStart = anchored == null ? start : anchored.position();
                CarryWorld stateWorld = anchored == null && state.spinRebound() == worldSpinRebound ? world : null;
                if (state.fluidTagOrder() != null) stateWorld = null;
                if (stateWorld != null
                        && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1)
                        && stateWorld.world().isUnderwater()
                                != ClientFluidQueries.eyeContactInWater(player, state.playerEyeFluidContact()))
                    stateWorld = null;
                SimulationContext context = this.createSimulationContext(
                        player,
                        lastPrediction,
                        target.add(start.subtract(stateStart)),
                        stateStart,
                        end,
                        testing,
                        xRot,
                        yRot,
                        authoredMovementFrame,
                        movementProfile,
                        false,
                        this.currentPredictionVehicle(),
                        stateWorld,
                        anchored,
                        new JavaPredictionCarry(carry.actor(), state.fallDistance(), List.of(state)));
                if (anchored == null && world == null && carried.states().size() > 1) {
                    world = CarryWorld.of(context);
                    worldSpinRebound = state.spinRebound();
                }
                context.setMovementPatch(state.movementPatch());
                Vec3 stuck =
                        player.boatData.adjustStuckSpeedMultiplierForCurrentTick(player, context, state.stuckSpeed());
                context.setRequiredCurrentMoveStuckSpeed(stuck);
                context.handleLastRequiredStuckSpeed(stuck);
                contexts.add(context);
            }
            return contexts;
        }
        boolean carryLastRequiredStuckSpeed = movementProfile.usesJavaEntityMoveStuckSpeed(player)
                && this.shouldCarryLastRequiredStuckSpeed(lastPrediction);
        SimulationContext primary = this.createSimulationContext(
                player,
                lastPrediction,
                target,
                start,
                end,
                testing,
                xRot,
                yRot,
                authoredMovementFrame,
                movementProfile,
                carryLastRequiredStuckSpeed);
        if (!player.isBedrockMovement() && player.getClientVersion() == ClientVersion.V_1_21_9) {
            primary.setMovementPatch(JavaMovementPatch.V_1_21_9);
            SimulationContext patched = this.createSimulationContext(
                    player,
                    lastPrediction,
                    target,
                    start,
                    end,
                    testing,
                    xRot,
                    yRot,
                    authoredMovementFrame,
                    movementProfile,
                    carryLastRequiredStuckSpeed,
                    null,
                    CarryWorld.of(primary));
            patched.setMovementPatch(JavaMovementPatch.V_1_21_10);
            return List.of(primary, patched);
        }
        return List.of(primary);
    }

    /**
     * A state carrying a fall distance range runs once, on its low end, unless its ends move differently: the range
     * reaches across PowderSnowBlock#getCollisionShape's 2.5 with powder snow in reach of this travel, or a sneaking
     * player's Player#isAboveGround, which lets Player#maybeBackOffFromEdge run, holds at one end and not the other.
     * Then each end runs.
     */
    private List<JavaPredictionCarry.State> fallRangeStates(List<JavaPredictionCarry.State> states, Vec3 start) {
        List<JavaPredictionCarry.State> split = null;
        for (int i = 0; i < states.size(); i++) {
            var state = states.get(i);
            if (!(state.fallRangeMovesDifferently() && powderSnowInReach(state, start))
                    && !edgeBackoffReadsFallRange(state, start)) {
                if (split != null) split.add(state);
                continue;
            }
            if (split == null) split = new ArrayList<>(states.subList(0, i));
            split.add(state.withFallDistance(state.fallDistance(), state.fallDistance()));
            split.add(state.withFallDistance(state.fallDistanceMax(), state.fallDistanceMax()));
        }
        return split == null ? states : split;
    }

    /** Whether Player#isAboveGround differs between the ends of this state's fall distance range. */
    private boolean edgeBackoffReadsFallRange(JavaPredictionCarry.State state, Vec3 start) {
        if (!state.hasFallDistanceRange()
                || !this.player.isSneaking
                || this.player.isFlying
                || state.fallDistance() >= this.player.getMaxUpStep()) return false;
        var anchored = state.pistonStart();
        // On the ground Player#isAboveGround holds whatever the fall distance.
        if (anchored != null ? anchored.onGround() : this.lastOnGround == DesyncStatus.TRUE) return false;
        Vec3 at = anchored == null ? start : anchored.position();
        SimpleCollisionBox box = GetBoundingBox.getCollisionBoxForPlayer(this.player, at.x, at.y, at.z);
        return ac.cult.cultac.utils.nmsutil.SneakEdgeBackoff.groundWithinStep(this.player, box, state.fallDistance())
                != ac.cult.cultac.utils.nmsutil.SneakEdgeBackoff.groundWithinStep(
                        this.player, box, state.fallDistanceMax());
    }

    /** Whether powder snow lies within the box this state's travel could sweep from {@code start}, plus a block. */
    private boolean powderSnowInReach(JavaPredictionCarry.State state, Vec3 start) {
        Vec3 velocity = state.velocity();
        var range = velocity instanceof PredVector vector ? vector.getVelocityRange() : null;
        double reach = velocity.length()
                + 1.0
                + (range == null
                        ? 0
                        : Math.max(
                                range.maxX - range.minX, Math.max(range.maxY - range.minY, range.maxZ - range.minZ)));
        int minX = (int) Math.floor(start.x - 0.3 - reach), maxX = (int) Math.floor(start.x + 0.3 + reach);
        int minY = (int) Math.floor(start.y - reach), maxY = (int) Math.floor(start.y + 1.8 + reach);
        int minZ = (int) Math.floor(start.z - 0.3 - reach), maxZ = (int) Math.floor(start.z + 0.3 + reach);
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++)
                    if (ac.cult.blocksim.data.BlockIds.is(
                            this.player.compensatedWorld.getBlockStateIdAt(
                                    new ac.cult.cultac.protocol.value.BlockPos(x, y, z)),
                            ac.cult.blocksim.data.BlockIds.POWDER_SNOW)) return true;
        return false;
    }

    /** A JVM's tag order remains attached to its complete state across ticks, including dry ticks. */
    private List<JavaPredictionCarry.State> fluidOrderStates(List<JavaPredictionCarry.State> states) {
        if (!ClientFluidTagOrder.hasOverlap(this.player)) return states;
        var ordered = new ArrayList<JavaPredictionCarry.State>();
        for (var state : states) {
            if (state.fluidTagOrder() != null) ordered.add(state);
            else for (var order : ClientFluidTagOrder.candidates(player)) ordered.add(state.withFluidTagOrder(order));
        }
        return ordered;
    }

    /** Protocol 773 permits either source behavior each tick; each keeps the complete state it starts from. */
    private List<JavaPredictionCarry.State> movementPatchStates(List<JavaPredictionCarry.State> states) {
        if (this.player.getClientVersion() != ClientVersion.V_1_21_9) return states;
        var patched = new java.util.LinkedHashSet<JavaPredictionCarry.State>();
        for (var state : states) for (var patch : JavaMovementPatch.PROTOCOL_773) patched.add(state.withPatch(patch));
        return List.copyOf(patched);
    }

    private boolean shouldCarryLastRequiredStuckSpeed(PredictionResult lastPrediction) {
        if (this.rideTicksSinceLastPrediction) {
            return false;
        } else if (lastPrediction != null && lastPrediction.getSimulationContext() != null) {
            PacketEntity lastVehicle = lastPrediction.getSimulationContext().getVehicle();
            PacketEntity currentVehicle = this.currentPredictionVehicle();
            return lastVehicle != null && currentVehicle != null
                    ? lastVehicle.getEntityId() == currentVehicle.getEntityId()
                    : lastVehicle == currentVehicle;
        } else {
            return false;
        }
    }

    private SimulationContext createSimulationContext(
            CultPlayer player,
            PredictionResult lastPrediction,
            Vec3 target,
            Vec3 start,
            Vec3 end,
            boolean testing,
            float xRot,
            float yRot,
            AuthoredMovementFrame authoredMovementFrame,
            MovementProfile movementProfile,
            boolean applyLastRequiredStuckSpeed) {
        return this.createSimulationContext(
                player,
                lastPrediction,
                target,
                start,
                end,
                testing,
                xRot,
                yRot,
                authoredMovementFrame,
                movementProfile,
                applyLastRequiredStuckSpeed,
                this.currentPredictionVehicle(),
                null);
    }

    /**
     * LocalPlayer#aiStep updates the sprint flag before LivingEntity#travel, and LocalPlayer#sendPosition sends
     * the resulting START/STOP_SPRINTING before the movement, so the client's last START/STOP is the travel flag.
     * LivingEntity#checkAutoSpinAttack runs after travel and can reach Player#causeExtraKnockback ->
     * setSprinting(false) before that START/STOP when it hits a RemotePlayer, so a tick whose spin may hit one keeps
     * both states.
     */
    private DesyncStatus sprintingAtTravel(CultPlayer player, @Nullable PacketEntity riding) {
        if (riding != null) return DesyncStatus.FALSE;
        if (player.isBedrockMovement()) return DesyncStatus.UNKNOWN;
        return DesyncStatus.fromBoolean(player.isSprinting);
    }

    // The tick's START/STOP and movement have already reconciled ClientSprintState to post-aiStep.
    private boolean sprintModifierAtTravel(CultPlayer player, @Nullable PacketEntity riding) {
        if (riding != null || player.isBedrockMovement()) return true;
        return player.sprintState.modifier();
    }

    private SimulationContext createSimulationContext(
            CultPlayer player,
            PredictionResult lastPrediction,
            Vec3 target,
            Vec3 start,
            Vec3 end,
            boolean testing,
            float xRot,
            float yRot,
            AuthoredMovementFrame authoredMovementFrame,
            MovementProfile movementProfile,
            boolean applyLastRequiredStuckSpeed,
            @Nullable PacketEntity riding,
            @Nullable CarryWorld world) {
        return this.createSimulationContext(
                player,
                lastPrediction,
                target,
                start,
                end,
                testing,
                xRot,
                yRot,
                authoredMovementFrame,
                movementProfile,
                applyLastRequiredStuckSpeed,
                riding,
                world,
                null,
                null);
    }

    private SimulationContext createSimulationContext(
            CultPlayer player,
            PredictionResult lastPrediction,
            Vec3 target,
            Vec3 start,
            Vec3 end,
            boolean testing,
            float xRot,
            float yRot,
            AuthoredMovementFrame authoredMovementFrame,
            MovementProfile movementProfile,
            boolean applyLastRequiredStuckSpeed,
            @Nullable PacketEntity riding,
            @Nullable CarryWorld world,
            @Nullable JavaPredictionCarry.PistonStart anchored,
            @Nullable JavaPredictionCarry selectedCarry) {
        Vec3 vehicleInputs = Float.floatToRawIntBits(player.boatData.vehicleHoriz) == 0
                        && Float.floatToRawIntBits(player.boatData.vehicleForward) == 0
                ? Vec3.ZERO
                : new Vec3(player.boatData.vehicleHoriz, 0.0, player.boatData.vehicleForward);
        SimulationContext context = new SimulationContext(
                start,
                end,
                target,
                player.getClientVersion(),
                player.trigHandler,
                player.compensatedEntities,
                riding,
                xRot,
                this.lastXRot,
                yRot,
                this.lastYRot,
                player.onGround,
                player.isSneaking,
                this.lastSneaking,
                riding == null && player.isGliding,
                player.compensatedEntities.getPotionLevelForPlayer(MovementEffect.JUMP_BOOST),
                this.getDepthStriderLevel(),
                this.getSwiftSneakLevel(),
                this.sprintingAtTravel(player, riding),
                player.minPlayerAttackSlow,
                player.maxPlayerAttackSlow,
                player.packetStateData.riptideLevel,
                testing,
                vehicleInputs,
                player.packetStateData.clientMovementInputKnown,
                player.packetStateData.clientMovementInput,
                anchored != null
                        ? anchored.support()
                        : lastPrediction == null
                                ? new MainSupportingBlockData(null, false)
                                : lastPrediction
                                        .getSimulationContext()
                                        .getWorldData()
                                        .getMainSupportingBlockPos(),
                player.pose,
                player.dimensionScale);
        // Player#tick assigns noPhysics and clears onGround before its travel.
        context.setNoPhysics(!player.isBedrockMovement() && riding == null && player.gamemode == GameMode.SPECTATOR);
        if (context.isNoPhysics() && context.getVersion().isOlderThan(ClientVersion.V_1_21_9)) {
            // Through 1.21.8 Entity#move's noPhysics branch leaves the previous collision flags unchanged.
            boolean horizontal = lastPrediction != null
                    && lastPrediction.getSimulationContext() != null
                    && (lastPrediction.getSimulationContext().isNoPhysics()
                            ? lastPrediction.getSimulationContext().isNoPhysicsHorizontalCollision()
                            : lastPrediction.getCollideAxisData() instanceof JavaMoveCollision move
                                    && move.horizontalCollision());
            context.setNoPhysicsHorizontalCollision(horizontal);
        }
        if (player.usesModernJavaItemUse()) {
            context.setItemUse(ac.cult.cultac.utils.data.ItemUseInventory.prepare(player));
            context.setServerUseTracked(player.itemUseBurst.tracking());
        }
        context.setBedrockMovement(player.isBedrockMovement());
        context.setSprintModifier(this.sprintModifierAtTravel(player, riding));
        DesyncStatus contextLastOnGround = anchored != null
                ? DesyncStatus.fromBoolean(anchored.onGround())
                : riding == null
                        ? this.lastOnGround
                        : (player.packetStateData.vehicleMovementOnGroundPresent
                                ? DesyncStatus.fromBoolean(player.packetStateData.vehicleMovementStartOnGround)
                                : this.lastOnGround);
        if (context.isNoPhysics()) contextLastOnGround = DesyncStatus.FALSE;
        if (riding == null) {
            context.setMovementEfficiency((float) player.compensatedEntities.getSelf().movementEfficiency);
        }
        PacketEntity traveller = riding == null ? player.compensatedEntities.getSelf() : riding;
        context.setWaterMovementEfficiency(getWaterMovementEfficiency(traveller, contextLastOnGround));
        // AbstractBoat#controlBoat reads the keys stored on the boat itself.
        if (riding != null) context.setBoatInputs(riding.boatInput);
        context.setRiptideReleaseCertain(player.packetStateData.riptideReleaseCertain);
        context.setProfileCarry(selectedCarry == null ? this.profileCarry : selectedCarry);
        if (selectedCarry != null && !selectedCarry.states().isEmpty())
            context.setSpinRebound(selectedCarry.states().getFirst().spinRebound() ? SpinRebound.ONLY : SpinRebound.NONE);
        context.setFluidTagOrder(
                selectedCarry != null && !selectedCarry.states().isEmpty()
                        ? selectedCarry.states().getFirst().fluidTagOrder()
                        : player.clientFluidTagOrder);
        if (!player.isBedrockMovement() && player.getClientVersion() == ClientVersion.V_1_21_9)
            context.setPistonStartState(
                    anchored == null
                            ? new JavaPredictionCarry.PistonStart(
                                    start,
                                    contextLastOnGround.determineOptimistically(),
                                    context.getLastTickMainSupportingBlockData(),
                                    this.getPistonDeltas(traveller))
                            : anchored);
        context.setBedrockTeleport(player.isBedrockMovement() ? this.bedrockTeleport : null);
        context = movementProfile.createContext(player, context, authoredMovementFrame);
        if (!player.isBedrockMovement()
                && (riding == null || player.packetStateData.isVehicleMovementFromClientTick())
                && JavaBlockEntityPass.reportsAfterPass(player.getClientVersion())) {
            context.setPassAfterTravel(this.passAfterTravel(context));
        }
        if (world == null) {
            MovementEngine engine = MovementEngines.requireForProfile(movementProfile);
            try (var ignored = ClientFluidTagOrder.use(player, context.getFluidTagOrder())) {
                context.setWorldData(engine.buildWorld(
                        this.worldStageBuilder, player, context, lastPrediction, contextLastOnGround));
            }
        } else {
            world.attachTo(context);
        }
        if (context.getWorldData().isSpinAttackPlayer()) {
            context.setIsSprinting(DesyncStatus.UNKNOWN);
            context.setSprintModifier(true);
        }
        if (selectedCarry == null && this.certainSpinHit && riding == null) context.setSpinRebound(SpinRebound.ONLY);
        if (applyLastRequiredStuckSpeed && lastPrediction != null) {
            Vec3 lastRequiredStuckSpeed = lastPrediction
                    .getSimulationContext()
                    .getWorldData()
                    .getStuckSpeed()
                    .getStuckSpeedMultiplier();
            Vec3 requiredCurrentMoveStuckSpeed =
                    player.boatData.adjustStuckSpeedMultiplierForCurrentTick(player, context, lastRequiredStuckSpeed);
            context.setRequiredCurrentMoveStuckSpeed(requiredCurrentMoveStuckSpeed);
            context.handleLastRequiredStuckSpeed(requiredCurrentMoveStuckSpeed);
        }

        return context;
    }

    @Nullable
    private PacketEntity currentPredictionVehicle() {
        PacketEntity riding = this.player.compensatedEntities.getSelf().getRiding();
        if (riding != null) {
            return riding;
        } else {
            return !this.player.packetStateData.isVehicleMovementFromClientTick()
                    ? null
                    : this.player.compensatedEntities.vehicles.getVelocityMovementVehicle();
        }
    }

    private int getSwiftSneakLevel() {
        SimItemStack leggings = this.player.getInventory().getLeggings();
        return !leggings.isEmpty() && !leggings.getEnchantments().isEmpty()
                ? ac.cult.cultac.utils.inventory.ItemUtil.enchantmentLevel(leggings, "minecraft:swift_sneak")
                : 0;
    }

    private static float getWaterMovementEfficiency(PacketEntity traveller, DesyncStatus onGround) {
        // The nautilus and happy ghast override travel and never reach LivingEntity#travelInWater
        if (traveller instanceof PacketEntityNautilus || traveller instanceof PacketEntityHappyGhast) return 0.0F;
        float efficiency = (float) traveller.waterMovementEfficiency;
        return onGround == DesyncStatus.FALSE ? efficiency * 0.5F : efficiency;
    }

    private float getDepthStriderLevel() {
        SimItemStack boots = this.player.getInventory().getBoots();
        if (!boots.isEmpty() && !boots.getEnchantments().isEmpty()) {
            float level = ac.cult.cultac.utils.inventory.ItemUtil.enchantmentLevel(boots, "minecraft:depth_strider");
            if (level > 3.0F) {
                level = 3.0F;
            }

            if (this.lastOnGround == DesyncStatus.FALSE) {
                level *= 0.5F;
            }

            return level;
        } else {
            return 0.0F;
        }
    }

    @Generated
    public static long getComputeTimeNS() {
        return computeTimeNS;
    }

    @Generated
    public EvictingQueue<Long> getLastMovementTime() {
        return this.lastMovementTime;
    }

    @Generated
    public PredictionResult getLastPrediction() {
        return this.lastPrediction;
    }

    /** Whether a committed player eye cache can be in water at the next tick's underwater/swimming update. */
    public boolean isLastWorldEyeInWater() {
        return ClientFluidQueries.eyeContactInWater(this.player, this.lastWorldEyeFluidContact);
    }

    /** Cached contact itself, for a later query after the client may have received new fluid tags. */
    public int getLastWorldEyeFluidContact() {
        return this.lastWorldEyeFluidContact;
    }

    public CarriedPush getCarriedPush() {
        return this.carriedPush;
    }

    /**
     * The x/z range of the entity pushes the player took while it waited for its level to load, carried into its
     * first loaded travel; null when none.
     */
    public SimpleCollisionBox getPushesWhileLoadingLevel() {
        return this.pushesWhileLoadingLevel;
    }

    /** The horizontal range a teleport's inherited-velocity transform maps {@code range} into; null when none is kept. */
    private static SimpleCollisionBox inheritedRange(TeleportData teleportData, SimpleCollisionBox range) {
        SimpleCollisionBox inherited = null;
        for (double x : new double[] {range.minX, range.maxX}) {
            for (double z : new double[] {range.minZ, range.maxZ}) {
                Vec3 corner = teleportData.transformInheritedVelocity(new Vec3(x, 0.0, z));
                if (inherited == null) inherited = new SimpleCollisionBox(corner.x, 0, corner.z, corner.x, 0, corner.z);
                else inherited.union(new SimpleCollisionBox(corner.x, 0, corner.z, corner.x, 0, corner.z));
            }
        }
        boolean kept = inherited.minX != 0.0 || inherited.maxX != 0.0 || inherited.minZ != 0.0 || inherited.maxZ != 0.0;
        return kept ? inherited : null;
    }

    @Generated
    public DesyncStatus getLastOnGround() {
        return this.lastOnGround;
    }

    public void setLastOnGround(DesyncStatus lastOnGround) {
        this.lastOnGround = lastOnGround;
    }

    /** The world one carry state's context built, as it stood before that state was applied. */
    private record CarryWorld(
            WorldData world,
            List<SimpleCollisionBox> entityCollisionBoxes,
            List<HardCollidingEntityCollision> hardCollidingEntityCollisions) {
        private static CarryWorld of(SimulationContext built) {
            return new CarryWorld(
                    built.getWorldData().copy(),
                    built.getEntityCollisionBoxesForMovementTick(),
                    built.getHardCollidingEntityCollisionsForMovementTick());
        }

        private void attachTo(SimulationContext context) {
            context.setWorldData(world.copy());
            context.cacheEntityCollisionBoxesForMovementTick(entityCollisionBoxes);
            context.cacheHardCollidingEntityCollisionsForMovementTick(hardCollidingEntityCollisions);
        }
    }
}
