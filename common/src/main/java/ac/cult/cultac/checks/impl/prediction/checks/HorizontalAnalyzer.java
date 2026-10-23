package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.InputKeys;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoSneakSlow;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.OmniSprint;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.Speed;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.Strafe;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.NumFormatter;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.KnownInput;
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

    public void handleResult(CultPlayer player, PredictionResult result, PredictionResult lastResult) {
        if (result.getProfileResult(BedrockPredictionResult.class) != null) {
            return;
        }
        PacketEntity vehicle = result.getSimulationContext().getVehicle();
        if (result.getSimulationContext().usesFallFlyingMovement()
                && !result.getSimulationContext().getWorldData().mustBeInLiquid()) {
            return;
        }
        if (vehicle != null) {
            return;
        }

        float playerSpeed = result.getSimulationContext().getMaxSpeed(player);
        // ClientSprintState decides whether the sprint speed is available for this tick's travel branches
        double sprintMultiplier =
                playerSpeed > 0 ? result.getSimulationContext().getMaxSprintInputSpeed(player, result.getInitialStartingVel()) / playerSpeed : 1.3D;

        Vec3 target = result.getTarget();

        // Let's check the speed of the player first as it's the simplest
        Vec3 closestForSpeed = result.getAcceptedClosestToTarget();
        double horizDiffX = target.x - closestForSpeed.x;
        double horizDiffZ = target.z - closestForSpeed.z;

        Vec3 minimumInputRequired = getBestTheoreticalPlayerInput(
                horizDiffX,
                horizDiffZ,
                result.getSimulationContext().getXRot(),
                result.getSimulationContext().getVersion()); // player inputs

        // Can a player have a speed of 0?
        double inputScaledToSpeed = minimumInputRequired.length() / playerSpeed;

        // To account for collisions, we need to figure out which way the player was moving to hit the collision
        // If a player was moving in positive X by 2.0 blocks/tick, and hit a block at 1.0 blocks away, they are fine.
        double absInputX = Math.abs(minimumInputRequired.x);
        double absInputZ = Math.abs(minimumInputRequired.z);

        boolean wasOnGround = result.getSimulationContext().getLastOnGround().determineOptimistically();
        Class<? extends Check> pseudoCheck = wasOnGround ? Speed.class : Strafe.class;
        final Check speedOrStrafe = player.checkManager.getCheck(pseudoCheck);

        // A player cannot move more than 0.98 "input" in a single direction
        // A player cannot move more than a total of 1 "input" in a single tick
        double speedFlagAmount = getHorizontalInputFlagAmount(
                absInputX,
                absInputZ,
                playerSpeed,
                sprintMultiplier,
                result.getSimulationContext().getWorldData().mustBeInLiquid());

        if (speedFlagAmount > 0.0D) {
            result.addFlag(
                    speedOrStrafe,
                    () -> NumFormatter.formatNumberStandard(minimumInputRequired.x) + " "
                            + NumFormatter.formatNumberStandard(minimumInputRequired.z),
                    speedFlagAmount);
        }

        double keysOffset = ReportedKeyInput.offset(player, result, lastResult);
        if (keysOffset > HORIZONTAL_INPUT_UNCERTAINTY) {
            KnownInput keys = player.packetStateData.knownInput;
            result.addFlag(
                    player.checkManager.getListener(InputKeys.class),
                    () -> NumFormatter.formatNumberStandard(keysOffset) + " " + keys,
                    keysOffset);
        }

        // TODO: If we want to, require sent a sneaking packet in the last tick to allow tick skip exempt
        boolean isSlowed = result.getSimulationContext().isLastSneaking()
                && !result.getInitialStartingVel().isTickSkip();
        Vec3 from = result.getSimulationContext().getStart();

        // The logic is that if the player has collisions above this, they will simply go into swimming position
        SimpleCollisionBox oldBox = GetBoundingBox.getBoundingBoxFromPosAndSize(from.x, from.y, from.z, 0.6f, 0.6f)
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
                && result.getSimulationContext().getWorldData().getInWater() != DesyncStatus.FALSE
                && (player.sprintState.flagBeforeAiStep()
                        || !player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING, from.x, from.y, from.z))) {
            isSlowed = false;
        }
        // FluidPush grants the on-foot current as a radius around the prediction, which does not keep the residual
        // inside the sneaking input square.
        if (result.getSimulationContext().getWorldData().getInFlowingLiquid() != DesyncStatus.FALSE) isSlowed = false;

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
        if (inputScaledToSpeed < 0.01
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
        AngleResult angleResult = getAngleRange(
                result.getValidMovements().getCollisionIgnoredMaxStartingVelExtents(), target, playerAngle);

        double minAngle = CultMath.clamp(0, angleResult.getMinAngle(), angleResult.getMaxAngle());

        // We exempted on ground because you can flag for one tick when off the ground
        // We don't actually increase flag severity due to a netcode bug.
        if (wasOnGround && Math.abs(minAngle) > 50 && inputScaledToSpeed > 1.01 && inputScaledToSpeed < 1.301) {
            // TODO: Why does 0.01 offset cause antikb to flag
            result.addFlag(player.checkManager.getListener(OmniSprint.class), () -> minAngle + "", 1e-6);
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

    /** The flag amount for the horizontal input {@code (absX, 0, absZ)}. */
    static double getHorizontalInputFlagAmount(
            double absX, double absZ, double playerSpeed, double sprintMultiplier, boolean mustBeInLiquid) {
        double magnitude = Math.sqrt(absX * absX + absZ * absZ);
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

    private double normalizeAngle(double angle) {
        angle = angle % 360;
        if (angle < 0) angle += 360;
        return angle;
    }

    private AngleResult getAngleRange(SimpleCollisionBox startingPoint, Vec3 target, double playerAngle) {
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
    private static class AngleResult {
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
        Vec3 closest = result.getAcceptedClosestToTarget();
        return minimumClippedInput(
                target.x - closest.x,
                target.z - closest.z,
                result.getSimulationContext().getXRot(),
                result.getCollideAxisData(),
                target,
                preCollisionMovement(result),
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
        Vec3 correctMath = invertInputRotation(
                wantedX, wantedZ, VanillaMath.sin(version, radians), VanillaMath.cos(version, radians));
        Vec3 shitMath = invertInputRotation(wantedX, wantedZ, OptifineFastMath.sin(radians), OptifineFastMath.cos(radians));

        if (correctMath == null || shitMath == null) {
            if (correctMath != null) return correctMath;
            if (shitMath != null) return shitMath;
            return unreachableInput(wantedX, wantedZ);
        }

        // Only a table saturated by a huge yaw shrinks its rotation, so the other table needs less input
        double correctLength = correctMath.lengthSqr();
        double shitLength = shitMath.lengthSqr();
        if (Math.abs(correctLength - shitLength) > 1.0E-3 * Math.max(correctLength, shitLength)) {
            return correctLength < shitLength ? correctMath : shitMath;
        }

        if (distanceToValidInput(correctMath) < distanceToValidInput(shitMath)) {
            return correctMath;
        } else {
            return shitMath;
        }
    }

    // Entity#getInputVector rotates input by [[cos, -sin], [sin, cos]], a rotation scaled by sin^2 + cos^2.
    // The trig tables only keep that scale at 1 for sane yaws; huge yaws saturate the table index and shrink it
    // towards 0, so dividing it back out is required for the leftover movement to map to the input that caused it.
    private static Vec3 invertInputRotation(double movementX, double movementZ, float sin, float cos) {
        double scale = (double) sin * sin + (double) cos * cos;
        if (scale == 0) return null; // No input moves the player at this yaw
        return new Vec3((movementX * cos + movementZ * sin) / scale, 0, (movementZ * cos - movementX * sin) / scale);
    }

    private static Vec3 unreachableInput(double movementX, double movementZ) {
        double length = Math.sqrt(movementX * movementX + movementZ * movementZ);
        if (length == 0) return Vec3.ZERO;
        // Finite so the flag severity stays usable by setbacks
        return new Vec3(movementX / length * Float.MAX_VALUE, 0, movementZ / length * Float.MAX_VALUE);
    }

    private static double distanceToValidInput(Vec3 input) {
        double absX = Math.abs(input.x);
        double absZ = Math.abs(input.z);
        return Math.min(Math.min(Math.abs(absX - absZ), absX), absZ);
    }
}
