package ac.grim.grimac.checks.impl.prediction.checks;

import ac.grim.grimac.bedrock.prediction.BedrockPredictionResult;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.checks.psuedo.NoSneakSlow;
import ac.grim.grimac.checks.impl.prediction.checks.psuedo.OmniSprint;
import ac.grim.grimac.checks.impl.prediction.checks.psuedo.Speed;
import ac.grim.grimac.checks.impl.prediction.checks.psuedo.Strafe;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.NumFormatter;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.StuckEdgeData;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.OptifineFastMath;
import ac.grim.grimac.utils.math.VanillaMath;
import ac.grim.grimac.utils.nmsutil.Collisions;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import lombok.AllArgsConstructor;
import lombok.Data;
import net.minecraft.world.phys.Vec3;

public class HorizontalAnalyzer implements EngineCheck {
    public static final double HORIZONTAL_INPUT_UNCERTAINTY = 0.001D;

    public void handleResult(GrimPlayer player, PredictionResult result, PredictionResult lastResult) {
        if (result.getProfileResult(BedrockPredictionResult.class) != null) {
            return;
        }
        PacketEntity vehicle = result.getSimulationContext().getVehicle();
        if (result.getSimulationContext().usesFallFlyingMovement() && !result.getSimulationContext().getWorldData().mustBeInLiquid()) {
            return;
        }
        if (vehicle != null) {
            return;
        }

        float playerSpeed = result.getSimulationContext().getMaxSpeed(player);

        Vec3 target = result.getTarget();

        // Let's check the speed of the player first as it's the simplest
        Vec3 closestForSpeed = result.getAcceptedClosestToTarget();
        Vec3 horizDiffForInputs = target.subtract(closestForSpeed).multiply(1, 0, 1);

        Vec3 minimumInputRequired = getBestTheoreticalPlayerInput(horizDiffForInputs, result.getSimulationContext().getXRot()); // player inputs

        // Can a player have a speed of 0?
        double inputScaledToSpeed = minimumInputRequired.length() / playerSpeed;

        // To account for collisions, we need to figure out which way the player was moving to hit the collision
        // If a player was moving in positive X by 2.0 blocks/tick, and hit a block at 1.0 blocks away, they are fine.
        Vec3 absInput = new Vec3(Math.abs(minimumInputRequired.x), 0, Math.abs(minimumInputRequired.z));

        boolean wasOnGround = result.getSimulationContext().getLastOnGround().determineOptimistically();
        Class<? extends Check> pseudoCheck = wasOnGround ? Speed.class : Strafe.class;
        final Check speedOrStrafe = player.checkManager.getCheck(pseudoCheck);

        double addHorizUncertainty = HORIZONTAL_INPUT_UNCERTAINTY;

        // A player cannot move more than 0.98 "input" in a single direction
        // A player cannot move more than a total of 1 "input" in a single tick
        // MCP-Reborn LivingEntity#setSprinting applies sprint as a movement-speed
        // attribute modifier. getMaxSpeed already contains that modifier, while
        // LocalPlayer#modifyInput independently enforces this input shape.
        double speedFlagAmount = getHorizontalInputExcess(absInput, playerSpeed);

        if (speedFlagAmount > addHorizUncertainty) {
            result.addFlag(speedOrStrafe, () -> NumFormatter.formatNumberStandard(minimumInputRequired.x) + " " + NumFormatter.formatNumberStandard(minimumInputRequired.z), speedFlagAmount);
        }

        boolean isSlowed = result.getSimulationContext().isSneaking() && result.getSimulationContext().getVersion().isOlderThanOrEquals(ClientVersion.V_1_14_4);
        // 1.14-1.14.4 uses both for slowing, not just one
        if (result.getSimulationContext().getVersion().isNewerThanOrEquals(ClientVersion.V_1_14)) {
            // TODO: If we want to, require sent a sneaking packet in the last tick to allow tick skip exempt
            isSlowed = result.getSimulationContext().isLastSneaking() && !result.getInitialStartingVel().isTickSkip();
            Vec3 from = result.getSimulationContext().getStart();

            // The logic is that if the player has collisions above this, they will simply go into swimming position
            SimpleCollisionBox oldBox = GetBoundingBox.getBoundingBoxFromPosAndSize(from.x, from.y, from.z, 0.6f, 0.6f).expand(-SimpleCollisionBox.COLLISION_EPSILON);
            if (!Collisions.isEmpty(player, oldBox)) {
                isSlowed = false;
            }
            if (player.inVehicle()) isSlowed = false;
        }

        if (isSlowed) {
            double maxDirLength = getSneakingMaxInputAxis(playerSpeed, result.getSimulationContext().getSwiftSneakLevel());
            speedFlagAmount = Math.max(absInput.x - maxDirLength, absInput.z - maxDirLength);

            if (!result.getSimulationContext().getWorldData().maybeInLiquid() && speedFlagAmount > 0.001) {
                result.addFlag(player.checkManager.getListener(NoSneakSlow.class), () -> NumFormatter.formatNumberStandard(minimumInputRequired.x) + " " + NumFormatter.formatNumberStandard(minimumInputRequired.z), speedFlagAmount);
            }
        }

        StuckEdgeData lastOnEdge = lastResult == null ? null : lastResult.getSimulationContext().getWorldData().getSneak();

        // From here on are checks we cannot do if the player doesn't have inputs, is sneaking, or is colliding with a wall
        if (inputScaledToSpeed < 0.01 || result.getCollideAxisData().getX().isLikelyCollide() || result.getCollideAxisData().getZ().isLikelyCollide()
                || (lastOnEdge != null && lastOnEdge.isOnEdge())
                || (lastResult != null && lastResult.getSimulationContext().getWorldData().getClimbing().determineOptimistically()))
            return;

        double playerAngle = normalizeAngle(result.getSimulationContext().getXRot());
        AngleResult angleResult = getAngleRange(result.getValidMovements().getCollisionIgnoredMaxStartingVelExtents(), target, playerAngle);

        double minAngle = GrimMath.clamp(0, angleResult.getMinAngle(), angleResult.getMaxAngle());

        // We exempted on ground because you can flag for one tick when off the ground
        // We don't actually increase flag severity due to a netcode bug.
        if (wasOnGround && Math.abs(minAngle) > 50 && inputScaledToSpeed > 1.01 && inputScaledToSpeed < 1.301) {
            // TODO: Why does 0.01 offset cause antikb to flag
            result.addFlag(player.checkManager.getListener(OmniSprint.class), () -> minAngle + "", 1e-6);
        }

        final Check angleCheck = player.checkManager.getAngleCheck();
        if (result.getValidMovements().getCollisionIgnoredMaxStartingVelExtents().isHorizEmpty()) {
            double absX = Math.abs(minimumInputRequired.x);
            double absZ = Math.abs(minimumInputRequired.z);

            double distToValid = Math.min(Math.min(Math.abs(absX - absZ), absX), absZ);
            if (distToValid > 0.01) { // More lenient than it must be.
                result.addFlag(angleCheck, () -> "1: " + NumFormatter.formatNumberStandard(distToValid), distToValid / 500); // Not that bad to flag
            }
        } else {
            // Angle check - make sure the player isn't moving in (0.8, 0.2) for example
            double distanceToValid = minDistanceToValidMovementAngle(angleResult.getMinAngle(), angleResult.getMaxAngle());

            if (distanceToValid > 7) { // Highly lenient but sane threshold
                result.addFlag(angleCheck, () -> "2: " + NumFormatter.formatNumberStandard(distanceToValid), distanceToValid / 500); // Not that bad to flag
            }
        }


        // We once had a check for making sure non-whole inputs were not allowed, but it was removed due to sneaking
        // Maybe it can come back, eventually.
        // Probably not, what would it even be checking?
    }

    static double getHorizontalInputExcess(Vec3 input, double playerSpeed) {
        double absX = Math.abs(input.x);
        double absZ = Math.abs(input.z);
        double magnitudeExcess = Math.hypot(absX, absZ) - playerSpeed;
        double maxSpeedInOneDirection = playerSpeed * 0.98D;
        double axisExcess = Math.max(absX - maxSpeedInOneDirection, absZ - maxSpeedInOneDirection);
        return Math.max(0.0D, Math.max(magnitudeExcess, axisExcess));
    }

    static double getSneakingMaxInputAxis(double playerSpeed, int swiftSneakLevel) {
        return playerSpeed * 0.295D + (0.15D * swiftSneakLevel);
    }

    public static double minDistanceToValidMovementAngle(double angleMin, double angleMax) {
        double minDistance = Double.MAX_VALUE;
        for (int i = -180; i <= 180; i += 45) {
            double clamped = GrimMath.clamp(i, angleMin, angleMax);
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

    public static Vec3 getBestTheoreticalPlayerInput(Vec3 wantedMovement, float f2) {
        float vanillaSin = VanillaMath.sin(f2 * 0.017453292f);
        float vanillaCos = VanillaMath.cos(f2 * 0.017453292f);
        Vec3 correctMath = new Vec3(wantedMovement.x * vanillaCos + wantedMovement.z * vanillaSin, 0, wantedMovement.z * vanillaCos - wantedMovement.x * vanillaSin);
        double absX = Math.abs(correctMath.x);
        double absZ = Math.abs(correctMath.z);
        double correctMathDiff = Math.min(Math.min(Math.abs(absX - absZ), absX), absZ);

        float f3 = OptifineFastMath.sin(f2 * 0.017453292f);
        float f4 = OptifineFastMath.cos(f2 * 0.017453292f);
        Vec3 shitMath = new Vec3(wantedMovement.x * f4 + wantedMovement.z * f3, 0, wantedMovement.z * f4 - wantedMovement.x * f3);
        absX = Math.abs(shitMath.x);
        absZ = Math.abs(shitMath.z);
        double shitMathDiff = Math.min(Math.min(Math.abs(absX - absZ), absX), absZ);

        if (correctMathDiff < shitMathDiff) {
            return correctMath;
        } else {
            return shitMath;
        }
    }
}
