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
import lombok.ToString;

@Getter
@ToString
public class SimulationContext implements Cloneable {
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
    @ToString.Exclude
    private Vec3 packetMovement;

    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    private Vec3 packetMovementEnd;

    @ToString.Exclude
    ClientVersion version;

    @ToString.Exclude
    TrigHandler trig;

    @ToString.Exclude
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

    @Setter
    DesyncStatus isSprinting;
    // Whether movement_speed may carry minecraft:sprinting during travel
    @Setter
    boolean sprintModifier = true;

    private float[][] groundMoveRelativeSpeeds;
    // JavaAiStepSprint#clearsModifier for this context's aiStep; null until read, and again after its world changes.
    @Getter(AccessLevel.NONE)
    private DesyncStatus aiStepSprintClear;
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
    @ToString.Exclude
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

    @ToString.Exclude
    List<SimpleCollisionBox> entityCollisionBoxesForMovementTick;

    @ToString.Exclude
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
        this.groundMoveRelativeSpeeds = null;
    }

    /**
     * Whether this context's LocalPlayer#aiStep removed minecraft:sprinting while the flag stayed clear
     * ({@link JavaAiStepSprint#clearsModifier}). Its travel moves without the modifier when TRUE, and may when UNKNOWN.
     */
    public DesyncStatus aiStepSprintClear(CultPlayer player) {
        if (aiStepSprintClear == null) aiStepSprintClear = JavaAiStepSprint.clearsModifier(player, this);
        return aiStepSprintClear;
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
            copy.groundMoveRelativeSpeeds = null;
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
        return GetBoundingBox.getBoundingBoxFromPosAndSize(end.x, end.y, end.z, getMaxWidth(), getMaxHeight());
    }

    public SimpleCollisionBox getToMaximumExtent() {
        if (getVehicle() != null) {
            return GetBoundingBox.getBoundingBoxFromPosAndSize(end.x, end.y, end.z, getMaxWidth(), getMaxHeight());
        }
        SimpleCollisionBox box =
                GetBoundingBox.getBoundingBoxFromPosAndSize(end.x, end.y, end.z, getMaxWidth(), getMaxHeight());
        return bedrockMovement ? box.expand(0.0002) : box;
    }

    public SimpleCollisionBox getToMinimumExtent() {
        if (getVehicle() != null) {
            return GetBoundingBox.getBoundingBoxFromPosAndSize(end.x, end.y, end.z, getMaxWidth(), 0.6f);
        }
        SimpleCollisionBox box =
                GetBoundingBox.getBoundingBoxFromPosAndSize(end.x, end.y, end.z, getMaxWidth(), getMinHeight());
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
        return GetBoundingBox.getBoundingBoxFromPosAndSize(start.x, start.y, start.z, getMaxWidth(), getMaxHeight());
    }

    public SimpleCollisionBox getFromMinimumExtent() {
        if (getVehicle() != null) {
            return GetBoundingBox.getBoundingBoxFromPosAndSize(
                    start.x, start.y, start.z, getMaxWidth(), getMaxHeight());
        }
        return GetBoundingBox.getBoundingBoxFromPosAndSize(start.x, start.y, start.z, getMaxWidth(), getMinHeight());
    }

    public SimpleCollisionBox getToCubeCollision() {
        if (getVehicle() != null) {
            return GetBoundingBox.getBoundingBoxFromPosAndSize(end.x, end.y, end.z, getMaxWidth(), getMaxHeight());
        }
        return GetBoundingBox.getBoundingBoxFromPosAndSize(end.x, end.y, end.z, getMaxWidth(), getMinHeight());
    }

    public SimpleCollisionBox getFromActualPose() {
        if (getVehicle() != null) {
            return getFromMaximumExtent();
        }
        return GetBoundingBox.getBoundingBoxFromPosAndSize(
                start.x, start.y, start.z, pose.width * scale, pose.height * scale);
    }

    public SimpleCollisionBox getToActualPose() {
        return getActualPoseAt(end);
    }

    /** EntityDimensions#makeBoundingBox at {@code position}, with this movement's dimensions. */
    public SimpleCollisionBox getActualPoseAt(Vec3 position) {
        if (getVehicle() != null) {
            return GetBoundingBox.getBoundingBoxFromPosAndSize(
                    position.x, position.y, position.z, getMaxWidth(), getMaxHeight());
        }
        return GetBoundingBox.getBoundingBoxFromPosAndSize(
                position.x, position.y, position.z, pose.width * scale, pose.height * scale);
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
        double[] speeds =
                entities.getPossiblePlayerMovementSpeeds(sprintModifier && !player.sprintState.modifier(), outcome);
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

    public float getMaxHeight() {
        if (getVehicle() instanceof PacketEntityCamel camel && getBedrockInput() == null) {
            return BoundingBoxSize.getCamelPoseHeight(camel, version);
        }
        if (getVehicle() != null) {
            return BoundingBoxSize.getHeight(getVehicle(), version, getBedrockInput() != null);
        }
        return 1.8f * scale;
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

        // Player#isAffectedByFluids is false in flight, so LivingEntity#travel takes travelInAir in water and lava.
        boolean fluidTravel = vehicle != null || !player.isFlying;
        if (water && fluidTravel) {
            float speed = 0.02f;

            if (waterMovementEfficiency > 0) {
                // A player's getSpeed carries minecraft:sprinting only while applied; a vehicle's is getRiddenSpeed
                float livingSpeed = (float)
                        (vehicle == null && sprintModifier && aiStepSprintClear(player) != DesyncStatus.TRUE
                                ? entities.getMaxPlayerMovementSpeed(!player.sprintState.modifier(), outcome)
                                : normalSpeed);
                speed += (livingSpeed - speed) * waterMovementEfficiency;
            }
            return speed;
        }

        if (lava && fluidTravel) {
            return 0.02f;
        }

        if (isOnGround) {
            // MCP-Reborn 26.2 LivingEntity#getFrictionInfluencedSpeed receives the
            // friction_modifier-adjusted block friction and only boosts movement
            // speed on slippery blocks (friction > 0.6).
            if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2)) {
                float modifiedFriction = Friction.computeModifiedFriction(
                        friction, (float) player.compensatedEntities.getEntityInControl().frictionModifier);
                if (modifiedFriction > 0.6f) {
                    return (float)
                            (normalSpeed * (0.21600002f / (modifiedFriction * modifiedFriction * modifiedFriction)));
                }
                return (float) normalSpeed;
            }
            return (float) (normalSpeed * (0.21600002f / (friction * friction * friction)));
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

    public record HardCollidingEntityCollision(SimpleCollisionBox box, boolean boatLike, boolean minecartLike) {}
}
