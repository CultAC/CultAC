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
                horizDiffX, horizDiffZ, result.getSimulationContext().getXRot()); // player inputs

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
                            preCollisionMovement(result)));
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
    // flush against a wall, any further input towards it is clipped too, so the input there is a one-sided range.
    // Return the smallest largest input axis over those inputs, or MAX_VALUE when no axis reaches a wall.
    static double minimumClippedInputAxis(
            double residualX,
            double residualZ,
            float yaw,
            CollideAxisData collide,
            Vec3 target,
            Vec3 preCollisionMovement) {
        return minimumClippedInput(residualX, residualZ, yaw, collide, target, preCollisionMovement, MAX_INPUT_AXIS);
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
                cost);
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
            InputCost cost) {
        int sideX = flushSide(collide.getX(), target.x);
        int sideZ = flushSide(collide.getZ(), target.z);
        boolean pastX = preCollisionMovement != null && reachesPast(collide.getX(), preCollisionMovement.x);
        boolean pastZ = preCollisionMovement != null && reachesPast(collide.getZ(), preCollisionMovement.z);
        if (sideX == 0 && sideZ == 0 && !pastX && !pastZ) return Double.MAX_VALUE;
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
            double x = residualX * cos + residualZ * sin;
            double z = residualZ * cos - residualX * sin;
            best = Math.min(best, cost.at(x, z));
            // A world X/Z unit rotates to these input-space directions
            if (sideX != 0) best = Math.min(best, cost.minimumAlong(x, z, sideX * cos, -sideX * sin));
            if (sideZ != 0) best = Math.min(best, cost.minimumAlong(x, z, sideZ * sin, sideZ * cos));
        }
        return best;
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

    public static Vec3 getBestTheoreticalPlayerInput(Vec3 wantedMovement, float f2) {
        return getBestTheoreticalPlayerInput(wantedMovement.x, wantedMovement.z, f2);
    }

    public static Vec3 getBestTheoreticalPlayerInput(double wantedX, double wantedZ, float f2) {
        float vanillaSin = VanillaMath.sin(f2 * 0.017453292f);
        float vanillaCos = VanillaMath.cos(f2 * 0.017453292f);
        double correctX = wantedX * vanillaCos + wantedZ * vanillaSin;
        double correctZ = wantedZ * vanillaCos - wantedX * vanillaSin;
        double absX = Math.abs(correctX);
        double absZ = Math.abs(correctZ);
        double correctMathDiff = Math.min(Math.min(Math.abs(absX - absZ), absX), absZ);

        float f3 = OptifineFastMath.sin(f2 * 0.017453292f);
        float f4 = OptifineFastMath.cos(f2 * 0.017453292f);
        double shitX = wantedX * f4 + wantedZ * f3;
        double shitZ = wantedZ * f4 - wantedX * f3;
        absX = Math.abs(shitX);
        absZ = Math.abs(shitZ);
        double shitMathDiff = Math.min(Math.min(Math.abs(absX - absZ), absX), absZ);

        if (correctMathDiff < shitMathDiff) {
            return new Vec3(correctX, 0, correctZ);
        } else {
            return new Vec3(shitX, 0, shitZ);
        }
    }
}
