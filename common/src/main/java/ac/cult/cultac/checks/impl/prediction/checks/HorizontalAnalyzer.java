package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.ItemUseTickResult;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoSneakSlow;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.OmniSprint;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.Speed;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.Strafe;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaTravelInputs;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.NumFormatter;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.StuckEdgeData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.OptifineFastMath;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.FlightToggle;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

public class HorizontalAnalyzer implements EngineCheck {
    public static final double HORIZONTAL_INPUT_UNCERTAINTY = 0.001D;
    // Read only within one handleResult call on the owning player's thread; never stored.
    private final SimpleCollisionBox crawlBox = new SimpleCollisionBox();

    public void handleResult(CultPlayer player, PredictionResult result, PredictionResult lastResult) {
        if (result.getProfileResult(BedrockPredictionResult.class) != null) {
            return;
        }
        PacketEntity vehicle = result.getSimulationContext().getVehicle();
        if (result.getSimulationContext().usesFallFlyingMovement()
                && !result.getSimulationContext().getWorldData().isInLiquid()) {
            return;
        }
        if (vehicle != null) {
            return;
        }

        float playerSpeed = result.getSimulationContext().getMaxSpeed(player);
        // ClientSprintState decides whether the sprint speed is available for this tick's travel branches
        double sprintMultiplier = playerSpeed > 0
                ? result.getSimulationContext().getMaxSprintInputSpeed(player, result.getInitialStartingVel())
                        / playerSpeed
                : 1.3D;

        Vec3 target = result.getTarget();

        // Let's check the speed of the player first as it's the simplest
        Vec3 closestForSpeed = result.getAcceptedClosestToTarget();
        double horizDiffX = target.x - closestForSpeed.x;
        double horizDiffZ = target.z - closestForSpeed.z;
        var pass = JavaMovementEngine.passAfterTravel(result);
        // A moving face can hide travel input on its axis. Measure the independently replayed input there;
        // the engine still requires the report to reach the exact face after that travel.
        boolean exactInput = JavaTravelInputs.isExact(result.getInitialStartingVel());
        if (exactInput && sprintHasForwardImpulse(result.getSimulationContext())) {
            // The refactored travel already consumed moveRelative's input, so its residual cannot show direction.
            // LocalPlayer#shouldStopRunSprinting requires forward impulse before that travel, even in the air.
            double sprintOffset = distanceToForwardImpulse(
                    new SimpleCollisionBox(0, 0, 0, 0, 0, 0),
                    Vec3.ZERO,
                    result.getInitialStartingVel().getTravelInput(),
                    result.getSimulationContext().getXRot(),
                    result.getSimulationContext().getVersion());
            if (sprintOffset > HORIZONTAL_INPUT_UNCERTAINTY) {
                result.addFlag(
                        player.checkManager.getListener(OmniSprint.class),
                        () -> NumFormatter.formatNumberStandard(sprintOffset),
                        sprintOffset);
            }
        }
        if (pass != null && !exactInput) {
            if (pass.result().fixedX()) horizDiffX = pass.input().x;
            if (pass.result().fixedZ()) horizDiffZ = pass.input().z;
        }

        Vec3 minimumInputRequired = getBestTheoreticalPlayerInput(
                horizDiffX,
                horizDiffZ,
                result.getSimulationContext().getXRot(),
                result.getSimulationContext().getVersion()); // player inputs

        // Entity#getInputVector scales the input by the speed, so it moves nothing at speed 0
        double inputScaledToSpeed = playerSpeed > 0 ? minimumInputRequired.length() / playerSpeed : 0;

        // To account for collisions, we need to figure out which way the player was moving to hit the collision
        // If a player was moving in positive X by 2.0 blocks/tick, and hit a block at 1.0 blocks away, they are fine.
        double absInputX = Math.abs(minimumInputRequired.x);
        double absInputZ = Math.abs(minimumInputRequired.z);

        boolean wasOnGround = result.getSimulationContext().getLastOnGround().determineOptimistically();
        Class<? extends Check> pseudoCheck = wasOnGround ? Speed.class : Strafe.class;
        final Check speedOrStrafe = player.checkManager.getCheck(pseudoCheck);

        // A player cannot move more than 0.98 "input" in a single direction
        // A player cannot move more than a total of 1 "input" in a single tick
        // Entity#move has already consumed this candidate's exact moveRelative input and clipped it.
        // A residual cannot spend another walking-speed allowance after that collision.
        double speedFlagAmount = exactInput
                ? Math.max(0.0D, Math.hypot(horizDiffX, horizDiffZ) - 1.0E-5D)
                : getHorizontalInputFlagAmount(
                        absInputX,
                        absInputZ,
                        playerSpeed,
                        sprintMultiplier,
                        result.getSimulationContext().getWorldData().isInLiquid());

        if (speedFlagAmount > 0.0D) {
            result.addFlag(
                    speedOrStrafe,
                    () -> NumFormatter.formatNumberStandard(minimumInputRequired.x) + " "
                            + NumFormatter.formatNumberStandard(minimumInputRequired.z),
                    speedFlagAmount);
        }

        boolean isSlowed = result.getSimulationContext().isLastSneaking();
        Vec3 from = result.getSimulationContext().getStart();

        // The logic is that if the player has collisions above this, they will simply go into swimming position
        SimpleCollisionBox oldBox = GetBoundingBox.getBoundingBoxFromPosAndSize(
                        crawlBox, from.x, from.y, from.z, 0.6f, 0.6f)
                .expand(-SimpleCollisionBox.COLLISION_EPSILON);
        if (!Collisions.isEmpty(player, oldBox)) {
            isSlowed = false;
        }
        if (player.inVehicle()) isSlowed = false;
        // LocalPlayer#aiStep crouches only a player not flying before its toggle; a flyer still moves slowly crawling.
        Pose pose = result.getSimulationContext().getPose();
        if (FlightToggle.flyingBeforeAiStep(player) && pose != Pose.SWIMMING && pose != Pose.FALL_FLYING) {
            isSlowed = false;
        }
        // LocalPlayer#aiStep crouches only where the crouching pose fits blocks and entities such as boats
        // (Player#canPlayerFitWithinBlocksAndEntitiesWhen); a player that does not crouch is slowed only crawling.
        if (isSlowed
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_14)
                && pose != Pose.SWIMMING
                && !player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING, from.x, from.y, from.z)) {
            isSlowed = false;
        }
        // LocalPlayer#aiStep crouches a player in water only when Entity#updateSwimming, which needs the sprint flag,
        // left it not swimming and the crouching pose fits; Entity#isVisuallyCrawling never slows it in water.
        if (isSlowed
                && result.getSimulationContext().getWorldData().isInWater()
                && (player.sprintState.flagBeforeAiStep()
                        || !player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING, from.x, from.y, from.z))) {
            isSlowed = false;
        }
        // FluidPush grants the on-foot current as a radius around the prediction, which does not keep the residual
        // inside the sneaking input square.
        if (result.getSimulationContext().getWorldData().isInFlowingLiquid()) isSlowed = false;

        if (isSlowed && !player.isFlying && player.usesModernJavaItemUse()) {
            result.setSneakingInputMultiplier(
                    (float) player.compensatedEntities.getSelf().sneakingSpeed.value());
        }

        if (isSlowed) {
            double maxDirLength = getSneakingMaxInputAxis(
                    playerSpeed,
                    sprintMultiplier,
                    player.compensatedEntities.getSelf().sneakingSpeed.value());
            double requiredAxis = Math.min(
                    Math.max(absInputX, absInputZ),
                    minimumClippedInputAxis(
                            horizDiffX,
                            horizDiffZ,
                            result.getSimulationContext().getXRot(),
                            result.getCollideAxisData(),
                            target,
                            preCollisionMovement(result),
                            touchedFaces(player, result, Collisions.Axis.X),
                            touchedFaces(player, result, Collisions.Axis.Z)));
            speedFlagAmount = requiredAxis - maxDirLength;

            if (speedFlagAmount > 0.001) {
                result.addFlag(
                        player.checkManager.getListener(NoSneakSlow.class),
                        () -> NumFormatter.formatNumberStandard(minimumInputRequired.x) + " "
                                + NumFormatter.formatNumberStandard(minimumInputRequired.z),
                        speedFlagAmount);
            }
        }

        StuckEdgeData lastOnEdge = lastResult == null
                ? null
                : lastResult.getSimulationContext().getWorldData().getSneak();

        // From here on are checks we cannot do if the player doesn't have inputs, is sneaking, or is colliding with a
        // wall
        // A piston can move the player away from the wall that clipped the travel. The packet's net
        // direction then probes the other side; retain Entity#move's exact collision from that travel.
        if (inputScaledToSpeed < 0.01
                || pass != null && pass.travelHorizontalCollision()
                || result.getCollideAxisData().getX().isLikelyCollide()
                || result.getCollideAxisData().getZ().isLikelyCollide()
                || (lastOnEdge != null && lastOnEdge.isOnEdge())
                || (lastResult != null
                        && lastResult
                                .getSimulationContext()
                                .getWorldData()
                                .getClimbing()
                                .determineOptimistically())) return;

        double playerAngle = normalizeAngle(result.getSimulationContext().getXRot());
        AngleResult angleResult = inputAngleRange(result, target, playerAngle);

        if (!exactInput && sprintHasForwardImpulse(result.getSimulationContext())) {
            double sprintOffset = distanceToForwardImpulse(
                    result.getValidMovements().getCollisionIgnoredMaxStartingVelExtents(),
                    closestForSpeed,
                    pass == null ? target : target.subtract(pass.movement()),
                    result.getSimulationContext().getXRot(),
                    result.getSimulationContext().getVersion());
            if (sprintOffset > HORIZONTAL_INPUT_UNCERTAINTY) {
                result.addFlag(
                        player.checkManager.getListener(OmniSprint.class),
                        () -> NumFormatter.formatNumberStandard(sprintOffset),
                        sprintOffset);
            }
        }

        final Check angleCheck = player.checkManager.getAngleCheck();
        if (result.getValidMovements()
                .getCollisionIgnoredMaxStartingVelExtents()
                .isHorizEmpty()) {
            double absX = Math.abs(minimumInputRequired.x);
            double absZ = Math.abs(minimumInputRequired.z);

            double distToValid = Math.min(Math.min(Math.abs(absX - absZ), absX), absZ);
            if (distToValid > 0.01) { // More lenient than it must be.
                result.addFlag(
                        angleCheck,
                        () -> "1: " + NumFormatter.formatNumberStandard(distToValid),
                        distToValid / 500); // Not that bad to flag
            }
        } else {
            // Angle check - make sure the player isn't moving in (0.8, 0.2) for example
            double distanceToValid =
                    minDistanceToValidMovementAngle(angleResult.getMinAngle(), angleResult.getMaxAngle());

            if (distanceToValid > 7) { // Highly lenient but sane threshold
                result.addFlag(
                        angleCheck,
                        () -> "2: " + NumFormatter.formatNumberStandard(distanceToValid),
                        distanceToValid / 500); // Not that bad to flag
            }
        }
    }

    static boolean sprintHasForwardImpulse(SimulationContext context) {
        if (context.getIsSprinting() != DesyncStatus.TRUE) return false;
        if (!context.getWorldData().isSwimming()) return true;
        return context.getLastOnGround() == DesyncStatus.FALSE && !context.isSneaking() && !context.isLastSneaking();
    }

    static double distanceToForwardImpulse(
            SimpleCollisionBox starts, Vec3 closest, Vec3 target, float yaw, ClientVersion version) {
        if (target.x >= starts.minX && target.x <= starts.maxX && target.z >= starts.minZ && target.z <= starts.maxZ) {
            return 0.0D;
        }
        float radians = yaw * 0.017453292f;
        double vanilla = distanceToForwardImpulse(
                starts, closest, target, VanillaMath.sin(version, radians), VanillaMath.cos(version, radians));
        if (vanilla == 0.0D) return 0.0D;
        return Math.min(
                vanilla,
                distanceToForwardImpulse(
                        starts, closest, target, OptifineFastMath.sin(radians), OptifineFastMath.cos(radians)));
    }

    private static double distanceToForwardImpulse(
            SimpleCollisionBox starts, Vec3 closest, Vec3 target, float sin, float cos) {
        double scale = (double) sin * sin + (double) cos * cos;
        if (scale == 0) return Float.MAX_VALUE;
        double closestX = target.x - closest.x;
        double closestZ = target.z - closest.z;
        double fromClosest = distanceToForwardCone(
                (closestX * cos + closestZ * sin) / scale, (closestZ * cos - closestX * sin) / scale);
        if (fromClosest == 0.0D) return 0.0D;
        double lowX = target.x - starts.maxX;
        double highX = target.x - starts.minX;
        double lowZ = target.z - starts.maxZ;
        double highZ = target.z - starts.minZ;
        double s0 = (lowX * cos + lowZ * sin) / scale;
        double f0 = (lowZ * cos - lowX * sin) / scale;
        double s1 = (highX * cos + lowZ * sin) / scale;
        double f1 = (lowZ * cos - highX * sin) / scale;
        double s2 = (highX * cos + highZ * sin) / scale;
        double f2 = (highZ * cos - highX * sin) / scale;
        double s3 = (lowX * cos + highZ * sin) / scale;
        double f3 = (highZ * cos - lowX * sin) / scale;
        return Math.min(
                fromClosest,
                Math.min(
                        Math.min(segmentToForwardCone(s0, f0, s1, f1), segmentToForwardCone(s1, f1, s2, f2)),
                        Math.min(segmentToForwardCone(s2, f2, s3, f3), segmentToForwardCone(s3, f3, s0, f0))));
    }

    private static double segmentToForwardCone(double as, double af, double bs, double bf) {
        double toA = distanceToForwardCone(as, af);
        if (toA == 0.0D) return 0.0D;
        double toB = distanceToForwardCone(bs, bf);
        if (toB == 0.0D) return 0.0D;
        double ds = bs - as;
        double df = bf - af;
        if (crossesBoundary(as, af, ds, df, 1.0D) || crossesBoundary(as, af, ds, df, -1.0D)) return 0.0D;
        double lengthSqr = ds * ds + df * df;
        double along = lengthSqr == 0.0D ? 0.0D : CultMath.clamp(-(as * ds + af * df) / lengthSqr, 0.0D, 1.0D);
        double toApex = Math.hypot(as + along * ds, af + along * df);
        return Math.min(Math.min(toA, toB), toApex);
    }

    private static boolean crossesBoundary(double as, double af, double ds, double df, double side) {
        double denominator = ds - df * side;
        if (denominator == 0.0D) return false;
        double along = -(as - af * side) / denominator;
        double ray = (af * ds - as * df) / denominator;
        return along >= 0.0D && along <= 1.0D && ray >= 0.0D;
    }

    private static double distanceToForwardCone(double strafe, double forward) {
        if (forward >= Math.abs(strafe)) return 0.0D;
        double best = Math.sqrt(strafe * strafe + forward * forward);
        for (int side = -1; side <= 1; side += 2) {
            double along = (side * strafe + forward) / Math.sqrt(2.0D);
            if (along <= 0.0D) continue;
            double offX = strafe - along * side / Math.sqrt(2.0D);
            double offZ = forward - along / Math.sqrt(2.0D);
            best = Math.min(best, Math.sqrt(offX * offX + offZ * offZ));
        }
        return best;
    }

    /** The flag amount for the horizontal input {@code (absX, 0, absZ)}. */
    static double getHorizontalInputFlagAmount(
            double absX, double absZ, double playerSpeed, double sprintMultiplier, boolean mustBeInLiquid) {
        double magnitude = Math.sqrt(absX * absX + absZ * absZ);
        // Entity#getInputVector scales the input by the speed, so it moves nothing at speed 0
        if (playerSpeed == 0) {
            return magnitude > ItemUseTickResult.NUMERIC_EPSILON ? magnitude : 0.0D;
        }
        if (magnitude > playerSpeed * (sprintMultiplier + HORIZONTAL_INPUT_UNCERTAINTY)) {
            return magnitude;
        }
        double sprinting = mustBeInLiquid ? 1.0D : sprintMultiplier;
        double maxSpeedInOneDirection = playerSpeed * sprinting * 0.98D;
        double axisExcess = Math.max(absX - maxSpeedInOneDirection, absZ - maxSpeedInOneDirection);
        return axisExcess > HORIZONTAL_INPUT_UNCERTAINTY ? axisExcess : 0.0D;
    }

    // LocalPlayer#modifyInput scales the normalized input by 0.98F and sneaking_speed
    static double getSneakingMaxInputAxis(double playerSpeed, double sprintMultiplier, double sneakingSpeed) {
        return playerSpeed * sprintMultiplier * 0.98D * sneakingSpeed;
    }

    public static double minDistanceToValidMovementAngle(double angleMin, double angleMax) {
        double minDistance = Double.MAX_VALUE;
        for (int i = -180; i <= 180; i += 45) {
            double clamped = CultMath.clamp(i, angleMin, angleMax);
            double distance = Math.abs(clamped - i);
            if (distance < minDistance) minDistance = distance;
        }
        return minDistance;
    }

    private static double normalizeAngle(double angle) {
        angle = angle % 360;
        if (angle < 0) angle += 360;
        return angle;
    }

    static AngleResult inputAngleRange(PredictionResult result, Vec3 target, double playerAngle) {
        // The extents contain the travel's input-free movement. Level#tickBlockEntities subsequently
        // adds position-only movement, so Entity#moveRelative's direction is measured before that pass.
        var pass = JavaMovementEngine.passAfterTravel(result);
        if (pass != null) target = target.subtract(pass.movement());
        return getAngleRange(
                result.getValidMovements().getCollisionIgnoredMaxStartingVelExtents(), target, playerAngle);
    }

    private static AngleResult getAngleRange(SimpleCollisionBox startingPoint, Vec3 target, double playerAngle) {
        double minAngle = Double.MAX_VALUE;
        double maxAngle = -Double.MAX_VALUE;

        double x = startingPoint.minX;
        while (true) {
            double z = startingPoint.minZ;
            while (true) {
                double angle = Math.atan2(target.z - z, target.x - x);
                angle = radiansToDegrees(angle);
                angle = normalizeAngle(angle);

                double diffToPlayerAngle = angleDistance(angle, playerAngle);
                minAngle = Math.min(minAngle, diffToPlayerAngle);
                maxAngle = Math.max(maxAngle, diffToPlayerAngle);

                if (z == startingPoint.maxZ) break;
                z = startingPoint.maxZ;
            }
            if (x == startingPoint.maxX) break;
            x = startingPoint.maxX;
        }

        return new AngleResult(minAngle, maxAngle);
    }

    public static double angleDistance(double angle1, double angle2) {
        double distance = angle1 - angle2;
        if (distance < -180) {
            distance += 360;
        } else if (distance > 180) {
            distance -= 360;
        }
        return distance;
    }

    @AllArgsConstructor
    @Data
    static class AngleResult {
        double minAngle;
        double maxAngle;
    }

    public static double radiansToDegrees(double radians) {
        double degrees = radians * 180 / Math.PI;
        degrees -= 90; // Minecraft's rotation doesn't match how math works
        if (degrees < 0) degrees += 360;
        return degrees;
    }

    // Entity#collide clips the velocity plus the input at a wall. On an axis whose pre-collision movement reaches
    // past the probed wall, the input is the residual from that movement, not from the clip; on an axis the move ends
    // flush against a wall, any further input towards it is clipped too, so the input there is a one-sided range. A
    // move Entity#collide leaves whole is the pre-collision movement plus the input, whatever the selected movement
    // clipped. Return the smallest largest input axis over those inputs, or MAX_VALUE when no axis reaches a wall
    // and the selected movement clipped nothing.
    static double minimumClippedInputAxis(
            double residualX,
            double residualZ,
            float yaw,
            CollideAxisData collide,
            Vec3 target,
            Vec3 preCollisionMovement) {
        return minimumClippedInputAxis(residualX, residualZ, yaw, collide, target, preCollisionMovement, 0, 0);
    }

    /** {@code touchedX} and {@code touchedZ} are {@link #touchedFaces} on axes the probe gives no side. */
    static double minimumClippedInputAxis(
            double residualX,
            double residualZ,
            float yaw,
            CollideAxisData collide,
            Vec3 target,
            Vec3 preCollisionMovement,
            int touchedX,
            int touchedZ) {
        return minimumClippedInput(
                residualX, residualZ, yaw, collide, target, preCollisionMovement, touchedX, touchedZ, MAX_INPUT_AXIS);
    }

    /** {@link #minimumClippedInputAxis} for any input cost, from the result's accepted movement and collisions. */
    static double minimumClippedInput(PredictionResult result, InputCost cost) {
        if (result.getCollideAxisData() == null) return Double.MAX_VALUE;
        Vec3 target = result.getTarget();
        // A candidate holding its exact input already moved by it; measure the input from the movement without it.
        Vec3 input = result.getInitialStartingVel() == null
                        || result.getInitialStartingVel().getTravelInput() == null
                ? Vec3.ZERO
                : result.getInitialStartingVel().getTravelInput();
        Vec3 closest = result.getAcceptedClosestToTarget().subtract(input);
        Vec3 preCollisionMovement = preCollisionMovement(result);
        if (preCollisionMovement != null) preCollisionMovement = preCollisionMovement.subtract(input);
        return minimumClippedInput(
                target.x - closest.x,
                target.z - closest.z,
                result.getSimulationContext().getXRot(),
                result.getCollideAxisData(),
                target,
                preCollisionMovement,
                touchedFaces(result.getPlayer(), result, Collisions.Axis.X),
                touchedFaces(result.getPlayer(), result, Collisions.Axis.Z),
                cost);
    }

    /**
     * The faces on {@code axis}, bit {@link #POSITIVE_FACE} and bit {@link #NEGATIVE_FACE}, that the box of the
     * accepted move ends against and that the move on that axis goes toward, when the probe likely collides on that
     * axis without a side. Entity#collide clips any further move into such a face to nothing, and clips a move toward
     * a face to no less than -1.0E-7 (VoxelShape#collideX, VoxelShape.java:265-266).
     */
    static int touchedFaces(CultPlayer player, PredictionResult result, Collisions.Axis axis) {
        CollideAxisData collide = result.getCollideAxisData();
        CollideAxisData.CollideResult axisCollide =
                collide == null ? null : axis == Collisions.Axis.X ? collide.getX() : collide.getZ();
        MovementTrace trace = result.getValidMovements() == null
                ? null
                : result.getValidMovements().getClosestTrace();
        if (player == null
                || result.getSimulationContext() == null
                || trace == null
                || axisCollide == null
                || !axisCollide.isLikelyCollide()
                || axisCollide.getSide() != 0) {
            return 0;
        }
        SimpleCollisionBox box =
                result.getSimulationContext().getFromActualPose().copy().offset(trace.position());
        double moved = axis == Collisions.Axis.X ? trace.position().x : trace.position().z;
        double probe = SimpleCollisionBox.COLLISION_EPSILON;
        List<SimpleCollisionBox> colliders = player.entityCollisionBoxes();
        SimpleCollisionBox query = axis == Collisions.Axis.X
                ? box.copy().expand(2 * probe, 0.0D, 0.0D)
                : box.copy().expand(0.0D, 0.0D, 2 * probe);
        Collisions.getCollisionBoxes(player, query, colliders, false, box.minY);
        int faces = 0;
        if (moved >= -probe
                && Collisions.collideAxis(probe, box, colliders, axis, SimpleCollisionBox.AxisEpsilon.JAVA) != probe) {
            faces |= POSITIVE_FACE;
        }
        if (moved <= probe
                && Collisions.collideAxis(-probe, box, colliders, axis, SimpleCollisionBox.AxisEpsilon.JAVA)
                        != -probe) {
            faces |= NEGATIVE_FACE;
        }
        return faces;
    }

    /** A convex cost of an input-space vector. */
    interface InputCost {
        double at(double x, double z);

        /** The smallest cost of {@code (x + t * dx, z + t * dz)} over {@code t >= 0}. */
        double minimumAlong(double x, double z, double dx, double dz);
    }

    private static final InputCost MAX_INPUT_AXIS = new InputCost() {
        @Override
        public double at(double x, double z) {
            return Math.max(Math.abs(x), Math.abs(z));
        }

        @Override
        public double minimumAlong(double x, double z, double dx, double dz) {
            return minimumMaxAxisAlong(x, z, dx, dz);
        }
    };

    private static double minimumClippedInput(
            double residualX,
            double residualZ,
            float yaw,
            CollideAxisData collide,
            Vec3 target,
            Vec3 preCollisionMovement,
            int touchedX,
            int touchedZ,
            InputCost cost) {
        int facesX = faces(flushSide(collide.getX(), target.x)) | touchedX;
        int facesZ = faces(flushSide(collide.getZ(), target.z)) | touchedZ;
        boolean pastX = preCollisionMovement != null && reachesPast(collide.getX(), preCollisionMovement.x);
        boolean pastZ = preCollisionMovement != null && reachesPast(collide.getZ(), preCollisionMovement.z);
        double wholeX = preCollisionMovement == null ? residualX : target.x - preCollisionMovement.x;
        double wholeZ = preCollisionMovement == null ? residualZ : target.z - preCollisionMovement.z;
        boolean clipped = wholeX != residualX || wholeZ != residualZ;
        if (facesX == 0 && facesZ == 0 && !pastX && !pastZ && !clipped) return Double.MAX_VALUE;
        if (pastX) residualX = target.x - preCollisionMovement.x;
        if (pastZ) residualZ = target.z - preCollisionMovement.z;

        double best = Double.MAX_VALUE;
        float radians = yaw * 0.017453292f;
        float[][] tables = {
            {VanillaMath.sin(radians), VanillaMath.cos(radians)},
            {OptifineFastMath.sin(radians), OptifineFastMath.cos(radians)}
        };
        for (float[] table : tables) {
            float sin = table[0], cos = table[1];
            double scale = (double) sin * sin + (double) cos * cos;
            if (scale == 0) continue;
            double x = (residualX * cos + residualZ * sin) / scale;
            double z = (residualZ * cos - residualX * sin) / scale;
            best = Math.min(best, cost.at(x, z));
            if (clipped) {
                best = Math.min(
                        best, cost.at((wholeX * cos + wholeZ * sin) / scale, (wholeZ * cos - wholeX * sin) / scale));
            }
            // A world X/Z unit rotates to these input-space directions
            for (int side = -1; side <= 1; side += 2) {
                int face = faces(side);
                if ((facesX & face) != 0) best = Math.min(best, cost.minimumAlong(x, z, side * cos, -side * sin));
                if ((facesZ & face) != 0) best = Math.min(best, cost.minimumAlong(x, z, side * sin, side * cos));
            }
        }
        return best;
    }

    private static final int POSITIVE_FACE = 1;
    private static final int NEGATIVE_FACE = 2;

    private static int faces(int side) {
        return side > 0 ? POSITIVE_FACE : side < 0 ? NEGATIVE_FACE : 0;
    }

    /** Whether {@code movement} reaches past the probed wall, where Entity#collide would clip it. */
    private static boolean reachesPast(CollideAxisData.CollideResult collide, double movement) {
        return collide != null
                && collide.isLikelyCollide()
                && collide.getSide() * (movement - collide.getResult()) > 0.0D;
    }

    /**
     * The movement the collision clipped, without the stuck speed like the target and the probe, or null when a piston
     * or shulker shove moved the player separately.
     */
    private static Vec3 preCollisionMovement(PredictionResult result) {
        MovementTrace trace = result.getValidMovements() == null
                ? null
                : result.getValidMovements().getClosestTrace();
        if (trace == null
                || trace.preCollisionMovement() == null
                || trace.collisionBaseOffset().lengthSqr() > 1.0E-14) {
            return null;
        }
        Vec3 stuck = trace.position().collisionStuckSpeedMultiplier(result.getSimulationContext());
        if (stuck == null || stuck.x == 1.0D && stuck.y == 1.0D && stuck.z == 1.0D) {
            return trace.preCollisionMovement();
        }
        return trace.preCollisionMovement().multiply(1 / stuck.x, 1 / stuck.y, 1 / stuck.z);
    }

    private static int flushSide(CollideAxisData.CollideResult collide, double movement) {
        if (collide == null || !collide.isLikelyCollide()) return 0;
        if (Math.abs(movement - collide.getResult()) > SimpleCollisionBox.COLLISION_EPSILON) return 0;
        return collide.getSide();
    }

    // max(|x + t * dx|, |z + t * dz|) over t >= 0 is piecewise linear and convex, so its minimum is at t = 0 or at a
    // point where either term is zero or both are equal in magnitude.
    private static double minimumMaxAxisAlong(double x, double z, double dx, double dz) {
        double[] candidates = {0, -x / dx, -z / dz, (z - x) / (dx - dz), -(x + z) / (dx + dz)};
        double best = Double.MAX_VALUE;
        for (double t : candidates) {
            if (!(t >= 0) || Double.isInfinite(t)) continue;
            best = Math.min(best, Math.max(Math.abs(x + t * dx), Math.abs(z + t * dz)));
        }
        return best;
    }

    public static Vec3 getBestTheoreticalPlayerInput(Vec3 wantedMovement, float f2, ClientVersion version) {
        return getBestTheoreticalPlayerInput(wantedMovement.x, wantedMovement.z, f2, version);
    }

    public static Vec3 getBestTheoreticalPlayerInput(double wantedX, double wantedZ, float f2, ClientVersion version) {
        float radians = f2 * 0.017453292f;
        float correctSin = VanillaMath.sin(version, radians);
        float correctCos = VanillaMath.cos(version, radians);
        float shitSin = OptifineFastMath.sin(radians);
        float shitCos = OptifineFastMath.cos(radians);
        double correctScale = rotationScale(correctSin, correctCos);
        double shitScale = rotationScale(shitSin, shitCos);

        if (correctScale == 0 || shitScale == 0) {
            if (correctScale != 0) return invertInputRotation(wantedX, wantedZ, correctSin, correctCos, correctScale);
            if (shitScale != 0) return invertInputRotation(wantedX, wantedZ, shitSin, shitCos, shitScale);
            return unreachableInput(wantedX, wantedZ);
        }

        double correctX = inputX(wantedX, wantedZ, correctSin, correctCos, correctScale);
        double correctZ = inputZ(wantedX, wantedZ, correctSin, correctCos, correctScale);
        double shitX = inputX(wantedX, wantedZ, shitSin, shitCos, shitScale);
        double shitZ = inputZ(wantedX, wantedZ, shitSin, shitCos, shitScale);

        // Only a table saturated by a huge yaw shrinks its rotation, so the other table needs less input
        double correctLength = correctX * correctX + correctZ * correctZ;
        double shitLength = shitX * shitX + shitZ * shitZ;
        boolean correct;
        if (Math.abs(correctLength - shitLength) > 1.0E-3 * Math.max(correctLength, shitLength)) {
            correct = correctLength < shitLength;
        } else {
            correct = distanceToValidInput(correctX, correctZ) < distanceToValidInput(shitX, shitZ);
        }
        return correct ? new Vec3(correctX, 0, correctZ) : new Vec3(shitX, 0, shitZ);
    }

    // Entity#getInputVector rotates input by [[cos, -sin], [sin, cos]], a rotation scaled by sin^2 + cos^2.
    // The trig tables only keep that scale at 1 for sane yaws; huge yaws saturate the table index and shrink it
    // towards 0, so dividing it back out is required for the leftover movement to map to the input that caused it.
    // A scale of 0 means no input moves the player at this yaw.
    private static double rotationScale(float sin, float cos) {
        return (double) sin * sin + (double) cos * cos;
    }

    private static Vec3 invertInputRotation(double movementX, double movementZ, float sin, float cos, double scale) {
        return new Vec3(
                inputX(movementX, movementZ, sin, cos, scale), 0, inputZ(movementX, movementZ, sin, cos, scale));
    }

    private static double inputX(double movementX, double movementZ, float sin, float cos, double scale) {
        return (movementX * cos + movementZ * sin) / scale;
    }

    private static double inputZ(double movementX, double movementZ, float sin, float cos, double scale) {
        return (movementZ * cos - movementX * sin) / scale;
    }

    private static Vec3 unreachableInput(double movementX, double movementZ) {
        double length = Math.sqrt(movementX * movementX + movementZ * movementZ);
        if (length == 0) return Vec3.ZERO;
        // Finite so the flag severity stays usable by setbacks
        return new Vec3(movementX / length * Float.MAX_VALUE, 0, movementZ / length * Float.MAX_VALUE);
    }

    private static double distanceToValidInput(double inputX, double inputZ) {
        double absX = Math.abs(inputX);
        double absZ = Math.abs(inputZ);
        return Math.min(Math.min(Math.abs(absX - absZ), absX), absZ);
    }
}
