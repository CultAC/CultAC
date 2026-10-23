// This file was designed and is an original check for CultACBukkitLoaderPlugin
// Copyright (C) 2021 DefineOutside
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
package ac.cult.cultac.utils.data;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.RootVehicleInterpolationCollision;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

// You may not copy the check unless you are licensed under GPL
public class ReachInterpolationData {
    private static final double REACH_PACKET_POSITION_UNCERTAINTY = 0.03125D;
    /** A start extent within this of the new size is that size; the size change rounds by less. */
    private static final double SIZE_EPSILON = 1.0E-7D;
    /** Queue length past which the two oldest steps fold into one. */
    static final int MAX_QUEUED_STEPS = 128;

    private SimpleCollisionBox targetLocation;
    // RC1 SteppedInterpolationHandler state belongs to this interpolation snapshot.
    // Keep exact transforms separately from boxes to avoid coordinate round trips.
    private ArrayDeque<Step> interpolationQueue;
    private Transform steppedCurrent;
    private Transform steppedTarget;
    private Transform lastStep;
    private Transform sampleFrom;
    private Transform sampleTo;
    private float sampleAmount;
    private float currentStepTicks;
    private float remainingTicks;
    private float interpolationSpeed = 1.0F;
    private CultPlayer interpolationPlayer;
    private SimpleCollisionBox startingLocation;
    private float currentYaw;
    private float currentPitch;
    private float targetYaw;
    private float targetPitch;
    private int interpolationStepsLowBound = 0;
    private int interpolationStepsHighBound = 0;
    private int interpolationSteps = 1;
    private final boolean reachesUsePacketPositionUncertainty;
    // Reach judges a Java snapshot without the margin until a queue fold or an uncertain receipt clears this.
    private boolean positionExact;
    // getPossibleMovementLocationCombined while boundsValid; every write to its inputs clears boundsValid.
    private boolean boundsValid;
    private double boundsMinX, boundsMinY, boundsMinZ, boundsMaxX, boundsMaxY, boundsMaxZ;
    private boolean boundsFullBlock;

    private boolean settled;
    private Transform probeCurrent;
    private int probeYaw, probePitch, probeRemaining, probeSpeed;
    private long probeMinX, probeMinY, probeMinZ, probeMaxX, probeMaxY, probeMaxZ;

    private ReachInterpolationData(ReachInterpolationData other) {
        this.targetLocation = other.targetLocation.copy();
        this.interpolationQueue = other.interpolationQueue == null ? null : new ArrayDeque<>(other.interpolationQueue);
        this.steppedCurrent = other.steppedCurrent;
        this.steppedTarget = other.steppedTarget;
        this.lastStep = other.lastStep;
        this.currentStepTicks = other.currentStepTicks;
        this.remainingTicks = other.remainingTicks;
        this.interpolationSpeed = other.interpolationSpeed;
        this.interpolationPlayer = other.interpolationPlayer;
        this.startingLocation = other.startingLocation.copy();
        this.currentYaw = other.currentYaw;
        this.currentPitch = other.currentPitch;
        this.targetYaw = other.targetYaw;
        this.targetPitch = other.targetPitch;
        this.interpolationStepsLowBound = other.interpolationStepsLowBound;
        this.interpolationStepsHighBound = other.interpolationStepsHighBound;
        this.interpolationSteps = other.interpolationSteps;
        this.reachesUsePacketPositionUncertainty = other.reachesUsePacketPositionUncertainty;
        this.positionExact = other.positionExact;
    }

    public ReachInterpolationData copy() {
        return new ReachInterpolationData(this);
    }

    public ReachInterpolationData(
            CultPlayer player,
            SimpleCollisionBox startingLocation,
            double x,
            double y,
            double z,
            boolean isPointNine,
            PacketEntity entity) {
        this(player, startingLocation, x, y, z, isPointNine, entity, true);
    }

    public ReachInterpolationData(
            CultPlayer player,
            SimpleCollisionBox startingLocation,
            double x,
            double y,
            double z,
            boolean isPointNine,
            PacketEntity entity,
            boolean uncertainInitialSteps) {
        this(
                player,
                startingLocation,
                x,
                y,
                z,
                entity.clientPhysicalYaw,
                entity.clientPhysicalPitch,
                entity.clientPhysicalYaw,
                entity.clientPhysicalPitch,
                isPointNine,
                entity,
                uncertainInitialSteps);
    }

    public ReachInterpolationData(
            CultPlayer player,
            SimpleCollisionBox startingLocation,
            double x,
            double y,
            double z,
            float currentYaw,
            float currentPitch,
            float targetYaw,
            float targetPitch,
            boolean isPointNine,
            PacketEntity entity,
            boolean uncertainInitialSteps) {
        this(
                startingLocation.copy(),
                player,
                x,
                y,
                z,
                currentYaw,
                currentPitch,
                targetYaw,
                targetPitch,
                isPointNine,
                entity,
                uncertainInitialSteps);
    }

    /** The constructor without its defensive copy: the result owns {@code startingLocation}. */
    public static ReachInterpolationData takingStart(
            CultPlayer player,
            SimpleCollisionBox startingLocation,
            double x,
            double y,
            double z,
            float currentYaw,
            float currentPitch,
            float targetYaw,
            float targetPitch,
            boolean isPointNine,
            PacketEntity entity,
            boolean uncertainInitialSteps) {
        return new ReachInterpolationData(
                startingLocation,
                player,
                x,
                y,
                z,
                currentYaw,
                currentPitch,
                targetYaw,
                targetPitch,
                isPointNine,
                entity,
                uncertainInitialSteps);
    }

    private ReachInterpolationData(
            SimpleCollisionBox startingLocation,
            CultPlayer player,
            double x,
            double y,
            double z,
            float currentYaw,
            float currentPitch,
            float targetYaw,
            float targetPitch,
            boolean isPointNine,
            PacketEntity entity,
            boolean uncertainInitialSteps) {
        this.startingLocation = startingLocation;
        this.targetLocation = GetBoundingBox.getBoundingBoxFromPosAndSize(
                x, y, z, BoundingBoxSize.getWidth(player, entity), BoundingBoxSize.getHeight(player, entity));
        this.currentYaw = currentYaw;
        this.currentPitch = currentPitch;
        this.targetYaw = targetYaw;
        this.targetPitch = targetPitch;
        this.reachesUsePacketPositionUncertainty = true;
        this.positionExact = !player.isBedrockMovement();
        this.interpolationSteps = vanillaInterpolationStepsFor(player, entity);

        if (!player.isBedrockMovement() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)) {
            interpolationPlayer = player;
            if (EntityTypeUtil.isLiving(entity.type) && entity.type != EntityTypeIds.SHULKER) {
                interpolationSteps = EntityTypeUtil.modelType(entity.type).updateInterval();
                interpolationQueue = new ArrayDeque<>();
                steppedCurrent = lastStep = new Transform(feet(startingLocation), currentYaw, currentPitch);
                steppedTarget = new Transform(Vec3.ZERO, 0.0F, 0.0F);
                if (!feet(startingLocation).equals(new Vec3(x, y, z))
                        || currentYaw != targetYaw
                        || currentPitch != targetPitch) {
                    appendSteppedPath(EntityPositionPath.linear(new Vec3(x, y, z)), targetYaw, targetPitch);
                }
            } else if (entity.type == EntityTypeIds.FISHING_BOBBER) {
                interpolationSteps = 3;
            }
        }
        if (interpolationQueue == null && isPointNine && uncertainInitialSteps)
            interpolationStepsHighBound = getInterpolationSteps();
    }

    /** Whether this follows the 26.3 client's InterpolationHandler, which Entity#commonTick runs for every entity. */
    public boolean followsInterpolationHandler() {
        return interpolationPlayer != null;
    }

    public boolean usesSteppedInterpolation() {
        return interpolationQueue != null;
    }

    /** Whether reach judges this snapshot without the packet-position margin. */
    public boolean hasExactPosition() {
        return positionExact;
    }

    public void markPositionInexact() {
        positionExact = false;
    }

    public void setPhysicalBeforePathAppend(
            EntityPositionPath path, float targetYaw, float targetPitch, Vec3 position, float yaw, float pitch) {
        settled = false;
        if (interpolationQueue != null && !matchesActiveSteppedTarget(path, targetYaw, targetPitch)) {
            boundsValid = false;
            steppedCurrent = new Transform(position, yaw, pitch);
            currentYaw = yaw;
            currentPitch = pitch;
        }
    }

    public void appendPath(EntityPositionPath path, float yaw, float pitch) {
        settled = false;
        if (interpolationQueue == null) throw new IllegalStateException("Not a stepped entity");
        appendSteppedPath(path, yaw, pitch);
        boundsValid = false;
        targetLocation.offset(path.endPosition().subtract(feet(targetLocation)));
        targetYaw = yaw;
        targetPitch = pitch;
    }

    private void appendSteppedPath(EntityPositionPath path, float yaw, float pitch) {
        Transform end = new Transform(path.endPosition(), yaw, pitch);
        if (interpolationSteps == 0) {
            boundsValid = false;
            steppedCurrent = steppedTarget = end;
            cancelSteppedInterpolation();
            return;
        }
        if (matchesActiveSteppedTarget(path, yaw, pitch)) return;
        if (!hasActiveInterpolationTarget()) {
            lastStep = steppedCurrent;
            currentStepTicks = 1.0F;
        }
        if (path.steps().isEmpty() || path.endPosition().equals(steppedTarget.position())) {
            addStep(end, interpolationSteps);
        } else {
            int total = 0;
            for (EntityPositionPath.Step step : path.steps()) total += step.tickOffset();
            int offset = 0;
            for (EntityPositionPath.Step step : path.steps()) {
                offset += step.tickOffset();
                float a = (float) offset / total;
                addStep(
                        new Transform(
                                step.position(),
                                yaw == steppedTarget.yaw() && pitch == steppedTarget.pitch()
                                        ? yaw
                                        : CultMath.rotLerp(a, steppedTarget.yaw(), yaw),
                                yaw == steppedTarget.yaw() && pitch == steppedTarget.pitch()
                                        ? pitch
                                        : CultMath.lerp(a, steppedTarget.pitch(), pitch)),
                        step.tickOffset());
            }
        }
        steppedTarget = end;
    }

    private void addStep(Transform transform, int ticks) {
        if (interpolationQueue.size() >= MAX_QUEUED_STEPS) {
            // Folding the two oldest steps keeps the queued ticks, so catch-up speed still matches the client.
            Step oldest = interpolationQueue.removeFirst();
            Step next = interpolationQueue.removeFirst();
            interpolationQueue.addFirst(new Step(next.transform(), oldest.ticks() + next.ticks()));
            positionExact = false;
        }
        interpolationQueue.addLast(new Step(transform, ticks));
        remainingTicks += ticks;
    }

    private Transform tickSteppedMovement(float relativeTickSpeed) {
        if (!hasActiveInterpolationTarget()) {
            cancelSteppedInterpolation();
            return steppedCurrent;
        }
        // Vanilla samples first, consumes completed steps, then advances tick progress.
        Transform sampled = sampleSteppedMovement();
        boundsValid = false;
        steppedCurrent = sampled;
        float targetSpeed = Math.max(remainingTicks / interpolationSteps, 1.0F);
        interpolationSpeed = CultMath.lerp(1.0F / interpolationSteps, interpolationSpeed, targetSpeed);
        float advance;
        if (relativeTickSpeed * interpolationSpeed < remainingTicks) {
            advance = relativeTickSpeed * interpolationSpeed;
        } else {
            advance = remainingTicks;
            interpolationSpeed = 1.0F;
        }
        currentStepTicks += advance;
        remainingTicks -= advance;
        return steppedCurrent;
    }

    /** A new transform with the sampled position and the sampled rotation wrapped by {@code % 360}. */
    private Transform sampleSteppedMovement() {
        while (!interpolationQueue.isEmpty()) {
            Step step = interpolationQueue.getFirst();
            if (currentStepTicks < step.ticks()) {
                float a = currentStepTicks / step.ticks();
                return new Transform(
                        lastStep.position().lerp(step.transform().position(), a),
                        CultMath.rotLerp(a, lastStep.yaw(), step.transform().yaw()) % 360.0F,
                        CultMath.lerp(a, lastStep.pitch(), step.transform().pitch()) % 360.0F);
            }
            currentStepTicks -= step.ticks();
            lastStep = step.transform();
            interpolationQueue.removeFirst();
        }
        return new Transform(steppedTarget.position(), steppedTarget.yaw() % 360.0F, steppedTarget.pitch() % 360.0F);
    }

    private boolean matchesActiveSteppedTarget(EntityPositionPath path, float yaw, float pitch) {
        return hasActiveInterpolationTarget()
                && steppedTarget.position().equals(path.endPosition())
                && steppedTarget.yaw() == yaw
                && steppedTarget.pitch() == pitch;
    }

    private void cancelSteppedInterpolation() {
        interpolationQueue.clear();
        remainingTicks = 0.0F;
        interpolationSpeed = 1.0F;
    }

    /** Call only after the native destination collision test has succeeded. */
    private void shiftSteppedPath(Vec3 movement, float yaw, float pitch) {
        steppedTarget = steppedTarget.shift(movement, yaw, pitch);
        lastStep = lastStep.shift(movement, yaw, pitch);
        int size = interpolationQueue.size();
        for (int i = 0; i < size; i++) {
            Step step = interpolationQueue.removeFirst();
            interpolationQueue.addLast(new Step(step.transform().shift(movement, yaw, pitch), step.ticks()));
        }
    }

    public record Transform(Vec3 position, float yaw, float pitch) {
        private Transform shift(Vec3 delta, float yawDelta, float pitchDelta) {
            return new Transform(position.add(delta), yaw + yawDelta, pitch + pitchDelta);
        }
    }

    private record Step(Transform transform, int ticks) {}

    private static Vec3 feet(SimpleCollisionBox box) {
        return new Vec3((box.minX + box.maxX) / 2.0D, box.minY, (box.minZ + box.maxZ) / 2.0D);
    }

    private SimpleCollisionBox steppedBox(Vec3 position) {
        return steppedBox(position, new SimpleCollisionBox());
    }

    private SimpleCollisionBox steppedBox(Vec3 position, SimpleCollisionBox out) {
        double feetX = (targetLocation.minX + targetLocation.maxX) / 2.0D;
        double feetY = targetLocation.minY;
        double feetZ = (targetLocation.minZ + targetLocation.maxZ) / 2.0D;
        return out.set(targetLocation).offset(position.x - feetX, position.y - feetY, position.z - feetZ);
    }

    /** The transform the next stepped tick moves to, without advancing this interpolation. */
    public Transform nextSteppedTransform() {
        if (interpolationQueue.isEmpty()) return steppedCurrent;
        Transform sampled = peekSteppedSample();
        return new Transform(sampled.position(), sampled.yaw() % 360.0F, sampled.pitch() % 360.0F);
    }

    /** {@link #nextSteppedTransform()}'s position. */
    private Vec3 nextSteppedPosition() {
        if (interpolationQueue.isEmpty()) return steppedCurrent.position();
        return locateSteppedSample()
                ? sampleFrom.position().lerp(sampleTo.position(), sampleAmount)
                : steppedTarget.position();
    }

    /** {@link #nextSteppedTransform()}'s yaw. */
    private float nextSteppedYaw() {
        if (interpolationQueue.isEmpty()) return steppedCurrent.yaw();
        float yaw = locateSteppedSample()
                ? CultMath.rotLerp(sampleAmount, sampleFrom.yaw(), sampleTo.yaw())
                : steppedTarget.yaw();
        return yaw % 360.0F;
    }

    /** {@link #nextSteppedTransform()}'s pitch. */
    private float nextSteppedPitch() {
        if (interpolationQueue.isEmpty()) return steppedCurrent.pitch();
        float pitch = locateSteppedSample()
                ? CultMath.lerp(sampleAmount, sampleFrom.pitch(), sampleTo.pitch())
                : steppedTarget.pitch();
        return pitch % 360.0F;
    }

    /**
     * Stores the step pair and amount {@link #peekSteppedSample()} lerps between, returning false when it samples the
     * target; the fields are read before any other call.
     */
    private boolean locateSteppedSample() {
        float stepTicks = currentStepTicks;
        Transform from = lastStep;
        for (Step step : interpolationQueue) {
            if (stepTicks < step.ticks()) {
                sampleFrom = from;
                sampleTo = step.transform();
                sampleAmount = stepTicks / step.ticks();
                return true;
            }
            stepTicks -= step.ticks();
            from = step.transform();
        }
        return false;
    }

    /** {@link #sampleSteppedMovement()} without consuming completed steps or wrapping the rotation. */
    private Transform peekSteppedSample() {
        float stepTicks = currentStepTicks;
        Transform from = lastStep;
        for (Step step : interpolationQueue) {
            if (stepTicks < step.ticks()) {
                float a = stepTicks / step.ticks();
                return new Transform(
                        from.position().lerp(step.transform().position(), a),
                        CultMath.rotLerp(a, from.yaw(), step.transform().yaw()),
                        CultMath.lerp(a, from.pitch(), step.transform().pitch()));
            }
            stepTicks -= step.ticks();
            from = step.transform();
        }
        return steppedTarget;
    }

    private static int vanillaInterpolationStepsFor(CultPlayer player, PacketEntity entity) {
        if (!player.isBedrockMovement() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_11)) {
            // ExperienceOrb's handler takes the default 3 steps.
            if (entity.type == EntityTypeIds.EXPERIENCE_ORB) return 3;
            // A display's handler takes teleport_duration steps. A 0-step display snaps to each packet position,
            // so only its spawn path, which covers no distance, is built with these steps.
            if (entity.isDisplay()) return Math.max(entity.displayTeleportDuration(), 1);
        }
        if (EntityTypeUtil.isBoat(entity.type)) {
            // MCP-Reborn AbstractBoat constructs its client interpolation handler
            // with 3 steps. Older 10-step assumptions leave collision entities
            // behind the client-visible boat.
            return 3;
        }
        if (EntityTypeUtil.isMinecart(entity.type)) {
            // Without FeatureFlags.MINECART_IMPROVEMENTS, AbstractMinecart uses
            // OldMinecartBehavior, whose InterpolationHandler is constructed with
            // the default 3 client ticks.
            return 3;
        }
        if (entity.type == EntityTypeIds.SHULKER) {
            return 1;
        }
        return EntityTypeUtil.isLiving(entity.type) ? 3 : 1;
    }

    // While riding entities, there is no interpolation.
    public ReachInterpolationData(SimpleCollisionBox finishedLoc) {
        this.startingLocation = finishedLoc.copy();
        this.targetLocation = finishedLoc.copy();
        this.reachesUsePacketPositionUncertainty = false;
        this.positionExact = true;
    }

    /**
     * Puts a state built by {@link #ReachInterpolationData(SimpleCollisionBox)}, or copied from one, back to the state
     * that constructor builds from {@code finishedLoc}. Returns false, changing nothing, for any other state.
     */
    public boolean resetFinished(SimpleCollisionBox finishedLoc) {
        if (reachesUsePacketPositionUncertainty) return false;
        targetLocation.set(finishedLoc);
        interpolationQueue = null;
        steppedCurrent = null;
        steppedTarget = null;
        lastStep = null;
        sampleFrom = null;
        sampleTo = null;
        sampleAmount = 0.0F;
        currentStepTicks = 0.0F;
        remainingTicks = 0.0F;
        interpolationSpeed = 1.0F;
        interpolationPlayer = null;
        startingLocation.set(finishedLoc);
        currentYaw = 0.0F;
        currentPitch = 0.0F;
        targetYaw = 0.0F;
        targetPitch = 0.0F;
        interpolationStepsLowBound = 0;
        interpolationStepsHighBound = 0;
        interpolationSteps = 1;
        positionExact = true;
        boundsValid = false;
        boundsMinX = boundsMinY = boundsMinZ = boundsMaxX = boundsMaxY = boundsMaxZ = 0.0D;
        boundsFullBlock = false;
        settled = false;
        probeCurrent = null;
        probeYaw = probePitch = probeRemaining = probeSpeed = 0;
        probeMinX = probeMinY = probeMinZ = probeMaxX = probeMaxY = probeMaxZ = 0L;
        return true;
    }

    private int getInterpolationSteps() {
        return interpolationSteps;
    }

    public static SimpleCollisionBox combineCollisionBox(SimpleCollisionBox one, SimpleCollisionBox two) {
        return combineCollisionBox(one, two, new SimpleCollisionBox());
    }

    /** Writes the union into {@code out}, which may be either input. */
    public static SimpleCollisionBox combineCollisionBox(
            SimpleCollisionBox one, SimpleCollisionBox two, SimpleCollisionBox out) {
        double minX = Math.min(one.minX, two.minX);
        double maxX = Math.max(one.maxX, two.maxX);
        double minY = Math.min(one.minY, two.minY);
        double maxY = Math.max(one.maxY, two.maxY);
        double minZ = Math.min(one.minZ, two.minZ);
        double maxZ = Math.max(one.maxZ, two.maxZ);

        return out.set(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public SimpleCollisionBox getPossibleLocationCombined(SimpleCollisionBox out) {
        SimpleCollisionBox box = getPossibleMovementLocationCombined(out);
        if (reachesUsePacketPositionUncertainty && !positionExact) {
            // Packet-position uncertainty applies to reach queries only: vanilla
            // EntityGetter#getEntityCollisions uses the concrete bounding box, so
            // movement physics must never see this expansion.
            box.expand(REACH_PACKET_POSITION_UNCERTAINTY);
        }
        return box;
    }

    // To avoid huge branching when bruteforcing interpolation -
    // we combine the collision boxes for the steps.
    //
    // Designed around being unsure of minimum interp, maximum interp, and target location on 1.9 clients
    public SimpleCollisionBox getPossibleMovementLocationCombined() {
        return getPossibleMovementLocationCombined(new SimpleCollisionBox());
    }

    public SimpleCollisionBox getPossibleMovementLocationCombined(SimpleCollisionBox out) {
        if (boundsValid)
            return out.set(boundsMinX, boundsMinY, boundsMinZ, boundsMaxX, boundsMaxY, boundsMaxZ, boundsFullBlock);
        if (interpolationQueue != null) steppedBox(steppedCurrent.position(), out);
        else
            combinedInterpolatedBounds(
                    startingLocation,
                    targetLocation,
                    getInterpolationSteps(),
                    interpolationStepsLowBound,
                    interpolationStepsHighBound,
                    out);
        boundsMinX = out.minX;
        boundsMinY = out.minY;
        boundsMinZ = out.minZ;
        boundsMaxX = out.maxX;
        boundsMaxY = out.maxY;
        boundsMaxZ = out.maxZ;
        boundsFullBlock = out.isFullBlock();
        boundsValid = true;
        return out;
    }

    static SimpleCollisionBox combinedInterpolatedBounds(
            SimpleCollisionBox start, SimpleCollisionBox target, int steps, int low, int high) {
        return combinedInterpolatedBounds(start, target, steps, low, high, new SimpleCollisionBox());
    }

    /** Grim 2.0's endpoint union: every corner is monotone over the interpolation range. */
    private static SimpleCollisionBox combinedInterpolatedBounds(
            SimpleCollisionBox start, SimpleCollisionBox target, int steps, int low, int high, SimpleCollisionBox out) {
        double minStepX = (target.minX - start.minX) / steps;
        double minStepY = (target.minY - start.minY) / steps;
        double minStepZ = (target.minZ - start.minZ) / steps;
        double maxStepX = (target.maxX - start.maxX) / steps;
        double maxStepY = (target.maxY - start.maxY) / steps;
        double maxStepZ = (target.maxZ - start.maxZ) / steps;
        return out.set(
                Math.min(start.minX + low * minStepX, start.minX + high * minStepX),
                Math.min(start.minY + low * minStepY, start.minY + high * minStepY),
                Math.min(start.minZ + low * minStepZ, start.minZ + high * minStepZ),
                Math.max(start.maxX + low * maxStepX, start.maxX + high * maxStepX),
                Math.max(start.maxY + low * maxStepY, start.maxY + high * maxStepY),
                Math.max(start.maxZ + low * maxStepZ, start.maxZ + high * maxStepZ));
    }

    private SimpleCollisionBox movementLocationAtStep(int step, SimpleCollisionBox out) {
        int interpSteps = getInterpolationSteps();
        double stepMinX = (targetLocation.minX - startingLocation.minX) / interpSteps;
        double stepMaxX = (targetLocation.maxX - startingLocation.maxX) / interpSteps;
        double stepMinY = (targetLocation.minY - startingLocation.minY) / interpSteps;
        double stepMaxY = (targetLocation.maxY - startingLocation.maxY) / interpSteps;
        double stepMinZ = (targetLocation.minZ - startingLocation.minZ) / interpSteps;
        double stepMaxZ = (targetLocation.maxZ - startingLocation.maxZ) / interpSteps;
        return out.set(
                startingLocation.minX + (step * stepMinX),
                startingLocation.minY + (step * stepMinY),
                startingLocation.minZ + (step * stepMinZ),
                startingLocation.maxX + (step * stepMaxX),
                startingLocation.maxY + (step * stepMaxY),
                startingLocation.maxZ + (step * stepMaxZ));
    }

    public List<SimpleCollisionBox> getPossibleMovementLocations() {
        if (interpolationQueue != null) return List.of(steppedBox(steppedCurrent.position()));
        List<SimpleCollisionBox> locations =
                new ArrayList<>(interpolationStepsHighBound - interpolationStepsLowBound + 1);
        for (int step = interpolationStepsLowBound; step <= interpolationStepsHighBound; step++) {
            locations.add(movementLocationAtStep(step, new SimpleCollisionBox()));
        }

        return locations;
    }

    /** The number of {@link #getPossibleMovementLocations()}. */
    public int possibleMovementLocationCount() {
        return interpolationQueue != null ? 1 : interpolationStepsHighBound - interpolationStepsLowBound + 1;
    }

    /** Writes the {@code index}th of {@link #getPossibleMovementLocations()} into {@code out}. */
    public SimpleCollisionBox possibleMovementLocation(int index, SimpleCollisionBox out) {
        if (interpolationQueue != null) return steppedBox(steppedCurrent.position(), out);
        return movementLocationAtStep(interpolationStepsLowBound + index, out);
    }

    public boolean hasExactMovementLocationAfterClientTick() {
        return interpolationStepsLowBound == interpolationStepsHighBound;
    }

    /**
     * Writes into {@code out} the location {@link #copyAdvancedForClientTick copyAdvancedForClientTick(true, true)}
     * would report. Call only when {@link #hasExactMovementLocationAfterClientTick()}.
     */
    public SimpleCollisionBox exactMovementLocationAfterClientTick(SimpleCollisionBox out) {
        if (interpolationQueue != null) {
            return steppedBox(nextSteppedPosition(), out);
        }
        int step = Math.min(interpolationStepsLowBound + 1, getInterpolationSteps());
        return movementLocationAtStep(step, out);
    }

    /**
     * Whether {@link #getExactMovementLocationAfterClientTickFromPhysical} advances a legacy interpolation from the
     * physical location. Stepped interpolations keep both step bounds at zero, so their physical answer equals
     * {@link #exactMovementLocationAfterClientTick}.
     */
    public boolean interpolatesFromPhysicalAfterClientTick() {
        return interpolationQueue == null
                && interpolationStepsLowBound == interpolationStepsHighBound
                && hasActiveInterpolationTarget()
                && getInterpolationSteps() - interpolationStepsLowBound > 0;
    }

    /** A legacy interpolation writes its answer into {@code physicalLocation}. */
    public SimpleCollisionBox getExactMovementLocationAfterClientTickFromPhysical(SimpleCollisionBox physicalLocation) {
        if (interpolationQueue != null) return steppedBox(nextSteppedPosition());
        if (interpolationStepsLowBound != interpolationStepsHighBound || !hasActiveInterpolationTarget()) {
            return null;
        }

        int remainingSteps = getInterpolationSteps() - interpolationStepsLowBound;
        if (remainingSteps <= 0) {
            return null;
        }

        double lerpAmount = 1.0D / remainingSteps;
        return lerpBox(physicalLocation, targetLocation, lerpAmount, physicalLocation);
    }

    public SimpleCollisionBox getExactMovementLocation() {
        if (interpolationStepsLowBound != interpolationStepsHighBound) {
            return null;
        }

        return getPossibleMovementLocationCombined();
    }

    /** {@link #getExactMovementLocation()} written into {@code out}. */
    public SimpleCollisionBox getExactMovementLocation(SimpleCollisionBox out) {
        if (interpolationStepsLowBound != interpolationStepsHighBound) {
            return null;
        }

        return getPossibleMovementLocationCombined(out);
    }

    public int getExactInterpolationStep() {
        return interpolationStepsLowBound == interpolationStepsHighBound ? interpolationStepsLowBound : -1;
    }

    public int getTotalInterpolationSteps() {
        return getInterpolationSteps();
    }

    /**
     * Client ticks the queued stepped path can still take. SteppedInterpolationHandler.InterpolationData#advance
     * consumes at least one tick of the path per client tick.
     */
    public int remainingSteppedTicks() {
        return interpolationQueue == null || interpolationQueue.isEmpty() ? 0 : (int) Math.ceil(remainingTicks);
    }

    public SimpleCollisionBox getTargetLocation() {
        return targetLocation.copy();
    }

    /** The feet position of the target location, as computed from {@link #getTargetLocation()}. */
    public Vec3 targetPosition() {
        return new Vec3(targetCenterX(), targetMinY(), targetCenterZ());
    }

    public double targetCenterX() {
        return (targetLocation.maxX - targetLocation.minX) / 2.0D + targetLocation.minX;
    }

    public double targetMinY() {
        return targetLocation.minY;
    }

    public double targetCenterZ() {
        return (targetLocation.maxZ - targetLocation.minZ) / 2.0D + targetLocation.minZ;
    }

    public float getCurrentYaw() {
        return currentYaw;
    }

    public float getCurrentPitch() {
        return currentPitch;
    }

    public float getTargetYaw() {
        return targetYaw;
    }

    public float getTargetPitch() {
        return targetPitch;
    }

    public void setExactMovementLocation(SimpleCollisionBox exactLocation) {
        settled = false;
        boundsValid = false;
        if (interpolationQueue != null) {
            if (!steppedCurrentIs(exactLocation, currentYaw, currentPitch)) {
                steppedCurrent = new Transform(feet(exactLocation), currentYaw, currentPitch);
            }
            return;
        }
        if (interpolationStepsLowBound != interpolationStepsHighBound) {
            return;
        }

        int interpSteps = getInterpolationSteps();
        int step = interpolationStepsLowBound;
        if (step <= 0) {
            startingLocation.set(exactLocation);
            return;
        }
        if (step >= interpSteps) {
            startingLocation.set(targetLocation);
            return;
        }

        double targetFraction = (double) step / interpSteps;
        double startFraction = 1.0D - targetFraction;
        startingLocation.set(
                solveStartCoordinate(exactLocation.minX, targetLocation.minX, targetFraction, startFraction),
                solveStartCoordinate(exactLocation.minY, targetLocation.minY, targetFraction, startFraction),
                solveStartCoordinate(exactLocation.minZ, targetLocation.minZ, targetFraction, startFraction),
                solveStartCoordinate(exactLocation.maxX, targetLocation.maxX, targetFraction, startFraction),
                solveStartCoordinate(exactLocation.maxY, targetLocation.maxY, targetFraction, startFraction),
                solveStartCoordinate(exactLocation.maxZ, targetLocation.maxZ, targetFraction, startFraction));
    }

    private boolean steppedCurrentIs(SimpleCollisionBox location, float yaw, float pitch) {
        Vec3 position = steppedCurrent.position();
        return Double.doubleToRawLongBits(position.x)
                        == Double.doubleToRawLongBits((location.minX + location.maxX) / 2.0D)
                && Double.doubleToRawLongBits(position.y) == Double.doubleToRawLongBits(location.minY)
                && Double.doubleToRawLongBits(position.z)
                        == Double.doubleToRawLongBits((location.minZ + location.maxZ) / 2.0D)
                && Float.floatToRawIntBits(steppedCurrent.yaw()) == Float.floatToRawIntBits(yaw)
                && Float.floatToRawIntBits(steppedCurrent.pitch()) == Float.floatToRawIntBits(pitch);
    }

    private static double solveStartCoordinate(
            double exact, double target, double targetFraction, double startFraction) {
        return (exact - (targetFraction * target)) / startFraction;
    }

    /** Writes the lerp into {@code out}, which may be {@code from}. */
    private static SimpleCollisionBox lerpBox(
            SimpleCollisionBox from, SimpleCollisionBox to, double amount, SimpleCollisionBox out) {
        return out.set(
                lerp(from.minX, to.minX, amount),
                lerp(from.minY, to.minY, amount),
                lerp(from.minZ, to.minZ, amount),
                lerp(from.maxX, to.maxX, amount),
                lerp(from.maxY, to.maxY, amount),
                lerp(from.maxZ, to.maxZ, amount));
    }

    private static double lerp(double from, double to, double amount) {
        return from + amount * (to - from);
    }

    private static float lerp(float from, float to, float amount) {
        return from + amount * (to - from);
    }

    /** Moves the start and the target each by the step {@code stepFrom} gives for its own position. */
    public void offset(UnaryOperator<Vec3> stepFrom) {
        boundsValid = false;
        startingLocation.offset(stepFrom.apply(feet(startingLocation)));
        targetLocation.offset(stepFrom.apply(feet(targetLocation)));
    }

    /** {@code Entity#reapplyPosition}: every box keeps its position and takes the new dimensions. */
    public void resize(float width, float height) {
        boundsValid = false;
        // A pending second transaction leaves the start a union of boxes the target's size; each keeps its feet.
        double widthChange = width - (targetLocation.maxX - targetLocation.minX);
        double heightChange = height - (targetLocation.maxY - targetLocation.minY);
        SimpleCollisionBox start = new SimpleCollisionBox(
                startingLocation.minX - widthChange / 2.0D,
                startingLocation.minY,
                startingLocation.minZ - widthChange / 2.0D,
                startingLocation.maxX + widthChange / 2.0D,
                startingLocation.maxY + heightChange,
                startingLocation.maxZ + widthChange / 2.0D);
        // A start smaller than the target never shrinks below the new size; the box constructor does not sort.
        if (start.maxX - start.minX < width - SIZE_EPSILON) {
            double centerX = (startingLocation.minX + startingLocation.maxX) / 2.0D;
            start.minX = centerX - width / 2.0D;
            start.maxX = centerX + width / 2.0D;
        }
        if (start.maxZ - start.minZ < width - SIZE_EPSILON) {
            double centerZ = (startingLocation.minZ + startingLocation.maxZ) / 2.0D;
            start.minZ = centerZ - width / 2.0D;
            start.maxZ = centerZ + width / 2.0D;
        }
        if (start.maxY - start.minY < height - SIZE_EPSILON) start.maxY = start.minY + height;
        startingLocation = start;
        targetLocation = resized(targetLocation, width, height);
    }

    private static SimpleCollisionBox resized(SimpleCollisionBox box, float width, float height) {
        Vec3 feet = feet(box);
        return GetBoundingBox.getBoundingBoxFromPosAndSize(feet.x, feet.y, feet.z, width, height);
    }

    public void shiftTargetIfCollisionFree(CultPlayer player, Vec3 movement) {
        settled = false;
        if (movement.lengthSqr() == 0.0D) {
            return;
        }

        SimpleCollisionBox shiftedTarget = targetLocation.copy().offset(movement);
        if (Collisions.isEmpty(player, shiftedTarget, shiftedTarget.minY)) {
            boundsValid = false;
            targetLocation.offset(movement);
            if (interpolationQueue != null) shiftSteppedPath(movement, 0, 0);
        }
    }

    public void shiftRootVehicleTargetIfCollisionFree(CultPlayer player, PacketEntity entity, Vec3 movement) {
        settled = false;
        if (movement.lengthSqr() == 0.0D) {
            return;
        }

        Vec3 targetPosition = new Vec3(
                (targetLocation.minX + targetLocation.maxX) / 2.0D,
                targetLocation.minY,
                (targetLocation.minZ + targetLocation.maxZ) / 2.0D);
        if (RootVehicleInterpolationCollision.shouldShiftRootInterpolationTarget(
                player, entity, targetPosition, movement)) {
            boundsValid = false;
            targetLocation.offset(movement);
            if (interpolationQueue != null) shiftSteppedPath(movement, 0, 0);
        }
    }

    public void shiftTargetRotationByPhysical(float physicalYaw, float physicalPitch) {
        settled = false;
        if (interpolationStepsLowBound != interpolationStepsHighBound || !hasActiveInterpolationTarget()) {
            return;
        }

        if (interpolationQueue != null)
            shiftSteppedPath(Vec3.ZERO, physicalYaw - currentYaw, physicalPitch - currentPitch);
        targetYaw += physicalYaw - currentYaw;
        targetPitch += physicalPitch - currentPitch;
    }

    public float getExactYawAfterClientTickFromPhysical(float physicalYaw) {
        if (interpolationQueue != null) return nextSteppedYaw();
        int remainingSteps = getInterpolationSteps() - interpolationStepsLowBound;
        if (remainingSteps <= 0) {
            return physicalYaw;
        }

        // LinearInterpolationHandler#doInterpolate lerps the yaw in double.
        if (followsInterpolationHandler() && !usesSteppedInterpolation())
            return (float) CultMath.rotLerp(1.0 / remainingSteps, physicalYaw, targetYaw);
        return CultMath.rotLerp(1.0F / remainingSteps, physicalYaw, targetYaw);
    }

    public float getExactPitchAfterClientTickFromPhysical(float physicalPitch) {
        if (interpolationQueue != null) return nextSteppedPitch();
        int remainingSteps = getInterpolationSteps() - interpolationStepsLowBound;
        if (remainingSteps <= 0) {
            return physicalPitch;
        }

        // LinearInterpolationHandler#doInterpolate lerps the pitch in double.
        if (followsInterpolationHandler())
            return (float) CultMath.lerp(1.0 / remainingSteps, physicalPitch, targetPitch);
        return lerp(physicalPitch, targetPitch, 1.0F / remainingSteps);
    }

    public void setExactRotation(float yaw, float pitch) {
        settled = false;
        if (interpolationQueue != null) {
            boundsValid = false;
            steppedCurrent = new Transform(steppedCurrent.position(), yaw, pitch);
        }
        this.currentYaw = yaw;
        this.currentPitch = pitch;
    }

    public void setCurrentAndTargetRotation(float yaw, float pitch) {
        settled = false;
        this.currentYaw = yaw;
        this.currentPitch = pitch;
        this.targetYaw = yaw;
        this.targetPitch = pitch;
    }

    public boolean hasActiveInterpolationTarget() {
        if (interpolationQueue != null) return !interpolationQueue.isEmpty();
        return reachesUsePacketPositionUncertainty && interpolationStepsLowBound < getInterpolationSteps();
    }

    public void updatePossibleStartingLocation(SimpleCollisionBox possibleLocationCombined) {
        settled = false;
        if (interpolationQueue != null) return;
        boundsValid = false;
        // CultACBukkitLoaderPlugin.staticGetLogger().info(ChatColor.BLUE + "Updated new starting location as second
        // trans hasn't arrived " + startingLocation);
        combineCollisionBox(startingLocation, possibleLocationCombined, startingLocation);
        // CultAC.staticGetLogger().info(ChatColor.BLUE + "Finished updating new starting location as second trans
        // hasn't arrived " + startingLocation);
    }

    /**
     * Whether an idle client tick has proven itself a no-op on this state and no mutator has run since. Only the idle
     * tick paths {@link #beginIdleProbe} admits reach this.
     */
    public boolean isSettled() {
        return settled;
    }

    /**
     * Records the fields an idle client tick writes, returning false when the state is not idle: a stepped
     * interpolation with an empty queue, or a legacy one with both step bounds at the final step.
     */
    public boolean beginIdleProbe() {
        if (interpolationQueue != null) {
            if (!interpolationQueue.isEmpty()) return false;
            probeCurrent = steppedCurrent;
            probeYaw = Float.floatToRawIntBits(currentYaw);
            probePitch = Float.floatToRawIntBits(currentPitch);
            probeRemaining = Float.floatToRawIntBits(remainingTicks);
            probeSpeed = Float.floatToRawIntBits(interpolationSpeed);
            return true;
        }
        int steps = getInterpolationSteps();
        if (interpolationStepsLowBound != steps || interpolationStepsHighBound != steps) return false;
        probeMinX = Double.doubleToRawLongBits(startingLocation.minX);
        probeMinY = Double.doubleToRawLongBits(startingLocation.minY);
        probeMinZ = Double.doubleToRawLongBits(startingLocation.minZ);
        probeMaxX = Double.doubleToRawLongBits(startingLocation.maxX);
        probeMaxY = Double.doubleToRawLongBits(startingLocation.maxY);
        probeMaxZ = Double.doubleToRawLongBits(startingLocation.maxZ);
        return true;
    }

    /** Marks this state settled when the idle tick since {@link #beginIdleProbe} left every field it writes as it was. */
    public boolean settleIfIdleTickUnchanged() {
        boolean unchanged;
        if (interpolationQueue != null) {
            unchanged = interpolationQueue.isEmpty()
                    && steppedCurrent == probeCurrent
                    && Float.floatToRawIntBits(currentYaw) == probeYaw
                    && Float.floatToRawIntBits(currentPitch) == probePitch
                    && Float.floatToRawIntBits(remainingTicks) == probeRemaining
                    && Float.floatToRawIntBits(interpolationSpeed) == probeSpeed;
        } else {
            int steps = getInterpolationSteps();
            unchanged = interpolationStepsLowBound == steps
                    && interpolationStepsHighBound == steps
                    && Double.doubleToRawLongBits(startingLocation.minX) == probeMinX
                    && Double.doubleToRawLongBits(startingLocation.minY) == probeMinY
                    && Double.doubleToRawLongBits(startingLocation.minZ) == probeMinZ
                    && Double.doubleToRawLongBits(startingLocation.maxX) == probeMaxX
                    && Double.doubleToRawLongBits(startingLocation.maxY) == probeMaxY
                    && Double.doubleToRawLongBits(startingLocation.maxZ) == probeMaxZ;
        }
        probeCurrent = null;
        settled = unchanged;
        return unchanged;
    }

    public ReachInterpolationData copyAdvancedForClientTick(boolean incrementLowBound, boolean tickingReliably) {
        ReachInterpolationData copy = new ReachInterpolationData(this);
        copy.tickMovement(incrementLowBound, tickingReliably);
        return copy;
    }

    public void tickMovement(boolean incrementLowBound, boolean tickingReliably) {
        settled = false;
        boundsValid = false;
        if (interpolationQueue != null) {
            Transform transform =
                    tickSteppedMovement(Math.max(interpolationPlayer.packetStateData.serverTickRate / 20.0F, 1.0F));
            currentYaw = transform.yaw();
            currentPitch = transform.pitch();
            return;
        }
        if (!tickingReliably) this.interpolationStepsHighBound = getInterpolationSteps();
        if (incrementLowBound)
            this.interpolationStepsLowBound = Math.min(interpolationStepsLowBound + 1, getInterpolationSteps());
        this.interpolationStepsHighBound = Math.min(interpolationStepsHighBound + 1, getInterpolationSteps());
    }

    @Override
    public String toString() {
        return "ReachInterpolationData{" + "targetLocation="
                + targetLocation + ", startingLocation="
                + startingLocation + ", currentYaw="
                + currentYaw + ", currentPitch="
                + currentPitch + ", targetYaw="
                + targetYaw + ", targetPitch="
                + targetPitch + ", interpolationStepsLowBound="
                + interpolationStepsLowBound + ", interpolationStepsHighBound="
                + interpolationStepsHighBound + '}';
    }
}
