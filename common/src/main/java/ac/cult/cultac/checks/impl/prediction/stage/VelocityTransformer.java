package ac.cult.cultac.checks.impl.prediction.stage;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.Riptide;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.FlightToggle;
import ac.cult.cultac.utils.nmsutil.WaterCurrent;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Setter;

@Setter
@AllArgsConstructor
public class VelocityTransformer {
    SimulationContext simulationContext;

    private static final Vec3 attackSlowMultiplier = new Vec3(0.6, 1, 0.6);

    public List<PredVector> generateMovementVectors(CultPlayer player, List<PredVector> input) {
        if (simulationContext.getVehicle() != null
                && ac.cult.cultac.utils.nmsutil.EntityTypeUtil.isBoat(simulationContext.getVehicle().type)) {
            return input;
        }

        input = Riptide.release(player, simulationContext, input);
        input = applyBaseTickFluidCurrents(player, input);
        input = applyLegacyLivingVehicleDrag(player, input);
        input = applyAttackSlow(input);
        if (simulationContext.getVehicle() instanceof PacketEntityHappyGhast) {
            input = applyMovementThreshold(input, simulationContext.getVersion(), false);
            return applyRideableInput(player, input);
        }

        if (shouldThresholdBeforeItemControlledRiddenInput(player)) {
            // MCP-Reborn LivingEntity#aiStep applies the 0.003
            // deltaMovement threshold before the later travelRidden path adds
            // pig/strider ridden input.
            input = applyMovementThreshold(input, simulationContext.getVersion(), false);
            input = applyRideableInput(player, input);
            return applyJumps(player, input);
        }

        if (simulationContext.getVehicle() instanceof PacketEntityHorse) {
            // LivingEntity#aiStep thresholds deltaMovement before travelRidden runs
            // AbstractHorse#tickRidden's rider jump and travel adds the ridden input.
            input = applyMovementThreshold(input, simulationContext.getVersion(), false);
            input = applyRideableInput(player, input);
            // Camel#travel clears the refusing camel's motion after tickRidden's dash.
            return applyCamelRefusal(player, applyJumps(player, input));
        }

        input = applyRideableInput(player, input);

        // MCP-Reborn LivingEntity#handleRelativeFrictionAndCalculateMovement moves
        // using the current deltaMovement first, and LivingEntity#travelInAir /
        // #travelInWater / #travelInLava only apply friction to the stored
        // next-tick delta after that move. Mounted living-entity control also
        // feeds controller input through LivingEntity#travelRidden into that same
        // path. So the current packet-visible rideable movement must start from
        // the exact carried deltaMovement, not from an extra pre-move 0.98 scale.
        //
        // The corresponding next-tick friction still belongs in
        // utils.nmsutil.NextTickVelocityDeriver.findVelocitiesForNextTick(...), which derives
        // the carried delta from the accepted packet movement after the move.

        boolean abilityFlying = simulationContext.getVehicle() == null && player.isFlying;
        if (abilityFlying) {
            input = applyFlightTakeoff(player, input);
            input = applyFlightInput(player, input);
        }
        input = applyMovementThreshold(input, simulationContext.getVersion(), simulationContext.getVehicle() == null);
        // Player#isAffectedByFluids is false in flight, so LivingEntity#aiStep runs neither jump.
        return abilityFlying ? input : applyJumps(player, input);
    }

    /**
     * LocalPlayer#aiStep runs jumpFromGround when its double-tap toggle turns flight on while on the ground, ahead of
     * the flight input. The player_abilities of that start precedes this tick's player_input and move. Any other
     * flight start is a player_abilities ServerGamePacketListenerImpl#handlePlayerAbilities accepts without a jump.
     */
    private List<PredVector> applyFlightTakeoff(CultPlayer player, List<PredVector> velocities) {
        if (!player.packetStateData.flyingStartBeforeTravel || !FlightToggle.canToggle(player)) return velocities;
        List<Boolean> onGroundStates = simulationContext.getLastOnGround().getStates();
        if (!onGroundStates.contains(true)) return velocities;
        List<PredVector> outputs = new ArrayList<>();
        for (PredVector velocity : velocities) {
            outputs.addAll(applyPossibleJumpStates(player, velocity));
        }
        // LivingEntity#jumpFromGround leaves the velocity unchanged at or below 1.0E-5F.
        if (onGroundStates.contains(false) || jumpFromGroundCanDoNothing(player)) outputs.addAll(velocities);
        return outputs;
    }

    private boolean jumpFromGroundCanDoNothing(CultPlayer player) {
        for (boolean onHoney :
                simulationContext.getWorldData().getOnHoneyBlock().getStates()) {
            if (jumpPower(player, onHoney) <= 1.0E-5F) return true;
        }
        return false;
    }

    private float jumpPower(CultPlayer player, boolean onHoney) {
        float jumpPower = (float) player.compensatedEntities.getSelf().jumpStrength * (onHoney ? 0.5f : 1.0f);
        if (simulationContext.getJumpAmplifier() != null) {
            jumpPower += 0.1f * (simulationContext.getJumpAmplifier() + 1);
        }
        return jumpPower;
    }

    /**
     * LocalPlayer#aiStep adds three times the abilities' flying speed to the delta movement's Y while jump is held
     * in flight and subtracts it while shift is held, before LivingEntity#aiStep's movement threshold. The keys are
     * the ones LocalPlayer#sendChanges reports in the player_input preceding this tick's move.
     */
    private static List<PredVector> applyFlightInput(CultPlayer player, List<PredVector> velocities) {
        KnownInput keys = player.packetStateData.knownInput;
        int inputYa = (keys.jump() ? 1 : 0) - (keys.shift() ? 1 : 0);
        if (inputYa == 0) return velocities;
        double impulse = (float) inputYa * player.flySpeed * 3.0F;
        List<PredVector> outputs = new ArrayList<>(velocities.size());
        for (PredVector velocity : velocities) {
            outputs.add(velocity.add(0, impulse, 0, "Flight input"));
        }
        return outputs;
    }

    private boolean shouldThresholdBeforeItemControlledRiddenInput(CultPlayer player) {
        PacketEntity vehicle = simulationContext.getVehicle();
        return vehicle != null
                && (vehicle.type == EntityTypeIds.PIG || vehicle.type == EntityTypeIds.STRIDER)
                && player.packetStateData.isVehicleMovementFromClientTick()
                && canUseItemControlledMovement(player, vehicle);
    }

    private List<PredVector> applyLegacyLivingVehicleDrag(CultPlayer player, List<PredVector> input) {
        PacketEntity vehicle = simulationContext.getVehicle();
        if (simulationContext.getVersion().isNewerThanOrEquals(ClientVersion.V_1_21_5)
                || vehicle == null
                || !vehicle.isLivingEntity()
                || !player.packetStateData.isVehicleMovementFromClientTick()
                || vehicle.newPacketLocation != null && vehicle.newPacketLocation.hasActiveInterpolationTarget()) {
            return input;
        }
        // Before 1.21.5, LivingEntity#aiStep scales deltaMovement before
        // thresholding and travelRidden because Mob#isEffectiveAi is false
        // on the client, including locally controlled living vehicles. The
        // preceding lerpSteps > 0 branch skips this drag, including the final
        // interpolation step; prediction still sees the pre-tick target here.
        // From 1.21.5 the branch tests canSimulateMovement, which keeps the delta.
        List<PredVector> transformed = new ArrayList<>(input.size());
        for (PredVector vector : input) {
            transformed.add(vector.multiply(0.98D, "legacy living vehicle drag"));
        }
        return transformed;
    }

    private List<PredVector> applyBaseTickFluidCurrents(CultPlayer player, List<PredVector> input) {
        PacketEntity vehicle = simulationContext.getVehicle();
        // Preserve CultAC's on-foot fluid contract: FluidPush only projects the
        // packet displacement, and the accepted displacement is carried through
        // the normal end-of-tick velocity derivation. Exact base-tick currents
        // here are solely for client-controlled mounted roots.
        if (input.isEmpty()
                || vehicle == null
                || EntityTypeUtil.isBoat(vehicle.type)
                || vehicle instanceof PacketEntityNautilus
                || !player.packetStateData.isVehicleMovementFromClientTick()) {
            return input;
        }

        List<PredVector> transformed = new ArrayList<>(input.size());
        for (PredVector vector : input) {
            PredVector current = vector;
            Vec3 water = WaterCurrent.calculateWaterCurrent(
                    player, simulationContext, simulationContext.getStart(), current);
            if (water != null && isFinite(water)) {
                current = current.add(water, "water current");
            }
            Vec3 lava =
                    WaterCurrent.calculateLavaCurrent(player, simulationContext, simulationContext.getStart(), current);
            if (lava != null && isFinite(lava)) {
                current = current.add(lava, "lava current");
            }
            transformed.add(current);
        }
        return transformed;
    }

    private boolean isFinite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    /** Camel#travel clears the horizontal deltaMovement of a grounded camel that refuses to move. */
    private List<PredVector> applyCamelRefusal(CultPlayer player, List<PredVector> input) {
        if (!(simulationContext.getVehicle() instanceof PacketEntityCamel camel)
                || !player.packetStateData.isVehicleMovementFromClientTick()
                || !camel.refuseToMove(player.compensatedWorld.clientGameTime())) {
            return input;
        }

        List<PredVector> refused = new ArrayList<>(input.size() * 2);
        for (PredVector vector : input) {
            for (boolean onGround : simulationContext.getLastOnGround().getStates()) {
                addDistinctPredVector(
                        refused, onGround ? vector.withXYZ(0.0D, vector.y, 0.0D, "camel refuses to move") : vector);
            }
        }
        return refused;
    }

    private List<PredVector> applyRideableInput(CultPlayer player, List<PredVector> input) {
        PacketEntity vehicle = simulationContext.getVehicle();
        // LivingEntity#aiStep runs travelRidden only while the root isAlive() (health > 0, not the dead
        // flag); otherwise it travels on its immobile self input (LivingEntity#isImmobile), which is zero.
        if (vehicle == null
                || input.isEmpty()
                || !vehicle.actionAlive
                || !player.packetStateData.isVehicleMovementFromClientTick()
                || !canUseItemControlledMovement(player, vehicle)
                || EntityTypeUtil.isBoat(vehicle.type)) {
            return input;
        }

        Vec3 riddenInput = getExactControlledRideableInput(player, vehicle);
        if (riddenInput == null) {
            return input;
        }

        List<Vec3> inputVectors = exactRideableInputVectors(player, riddenInput);
        if (inputVectors.isEmpty()) {
            return input;
        }

        boolean keepOriginalInput = isVehicleControlSwitchTimingUnproven(player, vehicle);
        List<PredVector> transformed =
                new ArrayList<>(input.size() * (inputVectors.size() + (keepOriginalInput ? 1 : 0)));
        if (keepOriginalInput) {
            transformed.addAll(input);
        }
        for (PredVector vector : input) {
            for (Vec3 inputVector : inputVectors) {
                addDistinctPredVector(transformed, vector.add(inputVector, "rideable input"));
            }
        }
        return transformed;
    }

    private boolean canUseItemControlledMovement(CultPlayer player, PacketEntity vehicle) {
        return player.compensatedEntities.vehicles.canCurrentPlayerControlServerVehicleForClientTickMovement()
                || isVehicleControlSwitchTimingUnproven(player, vehicle);
    }

    private boolean isVehicleControlSwitchTimingUnproven(CultPlayer player, PacketEntity vehicle) {
        return (vehicle.type == EntityTypeIds.PIG || vehicle.type == EntityTypeIds.STRIDER)
                && player.packetStateData.isVehicleMovementFromClientTick()
                && (player.compensatedEntities.vehicles.canOpenVehicleSwitchBufferForMovementPacket(vehicle)
                        || player.compensatedEntities.vehicles.isVehicleSwitchBufferActiveFor(vehicle));
    }

    private void addDistinctPredVector(List<PredVector> vectors, PredVector candidate) {
        for (PredVector existing : vectors) {
            if (existing.distanceToSqr(candidate) <= 1.0E-12D) {
                return;
            }
        }
        vectors.add(candidate);
    }

    private Vec3 getExactControlledRideableInput(CultPlayer player, PacketEntity vehicle) {
        if (vehicle.type == EntityTypeIds.PIG || vehicle.type == EntityTypeIds.STRIDER) {
            // Pig#getRiddenInput and Strider#getRiddenInput ignore controller selfInput and return
            // Vec3(0, 0, 1) once the saddled first passenger can control the mount.
            return new Vec3(0.0D, 0.0D, 1.0D);
        }

        if (vehicle instanceof PacketEntityHorse) {
            return getExactHorseFamilyRiddenInput(simulationContext.getHorseInputs());
        }

        if (vehicle instanceof PacketEntityHappyGhast happyGhast) {
            return getExactHappyGhastRiddenInput(
                    simulationContext.getHorseInputs(), player.boatData.vehicleJump, player.yRot, happyGhast);
        }

        if (vehicle instanceof PacketEntityNautilus) {
            return getExactNautilusRiddenInput(simulationContext.getHorseInputs(), player.yRot);
        }

        return null;
    }

    private List<Vec3> exactRideableInputVectors(CultPlayer player, Vec3 riddenInput) {
        List<Vec3> vectors = new ArrayList<>(6);
        if (simulationContext.getVehicle() instanceof PacketEntityHappyGhast happyGhast) {
            // HappyGhast#tickRidden turns the root 8% toward the controller before LivingEntity#travel.
            float travelYaw = happyGhast.getRiddenTickYaw(simulationContext.getXRot());
            addDistinctInputVector(vectors, happyGhast.getTravelInputVector(player, riddenInput, travelYaw));
            return vectors;
        }

        if (simulationContext.getVehicle() instanceof PacketEntityNautilus) {
            for (boolean water : simulationContext.getWorldData().getInWater().getStates()) {
                if (water) {
                    addDistinctInputVector(
                            vectors,
                            inputVectorForSpeed(
                                    player,
                                    riddenInput,
                                    simulationContext.getMoveRelativeSpeed(player, false, true, false)));
                }
            }
            for (boolean lava : simulationContext.getWorldData().getInLava().getStates()) {
                if (lava) {
                    addDistinctInputVector(
                            vectors,
                            inputVectorForSpeed(
                                    player,
                                    riddenInput,
                                    simulationContext.getMoveRelativeSpeed(player, false, false, true)));
                }
            }
            boolean canBeNonFluid = simulationContext
                            .getWorldData()
                            .getInWater()
                            .getStates()
                            .contains(false)
                    && simulationContext.getWorldData().getInLava().getStates().contains(false);
            if (canBeNonFluid) {
                for (boolean onGround :
                        simulationContext.getWorldData().getLastOnGround().getStates()) {
                    addDistinctInputVector(
                            vectors,
                            inputVectorForSpeed(
                                    player,
                                    riddenInput,
                                    simulationContext.getMoveRelativeSpeed(player, onGround, false, false)));
                }
            }
            return vectors;
        }

        PacketEntityHorse horse = simulationContext.getVehicle() instanceof PacketEntityHorse packetEntityHorse
                ? packetEntityHorse
                : null;
        // Camel#getRiddenInput runs before Camel#tickRidden can stand the camel up.
        if (horse instanceof PacketEntityCamel camel && camel.refuseToMove(player.compensatedWorld.clientGameTime())) {
            addDistinctInputVector(vectors, Vec3.ZERO);
            return vectors;
        }

        boolean standingHorseCanZeroInput = standingHorseCanZeroInput(horse);
        boolean standingHorseCanUseNormalInput = standingHorseCanUseNormalInput(horse);

        if (standingHorseCanZeroInput) {
            addDistinctInputVector(vectors, Vec3.ZERO);
        }

        if (horse != null && !standingHorseCanUseNormalInput) {
            return vectors;
        }

        // Camel#getRiddenSpeed reads the controller's sprint flag, which an attack this tick may have reset.
        List<Boolean> riderSprintStates = horse instanceof PacketEntityCamel
                ? simulationContext.getCamelRiderSprinting(player).getStates()
                : List.of(true);
        for (boolean riderSprinting : riderSprintStates) {
            for (boolean water : simulationContext.getWorldData().getInWater().getStates()) {
                if (water) {
                    addDistinctInputVector(
                            vectors,
                            inputVectorForSpeed(
                                    player,
                                    riddenInput,
                                    simulationContext.getMoveRelativeSpeed(
                                            player, false, true, false, riderSprinting)));
                }
            }

            for (boolean lava : simulationContext.getWorldData().getInLava().getStates()) {
                if (lava) {
                    addDistinctInputVector(
                            vectors,
                            inputVectorForSpeed(
                                    player,
                                    riddenInput,
                                    simulationContext.getMoveRelativeSpeed(
                                            player, false, false, true, riderSprinting)));
                }
            }

            boolean canBeNonFluid = simulationContext
                            .getWorldData()
                            .getInWater()
                            .getStates()
                            .contains(false)
                    && simulationContext.getWorldData().getInLava().getStates().contains(false);
            if (canBeNonFluid) {
                // LivingEntity#travelInFluid branches on exact isInWater()/isInLava(). When Cult can
                // only narrow the mount to maybe-fluid, preserve the non-fluid travel branch too.
                for (boolean onGround :
                        simulationContext.getWorldData().getLastOnGround().getStates()) {
                    // A grounded camel's dash sets its cooldown in tickRidden before getRiddenSpeed reads it.
                    boolean dashes = onGround && horse instanceof PacketEntityCamel && horse.horseJump > 0.0F;
                    addDistinctInputVector(
                            vectors,
                            inputVectorForSpeed(
                                    player,
                                    riddenInput,
                                    simulationContext.getMoveRelativeSpeed(
                                            player, onGround, false, false, riderSprinting && !dashes)));
                }
            }
        }

        return vectors;
    }

    private Vec3 getExactHappyGhastRiddenInput(
            Vec3 rawInputState, boolean jumping, float controllerPitch, PacketEntityHappyGhast happyGhast) {
        return happyGhast.getRiddenInput(rawInputState, jumping, controllerPitch);
    }

    static Vec3 getExactNautilusRiddenInput(Vec3 rawInputState, float controllerPitch) {
        Vec3 modified = getExactControllerModifiedInputStatic(rawInputState);
        float sideways = (float) modified.x;
        float forwardKey = (float) modified.z;
        float forward = 0.0F;
        float vertical = 0.0F;
        if (forwardKey != 0.0F) {
            forward = CultMath.cos(controllerPitch * ((float) Math.PI / 180.0F));
            vertical = -CultMath.sin(controllerPitch * ((float) Math.PI / 180.0F));
            if (forwardKey < 0.0F) {
                forward *= -0.5F;
                vertical *= -0.5F;
            }
        }
        return new Vec3(sideways, vertical, forward);
    }

    private boolean standingHorseCanZeroInput(PacketEntityHorse horse) {
        if (!standingHorseSuppressesGroundedInput(horse)) {
            return false;
        }
        return simulationContext.getLastOnGround().getStates().contains(true);
    }

    private boolean standingHorseCanUseNormalInput(PacketEntityHorse horse) {
        if (!standingHorseSuppressesGroundedInput(horse)) {
            return true;
        }
        return simulationContext.getLastOnGround().getStates().contains(false);
    }

    private boolean standingHorseSuppressesGroundedInput(PacketEntityHorse horse) {
        if (horse == null) {
            return false;
        }

        // AbstractHorse#getRiddenInput zeroes controller input while a standing horse is grounded,
        // has no pending jump charge, and allowStandSliding is false.
        return horse.isRearing && horse.horseJump <= 0.0D && !horse.allowStandSliding;
    }

    private Vec3 getExactHorseFamilyRiddenInput(Vec3 rawInputState) {
        // KeyboardInput#tick normalizes key impulses, LocalPlayer#applyInput stores modifyInput(...)
        // into xxa/zza, and AbstractHorse#getRiddenInput maps that exact pair to ridden Vec3.
        // Before 1.21.5, LocalPlayer#serverAiStep stores the raw impulses and LivingEntity#aiStep
        // scales them by 0.98.
        Vec3 modifiedInput = simulationContext.getVersion().isOlderThan(ClientVersion.V_1_21_5)
                ? new Vec3((float) rawInputState.x * 0.98F, 0.0D, (float) rawInputState.z * 0.98F)
                : getExactControllerModifiedInput(rawInputState);
        float modifiedX = (float) modifiedInput.x;
        float modifiedZ = (float) modifiedInput.z;
        if (modifiedInput.lengthSqr() < 1.0E-7D) {
            return Vec3.ZERO;
        }

        float riddenX = modifiedX * 0.5F;
        float riddenZ = modifiedZ;
        if (riddenZ <= 0.0F) {
            riddenZ *= 0.25F;
        }

        return new Vec3(riddenX, 0.0D, riddenZ);
    }

    private Vec3 getExactControllerModifiedInput(Vec3 rawInputState) {
        return getExactControllerModifiedInputStatic(rawInputState);
    }

    private static Vec3 getExactControllerModifiedInputStatic(Vec3 rawInputState) {
        return getExactControllerModifiedInputStatic(rawInputState, 1.0F);
    }

    /** LocalPlayer#modifyInput with no item in use; {@code movingSlowlyFactor} is LocalPlayer#isMovingSlowly's scale. */
    public static Vec3 getExactControllerModifiedInputStatic(Vec3 rawInputState, float movingSlowlyFactor) {
        return getExactControllerModifiedInputStatic(rawInputState, 1.0F, movingSlowlyFactor);
    }

    /** LocalPlayer#modifyInput, scaling by {@code itemUseMultiplier} for an item in use before the slow factor. */
    public static Vec3 getExactControllerModifiedInputStatic(
            Vec3 rawInputState, float itemUseMultiplier, float movingSlowlyFactor) {
        float xxa = (float) rawInputState.x;
        float zza = (float) rawInputState.z;
        float rawLengthSqr = xxa * xxa + zza * zza;
        if (rawLengthSqr < 1.0E-7F) {
            return Vec3.ZERO;
        }

        float rawLength = CultMath.sqrt(rawLengthSqr);
        float normalizedX = xxa / rawLength;
        float normalizedZ = zza / rawLength;

        float modifiedX = normalizedX * 0.98F * itemUseMultiplier * movingSlowlyFactor;
        float modifiedZ = normalizedZ * 0.98F * itemUseMultiplier * movingSlowlyFactor;
        float modifiedLength = CultMath.sqrt(modifiedX * modifiedX + modifiedZ * modifiedZ);
        if (modifiedLength > 0.0F) {
            float inverseLength = 1.0F / modifiedLength;
            float unitX = modifiedX * inverseLength;
            float unitZ = modifiedZ * inverseLength;
            float absX = Math.abs(unitX);
            float absZ = Math.abs(unitZ);
            float ratio = absZ > absX ? absX / absZ : absZ / absX;
            float distanceToUnitSquare = CultMath.sqrt(1.0F + CultMath.square(ratio));
            float scale = Math.min(modifiedLength * distanceToUnitSquare, 1.0F);
            modifiedX = unitX * scale;
            modifiedZ = unitZ * scale;
        }

        return new Vec3(modifiedX, 0.0D, modifiedZ);
    }

    private Vec3 inputVectorForSpeed(CultPlayer player, Vec3 riddenInput, float speed) {
        double lengthSqr = riddenInput.lengthSqr();
        if (lengthSqr < 1.0E-7D) {
            return Vec3.ZERO;
        }

        Vec3 scaled = (lengthSqr > 1.0D ? riddenInput.normalize() : riddenInput).scale(speed);
        float yawRadians = simulationContext.getXRot() * ((float) Math.PI / 180F);
        float sin = player.trigHandler.sin(yawRadians);
        float cos = player.trigHandler.cos(yawRadians);
        return new Vec3(scaled.x * cos - scaled.z * sin, scaled.y, scaled.z * cos + scaled.x * sin);
    }

    private void addDistinctInputVector(List<Vec3> vectors, Vec3 candidate) {
        for (Vec3 existing : vectors) {
            if (existing.distanceToSqr(candidate) <= 1.0E-12D) {
                return;
            }
        }
        vectors.add(candidate);
    }

    private List<PredVector> applyAttackSlow(List<PredVector> input) {
        if (simulationContext.getMaxAttackSlow() == 0 || simulationContext.getVehicle() != null)
            return input; // Do nothing

        List<PredVector> output = new ArrayList<>();

        for (PredVector vector : input) {
            for (int i = 0; i <= Math.min(5, simulationContext.getMaxAttackSlow()); i++) {
                Vec3 multVector = new Vec3(1, 1, 1);
                for (int j = 0; j < i; j++) {
                    multVector = multVector.multiply(attackSlowMultiplier);
                }

                PredVector slowed = new PredVector(vector.multiply(multVector), vector, "Attack slow");
                // KeepSprint compares this with the attacks PacketPlayerAttack proves reached causeExtraKnockback.
                slowed.setAttackSlows(i);
                output.add(slowed);
            }
        }

        return output;
    }

    public static List<PredVector> applyMovementThreshold(List<PredVector> velocities, ClientVersion version) {
        return applyMovementThreshold(velocities, version, true);
    }

    public static List<PredVector> applyMovementThreshold(
            List<PredVector> velocities, ClientVersion version, boolean playerEntity) {
        List<PredVector> outputs = new ArrayList<>(velocities.size());

        for (PredVector vector : velocities) {
            outputs.add(applyMovementThreshold(vector, version, playerEntity));
        }

        return outputs;
    }

    public static PredVector applyMovementThreshold(PredVector vector, ClientVersion version, boolean playerEntity) {
        double minimumMovement = 0.003D;

        // LivingEntity#aiStep switched players from per-axis cutoffs to a
        // horizontal-length cutoff in 1.21.5. Ridden entities retain per-axis cutoffs.
        if (playerEntity && version.isNewerThanOrEquals(ClientVersion.V_1_21_5)) {
            if (vector.x * vector.x + vector.z * vector.z < minimumMovement * minimumMovement) {
                vector = vector.withXYZOrSelf(0, vector.y, vector.z, "Inertia threshold")
                        .withXYZOrSelf(0, vector.y, 0, "Inertia threshold");
            }
        } else {
            if (Math.abs(vector.x) < minimumMovement) {
                vector = vector.withXYZOrSelf(0, vector.y, vector.z, "Inertia threshold");
            }

            if (Math.abs(vector.z) < minimumMovement) {
                vector = vector.withXYZOrSelf(vector.x, vector.y, 0, "Inertia threshold");
            }
        }

        if (Math.abs(vector.y) < minimumMovement) {
            vector = vector.withXYZOrSelf(vector.x, 0, vector.z, "Inertia threshold");
        }

        return vector;
    }

    private List<PredVector> applyJumps(CultPlayer player, List<PredVector> velocities) {
        boolean canPlayerJump = simulationContext.getWorldData().isCanJump();
        // AbstractHorse#tickRidden and AbstractNautilus#tickRidden run the rider's jump inside travelRidden,
        // which LivingEntity#aiStep gates on isAlive().
        boolean canHorseJump = simulationContext.getVehicle() instanceof PacketEntityHorse horse
                && horse.actionAlive
                && horse.horseJump > 0.0F
                && simulationContext.getLastOnGround().determineOptimistically();
        boolean canNautilusDash = simulationContext.getVehicle() instanceof PacketEntityNautilus nautilus
                && nautilus.actionAlive
                && nautilus.pendingJumpScale > 0.0D;
        if (!canPlayerJump && !canHorseJump && !canNautilusDash) return velocities;

        if (canNautilusDash) {
            PacketEntityNautilus nautilus = (PacketEntityNautilus) simulationContext.getVehicle();
            List<PredVector> dashed = new ArrayList<>(velocities.size() * 2);
            for (PredVector velocity : velocities) {
                for (boolean inWater :
                        simulationContext.getWorldData().getInWater().getStates()) {
                    addDistinctPredVector(dashed, applyNautilusDash(player, velocity, nautilus, inWater));
                }
            }
            return dashed;
        }

        List<PredVector> outputs = new ArrayList<>(velocities);

        for (PredVector vectorData : velocities) {
            // TODO: Restrict this for not being in shallow water (above some level submerged)
            if (Riptide.pushed(simulationContext, vectorData)) continue;
            outputs.addAll(applyPossibleJumpStates(player, vectorData));
        }

        return outputs;
    }

    private PredVector applyHorseJump(PredVector input, double jumpFactor) {
        final PacketEntityHorse horse = (PacketEntityHorse) simulationContext.getVehicle();
        if (simulationContext.getLastOnGround().determineOptimistically() && horse.horseJump > 0.0F) {
            double d0 = horse.jumpStrength * horse.horseJump * jumpFactor;
            double d1;

            // This doesn't even work because vehicle jump boost has (likely) been
            // broken ever since vehicle control became client sided
            //
            // But plugins can still send this, so support it anyways
            if (simulationContext.getJumpAmplifier() != null) {
                d1 = d0 + ((simulationContext.getJumpAmplifier() + 1) * 0.1F);
            } else {
                d1 = d0;
            }

            final float xRotRadians = simulationContext.getXRot() * ((float) Math.PI / 180F);
            final float f2 = simulationContext.getTrig().sin(xRotRadians);
            final float f3 = simulationContext.getTrig().cos(xRotRadians);

            input = input.withY(d1, "Horse jump");
            if (simulationContext.getHorseInputs().z > 0.0F) {
                input = input.add(
                        new Vec3(-0.4F * f2 * horse.horseJump, 0.0D, 0.4F * f3 * horse.horseJump),
                        "Horse horizontal jump");
            }
        }
        return input;
    }

    // TODO: fix camel dashing
    private PredVector applyCamelDash(CultPlayer player, PredVector baseVel, double dashFactor) {
        final PacketEntityHorse horse = (PacketEntityHorse) simulationContext.getVehicle();
        if (simulationContext.getLastOnGround().determineOptimistically() && horse.horseJump > 0.0F) {
            double d = horse.jumpStrength * dashFactor;

            if (simulationContext.getJumpAmplifier() != null) {
                d = d + ((simulationContext.getJumpAmplifier() + 1) * 0.1F);
            }

            final double f = horse.horseJump;

            final float xRotRadians = simulationContext.getXRot() * ((float) Math.PI / 180F);
            final float f2 = simulationContext.getTrig().sin(xRotRadians);
            final float f3 = simulationContext.getTrig().cos(xRotRadians);

            final double blockSpeedFactor = CultMath.lerp(
                    (float) horse.movementEfficiency,
                    BlockProperties.getEntityBlockSpeedFactor(
                            player,
                            simulationContext.getLastTickMainSupportingBlockData(),
                            simulationContext.getStart()),
                    1.0F);

            final double scale = 22.2222F * f * horse.movementSpeedAttribute * blockSpeedFactor;
            Vec3 dashVec = new Vec3(-f2, 0.0, f3)
                    .multiply(1.0, 0.0, 1.0)
                    .normalize()
                    .multiply(scale, scale, scale)
                    .add(0, 1.4285F * f * d, 0.0);

            /*
                 this.addDeltaMovement(this.getLookAngle().multiply(1.0, 0.0, 1.0)
                 .normalize()
                 .scale((double)(22.2222F * f) * this.getAttributeValue(Attributes.MOVEMENT_SPEED) * (double)this.getBlockSpeedFactor())
                 .add(0.0, (double)(1.4285F * f) * d, 0.0));

            */
            baseVel = baseVel.add(dashVec, "Camel horizontal jump");
        }
        return baseVel;
    }

    private List<PredVector> applyPossibleJumpStates(CultPlayer player, PredVector vector) {
        List<PredVector> outputs = new ArrayList<>();

        for (boolean onHoney :
                simulationContext.getWorldData().getOnHoneyBlock().getStates()) {
            for (boolean isSprinting : simulationContext.getIsSprinting().getStates()) {
                // To keep shit clean, we'll just do horse jumping separately
                final PacketEntity vehicle = simulationContext.getVehicle();
                if (vehicle instanceof PacketEntityHorse && !vehicle.actionAlive) continue;
                if (vehicle instanceof PacketEntityCamel) {
                    final PredVector dashed = applyCamelDash(player, vector, onHoney ? 0.5 : 1);
                    if (dashed != vector) outputs.add(dashed);
                    continue;
                } else if (vehicle instanceof PacketEntityHorse) {
                    final PredVector jumped = applyHorseJump(vector, onHoney ? 0.5 : 1);
                    if (jumped != vector) outputs.add(jumped);
                    continue;
                }
                PredVector modified;

                float jumpPower = jumpPower(player, onHoney);

                // LivingEntity#jumpFromGround does nothing at or below 1.0E-5F.
                if (jumpPower <= 1.0E-5F) continue;

                modified = vector.withY(groundJumpVelocityY(vector.y, jumpPower), "Jumping set Y");
                modified.setJump();

                if (isSprinting) {
                    for (int shitMath = 0; shitMath < 2; shitMath++) {
                        float f2 = simulationContext.getXRot() * ((float) Math.PI / 180F);
                        Vec3 jumpBonus = new Vec3(
                                -simulationContext.getTrig().sin(f2) * 0.2f,
                                0.0,
                                simulationContext.getTrig().cos(f2) * 0.2f);
                        // ensure to not change the original vector
                        outputs.add(modified.add(jumpBonus, "Jumping sprinting"));
                        // By doing this twice, we brute-force FastMath
                        simulationContext.getTrig().toggleShitMath();
                    }
                } else {
                    // no sprinting, fastmath can't fuck this up
                    outputs.add(modified);
                }
            }
        }

        return outputs;
    }

    private PredVector applyNautilusDash(
            CultPlayer player, PredVector input, PacketEntityNautilus nautilus, boolean inWater) {
        // The passenger Rot packet immediately preceding MoveVehicle retains the
        // controller look in CultPlayer; the vehicle packet carries the root's
        // separately smoothed rotation in SimulationContext.
        Vec3 look = getExactNautilusDashLook(player.xRot, player.yRot);
        float blockSpeedFactor = CultMath.lerp(
                (float) nautilus.movementEfficiency,
                BlockProperties.getEntityBlockSpeedFactor(
                        player, simulationContext.getLastTickMainSupportingBlockData(), simulationContext.getStart()),
                1.0F);
        double waterMultiplier = inWater ? 1.2F : 0.5F;
        double magnitude =
                waterMultiplier * nautilus.pendingJumpScale * nautilus.movementSpeedAttribute * blockSpeedFactor;
        return input.add(look.scale(magnitude), "Nautilus dash");
    }

    static Vec3 getExactNautilusDashLook(float controllerYaw, float controllerPitch) {
        // MCP-Reborn 26.1/26.2 Entity#calculateViewVector(xRot, yRot).
        float pitchRadians = controllerPitch * ((float) Math.PI / 180.0F);
        float yawRadians = -controllerYaw * ((float) Math.PI / 180.0F);
        float cosPitch = CultMath.cos(pitchRadians);
        return new Vec3(
                CultMath.sin(yawRadians) * cosPitch, -CultMath.sin(pitchRadians), CultMath.cos(yawRadians) * cosPitch);
    }

    static double groundJumpVelocityY(double currentVelocityY, double jumpPower) {
        // jumpFromGround preserves a larger existing Y velocity (MCP-Reborn LivingEntity.java:2332-2339).
        return Math.max(currentVelocityY, jumpPower);
    }
}
