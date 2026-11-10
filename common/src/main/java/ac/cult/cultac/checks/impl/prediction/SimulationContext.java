package ac.cult.cultac.checks.impl.prediction;

import ac.cult.blocksim.data.BlockTags;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockProtocolVersion;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaAiStepSprint;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaInsideBlockEffects;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementPatch;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.ClientSprintState;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.SprintingState;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.packetentity.*;
import ac.cult.cultac.utils.debug.DebugText;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.TrigHandler;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.Friction;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.Arrays;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
public class SimulationContext implements Cloneable, DebugText.Recordable {
    /**
     * Whether this tick starts from LivingEntity#checkAutoSpinAttack's -0.2 rebound: a hit scales deltaMovement and
     * sets autoSpinAttackTicks to 0, while a spin that hit nothing leaves deltaMovement as it was.
     */
    public enum SpinRebound {
        /** The last tick may have hit. */
        OPTIONAL,
        /** The last tick hit nothing. */
        NONE,
        /** The last tick hit. */
        ONLY
    }

    private static final Vec3 NO_STUCK_SPEED = new Vec3(1, 1, 1);

    @Setter
    ClientItemUseState.Snapshot itemUse = ClientItemUseState.Snapshot.NONE;

    // Residual input is recorded for the server-view item-use balance; evidence only.
    @Setter
    boolean serverUseTracked;

    Vec3 start;

    @Setter
    Vec3 end;

    @Setter
    Vec3 target;

    @Getter(AccessLevel.NONE)
    private Vec3 packetMovement;

    @Getter(AccessLevel.NONE)
    private Vec3 packetMovementEnd;

    ClientVersion version;

    TrigHandler trig;

    CompensatedEntities entities;

    PacketEntity vehicle;
    float xRot, lastXRot;
    float yRot, lastYRot;
    boolean onGround;
    boolean sneaking, lastSneaking;
    boolean gliding;
    // Index at 0, null = none
    Integer jumpAmplifier;
    float depthStriderLevel;
    int swiftSneakLevel;
    // Camel#tickRidden stands the fully seated vehicle up inside LivingEntity#travelRidden: after Entity#baseTick and
    // getRiddenInput read it seated, and before travel moves its standing box.
    @Setter
    boolean riddenStandUp;

    @Setter
    DesyncStatus isSprinting;
    // Whether movement_speed may carry minecraft:sprinting during travel
    @Setter
    boolean sprintModifier = true;

    private float[][] groundMoveRelativeSpeeds;

    @Getter(AccessLevel.NONE)
    private float[][] waterMoveRelativeSpeeds;

    // JavaAiStepSprint#clearsModifier for this context's aiStep; null until read, and again after its world changes.
    @Getter(AccessLevel.NONE)
    private DesyncStatus aiStepSprintClear;
    // JavaAiStepSprint#restartsModifier for this context's aiStep, cached and reset with aiStepSprintClear.
    @Getter(AccessLevel.NONE)
    private DesyncStatus aiStepSprintRestart;
    // The movement_efficiency attribute during travel; LivingEntity#getBlockSpeedFactor lerps the block's factor to 1
    @Setter
    float movementEfficiency;
    // The water_movement_efficiency attribute as LivingEntity#travelInWater reads it, halved off the ground
    @Setter
    float waterMovementEfficiency;

    int minAttackSlow, maxAttackSlow;
    // WorldData must be generated based on movement state...
    // I don't want to give access to the world to everything - bad for performance and an antipattern
    WorldData worldData;

    /** The source-proven protocol-773 implementation this candidate follows, or null for other protocols. */
    @Setter
    JavaMovementPatch movementPatch;

    @Setter
    ac.cult.cultac.utils.nmsutil.ClientFluidTagOrder fluidTagOrder;

    @Setter
    JavaPredictionCarry.PistonStart pistonStartState;

    @Setter
    int riptideLevel;

    /** TridentItem#releaseUsing spins this tick: it pushes, and lifts a player on ground. */
    @Setter
    boolean riptideReleaseCertain;

    /** Multiplier consumed by the release's move, retained as evidence that it replaced the carried velocity. */
    @Setter
    Vec3 riptideLiftStuck;

    @Setter
    Vec3 preTravelMovement = Vec3.ZERO;

    /**
     * The movements Entity#move recorded earlier in this client tick, which the travel's 1.21.5+
     * Entity#applyEffectsFromBlocks replays before the travel's own.
     */
    @Setter
    List<JavaInsideBlockEffects.RecordedMove> movesBeforeTravel = List.of();

    /** The starts the last tick's LivingEntity#checkAutoSpinAttack leaves this tick. */
    @Setter
    SpinRebound spinRebound = SpinRebound.OPTIONAL;

    @Setter
    boolean bedrockMovement;

    @Setter
    boolean noPhysics;

    @Setter
    boolean noPhysicsHorizontalCollision;
    // A tick whose position LocalPlayer#sendPosition withheld: the report is the last position sent.
    boolean withheldPosition;
    Vec3 lastStuckSpeed = NO_STUCK_SPEED;
    Vec3 horseInputs;

    @Setter
    KnownInput boatInputs = KnownInput.DEFAULT;

    MainSupportingBlockData lastTickMainSupportingBlockData;
    Pose pose;
    float scale;

    @Setter
    Vec3 rootClientVelocityAtPrediction;

    @Setter
    Vec3 normalizedRootClientVelocityAtPrediction;

    @Setter
    boolean rootClientVelocityPendingAtPrediction;

    /** The concrete vehicle position used only by the passenger's own ride-tick replay. */
    @Setter
    SimpleCollisionBox rideTickVehicleBox;

    @Setter
    Vec3 requiredCurrentMoveStuckSpeed;

    @Setter
    PredictionCarry profileCarry;

    /**
     * From 26.3, the Level#tickBlockEntities that runs between this travel and the report when its movers may reach
     * the player, which CollisionModifier replays after each candidate travel; null otherwise.
     */
    @Setter
    ClientBlockEntityPass passAfterTravel;

    @Setter
    boolean spinSprintResetRequired;

    AuthoredMovementFrame authoredInput;
    BedrockAuthInputFrame bedrockInput;
    BedrockProtocolVersion bedrockVersion = BedrockProtocolVersion.UNKNOWN;
    long bedrockInputTick = -1L;
    boolean hasTrustedAuthoredInput;

    @Setter
    TeleportData bedrockTeleport;

    List<SimpleCollisionBox> entityCollisionBoxesForMovementTick;

    List<HardCollidingEntityCollision> hardCollidingEntityCollisionsForMovementTick;

    public SimulationContext(
            Vec3 start,
            Vec3 end,
            Vec3 target,
            ClientVersion version,
            TrigHandler trig,
            CompensatedEntities entities,
            PacketEntity vehicle,
            float xRot,
            float lastXRot,
            float yRot,
            float lastYRot,
            boolean onGround,
            boolean sneaking,
            boolean lastSneaking,
            boolean gliding,
            Integer jumpAmplifier,
            float depthStriderLevel,
            int swiftSneakLevel,
            DesyncStatus isSprinting,
            int minAttackSlow,
            int maxAttackSlow,
            int riptideLevel,
            boolean withheldPosition,
            Vec3 horseInputs,
            boolean clientMovementInputKnown,
            Vec3 clientMovementInput,
            MainSupportingBlockData lastTickMainSupportingBlockData,
            Pose pose,
            float scale) {
        this.start = start;
        this.end = end;
        this.target = target;
        this.version = version;
        this.trig = trig;
        this.entities = entities;
        this.vehicle = vehicle;
        this.xRot = xRot;
        this.lastXRot = lastXRot;
        this.yRot = yRot;
        this.lastYRot = lastYRot;
        this.onGround = onGround;
        this.sneaking = sneaking;
        this.lastSneaking = lastSneaking;
        this.gliding = gliding;
        this.jumpAmplifier = jumpAmplifier;
        this.depthStriderLevel = depthStriderLevel;
        this.swiftSneakLevel = swiftSneakLevel;
        this.isSprinting = isSprinting;
        this.minAttackSlow = minAttackSlow;
        this.maxAttackSlow = maxAttackSlow;
        this.riptideLevel = riptideLevel;
        this.withheldPosition = withheldPosition;
        this.horseInputs = horseInputs;
        this.lastTickMainSupportingBlockData = lastTickMainSupportingBlockData;
        this.pose = pose;
        this.scale = scale;
    }

    public void handleLastRequiredStuckSpeed(Vec3 stuckSpeedRequired) {
        // Entity#move's noPhysics branch returns before consuming the stuck multiplier.
        if (noPhysics) return;
        if (stuckSpeedRequired != null) {
            lastStuckSpeed = stuckSpeedRequired;
        }
        // Dividing by an exact 1 keeps every component's bits.
        if (lastStuckSpeed.x == 1.0D && lastStuckSpeed.y == 1.0D && lastStuckSpeed.z == 1.0D) {
            return;
        }
        setTarget(target.multiply(1 / lastStuckSpeed.x, 1 / lastStuckSpeed.y, 1 / lastStuckSpeed.z));
    }

    /** The world this context's Entity#baseTick and LocalPlayer#aiStep read; the aiStep's sprint verdict follows it. */
    public void setWorldData(WorldData worldData) {
        this.worldData = worldData;
        this.aiStepSprintClear = null;
        this.aiStepSprintRestart = null;
        this.groundMoveRelativeSpeeds = null;
        this.waterMoveRelativeSpeeds = null;
    }

    /**
     * Whether this context's LocalPlayer#aiStep removed minecraft:sprinting while the flag stayed clear
     * ({@link JavaAiStepSprint#clearsModifier}). Its travel moves without the modifier when TRUE, and may when UNKNOWN.
     */
    public DesyncStatus aiStepSprintClear(CultPlayer player) {
        if (aiStepSprintClear == null) aiStepSprintClear = JavaAiStepSprint.clearsModifier(player, this);
        return aiStepSprintClear;
    }

    /**
     * Whether this context's 1.21.4 LocalPlayer#aiStep restarted the sprint on a set flag
     * ({@link JavaAiStepSprint#restartsModifier}). Its travel moves with vanilla's modifier when TRUE, and may when
     * UNKNOWN.
     */
    public DesyncStatus aiStepSprintRestart(CultPlayer player) {
        if (aiStepSprintRestart == null) aiStepSprintRestart = JavaAiStepSprint.restartsModifier(player, this);
        return aiStepSprintRestart;
    }

    /** A travel replay owns its endpoint and world fields; shared boxes and inputs remain read-only. */
    public SimulationContext forTravelReplay(Vec3 travelEnd) {
        try {
            SimulationContext copy = (SimulationContext) super.clone();
            copy.end = travelEnd;
            copy.target = travelEnd
                    .subtract(start)
                    .multiply(1 / lastStuckSpeed.x, 1 / lastStuckSpeed.y, 1 / lastStuckSpeed.z);
            copy.worldData = worldData.copy();
            copy.passAfterTravel = null;
            // A copy's start, sprint flags or world can change before it travels.
            copy.aiStepSprintClear = null;
            copy.aiStepSprintRestart = null;
            copy.groundMoveRelativeSpeeds = null;
            copy.waterMoveRelativeSpeeds = null;
            return copy;
        } catch (CloneNotSupportedException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** A release candidate owns its lifted travel origin, carry and world; the packet endpoint stays fixed. */
    public SimulationContext afterPreTravelMove(
            ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState moved) {
        SimulationContext copy = forTravelReplay(end);
        copy.passAfterTravel = passAfterTravel;
        copy.target = target;
        if (moved == null) return copy;
        copy.preTravelMovement = moved.position().subtract(start);
        copy.start = moved.position();
        copy.lastStuckSpeed = NO_STUCK_SPEED;
        copy.requiredCurrentMoveStuckSpeed = null;
        copy.target = end.subtract(copy.start);
        copy.handleLastRequiredStuckSpeed(moved.stuckSpeed());
        copy.packetMovement = null;
        copy.packetMovementEnd = null;
        copy.lastTickMainSupportingBlockData = moved.support();
        copy.entityCollisionBoxesForMovementTick = null;
        copy.hardCollidingEntityCollisionsForMovementTick = null;
        // forTravelReplay clears the after-travel pass; a release is still in the original client tick.
        return copy;
    }

    public void attachAuthoredInput(AuthoredMovementFrame authoredInput) {
        this.authoredInput = authoredInput;
        this.bedrockInput = authoredInput instanceof BedrockAuthInputFrame frame ? frame : null;
        this.hasTrustedAuthoredInput = authoredInput != null;
        this.bedrockInputTick = -1L;
        if (bedrockInput != null) {
            this.bedrockVersion = bedrockInput.getProtocolVersion();
        }
    }

    public void setBedrockAuthoritativeInputTick(long bedrockInputTick) {
        this.bedrockInputTick = Math.max(0L, bedrockInputTick);
    }

    public boolean hasTrustedAuthoredInput() {
        return hasTrustedAuthoredInput;
    }

    public void cacheEntityCollisionBoxesForMovementTick(List<SimpleCollisionBox> entityCollisionBoxesForMovementTick) {
        this.entityCollisionBoxesForMovementTick = entityCollisionBoxesForMovementTick;
    }

    public void cacheHardCollidingEntityCollisionsForMovementTick(
            List<HardCollidingEntityCollision> hardCollidingEntityCollisionsForMovementTick) {
        this.hardCollidingEntityCollisionsForMovementTick = hardCollidingEntityCollisionsForMovementTick;
    }

    /** Whether the tick's first Entity#move consumes an Entity#stuckSpeedMultiplier. */
    public boolean consumesStuckSpeed() {
        return !NO_STUCK_SPEED.equals(lastStuckSpeed);
    }

    public double getTargetScalarHoriz() {
        return 1 / lastStuckSpeed.x;
    }

    public double getTargetScalarVert() {
        return 1 / lastStuckSpeed.y;
    }

    public DesyncStatus getLastOnGround() {
        return worldData.getLastOnGround();
    }

    public boolean usesFallFlyingMovement() {
        return usesFallFlyingMovement(
                version, gliding, worldData == null ? DesyncStatus.FALSE : worldData.getClimbingAtStart());
    }

    public static boolean usesFallFlyingMovement(ClientVersion version, boolean gliding, DesyncStatus climbingAtStart) {
        // Added in 25w02a / 1.21.5: travelFallFlying calls travelInAir and then
        // stopFallFlying when already on a climbable.
        return gliding && (version.isOlderThan(ClientVersion.V_1_21_5) || !climbingAtStart.determinePessimistically());
    }

    public boolean stopsFallFlyingAfterTravel() {
        // LivingEntity#travel prioritizes fluids. In dry travelFallFlying,
        // stopFallFlying runs AFTER travelInAir, even if the move leaves the climbable.
        return vehicle == null
                && gliding
                && version.isNewerThanOrEquals(ClientVersion.V_1_21_5)
                && worldData.getClimbingAtStart().determinePessimistically()
                && !worldData.mayBeInLiquid();
    }

    public SimpleCollisionBox getToMaxPose() {
        return getToMaxPose(new SimpleCollisionBox());
    }

    /** {@link #getToMaxPose()} written into {@code out}. */
    public SimpleCollisionBox getToMaxPose(SimpleCollisionBox out) {
        return GetBoundingBox.getBoundingBoxFromPosAndSize(out, end.x, end.y, end.z, getMaxWidth(), getMaxHeight());
    }

    public SimpleCollisionBox getToMaximumExtent() {
        return getToMaximumExtent(new SimpleCollisionBox());
    }

    /** {@link #getToMaximumExtent()} written into {@code out}. */
    public SimpleCollisionBox getToMaximumExtent(SimpleCollisionBox out) {
        if (getVehicle() != null) {
            return GetBoundingBox.getBoundingBoxFromPosAndSize(out, end.x, end.y, end.z, getMaxWidth(), getMaxHeight());
        }
        SimpleCollisionBox box =
                GetBoundingBox.getBoundingBoxFromPosAndSize(out, end.x, end.y, end.z, getMaxWidth(), getMaxHeight());
        return bedrockMovement ? box.expand(0.0002) : box;
    }

    public SimpleCollisionBox getToMinimumExtent() {
        return getToMinimumExtent(new SimpleCollisionBox());
    }

    /** {@link #getToMinimumExtent()} written into {@code out}. */
    public SimpleCollisionBox getToMinimumExtent(SimpleCollisionBox out) {
        if (getVehicle() != null) {
            return GetBoundingBox.getBoundingBoxFromPosAndSize(out, end.x, end.y, end.z, getMaxWidth(), 0.6f);
        }
        SimpleCollisionBox box =
                GetBoundingBox.getBoundingBoxFromPosAndSize(out, end.x, end.y, end.z, getMaxWidth(), getMinHeight());
        return bedrockMovement ? box.expand(-0.0002) : box;
    }

    /** {@code end.subtract(start)}, shared until {@code end} changes. */
    public Vec3 getPacketMovement() {
        if (packetMovementEnd != end) {
            packetMovement = end.subtract(start);
            packetMovementEnd = end;
        }
        return packetMovement;
    }

    public SimpleCollisionBox getFromMaximumExtent() {
        return getFromMaximumExtent(new SimpleCollisionBox());
    }

    /** {@link #getFromMaximumExtent()} written into {@code out}. */
    public SimpleCollisionBox getFromMaximumExtent(SimpleCollisionBox out) {
        return GetBoundingBox.getBoundingBoxFromPosAndSize(
                out, start.x, start.y, start.z, getMaxWidth(), getMaxHeight());
    }

    public SimpleCollisionBox getFromMinimumExtent() {
        return getFromMinimumExtent(new SimpleCollisionBox());
    }

    /** {@link #getFromMinimumExtent()} written into {@code out}. */
    public SimpleCollisionBox getFromMinimumExtent(SimpleCollisionBox out) {
        if (getVehicle() != null) {
            return GetBoundingBox.getBoundingBoxFromPosAndSize(
                    out, start.x, start.y, start.z, getMaxWidth(), getMaxHeight());
        }
        return GetBoundingBox.getBoundingBoxFromPosAndSize(
                out, start.x, start.y, start.z, getMaxWidth(), getMinHeight());
    }

    public SimpleCollisionBox getToCubeCollision() {
        if (getVehicle() != null) {
            return GetBoundingBox.getBoundingBoxFromPosAndSize(end.x, end.y, end.z, getMaxWidth(), getMaxHeight());
        }
        return GetBoundingBox.getBoundingBoxFromPosAndSize(end.x, end.y, end.z, getMaxWidth(), getMinHeight());
    }

    /**
     * Where this movement's Entity#baseTick refreshed the fluid cache and applied the fluid currents. Through 26.2
     * LivingEntity#aiStep, or AbstractBoat#tick, steps a root's interpolation after Entity#baseTick, so a root stepped
     * before local travel read its fluids where it stood before that step. From 26.3 Entity#commonTick steps it first.
     */
    public Vec3 getBaseTickStart(CultPlayer player) {
        if (vehicle == null || player.isBedrockMovement() || version.isNewerThanOrEquals(ClientVersion.V_26_3))
            return start;
        Vec3 beforeInterpolation = vehicle.positionBeforeLocalInterpolation(player);
        return beforeInterpolation == null ? start : beforeInterpolation;
    }

    public SimpleCollisionBox getFromActualPose() {
        return getFromActualPose(new SimpleCollisionBox());
    }

    /** {@link #getFromActualPose()} written into {@code out}. */
    public SimpleCollisionBox getFromActualPose(SimpleCollisionBox out) {
        if (getVehicle() != null) {
            return getFromMaximumExtent(out);
        }
        return GetBoundingBox.getBoundingBoxFromPosAndSize(
                out, start.x, start.y, start.z, pose.width * scale, pose.height * scale);
    }

    public SimpleCollisionBox getToActualPose() {
        return getToActualPose(new SimpleCollisionBox());
    }

    /** {@link #getToActualPose()} written into {@code out}. */
    public SimpleCollisionBox getToActualPose(SimpleCollisionBox out) {
        return getActualPoseAt(end, out);
    }

    /** EntityDimensions#makeBoundingBox at {@code position}, with this movement's dimensions. */
    public SimpleCollisionBox getActualPoseAt(Vec3 position) {
        return getActualPoseAt(position, new SimpleCollisionBox());
    }

    /** {@link #getActualPoseAt(Vec3)} written into {@code out}. */
    public SimpleCollisionBox getActualPoseAt(Vec3 position, SimpleCollisionBox out) {
        if (getVehicle() != null) {
            return GetBoundingBox.getBoundingBoxFromPosAndSize(
                    out, position.x, position.y, position.z, getMaxWidth(), getMaxHeight());
        }
        return GetBoundingBox.getBoundingBoxFromPosAndSize(
                out, position.x, position.y, position.z, pose.width * scale, pose.height * scale);
    }

    public float getMaxSpeed(CultPlayer player) {
        float blockUnderPlayer = BlockProperties.getFriction(player, lastTickMainSupportingBlockData, start);
        return getFrictionInfluencedSpeed(blockUnderPlayer, player);
    }

    /** Radius of the circular horizontal input allowance around the input-free movement of {@code candidate}. */
    public double getHorizontalInputRadius(CultPlayer player, PredVector candidate) {
        // A candidate that already holds its exact moveRelative input has no input left to allow.
        if (candidate != null && candidate.hasTravelInput()) return 0.0D;
        return getVehicle() == null
                ? getHorizontalInputAllowance(player, getSprintOutcome(player, candidate))
                : getMaxSpeed(player);
    }

    /**
     * The 1.3x allowance on each state's input speed, widened on the ground to every getSpeed() the client's sprint
     * modifier allows there in {@code outcome}.
     */
    public double getHorizontalInputAllowance(CultPlayer player, ClientSprintState.Outcome outcome) {
        float blockUnderPlayer = BlockProperties.getFriction(player, lastTickMainSupportingBlockData, start);
        double allowance = 0;
        for (boolean onGround : worldData.getLastOnGround().getStates()) {
            for (boolean water : worldData.waterStates()) {
                for (boolean lava : worldData.lavaStates()) {
                    allowance = Math.max(
                            allowance,
                            getFrictionInfluencedSpeed(blockUnderPlayer, player, onGround, water, lava, outcome)
                                    * 1.3D);
                    if (groundSprintSpeed(player, onGround, water || lava)) {
                        for (float speed : getGroundMoveRelativeSpeeds(player, outcome)) {
                            allowance = Math.max(allowance, speed);
                        }
                    }
                }
            }
        }
        return allowance;
    }

    /** The sprint modifier outcome {@code candidate} moves with. */
    public ClientSprintState.Outcome getSprintOutcome(CultPlayer player, PredVector candidate) {
        return candidate == null || vehicle != null ? ClientSprintState.Outcome.ANY : candidate.getSprintOutcome();
    }

    /**
     * The largest moveRelative speed the client's sprint state permits. LivingEntity#getFrictionInfluencedSpeed
     * reads getSpeed(), which carries the minecraft:sprinting modifier, on the ground, and Player#getFlyingSpeed,
     * which reads the sprint flag, in the air.
     */
    public double getMaxSprintInputSpeed(CultPlayer player, PredVector candidate) {
        ClientSprintState.Outcome outcome = getSprintOutcome(player, candidate);
        float blockUnderPlayer = BlockProperties.getFriction(player, lastTickMainSupportingBlockData, start);
        double maxSpeed = 0;
        for (boolean onGround : worldData.getLastOnGround().getStates()) {
            for (boolean water : worldData.waterStates()) {
                for (boolean lava : worldData.lavaStates()) {
                    if (groundSprintSpeed(player, onGround, water || lava)) {
                        for (float speed : getGroundMoveRelativeSpeeds(player, outcome)) {
                            maxSpeed = Math.max(maxSpeed, speed);
                        }
                    } else {
                        maxSpeed = Math.max(
                                maxSpeed,
                                getFrictionInfluencedSpeed(blockUnderPlayer, player, onGround, water, lava, outcome)
                                        * (float) sprintSpeedMultiplier(player, onGround, water || lava));
                    }
                }
            }
        }
        return maxSpeed;
    }

    /** LivingEntity#getSpeed on the ground carries the minecraft:sprinting modifier the client holds. */
    private boolean groundSprintSpeed(CultPlayer player, boolean onGround, boolean fluid) {
        return onGround && !fluid && vehicle == null && !player.isFlying && sprintModifier;
    }

    /**
     * LivingEntity#getFrictionInfluencedSpeed on the ground for each getSpeed() the client's sprint modifier allows in
     * {@code outcome}, with vanilla's modifier also allowed for a riptide spin's sprint.
     */
    public float[] getGroundMoveRelativeSpeeds(CultPlayer player, ClientSprintState.Outcome outcome) {
        if (groundMoveRelativeSpeeds == null) {
            groundMoveRelativeSpeeds = new float[ClientSprintState.Outcome.values().length][];
        }
        float[] cached = groundMoveRelativeSpeeds[outcome.ordinal()];
        if (cached == null) {
            float blockUnderPlayer = BlockProperties.getFriction(player, lastTickMainSupportingBlockData, start);
            double[] speeds = vehicle == null
                    ? playerMovementSpeeds(player, outcome)
                    : new double[] {getPlayerMovementSpeed(player)};
            cached = new float[speeds.length];
            for (int i = 0; i < speeds.length; i++) {
                cached[i] = getFrictionInfluencedSpeed(
                        blockUnderPlayer, player, speeds[i], true, false, false, ClientSprintState.Outcome.ANY);
            }
            groundMoveRelativeSpeeds[outcome.ordinal()] = cached;
        }
        return cached;
    }

    /**
     * The player's LivingEntity#getSpeed values in {@code outcome} after this aiStep: none of them carries
     * minecraft:sprinting once aiStep certainly removed it, and movement_speed without it joins them when it may have.
     */
    private double[] playerMovementSpeeds(CultPlayer player, ClientSprintState.Outcome outcome) {
        double unmodified = entities.getPlayerMovementSpeed();
        DesyncStatus cleared = aiStepSprintClear(player);
        if (cleared == DesyncStatus.TRUE) return new double[] {unmodified};
        double[] speeds = entities.getPossiblePlayerMovementSpeeds(
                sprintModifier && !player.sprintState.modifier() || aiStepSprintRestart(player) == DesyncStatus.UNKNOWN,
                aiStepSprintRestart(player) == DesyncStatus.TRUE ? ClientSprintState.Outcome.CANONICAL : outcome);
        if (cleared == DesyncStatus.FALSE) return speeds;
        for (double speed : speeds) if (speed == unmodified) return speeds;
        double[] withUnmodified = Arrays.copyOf(speeds, speeds.length + 1);
        withUnmodified[speeds.length] = unmodified;
        return withUnmodified;
    }

    private double sprintSpeedMultiplier(CultPlayer player, boolean onGround, boolean fluid) {
        // Creative flight and vehicle travel keep their existing allowance.
        if (vehicle != null || player.isFlying) return 1.3D;
        // LivingEntity#travelInFluid moves by a fluid speed that already includes any sprint modifier
        if (fluid || onGround) return 1.0D;
        return isSprinting.determineOptimistically() ? 1.3D : 1.0D;
    }

    public float getMoveRelativeSpeed(CultPlayer player, boolean onGround, boolean water, boolean lava) {
        float blockUnderPlayer = BlockProperties.getFriction(player, lastTickMainSupportingBlockData, start);
        return getFrictionInfluencedSpeed(blockUnderPlayer, player, onGround, water, lava);
    }

    /**
     * Every {@link #getMoveRelativeSpeed} the client's sprint modifier allows in {@code outcome}. Only
     * LivingEntity#travelInWater has more than one here: it reads Player#getSpeed(), the movement_speed that carries the
     * minecraft:sprinting modifier the client holds. On the ground see {@link #getGroundMoveRelativeSpeeds}.
     */
    public float[] getMoveRelativeSpeeds(
            CultPlayer player, boolean onGround, boolean water, boolean lava, ClientSprintState.Outcome outcome) {
        if (water && playerWaterSprintSpeed(player)) return getWaterMoveRelativeSpeeds(player, outcome);
        return new float[] {getMoveRelativeSpeed(player, onGround, water, lava)};
    }

    /** A player's LivingEntity#travelInWater reads getSpeed(), which may carry minecraft:sprinting. */
    private boolean playerWaterSprintSpeed(CultPlayer player) {
        return vehicle == null && sprintModifier && fluidTravel(player);
    }

    /** Player#isAffectedByFluids is false in flight, so LivingEntity#travel takes travelInAir in water and lava. */
    private boolean fluidTravel(CultPlayer player) {
        return (vehicle != null || !player.isFlying) && !worldData.isCanStandOnStartFluid();
    }

    /**
     * LivingEntity#travelInWater's moveRelative speed for each Player#getSpeed() the client's sprint modifier allows in
     * {@code outcome}, the same values {@link #getGroundMoveRelativeSpeeds} reads on the ground.
     */
    private float[] getWaterMoveRelativeSpeeds(CultPlayer player, ClientSprintState.Outcome outcome) {
        if (waterMoveRelativeSpeeds == null) {
            waterMoveRelativeSpeeds = new float[ClientSprintState.Outcome.values().length][];
        }
        float[] cached = waterMoveRelativeSpeeds[outcome.ordinal()];
        if (cached == null) {
            double[] speeds = playerMovementSpeeds(player, outcome);
            cached = new float[speeds.length];
            for (int i = 0; i < speeds.length; i++) cached[i] = waterMoveRelativeSpeed(speeds[i]);
            waterMoveRelativeSpeeds[outcome.ordinal()] = cached;
        }
        return cached;
    }

    /** LivingEntity#travelInWater lerps 0.02 toward getSpeed() by water_movement_efficiency, halved off the ground. */
    private float waterMoveRelativeSpeed(double livingSpeed) {
        float speed = 0.02f;
        if (waterMovementEfficiency > 0) speed += ((float) livingSpeed - speed) * waterMovementEfficiency;
        return speed;
    }

    /** The moveRelative speed of a ridden camel whose controller's sprint flag is {@code riderSprinting}. */
    public float getMoveRelativeSpeed(
            CultPlayer player, boolean onGround, boolean water, boolean lava, boolean riderSprinting) {
        float blockUnderPlayer = BlockProperties.getFriction(player, lastTickMainSupportingBlockData, start);
        return getFrictionInfluencedSpeed(
                blockUnderPlayer,
                player,
                getPlayerMovementSpeed(player, riderSprinting),
                onGround,
                water,
                lava,
                ClientSprintState.Outcome.ANY);
    }

    public float getMaxWidth() {
        if (getVehicle() != null) {
            return BoundingBoxSize.getWidth(getVehicle(), version, getBedrockInput() != null);
        }
        return 0.6f * scale;
    }

    /** The height travel moves. Camel#standUp's pose change has refreshed the dimensions by then. */
    public float getMaxHeight() {
        if (getVehicle() instanceof PacketEntityCamel camel && getBedrockInput() == null) {
            return riddenStandUp
                    ? BoundingBoxSize.getCamelStandingHeight(camel, version)
                    : BoundingBoxSize.getHeight(camel, version);
        }
        if (getVehicle() != null) {
            return BoundingBoxSize.getHeight(getVehicle(), version, getBedrockInput() != null);
        }
        return 1.8f * scale;
    }

    /** {@link #getMaxHeight} before Camel#tickRidden stands the vehicle up, as Entity#baseTick reads it. */
    public float getHeightBeforeTravel() {
        return riddenStandUp && getVehicle() instanceof PacketEntityCamel camel
                ? BoundingBoxSize.getHeight(camel, version)
                : getMaxHeight();
    }

    /** {@link #getActualPoseAt} before Camel#tickRidden stands the vehicle up, as Entity#baseTick samples it. */
    public SimpleCollisionBox getPoseBeforeTravelAt(Vec3 position, SimpleCollisionBox out) {
        if (getVehicle() == null) return getActualPoseAt(position, out);
        return GetBoundingBox.getBoundingBoxFromPosAndSize(
                out, position.x, position.y, position.z, getMaxWidth(), getHeightBeforeTravel());
    }

    /** The smallest player pose height (swimming, gliding, crawling) at the player's scale. */
    public float getMinHeight() {
        return 0.6f * scale;
    }

    private float getFrictionInfluencedSpeed(float friction, CultPlayer player) {
        float maxSpeed = 0;
        for (boolean onGround : worldData.getLastOnGround().getStates()) {
            for (boolean water : worldData.waterStates()) {
                for (boolean lava : worldData.lavaStates()) {
                    maxSpeed = Math.max(maxSpeed, getFrictionInfluencedSpeed(friction, player, onGround, water, lava));
                }
            }
        }
        return maxSpeed;
    }

    private float getFrictionInfluencedSpeed(
            float friction, CultPlayer player, boolean isOnGround, boolean water, boolean lava) {
        return getFrictionInfluencedSpeed(friction, player, isOnGround, water, lava, ClientSprintState.Outcome.ANY);
    }

    private float getFrictionInfluencedSpeed(
            float friction,
            CultPlayer player,
            boolean isOnGround,
            boolean water,
            boolean lava,
            ClientSprintState.Outcome outcome) {
        return getFrictionInfluencedSpeed(
                friction, player, getPlayerMovementSpeed(player), isOnGround, water, lava, outcome);
    }

    private float getFrictionInfluencedSpeed(
            float friction,
            CultPlayer player,
            double normalSpeed,
            boolean isOnGround,
            boolean water,
            boolean lava,
            ClientSprintState.Outcome outcome) {

        if (vehicle instanceof PacketEntityNautilus && water) {
            // AbstractNautilus#travelInWater bypasses LivingEntity's generic
            // 0.02 fluid acceleration and calls moveRelative(getSpeed(), input).
            // travelRidden sets getSpeed() to getRiddenSpeed immediately before
            // travel, which is 0.0325 * MOVEMENT_SPEED while in water.
            return (float) normalSpeed;
        }

        boolean fluidTravel = fluidTravel(player);
        if (water && fluidTravel) {
            // A player's getSpeed carries minecraft:sprinting only while applied; a vehicle's is getRiddenSpeed
            if (!playerWaterSprintSpeed(player)) return waterMoveRelativeSpeed(normalSpeed);
            float[] speeds = getWaterMoveRelativeSpeeds(player, outcome);
            float max = speeds[0];
            for (float speed : speeds) max = Math.max(max, speed);
            return max;
        }

        if (lava && fluidTravel) {
            return 0.02f;
        }

        if (isOnGround) {
            return (float) (normalSpeed * Friction.getGroundSpeedFactor(player, friction));
        }

        // The game uses values known as flyingSpeed for some vehicles in the air
        if (vehicle != null) {
            if (vehicle.type == EntityTypeIds.PIG
                    || vehicle instanceof PacketEntityHorse
                    || vehicle instanceof PacketEntityStrider
                    || vehicle instanceof PacketEntityNautilus) {
                // MCP-Reborn LivingEntity#getFlyingSpeed uses this.getSpeed() * 0.1F for
                // player-controlled ridden living entities. normalSpeed already mirrors
                // getRiddenSpeed(...) for pigs, horses, and striders below.
                return (float) (normalSpeed * 0.1f);
            }
        }

        if (player.isFlying) {
            // TODO: Lunar support (FUCK LUNAR)
            return player.flySpeed * 20 * (player.isSprinting ? 0.1f : 0.05f);
        }

        return 0.02F;
    }

    /**
     * The controller's sprint flag when Camel#getRiddenSpeed reads it. Minecraft#tick handles the attack key before
     * the level tick, so Player#causeExtraKnockback's setSprinting(false) holds through the camel's travel; the
     * passenger's LocalPlayer#aiStep then re-sprints before sendIsSprintingIfNeeded, so no STOP_SPRINTING is sent.
     */
    public DesyncStatus getCamelRiderSprinting(CultPlayer player) {
        if (player.vehicleData.camelSprintingState == SprintingState.STOPPED || minAttackSlow > 0) {
            return DesyncStatus.FALSE;
        }
        return maxAttackSlow > 0 ? DesyncStatus.UNKNOWN : DesyncStatus.TRUE;
    }

    private double getPlayerMovementSpeed(CultPlayer player) {
        return getPlayerMovementSpeed(
                player,
                vehicle instanceof PacketEntityCamel
                        && getCamelRiderSprinting(player).determineOptimistically());
    }

    private double getPlayerMovementSpeed(CultPlayer player, boolean riderSprinting) {
        if (vehicle == null) {
            return entities.getPlayerMovementSpeed();
        }
        if (vehicle instanceof PacketEntityCamel camel) {
            final double sprintBonus = riderSprinting && camel.dashCooldown == 0 ? 0.1 : 0;
            return camel.movementSpeedAttribute + sprintBonus;
        }

        if (vehicle instanceof PacketEntityHorse) {
            return ((PacketEntityHorse) vehicle).movementSpeedAttribute;
        }
        if (vehicle instanceof PacketEntityNautilus nautilus) {
            boolean water = worldData != null && worldData.mayBeInWater();
            return (water ? 0.0325F : 0.02F) * nautilus.movementSpeedAttribute;
        }
        // Must be rideable?
        if (vehicle instanceof PacketEntityRideable) {
            PacketEntityRideable rideable = (PacketEntityRideable) vehicle;
            double speed = rideable.movementSpeedAttribute;
            if (vehicle.type == EntityTypeIds.PIG) {
                speed *= 0.225D;
            } else if (vehicle.type == EntityTypeIds.STRIDER) {
                boolean cold = player.isBedrockMovement()
                        ? isBedrockMountedStriderSuffocating(player)
                        : ((PacketEntityStrider) vehicle).isSuffocating();
                speed *= cold ? 0.35F : 0.55F;
            }
            return (float) (speed * rideable.boost.factor(player.getClientVersion()));
        }
        return 0; // ??? I guess riding a weird vehicle?
    }

    private boolean isBedrockMountedStriderSuffocating(CultPlayer player) {
        // Bedrock retains its existing block-based warmth model.
        BlockPos blockPos =
                new BlockPos((int) Math.floor(start.x), (int) Math.floor(start.y), (int) Math.floor(start.z));
        int currentBlock = player.compensatedWorld.getBlockStateIdAt(blockPos);
        int legacyBlock = BlockProperties.getOnPos(player, lastTickMainSupportingBlockData, start);
        boolean warm = BlockTags.STRIDER_WARM_BLOCKS.test(currentBlock)
                || BlockTags.STRIDER_WARM_BLOCKS.test(legacyBlock)
                || player.compensatedWorld.getLavaFluidLevelAt(blockPos) > 0.0D;
        return !warm;
    }

    public boolean isBedrockTeleportTick() {
        return bedrockTeleport != null;
    }

    /**
     * Every field but the engine handles and the per-tick caches, as the Cult Log prints them. A new field belongs here
     * too; CultLogContentTest checks.
     */
    @Override
    public void appendTo(DebugText.Builder sb) {
        sb.append("SimulationContext(itemUse=").append(itemUse);
        sb.append(", serverUseTracked=").append(serverUseTracked);
        sb.append(", start=").append(start);
        sb.append(", end=").append(end);
        sb.append(", target=").append(target);
        sb.append(", vehicle=").append(vehicle);
        sb.append(", xRot=").append(xRot);
        sb.append(", lastXRot=").append(lastXRot);
        sb.append(", yRot=").append(yRot);
        sb.append(", lastYRot=").append(lastYRot);
        sb.append(", onGround=").append(onGround);
        sb.append(", sneaking=").append(sneaking);
        sb.append(", lastSneaking=").append(lastSneaking);
        sb.append(", gliding=").append(gliding);
        sb.append(", jumpAmplifier=").append(jumpAmplifier);
        sb.append(", depthStriderLevel=").append(depthStriderLevel);
        sb.append(", swiftSneakLevel=").append(swiftSneakLevel);
        sb.append(", riddenStandUp=").append(riddenStandUp);
        sb.append(", isSprinting=").append(isSprinting);
        sb.append(", sprintModifier=").append(sprintModifier);
        appendSpeeds(sb.append(", groundMoveRelativeSpeeds="), groundMoveRelativeSpeeds);
        appendSpeeds(sb.append(", waterMoveRelativeSpeeds="), waterMoveRelativeSpeeds);
        sb.append(", aiStepSprintClear=").append(aiStepSprintClear);
        sb.append(", aiStepSprintRestart=").append(aiStepSprintRestart);
        sb.append(", movementEfficiency=").append(movementEfficiency);
        sb.append(", waterMovementEfficiency=").append(waterMovementEfficiency);
        sb.append(", minAttackSlow=").append(minAttackSlow);
        sb.append(", maxAttackSlow=").append(maxAttackSlow);
        sb.append(", worldData=").append(worldData);
        sb.append(", movementPatch=").append(movementPatch);
        sb.append(", fluidTagOrder=").append(fluidTagOrder);
        sb.append(", pistonStartState=").append(pistonStartState);
        sb.append(", riptideLevel=").append(riptideLevel);
        sb.append(", riptideReleaseCertain=").append(riptideReleaseCertain);
        sb.append(", riptideLiftStuck=").append(riptideLiftStuck);
        sb.append(", preTravelMovement=").append(preTravelMovement);
        sb.append(", movesBeforeTravel=").appendAll(movesBeforeTravel);
        sb.append(", spinRebound=").append(spinRebound);
        sb.append(", bedrockMovement=").append(bedrockMovement);
        sb.append(", noPhysics=").append(noPhysics);
        sb.append(", noPhysicsHorizontalCollision=").append(noPhysicsHorizontalCollision);
        sb.append(", withheldPosition=").append(withheldPosition);
        sb.append(", lastStuckSpeed=").append(lastStuckSpeed);
        sb.append(", horseInputs=").append(horseInputs);
        sb.append(", boatInputs=").append(boatInputs);
        sb.append(", lastTickMainSupportingBlockData=").append(lastTickMainSupportingBlockData);
        sb.append(", pose=").append(pose);
        sb.append(", scale=").append(scale);
        sb.append(", rootClientVelocityAtPrediction=").append(rootClientVelocityAtPrediction);
        sb.append(", normalizedRootClientVelocityAtPrediction=").append(normalizedRootClientVelocityAtPrediction);
        sb.append(", rootClientVelocityPendingAtPrediction=").append(rootClientVelocityPendingAtPrediction);
        sb.append(", rideTickVehicleBox=").append(rideTickVehicleBox);
        sb.append(", requiredCurrentMoveStuckSpeed=").append(requiredCurrentMoveStuckSpeed);
        sb.append(", profileCarry=").append(profileCarry);
        // The pass holds the movers' live world state; the log says only whether one follows the travel.
        sb.append(", passAfterTravel=").append(passAfterTravel != null);
        sb.append(", spinSprintResetRequired=").append(spinSprintResetRequired);
        sb.append(", authoredInput=").append(authoredInput);
        sb.append(", bedrockInput=").append(bedrockInput);
        sb.append(", bedrockVersion=").append(bedrockVersion);
        sb.append(", bedrockInputTick=").append(bedrockInputTick);
        sb.append(", hasTrustedAuthoredInput=").append(hasTrustedAuthoredInput);
        sb.append(", bedrockTeleport=").append(bedrockTeleport);
        sb.append(')');
    }

    /** {@link java.util.Arrays#deepToString} of the speeds. */
    private static void appendSpeeds(DebugText.Builder sb, float[][] speeds) {
        if (speeds == null) {
            sb.append("null");
            return;
        }
        sb.append('[');
        for (int i = 0; i < speeds.length; i++) {
            if (i > 0) sb.append(", ");
            float[] row = speeds[i];
            if (row == null) {
                sb.append("null");
                continue;
            }
            sb.append('[');
            for (int j = 0; j < row.length; j++) {
                if (j > 0) sb.append(", ");
                sb.append(row[j]);
            }
            sb.append(']');
        }
        sb.append(']');
    }

    @Override
    public String toString() {
        return DebugText.print(this);
    }

    /** The box of a solid actor a Bedrock move collides with; a happy ghast lifts a mover inside its top. */
    public record HardCollidingEntityCollision(SimpleCollisionBox box, boolean happyGhast) {}
}
