package ac.cult.cultac.checks.impl.prediction.runner;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockTags;
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
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.InputKeys;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoFallPseudo;
import ac.cult.cultac.checks.impl.prediction.pipeline.MovementEngine;
import ac.cult.cultac.checks.impl.prediction.pipeline.MovementEngines;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaAutoJump;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfile;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles;
import ac.cult.cultac.checks.impl.prediction.stage.MovementModifiers;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CarriedPush;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.checks.type.PositionListener;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.update.PositionUpdate;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.anticheat.update.RotationUpdate;
import ac.cult.cultac.utils.anticheat.update.VehiclePositionUpdate;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
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
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.lists.EvictingQueue;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.VectorUtils;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.FlightToggle;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.NextTickVelocityDeriver;
import ac.cult.cultac.utils.nmsutil.PassengerRideTick;
import ac.cult.cultac.utils.nmsutil.ReusableVelocitySet;
import ac.cult.cultac.utils.nmsutil.RideTickStuckContact;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
    LastInstance lastTickSkip = new LastInstance(this.player);
    LastInstance lastOnGroundSkip = new LastInstance(this.player);
    EvictingQueue<Long> lastMovementTime = new EvictingQueue(3);
    boolean couldPotentiallyTickSkip = false;
    Set<Vec3> validPlayerStartingVels = new HashSet<>();
    PredictionResult lastPrediction = null;
    /** The committed tick's LivingEntity#checkAutoSpinAttack certainly hit, so the next tick starts from the rebound. */
    private boolean certainSpinHit;

    boolean lastWorldEyeInWater = false;
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
    /** The carry a stuck-speed block changed on along its move, re-resolved once the world holds the change. */
    @Nullable
    private JavaPredictionCarry carryWithChangedStuckBlock;
    /** Whether the last client tick ended with the local player a passenger. */
    private boolean rideTicked;
    /** The on-foot candidates a mount temporarily replaces before the first actual passenger tick. */
    @Nullable
    private Set<Vec3> startingVelsBeforeRide;
    /** The multipliers that ride tick can have left, as RideTickStuckContact bits. */
    private int rideTickStuckSpeeds;
    /** Whether a dismount after a passenger tick precedes the next prediction. */
    private boolean rideTicksSinceLastPrediction;
    /** The multipliers the first move after that dismount can take, as RideTickStuckContact bits. */
    private int dismountStuckSpeeds = RideTickStuckContact.ANY;

    private final RideTickStuckContact rideTickStuckContact = new RideTickStuckContact();
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
        if (!this.player.isBedrockMovement()) {
            if (!this.player.compensatedEntities.vehicles.hasClientPlayerPassengerState()
                    && !this.player.packetStateData.hasPassengerRotationThisClientTick()) {
                if (!this.player.packetStateData.receivedMovementThisClientTick) {
                    if (CheckManagerListener.localPlayerTicked(this.player)) {
                        PredictionResult result = this.advanceNoPositionClientTick(
                                new Vec3(this.player.x, this.player.y, this.player.z),
                                this.player.xRot,
                                this.player.yRot);
                        if (this.isAcceptedNoPositionTick(result)) {
                            this.couldPotentiallyTickSkip = true;
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
                // Minecraft#tick runs piston block entities after LocalPlayer#tick
                // and its movement packet. Entity#move(PISTON) can change onGround
                // at that later boundary. Preserve the existing push approximation
                // in the carried ground state, including across handleMovePlayer's
                // position corrections, which do not assign Entity#onGround.
                // Do this after idle travel too, so it affects the following tick.
                if (this.currentPredictionVehicle() == null
                        && this.player.compensatedWorld.pistons.mayHavePushedOnLastClientTick(
                                GetBoundingBox.getCollisionBoxForPlayer(
                                        this.player, this.player.x, this.player.y, this.player.z))) {
                    this.lastOnGround = DesyncStatus.UNKNOWN;
                }
            }
        }
        if (this.player.packetStateData.flyingStopAfterTravel) {
            this.stopFlyingAfterTravel();
        }
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
                        boolean isPointThree = this.isAcceptedNoPositionTick(result);
                        if (isPointThree) {
                            this.couldPotentiallyTickSkip = true;
                        }

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
                            if (!this.lastOnGround.is(flying.onGround()) && this.lastPrediction != null) {
                                this.lastOnGroundSkip.reset();
                            }
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
            if (!this.player.isBedrockMovement()
                    && (this.player.checkManager.getKnockbackHandler().hasPacketVelocity()
                            || this.player.checkManager.getExplosionHandler().hasPacketVelocity())) {
                this.tryToAchievePointThree(
                        new Vec3(this.player.x, this.player.y, this.player.z), this.player.xRot, this.player.yRot);
            }

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
        this.couldPotentiallyTickSkip = false;
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
                this.lastTickSkip,
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
                    this.lastTickSkip,
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
                this.lastTickSkip,
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
            this.player.checkManager.getDebugHandler().onPredictionCommitted(result, this.validPlayerStartingVels);
            if (!this.player.isBedrockMovement()
                    && result.getSimulationContext().stopsFallFlyingAfterTravel()) {
                this.player.isGliding = false;
            }
            this.lastPrediction = result;
            this.carriedPush = CarriedPush.IDENTITY;
            this.rideTicksSinceLastPrediction = false;
            this.pushesWhileLoadingLevel = null;
            this.ticksWhileLoadingLevel = 0;
            this.lastXRot = xRot;
            this.lastYRot = yRot;
            this.commitSprintOutcomes(result);
            this.player.minPlayerAttackSlow = 0;
            this.player.maxPlayerAttackSlow = 0;
            if (this.player.isFlying) {
                this.lastFlying.reset();
            }

            this.lastSneaking = this.player.isSneaking;
            this.lastGliding = this.player.isGliding;
            if (!this.player.isBedrockMovement()) this.endSpinAttack(result);
            this.player.packetStateData.riptideLevel = 0;
            this.player.packetStateData.riptideReleaseCertain = false;
            this.player.riptideSpinAttackTicks = Math.max(0, this.player.riptideSpinAttackTicks - 1);
            this.couldPotentiallyTickSkip = false;
            this.player.compensatedEntities.fishingRodPulls.clear();
            if (!this.player.isBedrockMovement()) {
                this.player.swimState.onClientTick(
                        result.getSimulationContext().getWorldData().isSwimming(), this.player.isFlying);
            }
            this.player.refreshPlayerPose();
        } else {
            this.applyProfileCommit(preparedCommit);
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
        } else if (this.player.riptideSpinCertain && WorldStageBuilder.certainlySpinAttacks(this.player, context)) {
            this.player.riptideSpinAttackTicks = 0;
            this.certainSpinHit = true;
        } else if (context.getWorldData().isSpinAttackTarget() || !this.certainlyMissesWalls(result)) {
            this.player.riptideSpinCertain = false;
        }
    }

    private boolean certainlyMissesWalls(PredictionResult result) {
        CollideAxisData collide = result.getCollideAxisData();
        return !result.getSimulationContext().isTestingPointThree()
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

    public void seedVehicleStartingVelocity(Vec3 velocity) {
        this.seedStartingVelocity(velocity);
    }

    public void seedStartingVelocity(Vec3 velocity) {
        this.seedStartingVelocities(List.of(velocity));
    }

    public void seedStartingVelocities(Collection<Vec3> velocities) {
        this.validPlayerStartingVels = new HashSet<>(velocities);
    }

    /** A passenger packet changes the simulated actor, but does not consume the player's on-foot motion. */
    public void beginRide() {
        this.startingVelsBeforeRide = new HashSet<>(this.validPlayerStartingVels);
    }

    /** Records whether this client tick ran the local player's Entity#rideTick. */
    public void recordRideTick() {
        this.rideTicked =
                !this.player.isBedrockMovement() && this.player.compensatedEntities.playerEntity.getRiding() != null;
        if (!this.rideTicked) {
            this.rideTickStuckSpeeds = 0;
            this.passengerRideTick.clearBoat();
            return;
        }
        this.startingVelsBeforeRide = null;
        this.player.riptideSpinCertain = false;
        Vec3 seat = this.player.compensatedEntities.lastRideTickSeat();
        if (seat == null) {
            this.passengerRideTick.record(this.player, this.player.x, this.player.y, this.player.z, true);
        } else {
            this.passengerRideTick.record(
                    this.player, seat.x, seat.y, seat.z, this.rideTickStuckContact.touchedPowderSnow());
        }
        this.rideTickStuckSpeeds = this.rideTickStuckContact.scan(
                this.player,
                this.player.compensatedEntities.playerEntity.getRiding(),
                seat,
                this.passengerRideTick.inFluid());
    }

    /** Whether the current ride has executed Entity#rideTick, rather than only passenger packet handlers. */
    public boolean hasRideTick() {
        return this.rideTicked;
    }

    /** A set_entity_motion of the local passenger after its last Entity#rideTick sets the delta a dismount keeps. */
    public void replaceRideTickVelocity(Vec3 velocity) {
        if (this.rideTicked) this.passengerRideTick.setMotion(velocity);
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
        this.dismountStuckSpeeds = this.rideTickStuckSpeeds;
        if (this.profileCarry instanceof JavaPredictionCarry carry) {
            // LivingEntity#rideTick resets the fall distance after every passenger tick.
            List<JavaPredictionCarry.State> states = new ArrayList<>();
            if (!carry.states().isEmpty()) {
                for (Vec3 velocity : this.validPlayerStartingVels) {
                    states.add(new JavaPredictionCarry.State(velocity, 0.0D, null));
                    for (int i = 0; i < Collisions.PLAYER_STUCK_SPEEDS.size(); i++) {
                        if ((this.dismountStuckSpeeds & (1 << i)) == 0) continue;
                        states.add(
                                new JavaPredictionCarry.State(velocity, 0.0D, Collisions.PLAYER_STUCK_SPEEDS.get(i)));
                    }
                }
            }
            this.profileCarry = new JavaPredictionCarry(this.player.compensatedEntities.getSelf(), 0.0D, states);
            this.carryWithChangedStuckBlock = null;
        }
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
                if (result.getSimulationContext().getWorldData().getInLava().determinePessimistically()) {
                    trueFriction = 0.5;
                }

                if (result.getSimulationContext().getWorldData().getInWater().determineOptimistically()) {
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

            if (reality.getInitialStartingVel().isTickSkip() && reality.getFlagSeverity() == 0.0) {
                this.lastTickSkip.reset();
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

    public PredictionResult tryToAchievePointThree(Vec3 withPos, float xRot, float yRot) {
        return this.callPrediction(
                Vec3.ZERO, withPos, withPos, true, xRot, yRot, null, MovementProfiles.forPlayer(this.player));
    }

    private PredictionResult advanceNoPositionClientTick(Vec3 position, float xRot, float yRot) {
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
        if (result.getFlags().isEmpty() && !result.isExempt()) {
            PredictionResult committed = result;
            if (end != position) {
                // The withheld position lies anywhere within the threshold; the client is where it travelled.
                PredictionResult travelled =
                        this.callPrediction(movement, position, end, false, xRot, yRot, null, movementProfile);
                PredictionResult withheld = travelled.getFlags().isEmpty() && !travelled.isExempt()
                        ? travelWithinMovementThreshold(travelled, position, sent, this.player.getMovementThreshold())
                        : null;
                if (withheld != null) {
                    this.handlePossibleRealities(withheld);
                    committed = withheld;
                    movement = withheld.getAcceptedClosestToTarget();
                    end = position.add(movement);
                }
                this.commitPlayerPosition(end);
            }
            this.callPredictionEndListeners(committed, null);
            this.commitPredictionState(committed, end, movement, xRot, yRot, movementProfile);
        } else if (!result.isExempt() && result.hasFlag(InputKeys.class)) {
            // LocalPlayer#sendPosition withholds a position only within 2.0E-4 of xLast and before the 20th tick, so
            // this tick ended there; the reported keys cannot leave the client there. Judge it like a sent move.
            if (end != position) this.commitPlayerPosition(end);
            this.prepareFlaggedPrediction(result, movementProfile.shouldCreateVerboseLog(this.player, result));
            this.callPredictionEndListeners(result, null);
            this.commitPredictionState(result, end, movement, xRot, yRot, movementProfile);
        } else if (result.isExempt()) {
            // LocalPlayer#aiStep crouches the next tick from this tick's shift, whether or not it sends a position.
            this.lastSneaking = this.player.isSneaking;
            this.player.checkManager.getListener(JavaAutoJump.class).onExemptNoPositionTick(result);
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

    private boolean isAcceptedNoPositionTick(PredictionResult result) {
        return result.getFlags().isEmpty() || result.isExempt();
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
                this.lastTickSkip,
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
        return !this.player.isBedrockMovement() && this.player.isInBed
                || flyingExempt
                || this.player.gamemode == GameMode.SPECTATOR;
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
        }
    }

    public PredictionResult getPredictionResult(
            CultPlayer player,
            Set<Vec3> startingVel,
            PredictionResult lastPrediction,
            Vec3 target,
            Vec3 start,
            Vec3 end,
            LastInstance lastTickSkip,
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
                lastTickSkip,
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
            LastInstance lastTickSkip,
            boolean testing,
            float xRot,
            float yRot,
            AuthoredMovementFrame authoredMovementFrame,
            MovementProfile movementProfile) {
        // A time-based border lerp makes every collision read the clock, so a memoised one could go stale.
        stepCollisionMemo.open(!player.isBedrockMovement()
                && player.checkManager.getWorldBorder().hasClockIndependentDiameter());
        try {
            return predict(
                    player,
                    startingVel,
                    lastPrediction,
                    target,
                    start,
                    end,
                    lastTickSkip,
                    testing,
                    xRot,
                    yRot,
                    authoredMovementFrame,
                    movementProfile);
        } finally {
            stepCollisionMemo.close();
        }
    }

    private PredictionResult predict(
            CultPlayer player,
            Set<Vec3> startingVel,
            PredictionResult lastPrediction,
            Vec3 target,
            Vec3 start,
            Vec3 end,
            LastInstance lastTickSkip,
            boolean testing,
            float xRot,
            float yRot,
            AuthoredMovementFrame authoredMovementFrame,
            MovementProfile movementProfile) {
        List<PredictionResult> alternativeRealities = new ArrayList<>();
        List<PredictionResult> invalidOrValidRealities = new ArrayList<>();
        List<ValidMovements> valid = new ArrayList<>();
        MovementEngine engine = MovementEngines.requireForProfile(movementProfile);

        for (SimulationContext curSimulationContext : this.createReleaseSimulationContexts(
                player,
                lastPrediction,
                target,
                start,
                end,
                lastTickSkip,
                testing,
                xRot,
                yRot,
                authoredMovementFrame,
                movementProfile)) {
            List<PredVector> contextPacketStart = engine.applyModifiers(
                    new MovementModifiers(),
                    player,
                    startingVel,
                    curSimulationContext,
                    lastPrediction,
                    this.couldPotentiallyTickSkip);
            List<PredVector> validInitialVelocities = withSprintOutcomes(
                    player,
                    curSimulationContext,
                    engine.startingVelocities(
                            new VelocityTransformer(curSimulationContext),
                            player,
                            contextPacketStart,
                            curSimulationContext));
            Vec3 sortTarget = curSimulationContext.getTarget();
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
                        curSimulationContext,
                        curSimulationContext.getTarget(),
                        null,
                        alternativeRealities,
                        invalidOrValidRealities);
                ValidMovements validMovement = engine.createValidMovements(
                                thisResult.getInitialStartingVel(), player, thisResult, false)
                        .computeExtents(lastPrediction);
                valid.add(validMovement);
            }
        }

        PredictionResult bestResult = null;

        for (int i = 0, size = valid.size(); i < size; i++) {
            ValidMovements validMovements = valid.get(i);
            boolean allowStep = false;

            do {
                PredictionResult thisResult = validMovements.getResult();
                SimpleCollisionBox attemptedExtents = validMovements.getCollisionIgnoredMaxStartingVelExtents();
                boolean useSelectedMovement = engine == JavaMovementEngine.INSTANCE
                        && !validMovements.isCanStep()
                        && !thisResult.getSimulationContext().isTestingPointThree();
                // Entity#collide resolves a step's X/Z on the grounded box raised to the step height.
                boolean probeAtStepHeight = engine == JavaMovementEngine.INSTANCE && validMovements.isCanStep();
                Vec3 collisionTarget = useSelectedMovement
                        ? validMovements.computeCollisionIgnoredMovement(lastPrediction)
                        : probeAtStepHeight
                                ? stepHeightCollisionTarget(
                                        player,
                                        thisResult,
                                        attemptedExtents,
                                        stepHorizontal(validMovements, lastPrediction))
                                : thisResult.getSimulationContext().getTarget();
                CollideAxisData collisionData = probeCollisions(
                        player,
                        engine,
                        thisResult,
                        attemptedExtents,
                        collisionTarget,
                        validMovements.isCanStep() && !probeAtStepHeight);

                thisResult.setCollideAxisData(collisionData);
                if (useSelectedMovement) {
                    validMovements.applyCollisionsToSelectedMovement(lastPrediction);
                    reselectPastClippedAxes(player, engine, validMovements, attemptedExtents, lastPrediction);
                    probeOtherAxisOrder(
                            player, engine, validMovements, attemptedExtents, collisionTarget, lastPrediction, false);
                } else {
                    validMovements.computeClosest(lastPrediction);
                    if (probeAtStepHeight) {
                        probeOtherAxisOrder(
                                player,
                                engine,
                                validMovements,
                                attemptedExtents,
                                collisionTarget,
                                lastPrediction,
                                true);
                    }
                }
                engine.evaluateCandidate(player, thisResult);
                this.flagger.handleResult(thisResult, lastPrediction);
                // A multiplier the first move after a dismount can take is chosen only when it explains the move.
                boolean selectable = !this.rideTicksSinceLastPrediction
                        || thisResult.getFlagSeverity() == 0.0
                        || thisResult.getSimulationContext().getRequiredCurrentMoveStuckSpeed() == null;
                bestResult = selectable && engine.isBetterCandidate(thisResult, bestResult) ? thisResult : bestResult;
                if (thisResult.getFlagSeverity() == 0.0) {
                    alternativeRealities.add(thisResult);
                }

                invalidOrValidRealities.add(thisResult);
                if (allowStep) {
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
                    // Entity#collide tests horizontal obstruction on the move before
                    // stepping. A landing-to-step packet can end above that obstacle.
                    // Preserve the lower probe for step discovery only; its contacts
                    // must not replace the ordinary move's collision/velocity state.
                    CollideAxisData stepEligibility = probedCollisions;
                    if (engine == JavaMovementEngine.INSTANCE && !stepEligibility.couldCollideHorizontally()) {
                        stepEligibility = probeCollisions(
                                player,
                                engine,
                                thisResult,
                                attemptedExtents,
                                thisResult.getSimulationContext().getTarget(),
                                true);
                    }
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

        if (!alternativeRealities.contains(bestResult)) {
            alternativeRealities.add(bestResult);
        }

        assert bestResult != null;
        movementProfile.evaluatePredictionResult(player, bestResult);
        return bestResult;
    }

    private static int compareStartingVelocities(
            PredVector first, PredVector second, Vec3 target, TransactionVel velocity, TransactionVel explosion) {
        int order = Integer.compare(first.tickSkippingComparator(), second.tickSkippingComparator());
        if (order != 0) return order;
        order = Integer.compare(missing(first, velocity, explosion), missing(second, velocity, explosion));
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

    /**
     * The movement whose X and Z order a step probe's horizontal axes. Entity#collide orders a step by the move
     * before collision.
     */
    private static Vec3 stepHorizontal(ValidMovements validMovements, PredictionResult lastPrediction) {
        PredictionResult result = validMovements.getResult();
        return !CollisionModifier.isJavaVehicleMove(result.getSimulationContext())
                ? result.getSimulationContext().getTarget()
                : validMovements.computeCollisionIgnoredMovement(lastPrediction);
    }

    /**
     * {@code horizontal} with its Y replaced by {@link #stepHeightProbeY}; probing with it moves the box to the step
     * height before the horizontal axes.
     */
    private static Vec3 stepHeightCollisionTarget(
            CultPlayer player, PredictionResult result, SimpleCollisionBox attemptedExtents, Vec3 horizontal) {
        SimulationContext context = result.getSimulationContext();
        double probeY = stepHeightProbeY(
                context.getTarget().y,
                attemptedExtents.minY,
                context.getLastStuckSpeed().y,
                result.getInitialStartingVel().maxUpStep(player));
        return new Vec3(horizontal.x, probeY, horizontal.z);
    }

    /**
     * {@code targetY} and {@code attemptedMinY} are pre-stuck (the probe multiplies by {@code stuckY}); the step
     * height is post-stuck movement, bounded like StepTransform's step candidate by the unscaled max up step.
     * Returns the pre-stuck Y whose scaled value is that bounded step height.
     */
    static double stepHeightProbeY(double targetY, double attemptedMinY, double stuckY, double maxUpStep) {
        double stepY = CultMath.clamp(targetY * stuckY, Math.min(0.0D, attemptedMinY * stuckY), maxUpStep);
        return stepY / stuckY;
    }

    /**
     * Reselects the collision-ignored movement towards {@link ValidMovements#targetPastClippedAxes}, probes
     * collisions for it, and keeps whichever clipped movement ends nearer the target.
     */
    private void reselectPastClippedAxes(
            CultPlayer player,
            MovementEngine engine,
            ValidMovements validMovements,
            SimpleCollisionBox attemptedExtents,
            PredictionResult lastPrediction) {
        PredictionResult result = validMovements.getResult();
        Vec3 retarget = validMovements.targetPastClippedAxes();
        if (retarget == result.getTarget()) return;

        // Entity#collideWithShapes orders X and Z by magnitude, so the reselected movement needs its own probe.
        keepNearerReselection(validMovements, retarget, lastPrediction, reselected -> {
            result.setCollideAxisData(probeCollisions(player, engine, result, attemptedExtents, reselected, false));
            validMovements.applyCollisionsToSelectedMovement(lastPrediction);
        });
    }

    /**
     * Entity#collide moves X and Z in Direction#axisStepOrder of the move before collision. When a vehicle's
     * collision-ignored extents hold moves of both orders, selects a move of the order {@code selected} does not
     * take, probes collisions for it, and keeps whichever clipped movement ends nearer the target.
     */
    private void probeOtherAxisOrder(
            CultPlayer player,
            MovementEngine engine,
            ValidMovements validMovements,
            SimpleCollisionBox attemptedExtents,
            Vec3 selected,
            PredictionResult lastPrediction,
            boolean step) {
        PredictionResult result = validMovements.getResult();
        if (!CollisionModifier.isJavaVehicleMove(result.getSimulationContext())
                || !holdsBothAxisOrders(attemptedExtents)) {
            return;
        }
        Vec3 retarget = otherAxisOrderTarget(result.getTarget(), selected, attemptedExtents);
        if (retarget == null) return;

        keepNearerReselection(validMovements, retarget, lastPrediction, reselected -> {
            if (step) {
                Vec3 stepTarget = stepHeightCollisionTarget(player, result, attemptedExtents, reselected);
                result.setCollideAxisData(probeCollisions(player, engine, result, attemptedExtents, stepTarget, false));
                validMovements.computeClosest(lastPrediction);
            } else {
                result.setCollideAxisData(probeCollisions(player, engine, result, attemptedExtents, reselected, false));
                validMovements.applyCollisionsToSelectedMovement(lastPrediction);
            }
        });
    }

    /**
     * Selects the collision-ignored movement towards {@code retarget}, has {@code clip} probe and clip it, and keeps
     * whichever clipped movement ends nearer the target.
     */
    private static void keepNearerReselection(
            ValidMovements validMovements, Vec3 retarget, PredictionResult lastPrediction, Consumer<Vec3> clip) {
        MovementTrace firstTrace = validMovements.getClosestTrace();
        CollideAxisData firstCollisions = validMovements.getResult().getCollideAxisData();
        clip.accept(validMovements.computeCollisionIgnoredMovement(retarget, lastPrediction));
        validMovements.keepNearerToTarget(firstTrace, firstCollisions);
    }

    /** Whether {@code extents} holds a move with |x| < |z| and one without. */
    private static boolean holdsBothAxisOrders(SimpleCollisionBox extents) {
        if (extents == null) return false;
        return nearestToZero(extents.minX, extents.maxX) < farthestFromZero(extents.minZ, extents.maxZ)
                && farthestFromZero(extents.minX, extents.maxX) >= nearestToZero(extents.minZ, extents.maxZ);
    }

    private static double nearestToZero(double min, double max) {
        return min <= 0.0D && max >= 0.0D ? 0.0D : Math.min(Math.abs(min), Math.abs(max));
    }

    private static double farthestFromZero(double min, double max) {
        return Math.max(Math.abs(min), Math.abs(max));
    }

    /**
     * {@code target} with the axis {@code selected} moves second carried to the edge of {@code extents} in its
     * direction, where that axis moves first; null when that edge does not reverse the order.
     */
    private static Vec3 otherAxisOrderTarget(Vec3 target, Vec3 selected, SimpleCollisionBox extents) {
        boolean xFirst = Math.abs(selected.x) >= Math.abs(selected.z);
        if (xFirst) {
            double z = farEdge(target.z, selected.z, extents.minZ, extents.maxZ);
            return Math.abs(target.x) < Math.abs(z) ? new Vec3(target.x, target.y, z) : null;
        }
        double x = farEdge(target.x, selected.x, extents.minX, extents.maxX);
        return Math.abs(x) >= Math.abs(target.z) && x != 0.0D ? new Vec3(x, target.y, target.z) : null;
    }

    private static double farEdge(double target, double selected, double min, double max) {
        double side = target != 0.0D ? target : selected;
        return side > 0.0D ? max : side < 0.0D ? min : 0.0D;
    }

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
     * only when Entity#isInWaterOrRain holds, and a spin leaves onGround false for the tick's LivingEntity#aiStep.
     */
    private List<SimulationContext> createReleaseSimulationContexts(
            CultPlayer player,
            PredictionResult lastPrediction,
            Vec3 target,
            Vec3 start,
            Vec3 end,
            LastInstance lastTickSkip,
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
                lastTickSkip,
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
                    lastTickSkip,
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
                            lastTickSkip,
                            testing,
                            xRot,
                            yRot,
                            authoredMovementFrame,
                            movementProfile);
            for (SimulationContext spin : spins) {
                spin.setWaterMovementEfficiency(
                        getWaterMovementEfficiency(player.compensatedEntities.getSelf(), DesyncStatus.FALSE));
                released.add(spin);
                if (!spin.consumesStuckSpeed()
                        || !spin.getWorldData().getCarriedOnGround().getStates().contains(true)) continue;
                SimulationContext lift = this.createSimulationContext(
                        player,
                        lastPrediction,
                        target,
                        start,
                        end,
                        lastTickSkip,
                        testing,
                        xRot,
                        yRot,
                        authoredMovementFrame,
                        movementProfile,
                        false,
                        null,
                        null);
                lift.setProfileCarry(spin.getProfileCarry());
                lift.setRiptideLiftStuck(spin.getLastStuckSpeed());
                lift.setWaterMovementEfficiency(spin.getWaterMovementEfficiency());
                released.add(lift);
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
            LastInstance lastTickSkip,
            boolean testing,
            float xRot,
            float yRot,
            AuthoredMovementFrame authoredMovementFrame,
            MovementProfile movementProfile) {
        if (player.isBedrockMovement()
                || lastPrediction == null
                || !lastPrediction.getSimulationContext().getWorldData().isSpinAttackTarget()) return contexts;
        List<SimulationContext> split = new ArrayList<>(contexts.size() * 2);
        for (SimulationContext context : contexts) {
            if (!context.getWorldData().isSpinAttackTarget()) continue;
            context.setSpinRebound(SpinRebound.NONE);
            split.add(context);
        }
        if (split.isEmpty()) return contexts;
        int spinTicks = player.riptideSpinAttackTicks;
        player.riptideSpinAttackTicks = 0;
        try {
            List<SimulationContext> rebounds = this.createSimulationContexts(
                    player,
                    lastPrediction,
                    target,
                    start,
                    end,
                    lastTickSkip,
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
            LastInstance lastTickSkip,
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
            if (carried == this.carryWithChangedStuckBlock && lastPrediction != null) {
                carried = JavaMovementEngine.withStuckStatesFromCurrentWorld(player, lastPrediction, carried);
                if (!testing) {
                    this.profileCarry = carried;
                    this.carryWithChangedStuckBlock = null;
                }
            }
            List<SimulationContext> contexts = new ArrayList<>();
            // Every state's context is built from the same inputs before the state is applied to it.
            CarryWorld world = null;
            for (var state : carried.states()) {
                SimulationContext context = this.createSimulationContext(
                        player,
                        lastPrediction,
                        target,
                        start,
                        end,
                        lastTickSkip,
                        testing,
                        xRot,
                        yRot,
                        authoredMovementFrame,
                        movementProfile,
                        false,
                        this.currentPredictionVehicle(),
                        world);
                if (world == null && carried.states().size() > 1) world = CarryWorld.of(context);
                context.setProfileCarry(new ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry(
                        carry.actor(), state.fallDistance(), List.of(state)));
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
                lastTickSkip,
                testing,
                xRot,
                yRot,
                authoredMovementFrame,
                movementProfile,
                carryLastRequiredStuckSpeed);
        if (!this.rideTicksSinceLastPrediction || !movementProfile.usesJavaEntityMoveStuckSpeed(player))
            return List.of(primary);
        // The first move after a dismount consumes no multiplier or one the last ride tick's blocks can have given.
        List<SimulationContext> contexts = new ArrayList<>(1 + Collisions.PLAYER_STUCK_SPEEDS.size());
        contexts.add(primary);
        CarryWorld world = CarryWorld.of(primary);
        for (int i = 0; i < Collisions.PLAYER_STUCK_SPEEDS.size(); i++) {
            if ((this.dismountStuckSpeeds & (1 << i)) == 0) continue;
            Vec3 stuckSpeed = Collisions.PLAYER_STUCK_SPEEDS.get(i);
            SimulationContext context = this.createSimulationContext(
                    player,
                    lastPrediction,
                    target,
                    start,
                    end,
                    lastTickSkip,
                    testing,
                    xRot,
                    yRot,
                    authoredMovementFrame,
                    movementProfile,
                    false,
                    this.currentPredictionVehicle(),
                    world);
            Vec3 stuck = player.boatData.adjustStuckSpeedMultiplierForCurrentTick(player, context, stuckSpeed);
            context.setRequiredCurrentMoveStuckSpeed(stuck);
            context.handleLastRequiredStuckSpeed(stuck);
            contexts.add(context);
        }
        return contexts;
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
            LastInstance lastTickSkip,
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
                lastTickSkip,
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
            LastInstance lastTickSkip,
            boolean testing,
            float xRot,
            float yRot,
            AuthoredMovementFrame authoredMovementFrame,
            MovementProfile movementProfile,
            boolean applyLastRequiredStuckSpeed,
            @Nullable PacketEntity riding,
            @Nullable CarryWorld world) {
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
                lastTickSkip,
                testing,
                vehicleInputs,
                player.packetStateData.clientMovementInputKnown,
                player.packetStateData.clientMovementInput,
                lastPrediction == null
                        ? new MainSupportingBlockData(null, false)
                        : lastPrediction.getSimulationContext().getWorldData().getMainSupportingBlockPos(),
                player.pose,
                player.dimensionScale);
        if (player.usesModernJavaItemUse()) {
            context.setItemUse(ac.cult.cultac.utils.data.ItemUseInventory.prepare(player));
            context.setServerUseTracked(player.itemUseBurst.tracking());
        }
        context.setSprintModifier(this.sprintModifierAtTravel(player, riding));
        DesyncStatus contextLastOnGround = riding == null
                ? this.lastOnGround
                : (player.packetStateData.vehicleMovementOnGroundPresent
                        ? DesyncStatus.fromBoolean(player.packetStateData.vehicleMovementStartOnGround)
                        : this.lastOnGround);
        if (riding == null) {
            context.setMovementEfficiency((float) player.compensatedEntities.getSelf().movementEfficiency);
        }
        PacketEntity traveller = riding == null ? player.compensatedEntities.getSelf() : riding;
        context.setWaterMovementEfficiency(getWaterMovementEfficiency(traveller, contextLastOnGround));
        context.setBoatInputs(player.boatData.vehicleKeys);
        context.setRiptideReleaseCertain(player.packetStateData.riptideReleaseCertain);
        context.setProfileCarry(this.profileCarry);
        context.setBedrockTeleport(player.isBedrockMovement() ? this.bedrockTeleport : null);
        context = movementProfile.createContext(player, context, authoredMovementFrame);
        if (world == null) {
            MovementEngine engine = MovementEngines.requireForProfile(movementProfile);
            context.setWorldData(
                    engine.buildWorld(this.worldStageBuilder, player, context, lastPrediction, contextLastOnGround));
            this.lastWorldEyeInWater = context.getWorldData().isEyeInWater();
        } else {
            world.attachTo(context);
        }
        if (context.getWorldData().isSpinAttackPlayer()) {
            context.setIsSprinting(DesyncStatus.UNKNOWN);
            context.setSprintModifier(true);
        }
        if (this.certainSpinHit && riding == null) context.setSpinRebound(SpinRebound.ONLY);
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

    public void handleBlockChange(BlockPos pos, int to) {
        if (this.lastPrediction != null) {
            if (!this.player.compensatedEntities.getSelf().inVehicle()) {
                SimulationContext context = this.lastPrediction.getSimulationContext();
                if (context != null) {
                    SimpleCollisionBox blockCollisionBox = new SimpleCollisionBox(pos);
                    SimpleCollisionBox playerMaxExtent = context.getToMaximumExtent();
                    playerMaxExtent.expand(1.0);
                    if (blockCollisionBox.isIntersected(playerMaxExtent)) {
                        if (ClientFluidQueries.modelFluid(to)
                                .isSame(ClientFluidQueries.modelFluid(BlockIds.WATER.defaultState()))) {
                            this.lastPrediction
                                    .getSimulationContext()
                                    .getWorldData()
                                    .setInWater(DesyncStatus.UNKNOWN);
                            this.lastPrediction
                                    .getSimulationContext()
                                    .getWorldData()
                                    .setInFlowingLiquid(DesyncStatus.UNKNOWN);
                        }

                        if (BlockIds.is(to, BlockIds.LAVA)) {
                            this.lastPrediction
                                    .getSimulationContext()
                                    .getWorldData()
                                    .setInLava(DesyncStatus.UNKNOWN);
                            this.lastPrediction
                                    .getSimulationContext()
                                    .getWorldData()
                                    .setInFlowingLiquid(DesyncStatus.UNKNOWN);
                        }

                        if (BlockTags.CLIMBABLE.test(to)
                                || BlockIds.is(to, BlockIds.POWDER_SNOW)
                                        && this.player.getInventory().getBoots().getItem()
                                                == ac.cult.cultac.utils.inventory.ItemTypes.LEATHER_BOOTS) {
                            this.lastPrediction
                                    .getSimulationContext()
                                    .getWorldData()
                                    .setClimbing(DesyncStatus.UNKNOWN);
                        }

                        Vec3 stuckSpeed = Collisions.getStuckSpeedForBlock(
                                this.player, to, this.changedBlockCanApplyPowderSnowStuckSpeed(context, pos, to));
                        if (stuckSpeed != null && this.couldChangedStuckSpeedBlockAffectLastMovement(context, pos)) {
                            this.lastPrediction
                                    .getSimulationContext()
                                    .getWorldData()
                                    .setStuckSpeed(new StuckSpeedData(null, stuckSpeed));
                            this.validPlayerStartingVels.add(Vec3.ZERO);
                            if (JavaMovementEngine.contextUsesExactEffects(context)
                                    && !this.player.isBedrockMovement()
                                    && this.profileCarry instanceof JavaPredictionCarry carry
                                    && carry.actor() == this.player.compensatedEntities.getSelf()) {
                                this.carryWithChangedStuckBlock = carry;
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean changedBlockCanApplyPowderSnowStuckSpeed(SimulationContext context, BlockPos pos, int to) {
        if (!BlockIds.is(to, BlockIds.POWDER_SNOW)) {
            return true;
        }

        BlockPos rootBlockPosition = new BlockPos(
                CultMath.floor(context.getEnd().x),
                CultMath.floor(context.getEnd().y),
                CultMath.floor(context.getEnd().z));
        return rootBlockPosition.equals(pos);
    }

    private boolean couldChangedStuckSpeedBlockAffectLastMovement(SimulationContext context, BlockPos pos) {
        return Collisions.canFullBlockAffectInsideBlockMovement(
                        context.getFromMinimumExtent(), context.getToMinimumExtent(), context.getTarget(), pos)
                || Collisions.canFullBlockAffectInsideBlockMovement(
                        context.getFromMaximumExtent(), context.getToMaximumExtent(), context.getTarget(), pos);
    }

    @Generated
    public static long getComputeTimeNS() {
        return computeTimeNS;
    }

    @Generated
    public LastInstance getLastTickSkip() {
        return this.lastTickSkip;
    }

    @Generated
    public LastInstance getLastOnGroundSkip() {
        return this.lastOnGroundSkip;
    }

    @Generated
    public EvictingQueue<Long> getLastMovementTime() {
        return this.lastMovementTime;
    }

    @Generated
    public PredictionResult getLastPrediction() {
        return this.lastPrediction;
    }

    /** The eye fluid of the last simulated movement start, whether or not its prediction was committed. */
    public boolean isLastWorldEyeInWater() {
        return this.lastWorldEyeInWater;
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
