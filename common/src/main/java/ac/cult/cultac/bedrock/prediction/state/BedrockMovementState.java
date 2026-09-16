package ac.cult.cultac.bedrock.prediction.state;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockPositionTranslator;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import ac.cult.cultac.bedrock.prediction.input.BedrockPoseInputData;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockBoundingBoxMode;
import ac.cult.cultac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact;
import java.util.Objects;

public record BedrockMovementState(
    Motion motion,
    ActorState actor,
    TickMemory memory,
    boolean hasTeleported
) {
    public BedrockMovementState(Motion motion, ActorState actor, TickMemory memory) {
        this(motion, actor, memory, false);
    }

    public BedrockMovementState {
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(memory, "memory");
    }

    public static BedrockMovementState fromPhysicalFeet(
        Vec3d physicalFeetPosition,
        Vec3d velocity,
        BedrockInputFrame inputFrame,
        BedrockCollisionFlags collisionFlags
    ) {
        return fromPhysicalFeet(
            physicalFeetPosition,
            velocity,
            inputFrame,
            collisionFlags,
            collisionFlags.onGround() ? Medium.GROUND : Medium.AIR
        );
    }

    public static BedrockMovementState fromPhysicalFeet(
        Vec3d physicalFeetPosition,
        Vec3d velocity,
        BedrockInputFrame inputFrame,
        BedrockCollisionFlags collisionFlags,
        Medium movementBranch
    ) {
        return fromPhysicalFeet(physicalFeetPosition, velocity, inputFrame, collisionFlags, movementBranch,
                BedrockCoordinateFrame.IDENTITY);
    }

    public static BedrockMovementState fromPhysicalFeet(Vec3d physicalFeetPosition, Vec3d velocity,
            BedrockInputFrame inputFrame, BedrockCollisionFlags collisionFlags, Medium movementBranch,
            BedrockCoordinateFrame coordinateFrame) {
        boolean swimming = BedrockSwimmingPoseProgress.initialSwimming(inputFrame);
        return new BedrockMovementState(
            new Motion(
                BedrockPositionTranslator.normalizePhysicalFeetPosition(physicalFeetPosition, coordinateFrame),
                velocity,
                0.0D,
                Vec3d.ZERO,
                inputFrame,
                collisionFlags, coordinateFrame
            ),
            new ActorState(
                new ContactState(
                    BlockMovementSlowdownState.NONE,
                    BedrockClimbableContact.NONE,
                    movementBranch == Medium.WATER),
                new PoseState(false, swimming,
                    BedrockSwimmingPoseProgress.initialHorizontalPose(inputFrame), false),
                new TravelMode(false, false, false, movementBranch == Medium.WATER, movementBranch),
                BedrockBoundingBoxMode.initial(inputFrame),
                PlayerDimensionsState.DEFAULT,
                null
            ),
            new TickMemory(
                0L,
                0L,
                0L,
                0.0F,
                BedrockSwimmingPoseProgress.initialSwimAmount(inputFrame),
                0L,
                false,
                0L,
                initialSneakingTicks(inputFrame),
                0L,
                BedrockDolphinBoost.INITIAL
            )
        );
    }

    public BedrockCoordinateFrame coordinateFrame() { return motion.coordinateFrame(); }

    public BedrockMovementState withCoordinateFrame(BedrockCoordinateFrame frame) {
        return frame.equals(coordinateFrame()) ? this : withMotion(new Motion(physicalFeetPosition(), velocity(),
                lastPhysicalDisplacementSquared(), lastPhysicalDisplacement(), inputFrame(), collisionFlags(), frame));
    }

    public Vec3d physicalFeetPosition() { return motion.physicalFeetPosition(); }
    public Vec3d velocity() { return motion.velocity(); }
    public double lastPhysicalDisplacementSquared() { return motion.lastPhysicalDisplacementSquared(); }
    public Vec3d lastPhysicalDisplacement() { return motion.lastPhysicalDisplacement(); }
    public BedrockInputFrame inputFrame() { return motion.inputFrame(); }
    public BedrockCollisionFlags collisionFlags() { return motion.collisionFlags(); }
    public long simulationTick() { return memory.simulationTick(); }
    public long powderSnowTicks() { return memory.powderSnowTicks(); }
    public BlockMovementSlowdownState pendingBlockMovementSlowdownState() { return actor.pendingBlockMovementSlowdownState(); }
    public BedrockClimbableContact climbableContact() { return actor.climbableContact(); }
    public boolean scaffoldingDescendAllowed() { return actor.scaffoldingDescendAllowed(); }
    public boolean gliding() { return actor.gliding(); }
    public boolean glidingRequest() { return actor.glidingRequest(); }
    public long fallFlyTicks() { return memory.fallFlyTicks(); }
    public float fallDistance() { return memory.fallDistance(); }
    public boolean sprinting() { return actor.sprinting(); }
    public boolean swimming() { return actor.swimming(); }
    public boolean horizontalPose() { return actor.horizontalPose(); }
    public double swimAmount() { return memory.swimAmount(); }
    public BedrockDolphinBoost dolphinBoost() { return memory.dolphinBoost(); }
    public BedrockCameraWaterState cameraWater() { return memory.cameraWater(); }

    public BedrockMovementState withAcknowledgedPose(Boolean crawling, Boolean swimming, Boolean spinning) {
        PoseState pose = actor.pose();
        ActorState nextActor = actor.copy(actor.contacts(), new PoseState(pose.sprinting(),
            swimming == null ? pose.swimming() : swimming,
            crawling == null ? pose.horizontal() : crawling, pose.itemUseSlowdownActive()), actor.travel());
        return new BedrockMovementState(motion, nextActor,
            spinning == null ? memory : memory.withSpin(spinning), hasTeleported);
    }
    public long riptideChargeTicks() { return memory.riptideChargeTicks(); }
    public boolean riptideSpinActive() { return memory.riptideSpinActive(); }
    public long riptideSpinTicks() { return memory.riptideSpinTicks(); }
    public long sneakingTicks() { return memory.sneakingTicks(); }
    public boolean itemUseSlowdownActive() { return actor.itemUseSlowdownActive(); }
    public long itemUseSlowdownTicks() { return memory.itemUseSlowdownTicks(); }
    public boolean autoClimbTravel() { return actor.autoClimbTravel(); }
    public boolean waterTravelFlag() { return actor.waterTravelFlag(); }
    public boolean wasInWaterFlag() { return actor.wasInWaterFlag(); }
    public Medium movementBranch() { return actor.movementBranch(); }
    public BedrockBoundingBoxMode boundingBoxMode() { return actor.boundingBoxMode(); }
    public PlayerDimensionsState playerDimensions() { return actor.playerDimensions(); }
    public boolean explicitPlayerDimensions() { return actor.acknowledgedPlayerDimensions() != null; }
    public PlayerDimensionsState acknowledgedPlayerDimensions() { return actor.acknowledgedPlayerDimensions(); }

    public Vec3d bedrockPacketPosition() {
        return BedrockPositionTranslator.physicalFeetToPacketPosition(physicalFeetPosition(), coordinateFrame());
    }

    public long clientTick() {
        return inputFrame().clientTick();
    }

    public boolean movementGrounded() {
        return collisionFlags().onGround() || movementBranch() == Medium.GROUND;
    }


    public BedrockMovementState withDolphinBoost(BedrockDolphinBoost value) {
        return new BedrockMovementState(motion, actor, memory.withDolphinBoost(value), hasTeleported);
    }

    public BedrockMovementState withCameraWater(BedrockCameraWaterState value) {
        return new BedrockMovementState(motion, actor, memory.withCameraWater(value), hasTeleported);
    }

    public BedrockMovementState withTeleportPending() {
        return hasTeleported ? this : new BedrockMovementState(motion, actor, memory, true);
    }

    public BedrockMovementState withMovementBranch(Medium movementBranch) {
        return withActor(actor.withMovementBranch(movementBranch));
    }

    public BedrockMovementState withWaterTravelFlag(boolean waterTravelFlag) {
        return actor.waterTravelFlag() == waterTravelFlag
            ? this
            : withActor(actor.withWaterTravelFlag(waterTravelFlag));
    }

    public BedrockMovementState withWasInWaterFlag(boolean wasInWaterFlag) {
        return actor.wasInWaterFlag() == wasInWaterFlag
            ? this
            : withActor(actor.withWasInWaterFlag(wasInWaterFlag));
    }

    public BedrockMovementState withSprinting(boolean sprinting) {
        return actor.sprinting() == sprinting ? this : withActor(actor.withSprinting(sprinting));
    }

    public BedrockMovementState withVelocityAndCollisionFlags(
        Vec3d velocity,
        BedrockCollisionFlags collisionFlags
    ) {
        return withMotion(motion.withVelocityAndCollisionFlags(velocity, collisionFlags));
    }

    /** Advances an input frame without changing movement state. */
    public BedrockMovementState withoutActorMovementTick(BedrockInputFrame frame) {
        return withMotion(new Motion(
            physicalFeetPosition(), velocity(), 0.0D, Vec3d.ZERO, frame, collisionFlags(), coordinateFrame()));
    }

    /** Applies an immobile metadata boundary without completing a client tick. */
    public BedrockMovementState afterImmobileBoundary() {
        return withMotion(new Motion(
            physicalFeetPosition(), Vec3d.ZERO, 0.0D, Vec3d.ZERO, inputFrame(), collisionFlags(), coordinateFrame()));
    }

    public BedrockMovementState withAutoClimbTravel(boolean autoClimbTravel) {
        return actor.autoClimbTravel() == autoClimbTravel
            ? this
            : withActor(actor.withAutoClimbTravel(autoClimbTravel));
    }

    public BedrockMovementState withPhysicalFeetPosition(
        Vec3d physicalFeetPosition,
        double lastPhysicalDisplacementSquared
    ) {
        Vec3d position = BedrockPositionTranslator.normalizePhysicalFeetPosition(physicalFeetPosition, coordinateFrame());
        return withMotion(motion.withPosition(
            position,
            lastPhysicalDisplacementSquared,
            motion.lastPhysicalDisplacement()
        ));
    }

    /** Applies an ordered teleport and motion update without restoring older tick state. */
    public BedrockMovementState afterServerTeleport(Vec3d physicalFeetPosition, Vec3d velocity) {
        Vec3d position = BedrockPositionTranslator.normalizePhysicalFeetPosition(physicalFeetPosition, coordinateFrame());
        return new BedrockMovementState(
            // bedrock teleports preserve collision
            new Motion(position, velocity, 0.0D, Vec3d.ZERO, motion.inputFrame(), motion.collisionFlags(), coordinateFrame()),
            actor,
            memory.withFallDistance(0.0F),
            true
        );
    }

    public BedrockMovementState withPhysicalFeetPosition(
        Vec3d physicalFeetPosition,
        Vec3d lastPhysicalDisplacement
    ) {
        Vec3d position = BedrockPositionTranslator.normalizePhysicalFeetPosition(physicalFeetPosition, coordinateFrame());
        return withMotion(motion.withPosition(
            position,
            displacementSquared(lastPhysicalDisplacement),
            lastPhysicalDisplacement
        ));
    }

    public BedrockMovementState withItemUseSlowdownState(boolean active, long ticks) {
        return new BedrockMovementState(
            motion,
            actor.withItemUseSlowdownActive(active),
            memory.withItemUseSlowdownTicks(ticks),
            hasTeleported
        );
    }

    public BedrockMovementState withGliding(boolean gliding) {
        return new BedrockMovementState(
            motion,
            actor.withGliding(gliding, gliding),
            memory.withFallFlyTicks(gliding ? fallFlyTicks() : 0L),
            hasTeleported
        );
    }

    public BedrockMovementState withPlayerDimensions(PlayerDimensionsState dimensions, boolean explicit) {
        return withActor(actor.withPlayerDimensions(dimensions, explicit ? dimensions : null));
    }

    public BedrockMovementState withPlayerDimensions(
        BedrockBoundingBoxMode boundingBoxMode,
        PlayerDimensionsState dimensions,
        boolean explicit
    ) {
        return withActor(actor.withPlayerDimensions(
            boundingBoxMode, dimensions, explicit ? dimensions : null));
    }

    public BedrockMovementState withPendingBlockMovementSlowdownState(BlockMovementSlowdownState state) {
        return withActor(actor.withPendingBlockMovementSlowdownState(state));
    }

    public BedrockMovementState withScaffoldingDescendAllowed(boolean allowed) {
        return withActor(actor.withScaffoldingDescendAllowed(allowed));
    }

    public BedrockMovementState withClimbableContact(BedrockClimbableContact contact) {
        return withActor(actor.withClimbableContact(contact));
    }

    public BedrockMovementState advance(BedrockMovementUpdate update) {
        Vec3d position = BedrockPositionTranslator.normalizePhysicalFeetPosition(update.physicalFeetPosition(), coordinateFrame());
        Vec3d displacement = position.subtract(physicalFeetPosition());
        BedrockInputFrame frame = update.frame();
        boolean horizontalPose = BedrockPoseInputData.committedHorizontalPose(frame);
        // HasTeleportedFlagComponent is cleared only after an actor movement tick.
        return new BedrockMovementState(
            new Motion(position, update.velocity(), displacementSquared(displacement), displacement,
                frame, update.collisionFlags(), coordinateFrame()),
            new ActorState(
                new ContactState(
                    BlockMovementSlowdownState.NONE,
                    actor.climbableContact(),
                    actor.wasInWaterFlag()),
                new PoseState(nextSprinting(frame), update.swimming(), horizontalPose,
                    update.itemUse().slowdownActive()),
                new TravelMode(update.glide().active(), update.glide().requested(), false,
                    actor.waterTravelFlag(), actor.movementBranch()),
                update.boundingBoxMode(),
                update.playerDimensions(),
                actor.acknowledgedPlayerDimensions()
            ),
            new TickMemory(
                simulationTick() + 1L,
                update.powderSnowTicks(),
                update.glide().active() ? fallFlyTicks() + 1L : 0L,
                update.fallDistance(),
                update.swimAmount(),
                update.riptide().chargeTicks(),
                update.riptide().spinActive(),
                update.riptide().spinTicks(),
                frame.sneaking() ? sneakingTicks() + 1L : 0L,
                update.itemUse().slowdownTicks(),
                dolphinBoost(),
                cameraWater()
            )
        );
    }

    /** Copies already-calculated action results; no movement or impulses are replayed. */
    public BedrockMovementState withRejectedTickActions(BedrockMovementState completed) {
        return new BedrockMovementState(
            new Motion(physicalFeetPosition(), velocity(), lastPhysicalDisplacementSquared(), lastPhysicalDisplacement(),
                completed.inputFrame(), collisionFlags(), coordinateFrame()),
            new ActorState(
                actor.contacts(),
                new PoseState(completed.sprinting(), completed.swimming(), completed.horizontalPose(),
                    completed.itemUseSlowdownActive()),
                new TravelMode(completed.gliding(), completed.glidingRequest(), autoClimbTravel(),
                    waterTravelFlag(), movementBranch()),
                boundingBoxMode(), playerDimensions(), acknowledgedPlayerDimensions()),
            new TickMemory(
                completed.simulationTick(),
                powderSnowTicks(),
                completed.fallFlyTicks(),
                fallDistance(),
                completed.swimAmount(),
                completed.riptideChargeTicks(),
                completed.riptideSpinActive(),
                completed.riptideSpinTicks(),
                completed.sneakingTicks(),
                completed.itemUseSlowdownTicks(),
                completed.dolphinBoost(), completed.cameraWater()),
            hasTeleported);
    }

    private BedrockMovementState withMotion(Motion motion) {
        return new BedrockMovementState(motion, actor, memory, hasTeleported);
    }

    private BedrockMovementState withActor(ActorState actor) {
        return new BedrockMovementState(motion, actor, memory, hasTeleported);
    }

    private boolean nextSprinting(BedrockInputFrame frame) {
        BedrockInputIntent.SprintIntent sprintIntent = frame.intent().sprint();
        return sprintIntent.nextActorSprinting(sprinting());
    }

    private static long initialSneakingTicks(BedrockInputFrame frame) {
        return frame.sneaking() ? 1L : 0L;
    }

    private static double displacementSquared(Vec3d displacement) {
        return displacement.x() * displacement.x()
            + displacement.y() * displacement.y()
            + displacement.z() * displacement.z();
    }

    public record Motion(
        Vec3d physicalFeetPosition,
        Vec3d velocity,
        double lastPhysicalDisplacementSquared,
        Vec3d lastPhysicalDisplacement,
        BedrockInputFrame inputFrame,
        BedrockCollisionFlags collisionFlags,
        BedrockCoordinateFrame coordinateFrame
    ) {
        public Motion(Vec3d position, Vec3d velocity, double distance, Vec3d displacement,
                      BedrockInputFrame input, BedrockCollisionFlags flags) {
            this(position, velocity, distance, displacement, input, flags, BedrockCoordinateFrame.IDENTITY);
        }

        public Motion {
            Objects.requireNonNull(coordinateFrame, "coordinateFrame");
            Objects.requireNonNull(physicalFeetPosition, "physicalFeetPosition");
            Objects.requireNonNull(velocity, "velocity");
            Objects.requireNonNull(lastPhysicalDisplacement, "lastPhysicalDisplacement");
            Objects.requireNonNull(inputFrame, "inputFrame");
            Objects.requireNonNull(collisionFlags, "collisionFlags");
            if (!Double.isFinite(lastPhysicalDisplacementSquared) || lastPhysicalDisplacementSquared < 0.0D) {
                throw new IllegalArgumentException("lastPhysicalDisplacementSquared must be finite and non-negative");
            }
        }

        Motion withVelocityAndCollisionFlags(Vec3d velocity, BedrockCollisionFlags flags) {
            return new Motion(
                physicalFeetPosition, velocity, lastPhysicalDisplacementSquared,
                lastPhysicalDisplacement, inputFrame, flags, coordinateFrame
            );
        }

        Motion withPosition(Vec3d position, double displacementSquared, Vec3d displacement) {
            return new Motion(position, velocity, displacementSquared, displacement, inputFrame, collisionFlags, coordinateFrame);
        }
    }

    public record ActorState(
        ContactState contacts,
        PoseState pose,
        TravelMode travel,
        BedrockBoundingBoxMode boundingBoxMode,
        PlayerDimensionsState playerDimensions,
        PlayerDimensionsState acknowledgedPlayerDimensions
    ) {
        public ActorState {
            Objects.requireNonNull(contacts, "contacts");
            Objects.requireNonNull(pose, "pose");
            Objects.requireNonNull(travel, "travel");
            Objects.requireNonNull(boundingBoxMode, "boundingBoxMode");
            Objects.requireNonNull(playerDimensions, "playerDimensions");
        }

        BlockMovementSlowdownState pendingBlockMovementSlowdownState() { return contacts.pendingSlowdown(); }
        BedrockClimbableContact climbableContact() { return contacts.climbableContact(); }
        boolean scaffoldingDescendAllowed() { return contacts.scaffoldingDescendAllowed(); }
        boolean gliding() { return travel.gliding(); }
        boolean glidingRequest() { return travel.glidingRequest(); }
        boolean sprinting() { return pose.sprinting(); }
        boolean swimming() { return pose.swimming(); }
        boolean horizontalPose() { return pose.horizontal(); }
        boolean itemUseSlowdownActive() { return pose.itemUseSlowdownActive(); }
        boolean autoClimbTravel() { return travel.autoClimb(); }
        boolean waterTravelFlag() { return travel.waterTravel(); }
        boolean wasInWaterFlag() { return contacts.wasInWater(); }
        Medium movementBranch() { return travel.movementBranch(); }

        ActorState withMovementBranch(Medium value) { return copy(contacts, pose, travel.withMovementBranch(value)); }
        ActorState withWaterTravelFlag(boolean value) { return copy(contacts, pose, travel.withWaterTravel(value)); }
        ActorState withWasInWaterFlag(boolean value) { return copy(contacts.withWasInWater(value), pose, travel); }
        ActorState withSprinting(boolean value) { return copy(contacts, pose.withSprinting(value), travel); }
        ActorState withAutoClimbTravel(boolean value) { return copy(contacts, pose, travel.withAutoClimb(value)); }
        ActorState withItemUseSlowdownActive(boolean value) { return copy(contacts, pose.withItemUse(value), travel); }
        ActorState withGliding(boolean value, boolean request) { return copy(contacts, pose, travel.withGliding(value, request)); }
        ActorState withPendingBlockMovementSlowdownState(BlockMovementSlowdownState value) { return copy(contacts.withSlowdown(value), pose, travel); }
        ActorState withClimbableContact(BedrockClimbableContact value) { return copy(contacts.withClimbableContact(value), pose, travel); }
        ActorState withScaffoldingDescendAllowed(boolean value) { return copy(contacts.withDescendAllowed(value), pose, travel); }
        ActorState withPlayerDimensions(PlayerDimensionsState value, PlayerDimensionsState acknowledged) {
            return new ActorState(contacts, pose, travel, boundingBoxMode, value, acknowledged);
        }

        ActorState withPlayerDimensions(
            BedrockBoundingBoxMode mode,
            PlayerDimensionsState value,
            PlayerDimensionsState acknowledged
        ) {
            return new ActorState(contacts, pose, travel, mode, value, acknowledged);
        }

        private ActorState copy(ContactState contacts, PoseState pose, TravelMode travel) {
            return new ActorState(
                contacts, pose, travel, boundingBoxMode, playerDimensions, acknowledgedPlayerDimensions);
        }
    }

    public record ContactState(
        BlockMovementSlowdownState pendingSlowdown,
        BedrockClimbableContact climbableContact,
        boolean wasInWater
    ) {
        public ContactState {
            Objects.requireNonNull(pendingSlowdown, "pendingSlowdown");
            Objects.requireNonNull(climbableContact, "climbableContact");
        }

        boolean scaffoldingDescendAllowed() { return climbableContact.scaffoldingDescendAllowed(); }
        ContactState withSlowdown(BlockMovementSlowdownState value) { return new ContactState(value, climbableContact, wasInWater); }
        ContactState withClimbableContact(BedrockClimbableContact value) { return new ContactState(pendingSlowdown, value, wasInWater); }
        ContactState withWasInWater(boolean value) { return new ContactState(pendingSlowdown, climbableContact, value); }
        ContactState withDescendAllowed(boolean value) {
            return withClimbableContact(new BedrockClimbableContact(
                climbableContact.climbing(),
                climbableContact.scaffolding(),
                value,
                climbableContact.ascendableBlock()));
        }
    }

    public record PoseState(
        boolean sprinting,
        boolean swimming,
        boolean horizontal,
        boolean itemUseSlowdownActive
    ) {
        PoseState withSprinting(boolean value) { return new PoseState(value, swimming, horizontal, itemUseSlowdownActive); }
        PoseState withItemUse(boolean value) { return new PoseState(sprinting, swimming, horizontal, value); }
    }

    public record TravelMode(
        boolean gliding,
        boolean glidingRequest,
        boolean autoClimb,
        boolean waterTravel,
        Medium movementBranch
    ) {
        public TravelMode {
            Objects.requireNonNull(movementBranch, "movementBranch");
        }

        TravelMode withMovementBranch(Medium value) { return new TravelMode(gliding, glidingRequest, autoClimb, waterTravel, value); }
        TravelMode withWaterTravel(boolean value) { return new TravelMode(gliding, glidingRequest, autoClimb, value, movementBranch); }
        TravelMode withAutoClimb(boolean value) { return new TravelMode(gliding, glidingRequest, value, waterTravel, movementBranch); }
        TravelMode withGliding(boolean value, boolean request) { return new TravelMode(value, request, autoClimb, waterTravel, movementBranch); }
    }

    public record TickMemory(
        long simulationTick,
        long powderSnowTicks,
        long fallFlyTicks,
        float fallDistance,
        double swimAmount,
        long riptideChargeTicks,
        boolean riptideSpinActive,
        long riptideSpinTicks,
        long sneakingTicks,
        long itemUseSlowdownTicks,
        BedrockDolphinBoost dolphinBoost,
        BedrockCameraWaterState cameraWater
    ) {
        public TickMemory(long simulationTick, long powderSnowTicks, long fallFlyTicks, float fallDistance,
                          double swimAmount, long riptideChargeTicks, boolean riptideSpinActive,
                          long riptideSpinTicks, long sneakingTicks, long itemUseSlowdownTicks,
                          BedrockDolphinBoost dolphinBoost) {
            this(simulationTick, powderSnowTicks, fallFlyTicks, fallDistance, swimAmount, riptideChargeTicks,
                riptideSpinActive, riptideSpinTicks, sneakingTicks, itemUseSlowdownTicks, dolphinBoost,
                BedrockCameraWaterState.INITIAL);
        }

        public TickMemory {
            Objects.requireNonNull(dolphinBoost, "dolphinBoost");
            Objects.requireNonNull(cameraWater, "cameraWater");
            requireNonNegative("simulationTick", simulationTick);
            requireNonNegative("powderSnowTicks", powderSnowTicks);
            requireNonNegative("fallFlyTicks", fallFlyTicks);
            if (!Float.isFinite(fallDistance) || fallDistance < 0.0F) {
                throw new IllegalArgumentException("fallDistance must be finite and non-negative");
            }
            requireNonNegative("riptideChargeTicks", riptideChargeTicks);
            requireNonNegative("riptideSpinTicks", riptideSpinTicks);
            if (!riptideSpinActive && riptideSpinTicks != 0L) {
                throw new IllegalArgumentException("inactive riptide spin must have zero ticks");
            }
            requireNonNegative("sneakingTicks", sneakingTicks);
            requireNonNegative("itemUseSlowdownTicks", itemUseSlowdownTicks);
            if (!Double.isFinite(swimAmount) || swimAmount < 0.0D || swimAmount > 1.0D) {
                throw new IllegalArgumentException("swimAmount must be finite and between 0 and 1");
            }
        }

        TickMemory withFallFlyTicks(long value) { return new TickMemory(simulationTick, powderSnowTicks, value, fallDistance, swimAmount, riptideChargeTicks, riptideSpinActive, riptideSpinTicks, sneakingTicks, itemUseSlowdownTicks, dolphinBoost, cameraWater); }
        TickMemory withFallDistance(float value) { return new TickMemory(simulationTick, powderSnowTicks, fallFlyTicks, value, swimAmount, riptideChargeTicks, riptideSpinActive, riptideSpinTicks, sneakingTicks, itemUseSlowdownTicks, dolphinBoost, cameraWater); }
        TickMemory withItemUseSlowdownTicks(long value) { return new TickMemory(simulationTick, powderSnowTicks, fallFlyTicks, fallDistance, swimAmount, riptideChargeTicks, riptideSpinActive, riptideSpinTicks, sneakingTicks, value, dolphinBoost, cameraWater); }
        TickMemory withDolphinBoost(BedrockDolphinBoost value) { return new TickMemory(simulationTick, powderSnowTicks, fallFlyTicks, fallDistance, swimAmount, riptideChargeTicks, riptideSpinActive, riptideSpinTicks, sneakingTicks, itemUseSlowdownTicks, value, cameraWater); }
        TickMemory withCameraWater(BedrockCameraWaterState value) { return new TickMemory(simulationTick, powderSnowTicks, fallFlyTicks, fallDistance, swimAmount, riptideChargeTicks, riptideSpinActive, riptideSpinTicks, sneakingTicks, itemUseSlowdownTicks, dolphinBoost, value); }
        TickMemory withSpin(boolean value) { return new TickMemory(simulationTick, powderSnowTicks, fallFlyTicks, fallDistance, swimAmount, riptideChargeTicks, value, value ? riptideSpinTicks : 0L, sneakingTicks, itemUseSlowdownTicks, dolphinBoost, cameraWater); }

        private static void requireNonNegative(String name, long value) {
            if (value < 0L) {
                throw new IllegalArgumentException(name + " must be non-negative");
            }
        }
    }
}
