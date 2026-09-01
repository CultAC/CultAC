// This file was designed and is an original check for GrimACBukkitLoaderPlugin
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
package ac.grim.grimac.utils.data;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.nmsutil.BoundingBoxSize;
import ac.grim.grimac.utils.nmsutil.Collisions;
import ac.grim.grimac.utils.nmsutil.EntityTypesCompat;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import ac.grim.grimac.utils.nmsutil.EntityTypeUtil;
import ac.grim.grimac.utils.nmsutil.RootVehicleInterpolationCollision;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// You may not copy the check unless you are licensed under GPL
public class ReachInterpolationData {
    private static final double REACH_PACKET_POSITION_UNCERTAINTY = 0.03125D;

    private final SimpleCollisionBox targetLocation;
    private SimpleCollisionBox startingLocation;
    private float currentYaw;
    private float currentPitch;
    private float targetYaw;
    private float targetPitch;
    private int interpolationStepsLowBound = 0;
    private int interpolationStepsHighBound = 0;
    private int interpolationSteps = 1;
    private final boolean reachesUsePacketPositionUncertainty;

    private ReachInterpolationData(ReachInterpolationData other) {
        this.targetLocation = other.targetLocation.copy();
        this.startingLocation = other.startingLocation.copy();
        this.currentYaw = other.currentYaw;
        this.currentPitch = other.currentPitch;
        this.targetYaw = other.targetYaw;
        this.targetPitch = other.targetPitch;
        this.interpolationStepsLowBound = other.interpolationStepsLowBound;
        this.interpolationStepsHighBound = other.interpolationStepsHighBound;
        this.interpolationSteps = other.interpolationSteps;
        this.reachesUsePacketPositionUncertainty = other.reachesUsePacketPositionUncertainty;
    }

    public ReachInterpolationData copy() {
        return new ReachInterpolationData(this);
    }

    public ReachInterpolationData(GrimPlayer player, SimpleCollisionBox startingLocation, double x, double y, double z, boolean isPointNine, PacketEntity entity) {
        this(player, startingLocation, x, y, z, isPointNine, entity, true);
    }

    public ReachInterpolationData(GrimPlayer player, SimpleCollisionBox startingLocation, double x, double y, double z, boolean isPointNine, PacketEntity entity, boolean uncertainInitialSteps) {
        this(player, startingLocation, x, y, z, entity.clientPhysicalYaw, entity.clientPhysicalPitch,
                entity.clientPhysicalYaw, entity.clientPhysicalPitch, isPointNine, entity, uncertainInitialSteps);
    }

    public ReachInterpolationData(GrimPlayer player,
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
        this.startingLocation = startingLocation.copy();
        this.targetLocation = GetBoundingBox.getBoundingBoxFromPosAndSize(x, y, z, BoundingBoxSize.getWidth(player, entity), BoundingBoxSize.getHeight(player, entity));
        this.currentYaw = currentYaw;
        this.currentPitch = currentPitch;
        this.targetYaw = targetYaw;
        this.targetPitch = targetPitch;
        this.reachesUsePacketPositionUncertainty = true;
        this.interpolationSteps = vanillaInterpolationStepsFor(entity);

        if (isPointNine && uncertainInitialSteps) interpolationStepsHighBound = getInterpolationSteps();
    }

    private static int vanillaInterpolationStepsFor(PacketEntity entity) {
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
        if (entity.type == EntityTypesCompat.SHULKER) {
            return 1;
        }
        return EntityTypeUtil.isLiving(entity.type) ? 3 : 1;
    }

    // While riding entities, there is no interpolation.
    public ReachInterpolationData(SimpleCollisionBox finishedLoc) {
        this.startingLocation = finishedLoc.copy();
        this.targetLocation = finishedLoc.copy();
        this.reachesUsePacketPositionUncertainty = false;
    }

    private int getInterpolationSteps() {
        return interpolationSteps;
    }

    public static SimpleCollisionBox combineCollisionBox(SimpleCollisionBox one, SimpleCollisionBox two) {
        double minX = Math.min(one.minX, two.minX);
        double maxX = Math.max(one.maxX, two.maxX);
        double minY = Math.min(one.minY, two.minY);
        double maxY = Math.max(one.maxY, two.maxY);
        double minZ = Math.min(one.minZ, two.minZ);
        double maxZ = Math.max(one.maxZ, two.maxZ);

        return new SimpleCollisionBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public SimpleCollisionBox getPossibleLocationCombined() {
        SimpleCollisionBox box = getPossibleMovementLocationCombined();
        if (reachesUsePacketPositionUncertainty) {
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
        List<SimpleCollisionBox> locations = getPossibleMovementLocations();
        SimpleCollisionBox combined = locations.get(0);
        for (int i = 1; i < locations.size(); i++) {
            combined = combineCollisionBox(combined, locations.get(i));
        }
        return combined;
    }

    public List<SimpleCollisionBox> getPossibleMovementLocations() {
        int interpSteps = getInterpolationSteps();

        double stepMinX = (targetLocation.minX - startingLocation.minX) / interpSteps;
        double stepMaxX = (targetLocation.maxX - startingLocation.maxX) / interpSteps;
        double stepMinY = (targetLocation.minY - startingLocation.minY) / interpSteps;
        double stepMaxY = (targetLocation.maxY - startingLocation.maxY) / interpSteps;
        double stepMinZ = (targetLocation.minZ - startingLocation.minZ) / interpSteps;
        double stepMaxZ = (targetLocation.maxZ - startingLocation.maxZ) / interpSteps;

        List<SimpleCollisionBox> locations = new ArrayList<>(interpolationStepsHighBound - interpolationStepsLowBound + 1);
        for (int step = interpolationStepsLowBound; step <= interpolationStepsHighBound; step++) {
            locations.add(new SimpleCollisionBox(
                    startingLocation.minX + (step * stepMinX),
                    startingLocation.minY + (step * stepMinY),
                    startingLocation.minZ + (step * stepMinZ),
                    startingLocation.maxX + (step * stepMaxX),
                    startingLocation.maxY + (step * stepMaxY),
                    startingLocation.maxZ + (step * stepMaxZ)));
        }

        return locations;
    }

    public SimpleCollisionBox getExactMovementLocationAfterClientTick() {
        if (interpolationStepsLowBound != interpolationStepsHighBound) {
            return null;
        }

        ReachInterpolationData copy = copyAdvancedForClientTick(true, true);
        if (copy.interpolationStepsLowBound != copy.interpolationStepsHighBound) {
            return null;
        }

        return copy.getPossibleMovementLocations().get(0);
    }

    public SimpleCollisionBox getExactMovementLocationAfterClientTickFromPhysical(SimpleCollisionBox physicalLocation) {
        if (interpolationStepsLowBound != interpolationStepsHighBound || !hasActiveInterpolationTarget()) {
            return null;
        }

        int remainingSteps = getInterpolationSteps() - interpolationStepsLowBound;
        if (remainingSteps <= 0) {
            return null;
        }

        double lerpAmount = 1.0D / remainingSteps;
        return lerpBox(physicalLocation, targetLocation, lerpAmount);
    }

    public SimpleCollisionBox getExactMovementLocation() {
        if (interpolationStepsLowBound != interpolationStepsHighBound) {
            return null;
        }

        return getPossibleMovementLocations().get(0);
    }

    public int getExactInterpolationStep() {
        return interpolationStepsLowBound == interpolationStepsHighBound ? interpolationStepsLowBound : -1;
    }

    public int getTotalInterpolationSteps() {
        return getInterpolationSteps();
    }

    public SimpleCollisionBox getTargetLocation() {
        return targetLocation.copy();
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
        if (interpolationStepsLowBound != interpolationStepsHighBound) {
            return;
        }

        int interpSteps = getInterpolationSteps();
        int step = interpolationStepsLowBound;
        if (step <= 0) {
            this.startingLocation = exactLocation.copy();
            return;
        }
        if (step >= interpSteps) {
            this.startingLocation = targetLocation.copy();
            return;
        }

        double targetFraction = (double) step / interpSteps;
        double startFraction = 1.0D - targetFraction;
        this.startingLocation = new SimpleCollisionBox(
                solveStartCoordinate(exactLocation.minX, targetLocation.minX, targetFraction, startFraction),
                solveStartCoordinate(exactLocation.minY, targetLocation.minY, targetFraction, startFraction),
                solveStartCoordinate(exactLocation.minZ, targetLocation.minZ, targetFraction, startFraction),
                solveStartCoordinate(exactLocation.maxX, targetLocation.maxX, targetFraction, startFraction),
                solveStartCoordinate(exactLocation.maxY, targetLocation.maxY, targetFraction, startFraction),
                solveStartCoordinate(exactLocation.maxZ, targetLocation.maxZ, targetFraction, startFraction)
        );
    }

    private static double solveStartCoordinate(double exact, double target, double targetFraction, double startFraction) {
        return (exact - (targetFraction * target)) / startFraction;
    }

    private static SimpleCollisionBox lerpBox(SimpleCollisionBox from, SimpleCollisionBox to, double amount) {
        return new SimpleCollisionBox(
                lerp(from.minX, to.minX, amount),
                lerp(from.minY, to.minY, amount),
                lerp(from.minZ, to.minZ, amount),
                lerp(from.maxX, to.maxX, amount),
                lerp(from.maxY, to.maxY, amount),
                lerp(from.maxZ, to.maxZ, amount)
        );
    }

    private static double lerp(double from, double to, double amount) {
        return from + amount * (to - from);
    }

    private static float lerp(float from, float to, float amount) {
        return from + amount * (to - from);
    }

    public void shiftTargetIfCollisionFree(GrimPlayer player, Vec3 movement) {
        if (movement.lengthSqr() == 0.0D) {
            return;
        }

        SimpleCollisionBox shiftedTarget = targetLocation.copy().offset(movement);
        if (Collisions.isEmpty(player, shiftedTarget, shiftedTarget.minY)) {
            targetLocation.offset(movement);
        }
    }

    public void shiftRootVehicleTargetIfCollisionFree(GrimPlayer player, PacketEntity entity, Vec3 movement) {
        if (movement.lengthSqr() == 0.0D) {
            return;
        }

        Vec3 targetPosition = new Vec3(
                (targetLocation.minX + targetLocation.maxX) / 2.0D,
                targetLocation.minY,
                (targetLocation.minZ + targetLocation.maxZ) / 2.0D
        );
        if (RootVehicleInterpolationCollision.shouldShiftRootInterpolationTarget(player, entity, targetPosition, movement)) {
            targetLocation.offset(movement);
        }
    }

    public void shiftTargetRotationByPhysical(float physicalYaw, float physicalPitch) {
        if (interpolationStepsLowBound != interpolationStepsHighBound || !hasActiveInterpolationTarget()) {
            return;
        }

        targetYaw += physicalYaw - currentYaw;
        targetPitch += physicalPitch - currentPitch;
    }

    public float getExactYawAfterClientTickFromPhysical(float physicalYaw) {
        int remainingSteps = getInterpolationSteps() - interpolationStepsLowBound;
        if (remainingSteps <= 0) {
            return physicalYaw;
        }

        return Mth.rotLerp(1.0F / remainingSteps, physicalYaw, targetYaw);
    }

    public float getExactPitchAfterClientTickFromPhysical(float physicalPitch) {
        int remainingSteps = getInterpolationSteps() - interpolationStepsLowBound;
        if (remainingSteps <= 0) {
            return physicalPitch;
        }

        return lerp(physicalPitch, targetPitch, 1.0F / remainingSteps);
    }

    public void setExactRotation(float yaw, float pitch) {
        this.currentYaw = yaw;
        this.currentPitch = pitch;
    }

    public void setCurrentAndTargetRotation(float yaw, float pitch) {
        this.currentYaw = yaw;
        this.currentPitch = pitch;
        this.targetYaw = yaw;
        this.targetPitch = pitch;
    }

    public boolean hasActiveInterpolationTarget() {
        return reachesUsePacketPositionUncertainty
                && interpolationStepsLowBound < getInterpolationSteps();
    }

    public void updatePossibleStartingLocation(SimpleCollisionBox possibleLocationCombined) {
        //GrimACBukkitLoaderPlugin.staticGetLogger().info(ChatColor.BLUE + "Updated new starting location as second trans hasn't arrived " + startingLocation);
        this.startingLocation = combineCollisionBox(startingLocation, possibleLocationCombined);
        //GrimAC.staticGetLogger().info(ChatColor.BLUE + "Finished updating new starting location as second trans hasn't arrived " + startingLocation);
    }

    public ReachInterpolationData copyAdvancedForClientTick(boolean incrementLowBound, boolean tickingReliably) {
        ReachInterpolationData copy = new ReachInterpolationData(this);
        copy.tickMovement(incrementLowBound, tickingReliably);
        return copy;
    }

    public void tickMovement(boolean incrementLowBound, boolean tickingReliably) {
        if (!tickingReliably) this.interpolationStepsHighBound = getInterpolationSteps();
        if (incrementLowBound)
            this.interpolationStepsLowBound = Math.min(interpolationStepsLowBound + 1, getInterpolationSteps());
        this.interpolationStepsHighBound = Math.min(interpolationStepsHighBound + 1, getInterpolationSteps());
    }

    @Override
    public String toString() {
        return "ReachInterpolationData{" +
                "targetLocation=" + targetLocation +
                ", startingLocation=" + startingLocation +
                ", currentYaw=" + currentYaw +
                ", currentPitch=" + currentPitch +
                ", targetYaw=" + targetYaw +
                ", targetPitch=" + targetPitch +
                ", interpolationStepsLowBound=" + interpolationStepsLowBound +
                ", interpolationStepsHighBound=" + interpolationStepsHighBound +
                '}';
    }
}
