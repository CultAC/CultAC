// This file was designed and is an original check for CultAC
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
package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.network.packet.PacketCodecUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.BoatData;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.enums.BoatEntityStatus;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

// You may not copy this check unless your anticheat is licensed under GPL
public class PacketEntity {
    private static final EntityPose[] POSES = EntityPose.values();

    public long bedrockRuntimeId = -1;
    // Final Bedrock wire attributes, retained even before this actor is locally controlled.
    public ac.cult.cultac.bedrock.prediction.state.BedrockActorAttributes bedrockAttributes =
            ac.cult.cultac.bedrock.prediction.state.BedrockActorAttributes.EMPTY;
    public ac.cult.cultac.bedrock.prediction.integration.BedrockEntityInterpolation bedrockInterpolation;
    public ac.cult.cultac.bedrock.prediction.integration.BedrockVehiclePredictionState bedrockPrediction;
    public ac.cult.cultac.bedrock.prediction.state.BedrockBoatProperties bedrockBoat;
    public Vec3 desyncClientPos;

    public final int type;
    private final boolean living;
    private final boolean boat;
    private final boolean minecart;
    private final boolean happyGhast;

    public PacketEntity riding;
    // Mutated only through mount and eject, which keep the tracker's passenger count.
    public final List<PacketEntity> passengers = new ArrayList<>(0);
    // The tracker whose entityMap holds this entity; null while untracked.
    private @Nullable CompensatedEntities tracker;
    public boolean isDead = false;
    public boolean isBaby = false;
    public boolean hasGravity = true;
    public double gravity = 0.08D;
    public double bounciness = 0.0D;
    public double frictionModifier = 1.0D;
    public double airDragModifier = 1.0D;
    public double movementEfficiency = 0.0D;
    public double waterMovementEfficiency = 0.0D;
    // Null means no client-visible update has overridden the entity type's
    // vanilla step height. CultPlayer#getMaxUpStep retains those type defaults.
    public Double stepHeightAttribute;
    public boolean noAI = false;
    public EntityPose actionPose = EntityPose.STANDING;
    public boolean actionAlive = true;
    // The EquipmentSlot.SADDLE item, which a mount's inventory menu shows in its saddle slot.
    public SimItemStack saddleItem = SimItemStack.EMPTY;
    public byte actionSharedFlags;
    public boolean actionArrowInGround;
    public float scale = 1.0f;
    // A display's teleport_duration, which Display#onSyncedDataUpdated makes its handler's step count.
    private int displayTeleportDuration;
    // The poses actionPose replaced, as bits by ordinal, and the least scale scale replaced, while the client may not
    // have handled the replacements.
    private int replacedPoses;
    private float replacedScale = Float.NaN;
    // The transaction whose answer proves the client applied every replacement.
    private int sizeProvenAt = Integer.MIN_VALUE;
    // The transaction whose answer proves the client applied the last entity data.
    private int dataProvenAt = Integer.MIN_VALUE;
    public boolean onGround = false;
    public Vec3 deltaMovement = Vec3.ZERO;
    public Vec3 clientPhysicalPosition;
    public boolean clientPhysicalPositionExact = true;
    public float clientPhysicalYaw = 0.0F;
    // Whether clientPhysicalYaw is the yaw LinearInterpolationHandler#interpolate gave the client's entity.
    public boolean clientPhysicalYawCurrent = true;
    public float clientPhysicalPitch = 0.0F;

    public ReachInterpolationData oldPacketLocation;
    public ReachInterpolationData newPacketLocation;
    // Set by an unproven receipt, fold, teleport, far sync, seat placement or uncertain vehicle; holds for life.
    private boolean trackedPositionInexact;
    // Unbundled position paths received but not yet proven by the transaction after them.
    private int unprovenUnbundledPaths;
    private long clientTickOrder = Long.MAX_VALUE;
    // Reused: valid only until the client tick that computed it applies it.
    private SimpleCollisionBox exactAfterClientTickBox;
    // Holds the current interpolation location only while carrying the target reads its position.
    private SimpleCollisionBox carryLocationBox;
    private SimpleCollisionBox oldLocationBox;
    private @Nullable ReachInterpolationData settledInterpolation;
    private @Nullable Vec3 settledPhysicalPosition;
    // Valid while the position, the world and its chunk epoch are the ones it was computed for.
    private @Nullable Vec3 loadedChunkPosition;
    private @Nullable CompensatedWorld loadedChunkWorld;
    private long loadedChunkEpoch;
    private boolean loadedChunk;

    public HashMap<MovementEffect, Integer> potionsMap = null;
    public BoatEntityStatus boatStatus = null;
    // AbstractBoat#deltaRotation of this boat as the player's control moved to another boat.
    public float boatDeltaRotation;

    @Getter
    private final int entityId;

    public PacketEntity(int type, int entityId) {
        this.entityId = entityId;
        this.type = type;
        this.living = EntityTypeUtil.isLiving(type);
        this.boat = EntityTypeUtil.isBoat(type);
        this.minecart = EntityTypeUtil.isMinecart(type);
        this.happyGhast = EntityTypeUtil.isHappyGhast(type);
    }

    public PacketEntity(CultPlayer cultPlayer, int entityId, int type, double x, double y, double z) {
        this(type, entityId);
        Vec3 spawnPosition = new Vec3(x, y, z);
        this.desyncClientPos = spawnPosition;
        this.clientPhysicalPosition = spawnPosition;
        SimpleCollisionBox spawnBox = GetBoundingBox.getPacketEntityBoundingBox(cultPlayer, x, y, z, this);
        boolean ridingInVehicle = cultPlayer.compensatedEntities.getSelf().inVehicle();
        this.newPacketLocation = ReachInterpolationData.takingStart(
                cultPlayer,
                spawnBox,
                x,
                y,
                z,
                clientPhysicalYaw,
                clientPhysicalPitch,
                clientPhysicalYaw,
                clientPhysicalPitch,
                !ridingInVehicle,
                this,
                false);
    }

    public int getType() {
        return type;
    }

    public boolean isLivingEntity() {
        return living;
    }

    public boolean isMinecart() {
        return minecart;
    }

    public boolean isHorse() {
        return EntityTypeUtil.isHorseFamily(type);
    }

    public boolean isAgeable() {
        return EntityTypeUtil.isAgeable(type);
    }

    public boolean isBoat() {
        return boat;
    }

    public boolean isHappyGhast() {
        return happyGhast;
    }

    /** The type test a push or collision with the player applies before any position or riding test. */
    public boolean mayPushOrCollide() {
        boolean hardCollidingEntity = boat || minecart || type == EntityTypeIds.STRIDER || happyGhast;
        // Players can push living entities. Some entities also provide
        // hard movement collision boxes that Bedrock consumes as geometry.
        // The one exemption to a living entity is an armor stand (matters due to plugins sending them)
        return !(!living && !hardCollidingEntity || type == EntityTypeIds.ARMOR_STAND);
    }

    public boolean isAnimal() {
        return EntityTypeUtil.isAnimal(type);
    }

    public boolean isSize() {
        return type == EntityTypeIds.PHANTOM || type == EntityTypeIds.SLIME || type == EntityTypeIds.MAGMA_CUBE;
    }

    public boolean isStrider() {
        return type == EntityTypeIds.STRIDER;
    }

    // Set the old packet location to the new one
    // Set the new packet location to the updated packet location
    public void onFirstTransaction(
            boolean relative, boolean hasPos, double relX, double relY, double relZ, CultPlayer player) {
        onFirstTransaction(relative, hasPos, relX, relY, relZ, null, null, player, true);
    }

    public void onFirstTransaction(
            boolean relative,
            boolean hasPos,
            double relX,
            double relY,
            double relZ,
            @Nullable Float yaw,
            @Nullable Float pitch,
            CultPlayer player) {
        onFirstTransaction(relative, hasPos, relX, relY, relZ, yaw, pitch, player, true);
    }

    public void onBundleFirstTransaction(
            boolean relative, boolean hasPos, double relX, double relY, double relZ, CultPlayer player) {
        onBundleFirstTransaction(relative, hasPos, relX, relY, relZ, null, null, player);
    }

    public void onBundleFirstTransaction(
            boolean relative,
            boolean hasPos,
            double relX,
            double relY,
            double relZ,
            @Nullable Float yaw,
            @Nullable Float pitch,
            CultPlayer player) {
        // MCP-Reborn ClientPacketListener#handleBundlePacket handles bundled
        // sub-packets in order, so the trailing ping's pong proves the handler
        // applied this movement before the client's next tick; the caller drops
        // the old packet box with onSecondTransaction at that same pong.
        onFirstTransaction(relative, hasPos, relX, relY, relZ, yaw, pitch, player, false);
    }

    private void onFirstTransaction(
            boolean relative,
            boolean hasPos,
            double relX,
            double relY,
            double relZ,
            @Nullable Float yaw,
            @Nullable Float pitch,
            CultPlayer player,
            boolean uncertainInitialSteps) {
        applyEntityPositionUpdate(relative, hasPos, relX, relY, relZ);
        if (desyncClientPos == null) {
            return;
        }

        float targetYaw = interpolationTargetYaw(yaw);
        float targetPitch = interpolationTargetPitch(pitch);
        if (matchesActiveInterpolationTarget(
                desyncClientPos.x, desyncClientPos.y, desyncClientPos.z, targetYaw, targetPitch)) {
            return;
        }

        boolean placed = placedAtPacketPosition(player);
        SimpleCollisionBox startingLocation =
                placed ? packetPositionBox(player) : interpolationStartingLocation(player);
        boolean ridingInVehicle = player.compensatedEntities.getSelf().inVehicle();
        this.oldPacketLocation = newPacketLocation;
        this.newPacketLocation = ReachInterpolationData.takingStart(
                player,
                startingLocation,
                desyncClientPos.x,
                desyncClientPos.y,
                desyncClientPos.z,
                placed ? targetYaw : clientPhysicalYaw,
                placed ? targetPitch : clientPhysicalPitch,
                targetYaw,
                targetPitch,
                !ridingInVehicle,
                this,
                uncertainInitialSteps);
        trackedPositionInexact = true;
        keepPositionCertainty();
    }

    /** Carries an inexact tracked position into the current snapshots, and a fold out of them. */
    private void keepPositionCertainty() {
        if (newPacketLocation == null) return;
        if (!newPacketLocation.hasExactPosition()) trackedPositionInexact = true;
        if (!trackedPositionInexact) return;
        newPacketLocation.markPositionInexact();
        if (oldPacketLocation != null) oldPacketLocation.markPositionInexact();
    }

    /** Keeps the packet-position margin for the rest of this entity's lifetime. */
    public void markPositionInexact() {
        trackedPositionInexact = true;
        keepPositionCertainty();
    }

    /** Takes on the uncertainty of a position derived from {@code vehicle}, whose flag covers its own ancestry. */
    public void inheritPositionCertainty(@Nullable PacketEntity vehicle) {
        if (vehicle != null && vehicle.hasInexactPosition()) markPositionInexact();
    }

    private boolean hasInexactPosition() {
        return trackedPositionInexact || (newPacketLocation != null && !newPacketLocation.hasExactPosition());
    }

    /** Packet processing appends to the client's queue; its codec base remains separate. */
    public void onPositionPath(
            EntityPositionPath path,
            boolean hasPosition,
            @Nullable Float yaw,
            @Nullable Float pitch,
            CultPlayer player,
            boolean bundled) {
        if (hasPosition) desyncClientPos = path.endPosition();
        if (skipsInterpolation(player)) {
            setPositionRaw(
                    GetBoundingBox.getPacketEntityBoundingBox(
                            player, desyncClientPos.x, desyncClientPos.y, desyncClientPos.z, this),
                    interpolationTargetYaw(yaw),
                    interpolationTargetPitch(pitch));
            return;
        }
        if (living && (newPacketLocation == null || !newPacketLocation.usesSteppedInterpolation())) {
            SimpleCollisionBox start = interpolationStartingLocation(player);
            Vec3 current = positionFromCollisionBox(start);
            newPacketLocation = ReachInterpolationData.takingStart(
                    player,
                    start,
                    current.x,
                    current.y,
                    current.z,
                    clientPhysicalYaw,
                    clientPhysicalPitch,
                    clientPhysicalYaw,
                    clientPhysicalPitch,
                    false,
                    this,
                    false);
        }
        if (newPacketLocation != null && newPacketLocation.usesSteppedInterpolation()) {
            float targetYaw = interpolationTargetYaw(yaw);
            float targetPitch = interpolationTargetPitch(pitch);
            EntityPositionPath actualPath = hasPosition
                    ? path
                    : EntityPositionPath.linear(
                            newPacketLocation.hasActiveInterpolationTarget()
                                    ? newPacketLocation.targetPosition()
                                    : clientPhysicalPosition);
            if (bundled) {
                oldPacketLocation = null;
            } else {
                oldPacketLocation = newPacketLocation;
                newPacketLocation = newPacketLocation.copy();
            }
            // Starting/appending an interpolation records the entity's physical transform,
            // including its current passenger attachment, as the next delta reference.
            newPacketLocation.setPhysicalBeforePathAppend(
                    actualPath, targetYaw, targetPitch, clientPhysicalPosition, clientPhysicalYaw, clientPhysicalPitch);
            newPacketLocation.appendPath(actualPath, targetYaw, targetPitch);
            if (!bundled) unprovenUnbundledPaths++;
            keepPositionCertainty();
            return;
        }
        if (bundled)
            onBundleTransaction(
                    false,
                    hasPosition,
                    path.endPosition().x,
                    path.endPosition().y,
                    path.endPosition().z,
                    yaw,
                    pitch,
                    player);
        else
            onFirstTransaction(
                    false,
                    hasPosition,
                    path.endPosition().x,
                    path.endPosition().y,
                    path.endPosition().z,
                    yaw,
                    pitch,
                    player);
    }

    private void applyEntityPositionUpdate(boolean relative, boolean hasPos, double relX, double relY, double relZ) {
        if (!hasPos) {
            return;
        }
        desyncClientPos = relative
                ? PacketCodecUtil.decodeRelativeEntityPosition(desyncClientPos, relX, relY, relZ)
                : new Vec3(relX, relY, relZ);
    }

    public void onBundleTransaction(
            boolean relative, boolean hasPos, double relX, double relY, double relZ, CultPlayer player) {
        onBundleTransaction(relative, hasPos, relX, relY, relZ, null, null, player);
    }

    public void onBundleTransaction(
            boolean relative,
            boolean hasPos,
            double relX,
            double relY,
            double relZ,
            @Nullable Float yaw,
            @Nullable Float pitch,
            CultPlayer player) {
        if (hasPos) {
            if (relative) {
                desyncClientPos = PacketCodecUtil.decodeRelativeEntityPosition(desyncClientPos, relX, relY, relZ);
            } else {
                desyncClientPos = new Vec3(relX, relY, relZ);
            }
        }

        if (desyncClientPos == null) {
            return;
        }

        float targetYaw = interpolationTargetYaw(yaw);
        float targetPitch = interpolationTargetPitch(pitch);
        if (placedAtPacketPosition(player)) {
            // The client has handled the packet: setPos moves the physical position with the packet position, even
            // where local physics has carried it off an unchanged target. Delta movement is kept.
            this.oldPacketLocation = null;
            setPositionRaw(packetPositionBox(player), targetYaw, targetPitch);
            return;
        }
        if (matchesActiveInterpolationTarget(
                desyncClientPos.x, desyncClientPos.y, desyncClientPos.z, targetYaw, targetPitch)) {
            return;
        }
        this.oldPacketLocation = null;
        this.newPacketLocation = ReachInterpolationData.takingStart(
                player,
                interpolationStartingLocation(player),
                desyncClientPos.x,
                desyncClientPos.y,
                desyncClientPos.z,
                clientPhysicalYaw,
                clientPhysicalPitch,
                targetYaw,
                targetPitch,
                !player.compensatedEntities.getSelf().inVehicle(),
                this,
                false);
        keepPositionCertainty();
    }

    private float interpolationTargetYaw(@Nullable Float yaw) {
        if (yaw != null) {
            return yaw;
        }
        return newPacketLocation != null && newPacketLocation.hasActiveInterpolationTarget()
                ? newPacketLocation.getTargetYaw()
                : clientPhysicalYaw;
    }

    private float interpolationTargetPitch(@Nullable Float pitch) {
        if (pitch != null) {
            return pitch;
        }
        return newPacketLocation != null && newPacketLocation.hasActiveInterpolationTarget()
                ? newPacketLocation.getTargetPitch()
                : clientPhysicalPitch;
    }

    public boolean matchesActiveInterpolationTarget(
            double x, double y, double z, @Nullable Float yaw, @Nullable Float pitch) {
        return matchesActiveInterpolationTarget(x, y, z, interpolationTargetYaw(yaw), interpolationTargetPitch(pitch));
    }

    private boolean matchesActiveInterpolationTarget(double x, double y, double z, float yaw, float pitch) {
        if (!hasExactActiveInterpolationTarget()) {
            return false;
        }

        Vec3 target = newPacketLocation.targetPosition();
        return matchesPosition(target, x, y, z)
                && Float.compare(newPacketLocation.getTargetYaw(), yaw) == 0
                && Float.compare(newPacketLocation.getTargetPitch(), pitch) == 0;
    }

    private boolean matchesPosition(Vec3 position, double x, double y, double z) {
        return matchesCoordinate(position.x, x) && matchesCoordinate(position.y, y) && matchesCoordinate(position.z, z);
    }

    private boolean matchesCoordinate(double expected, double actual) {
        double tolerance = Math.max(Math.ulp(expected), Math.ulp(actual)) * 8.0D;
        return Math.abs(expected - actual) <= tolerance;
    }

    private boolean hasExactActiveInterpolationTarget() {
        return newPacketLocation != null
                && newPacketLocation.hasActiveInterpolationTarget()
                && newPacketLocation.getExactInterpolationStep() >= 0;
    }

    /**
     * Whether a pre-26.3 Java client sets this entity straight onto a packet position: {@code Entity#lerpTo} (1.20.2,
     * 1.21.1) and {@code Entity#moveOrInterpolateTo} without an {@code InterpolationHandler} (1.21.11) call
     * {@code setPos}.
     */
    private boolean placedAtPacketPosition(CultPlayer player) {
        if (player.isBedrockMovement() || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)) {
            return false;
        }
        return skipsInterpolation(player);
    }

    /**
     * Whether a Java client puts this entity straight onto a packet position. Living entities other than shulkers,
     * boats, minecarts, fishing hooks and experience orbs interpolate. From 1.21.11 a display's handler takes its steps
     * from teleport_duration, and with 0 steps {@code interpolateTo} calls {@code snapTo}.
     */
    private boolean skipsInterpolation(CultPlayer player) {
        if (type == EntityTypeIds.SHULKER) return true;
        if (living
                || boat
                || minecart
                || type == EntityTypeIds.FISHING_BOBBER
                || type == EntityTypeIds.EXPERIENCE_ORB) {
            return false;
        }
        if (!isDisplay()) return true;
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_11) && displayTeleportDuration == 0;
    }

    public boolean isDisplay() {
        return type == EntityTypeIds.BLOCK_DISPLAY
                || type == EntityTypeIds.ITEM_DISPLAY
                || type == EntityTypeIds.TEXT_DISPLAY;
    }

    public int displayTeleportDuration() {
        return displayTeleportDuration;
    }

    /** Entity data the client has applied: later position packets interpolate over this many steps. */
    public void onDisplayTeleportDuration(int duration) {
        displayTeleportDuration = duration;
    }

    private SimpleCollisionBox packetPositionBox(CultPlayer player) {
        return GetBoundingBox.getPacketEntityBoundingBox(
                player, desyncClientPos.x, desyncClientPos.y, desyncClientPos.z, this);
    }

    private SimpleCollisionBox interpolationStartingLocation(CultPlayer player) {
        if (hasUsableMountedPhysicalPosition(player)) {
            return GetBoundingBox.getPacketEntityBoundingBox(
                    player, clientPhysicalPosition.x, clientPhysicalPosition.y, clientPhysicalPosition.z, this);
        }

        return newPacketLocation == null
                ? GetBoundingBox.getPacketEntityBoundingBox(
                        player, desyncClientPos.x, desyncClientPos.y, desyncClientPos.z, this)
                : newPacketLocation.getPossibleMovementLocationCombined();
    }

    public void onBundledPositionSyncTransaction(double x, double y, double z, CultPlayer player) {
        onBundledPositionSyncTransaction(x, y, z, null, null, player);
    }

    public void onBundledPositionSyncTransaction(
            double x, double y, double z, @Nullable Float yaw, @Nullable Float pitch, CultPlayer player) {
        // MCP-Reborn ClientPacketListener#handleEntityPositionSync calls
        // moveOrInterpolateTo for normal active entities. ClientLevel#isTickingEntity
        // is membership in EntityTickList, not "currently inside this entity's
        // tick", so nearby tracked entities keep their current box until their
        // interpolation tick runs. The post-packet bundle ping proves the handler
        // ran, not that interpolation has advanced.
        onBundleTransaction(false, true, x, y, z, yaw, pitch, player);
    }

    // Remove the possibility of the old packet location
    public void onSecondTransaction() {
        this.oldPacketLocation = null;
    }

    public int unprovenUnbundledPaths() {
        return unprovenUnbundledPaths;
    }

    /**
     * The transaction after an unbundled position path proves the client handled it. With no client tick since the one
     * before it, the client handled the path between the same two client ticks as both pings.
     */
    public void onUnbundledPathProven() {
        if (unprovenUnbundledPaths > 0) unprovenUnbundledPaths--;
    }

    /**
     * Minecraft#runTick handles queued packets before each frame's client ticks, so a client tick between an unbundled
     * path's two transactions leaves the path handled before or after that tick.
     */
    private void tickWithUnprovenUnbundledPath() {
        if (unprovenUnbundledPaths > 0) markPositionInexact();
    }

    /** Entity data replacing the pose, which the client has applied once it answers provenAt. */
    public void onPose(EntityPose pose, int provenAt) {
        if (pose == actionPose) return;
        replacedPoses |= 1 << actionPose.ordinal();
        actionPose = pose;
        sizeProvenAt = Math.max(sizeProvenAt, provenAt);
    }

    /** An attribute update replacing the scale, which the client has applied once it answers provenAt. */
    public void onScale(float scale, int provenAt) {
        if (scale == this.scale) return;
        if (!(replacedScale <= this.scale)) replacedScale = this.scale;
        this.scale = scale;
        sizeProvenAt = Math.max(sizeProvenAt, provenAt);
    }

    /** The client answered this transaction: once it proves every replacement, it shows only the current size. */
    public void onSizeHandled(int transaction) {
        if (transaction < sizeProvenAt) return;
        replacedPoses = 0;
        replacedScale = Float.NaN;
    }

    /** The least scale the client may show. */
    public float leastScale() {
        return replacedScale < scale ? replacedScale : scale;
    }

    /** Whether Avatar#POSES sizes the entity by its pose. */
    public boolean isAvatar() {
        return type == EntityTypeIds.PLAYER || EntityTypeUtil.isType(type, "mannequin");
    }

    /** The least width an avatar's box can have among the poses and scales the client may show. */
    public float leastPoseWidth() {
        float scale = leastScale();
        float width = avatarWidth(actionPose, scale);
        for (int poses = replacedPoses; poses != 0; poses &= poses - 1) {
            width = Math.min(width, avatarWidth(POSES[Integer.numberOfTrailingZeros(poses)], scale));
        }
        return width;
    }

    /** The least height an avatar's box can have among the poses and scales the client may show. */
    public float leastPoseHeight() {
        float scale = leastScale();
        float height = avatarHeight(actionPose, scale);
        for (int poses = replacedPoses; poses != 0; poses &= poses - 1) {
            height = Math.min(height, avatarHeight(POSES[Integer.numberOfTrailingZeros(poses)], scale));
        }
        return height;
    }

    // LivingEntity#getDimensions with Avatar#POSES: sleeping and dying are fixed, the others scale.
    public static float avatarWidth(EntityPose pose, float scale) {
        return pose == EntityPose.SLEEPING || pose == EntityPose.DYING ? .2F : .6F * scale;
    }

    public static float avatarHeight(EntityPose pose, float scale) {
        return switch (pose) {
            case SLEEPING, DYING -> .2F;
            case FALL_FLYING, SWIMMING, SPIN_ATTACK -> .6F * scale;
            case CROUCHING -> 1.5F * scale;
            default -> 1.8F * scale;
        };
    }

    // If the old and new packet location are split, we need to combine bounding boxes
    public void onMovement(CultPlayer player, boolean tickingReliably) {
        if (player.isBedrockMovement() && bedrockRuntimeId != -1) {
            if (bedrockInterpolation != null) bedrockInterpolation.tick(player, this);
            if (isBoat() && bedrockPrediction != null) {
                ac.cult.cultac.bedrock.prediction.integration.BedrockVehiclePredictionState.rebase(
                        this, player.getSetbackTeleportUtil().getActiveBedrockCoordinateFrame());
            }
            return;
        }
        tickWithUnprovenUnbundledPath();
        ReachInterpolationData interpolation = newPacketLocation;
        Vec3 physicalBefore = clientPhysicalPosition;
        boolean idleProbe = false;
        if (oldPacketLocation == null
                && interpolation != null
                && clientPhysicalPositionExact
                && !player.isBedrockMovement()) {
            // A settled state with the inputs it settled on makes this tick a no-op.
            if (interpolation == settledInterpolation
                    && physicalBefore == settledPhysicalPosition
                    && interpolation.isSettled()) return;
            idleProbe = interpolation.beginIdleProbe();
        }
        stepInterpolation(player, tickingReliably);

        if (player.isBedrockMovement() && isBoat() && bedrockPrediction != null) {
            ac.cult.cultac.bedrock.prediction.integration.BedrockVehiclePredictionState.rebase(
                    this, player.getSetbackTeleportUtil().getActiveBedrockCoordinateFrame());
        }

        // Handle uncertainty of second transaction spanning over multiple ticks
        if (oldPacketLocation != null) {
            oldPacketLocation.tickMovement(true, tickingReliably);
            widenStartingLocationByOldLocation();
        }
        if (idleProbe
                && newPacketLocation == interpolation
                && oldPacketLocation == null
                && clientPhysicalPosition == physicalBefore
                && clientPhysicalPositionExact
                && interpolation.settleIfIdleTickUnchanged()) {
            settledInterpolation = interpolation;
            settledPhysicalPosition = physicalBefore;
        }
    }

    private void stepInterpolation(CultPlayer player, boolean tickingReliably) {
        carryInterpolationTargetByLocalPhysics(player);
        SimpleCollisionBox exactAfterClientTick = exactInterpolatedLocationAfterClientTick(player);
        float exactYawAfterClientTick = exactYawAfterClientTick();
        float exactPitchAfterClientTick = exactPitchAfterClientTick();
        boolean exactRotationAfterClientTick = exactRotationAfterClientTick();
        boolean rotationMayStep = oldPacketLocation != null || newPacketLocation.hasActiveInterpolationTarget();
        newPacketLocation.tickMovement(oldPacketLocation == null, tickingReliably);
        alignExactInterpolatedLocation(exactAfterClientTick);
        if (exactRotationAfterClientTick) {
            newPacketLocation.setExactRotation(exactYawAfterClientTick, exactPitchAfterClientTick);
            clientPhysicalYaw = exactYawAfterClientTick;
            clientPhysicalPitch = exactPitchAfterClientTick;
            clientPhysicalYawCurrent = true;
        } else if (rotationMayStep) {
            clientPhysicalYawCurrent = false;
        }
        if (exactAfterClientTick != null) {
            clientPhysicalPosition = positionFromCollisionBox(exactAfterClientTick, clientPhysicalPosition);
            clientPhysicalPositionExact = true;
        } else {
            clientPhysicalPositionExact = false;
        }
    }

    /**
     * Entity#commonTick for the root of a client tick's ServerboundMoveVehiclePacket, whether or not the server accepts
     * that packet: InterpolationHandler#interpolate advances the server path the root still follows before
     * LivingEntity#aiStep reaches travelRidden. Covers 26.3's stepped interpolation and a boat's LinearInterpolationHandler.
     */
    public void interpolateBeforeLocalTravel(CultPlayer player) {
        tickWithUnprovenUnbundledPath();
        if (!hasActiveLocalRootInterpolation()) {
            return;
        }
        stepInterpolation(player, true);
        if (oldPacketLocation != null) {
            oldPacketLocation.tickMovement(true, true);
            widenStartingLocationByOldLocation();
        }
        // AbstractBoat#controlBoat turns the yaw this step leaves.
        if (boat && this == player.compensatedEntities.getSelf().getRiding()) {
            player.boatYawBeforeTurn = clientPhysicalYawCurrent ? clientPhysicalYaw : Float.NaN;
        }
    }

    private boolean hasActiveLocalRootInterpolation() {
        return hasActiveSteppedInterpolation()
                || (boat
                        && newPacketLocation != null
                        && newPacketLocation.followsInterpolationHandler()
                        && newPacketLocation.hasActiveInterpolationTarget());
    }

    private boolean hasActiveSteppedInterpolation() {
        return newPacketLocation != null
                && newPacketLocation.usesSteppedInterpolation()
                && newPacketLocation.hasActiveInterpolationTarget();
    }

    public void tickPacketInterpolationPreservingPhysical(CultPlayer player, Vec3 physicalPosition) {
        tickPacketInterpolationPreservingPhysical(player, physicalPosition, clientPhysicalYaw, clientPhysicalPitch);
    }

    public void tickPacketInterpolationPreservingPhysical(
            CultPlayer player, Vec3 physicalPosition, float physicalYaw, float physicalPitch) {
        tickPacketInterpolationPreservingPhysical(player, physicalPosition, physicalYaw, physicalPitch, true);
    }

    private void tickPacketInterpolationPreservingPhysical(
            CultPlayer player, Vec3 physicalPosition, float physicalYaw, float physicalPitch, boolean carryTarget) {
        tickWithUnprovenUnbundledPath();
        if (carryTarget) {
            carryInterpolationTargetByLocalPhysics(player);
        }
        SimpleCollisionBox exactAfterInterpolation = exactInterpolatedLocationAfterClientTick(player);
        float exactYawAfterInterpolation = exactYawAfterClientTick();
        float exactPitchAfterInterpolation = exactPitchAfterClientTick();
        boolean exactRotationAfterInterpolation = exactRotationAfterClientTick();
        if (newPacketLocation != null) {
            newPacketLocation.tickMovement(oldPacketLocation == null, true);
        }
        alignExactInterpolatedLocation(exactAfterInterpolation);
        if (newPacketLocation != null && exactRotationAfterInterpolation) {
            newPacketLocation.setExactRotation(exactYawAfterInterpolation, exactPitchAfterInterpolation);
        }

        if (oldPacketLocation != null) {
            oldPacketLocation.tickMovement(true, true);
            widenStartingLocationByOldLocation();
        }

        this.clientPhysicalPosition = physicalPosition;
        this.clientPhysicalPositionExact = true;
        this.clientPhysicalYaw = physicalYaw;
        this.clientPhysicalPitch = physicalPitch;
    }

    public void tickPacketInterpolationPreservingPhysical(
            CultPlayer player, Vec3 physicalPosition, ReachInterpolationData interpolationData) {
        tickPacketInterpolationPreservingPhysical(
                player, physicalPosition, interpolationData, clientPhysicalYaw, clientPhysicalPitch);
    }

    public void tickPacketInterpolationPreservingPhysical(
            CultPlayer player,
            Vec3 physicalPosition,
            ReachInterpolationData interpolationData,
            float physicalYaw,
            float physicalPitch) {
        this.oldPacketLocation = null;
        this.newPacketLocation = interpolationData.copy();
        keepPositionCertainty();
        tickPacketInterpolationPreservingPhysical(player, physicalPosition, physicalYaw, physicalPitch);
    }

    private void carryInterpolationTargetByLocalPhysics(CultPlayer player) {
        carryInterpolationTargetByLocalPhysics(player, newPacketLocation);
    }

    protected void carryInterpolationTargetByLocalPhysics(CultPlayer player, ReachInterpolationData interpolationData) {
        // Only an interpolating entity carries local movement into its target.
        if (interpolationData == null
                || !interpolationData.hasActiveInterpolationTarget()
                || !hasUsableMountedPhysicalPosition(player)
                || placedAtPacketPosition(player)) {
            return;
        }

        if (carryLocationBox == null) carryLocationBox = new SimpleCollisionBox();
        SimpleCollisionBox current = interpolationData.getExactMovementLocation(carryLocationBox);
        if (current == null) {
            return;
        }
        double deltaX = clientPhysicalPosition.x - ((current.maxX - current.minX) / 2 + current.minX);
        double deltaY = clientPhysicalPosition.y - current.minY;
        double deltaZ = clientPhysicalPosition.z - ((current.maxZ - current.minZ) / 2 + current.minZ);
        double deltaLengthSqr = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        // A zero delta is a no-op shift.
        if (deltaLengthSqr != 0.0D && (!interpolationData.usesSteppedInterpolation() || deltaLengthSqr > 1.0E-5F)) {
            interpolationData.shiftRootVehicleTargetIfCollisionFree(player, this, new Vec3(deltaX, deltaY, deltaZ));
        }
        interpolationData.shiftTargetRotationByPhysical(clientPhysicalYaw, clientPhysicalPitch);
        syncDesyncClientPosToInterpolationTarget(interpolationData);
    }

    private void widenStartingLocationByOldLocation() {
        if (oldLocationBox == null) oldLocationBox = new SimpleCollisionBox();
        newPacketLocation.updatePossibleStartingLocation(
                oldPacketLocation.getPossibleMovementLocationCombined(oldLocationBox));
    }

    private void syncDesyncClientPosToInterpolationTarget(ReachInterpolationData interpolationData) {
        if (!interpolationData.usesSteppedInterpolation()
                && interpolationData == newPacketLocation
                && interpolationData.hasActiveInterpolationTarget()) {
            this.desyncClientPos = position(
                    interpolationData.targetCenterX(),
                    interpolationData.targetMinY(),
                    interpolationData.targetCenterZ(),
                    desyncClientPos);
        }
    }

    private SimpleCollisionBox exactInterpolatedLocationAfterClientTick(CultPlayer player) {
        if (newPacketLocation == null) return null;
        if (oldPacketLocation == null
                && newPacketLocation.interpolatesFromPhysicalAfterClientTick()
                && hasUsableMountedPhysicalPosition(player)) {
            if (exactAfterClientTickBox == null) exactAfterClientTickBox = new SimpleCollisionBox();
            SimpleCollisionBox physicalLocation = GetBoundingBox.getBoundingBoxFromPosAndSize(
                    exactAfterClientTickBox,
                    clientPhysicalPosition.x,
                    clientPhysicalPosition.y,
                    clientPhysicalPosition.z,
                    BoundingBoxSize.getWidth(player, this),
                    BoundingBoxSize.getHeight(player, this));
            return newPacketLocation.getExactMovementLocationAfterClientTickFromPhysical(physicalLocation);
        }
        if (!newPacketLocation.hasExactMovementLocationAfterClientTick()) return null;
        if (exactAfterClientTickBox == null) exactAfterClientTickBox = new SimpleCollisionBox();
        return newPacketLocation.exactMovementLocationAfterClientTick(exactAfterClientTickBox);
    }

    private boolean exactRotationAfterClientTick() {
        return newPacketLocation != null
                && oldPacketLocation == null
                && newPacketLocation.hasActiveInterpolationTarget()
                && newPacketLocation.getExactInterpolationStep() >= 0;
    }

    private boolean hasUsableMountedPhysicalPosition(CultPlayer player) {
        return clientPhysicalPosition != null
                && (clientPhysicalPositionExact
                        || this == player.compensatedEntities.vehicles.getVelocityMovementVehicle());
    }

    private float exactYawAfterClientTick() {
        return exactRotationAfterClientTick()
                ? newPacketLocation.getExactYawAfterClientTickFromPhysical(clientPhysicalYaw)
                : clientPhysicalYaw;
    }

    private float exactPitchAfterClientTick() {
        return exactRotationAfterClientTick()
                ? newPacketLocation.getExactPitchAfterClientTickFromPhysical(clientPhysicalPitch)
                : clientPhysicalPitch;
    }

    private void alignExactInterpolatedLocation(SimpleCollisionBox exactLocation) {
        if (newPacketLocation != null && exactLocation != null) {
            newPacketLocation.setExactMovementLocation(exactLocation);
        }
    }

    public void updateBoatStatus(CultPlayer player) {
        if (isBoat() && newPacketLocation != null) {
            // MCP-Reborn AbstractBoat#tick updates status even for client-side
            // non-controlled boats before deciding whether local physics runs.
            boatStatus = BoatData.sampleStatus(player, getPossibleMovementCollisionBoxes())
                    .status();
        }
    }

    public boolean hasPassenger(PacketEntity entity) {
        return passengers.contains(entity);
    }

    public void mount(PacketEntity vehicle) {
        if (riding != null) eject();
        vehicle.passengers.add(this);
        if (vehicle.tracker != null) vehicle.tracker.onPassengerAdded();
        riding = vehicle;
    }

    public void eject() {
        if (riding != null && riding.passengers.remove(this) && riding.tracker != null) {
            riding.tracker.onPassengerRemoved();
        }
        this.riding = null;
    }

    public @Nullable CompensatedEntities getTracker() {
        return tracker;
    }

    /** Called by the tracker when it adds this entity to its entityMap (non-null) or drops it (null). */
    public void setTracker(@Nullable CompensatedEntities tracker) {
        this.tracker = tracker;
    }

    // This is for handling riding and entities attached to one another.
    public void setPositionRaw(SimpleCollisionBox box) {
        // The client snaps ridden/attached entities straight onto the packet
        // position, desync be damned — mirror that flawed client-sided logic.
        Vec3 snappedPosition = positionFromCollisionBox(box);
        this.desyncClientPos = snappedPosition;
        this.clientPhysicalPosition = snappedPosition;
        this.clientPhysicalPositionExact = true;
        // Snapping to an exact position means interpolation is disabled
        this.newPacketLocation = new ReachInterpolationData(box);
        keepPositionCertainty();
    }

    private SimpleCollisionBox passengerPhysicalBox;

    public void setPassengerPosition(CultPlayer player, SimpleCollisionBox box) {
        if (player.isBedrockMovement() || player.getClientVersion().isOlderThan(ClientVersion.V_26_3)) {
            setPositionRaw(box);
            return;
        }
        // Entity#positionRider uses setPos, not snapTo: neither the codec nor
        // the pending interpolation path is replaced by the attachment position.
        passengerPhysicalBox = box.copy();
        clientPhysicalPosition = positionFromCollisionBox(box);
        clientPhysicalPositionExact = true;
    }

    /**
     * Snaps a rider whose seat was not simulated onto {@code seat}, its seat right now, dropping every location its
     * frozen interpolation still holds; the next server position interpolates from there. Keeps the 26.3 codec base,
     * which riding does not move.
     */
    public void settleAtSeat(CultPlayer player, SimpleCollisionBox seat) {
        Vec3 codecBase = desyncClientPos;
        passengerPhysicalBox = null;
        oldPacketLocation = null;
        bedrockInterpolation = null;
        setPositionRaw(seat, clientPhysicalYaw, clientPhysicalPitch);
        markPositionInexact();
        if (!player.isBedrockMovement() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)) {
            desyncClientPos = codecBase;
        }
    }

    public void setPositionRaw(SimpleCollisionBox box, float yaw, float pitch) {
        setPositionRaw(box);
        setClientPhysicalRotation(yaw, pitch);
        this.newPacketLocation.setCurrentAndTargetRotation(yaw, pitch);
    }

    /**
     * Entity#snapTo (Entity#moveTo before 1.21.5), which leaves the running interpolation in place: the entity sits at
     * {@code box} and its next step lerps on from there. From 1.21.5, InterpolationHandler#interpolate first shifts the
     * path it still follows by the jump from where its last step left the entity, where the shifted target is collision
     * free; before it, LivingEntity#aiStep lerps toward the unshifted lerp target. A path whose current location is not
     * exact is replaced by {@code box}.
     */
    public void snapTo(CultPlayer player, SimpleCollisionBox box, float yaw, float pitch) {
        if (newPacketLocation == null
                || !newPacketLocation.hasActiveInterpolationTarget()
                || newPacketLocation.getExactMovementLocation() == null) {
            setPositionRaw(box, yaw, pitch);
            return;
        }
        Vec3 snappedPosition = positionFromCollisionBox(box);
        clientPhysicalPosition = snappedPosition;
        clientPhysicalPositionExact = true;
        setClientPhysicalRotation(yaw, pitch);
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_5)) {
            carryInterpolationTargetByLocalPhysics(player);
        }
        newPacketLocation.setExactMovementLocation(box);
        newPacketLocation.setExactRotation(yaw, pitch);
        desyncClientPos = snappedPosition;
    }

    /**
     * Moves a root the client moves itself to {@code box}. A 26.3 path it still follows keeps its steps and
     * codec base, since ClientPacketListener#handleMoveVehicle alone cancels it; without one this is
     * {@link #setPositionRaw(SimpleCollisionBox, float, float)}.
     */
    public void moveLocallyControlledRoot(SimpleCollisionBox box, float yaw, float pitch) {
        if (!hasActiveLocalRootInterpolation()) {
            setPositionRaw(box, yaw, pitch);
            return;
        }
        clientPhysicalPosition = positionFromCollisionBox(box);
        clientPhysicalPositionExact = true;
        setClientPhysicalRotation(yaw, pitch);
    }

    public void setClientPhysicalRotation(float yaw, float pitch) {
        this.clientPhysicalYaw = yaw;
        this.clientPhysicalPitch = pitch;
        this.clientPhysicalYawCurrent = true;
    }

    private Vec3 positionFromCollisionBox(SimpleCollisionBox box) {
        return new Vec3((box.maxX - box.minX) / 2 + box.minX, box.minY, (box.maxZ - box.minZ) / 2 + box.minZ);
    }

    /** {@link #positionFromCollisionBox(SimpleCollisionBox)}, returning {@code previous} when it holds those bits. */
    private static Vec3 positionFromCollisionBox(SimpleCollisionBox box, @Nullable Vec3 previous) {
        return position((box.maxX - box.minX) / 2 + box.minX, box.minY, (box.maxZ - box.minZ) / 2 + box.minZ, previous);
    }

    /** A position with these coordinates: {@code previous} when it holds their bits. */
    private static Vec3 position(double x, double y, double z, @Nullable Vec3 previous) {
        if (previous != null
                && Double.doubleToRawLongBits(previous.x) == Double.doubleToRawLongBits(x)
                && Double.doubleToRawLongBits(previous.y) == Double.doubleToRawLongBits(y)
                && Double.doubleToRawLongBits(previous.z) == Double.doubleToRawLongBits(z)) {
            return previous;
        }
        return new Vec3(x, y, z);
    }

    /** {@code Entity#refreshDimensions}: the current, pending and seated positions take the entity's new dimensions. */
    public void refreshDimensions(CultPlayer player) {
        float width = BoundingBoxSize.getWidth(player, this);
        float height = BoundingBoxSize.getHeight(player, this);
        newPacketLocation.resize(width, height);
        if (oldPacketLocation != null) oldPacketLocation.resize(width, height);
        if (passengerPhysicalBox != null) {
            Vec3 feet = positionFromCollisionBox(passengerPhysicalBox);
            passengerPhysicalBox = GetBoundingBox.getBoundingBoxFromPosAndSize(feet.x, feet.y, feet.z, width, height);
        }
    }

    public SimpleCollisionBox getPossibleCollisionBoxes() {
        return getPossibleCollisionBoxes(new SimpleCollisionBox(), new SimpleCollisionBox());
    }

    /** The bounds of {@link #getPossibleCollisionBoxes()} in {@code out}; {@code scratch} must be distinct. */
    public SimpleCollisionBox getPossibleCollisionBoxes(SimpleCollisionBox out, SimpleCollisionBox scratch) {
        if (passengerPhysicalBox != null && riding != null) return out.set(passengerPhysicalBox);
        newPacketLocation.getPossibleLocationCombined(out);
        if (oldPacketLocation == null) return out;
        return ReachInterpolationData.combineCollisionBox(
                oldPacketLocation.getPossibleLocationCombined(scratch), out, out);
    }

    public SimpleCollisionBox getPossibleMovementCollisionBoxes() {
        return getPossibleMovementCollisionBoxes(new SimpleCollisionBox(), new SimpleCollisionBox());
    }

    /** The bounds of {@link #getPossibleMovementCollisionBoxes()} in {@code out}; {@code scratch} must be distinct. */
    public SimpleCollisionBox getPossibleMovementCollisionBoxes(SimpleCollisionBox out, SimpleCollisionBox scratch) {
        if (passengerPhysicalBox != null && riding != null) return out.set(passengerPhysicalBox);
        newPacketLocation.getPossibleMovementLocationCombined(out);
        if (oldPacketLocation == null) return out;
        return ReachInterpolationData.combineCollisionBox(
                oldPacketLocation.getPossibleMovementLocationCombined(scratch), out, out);
    }

    /**
     * Moves {@code box}, one of this entity's position boxes (minY at its position), onto its Java bounding box:
     * {@code AbstractWindCharge#makeBoundingBox} starts the box 0.15F below the position, and {@code AABB} orders the
     * corners, so a negative height spans below the position. Bedrock clients keep the position box. Readers of the
     * position itself (riding offsets, eye positions) use the position box.
     */
    public SimpleCollisionBox toBoundingBox(CultPlayer player, SimpleCollisionBox box) {
        if (player.isBedrockMovement()) return box;
        if (type == EntityTypeIds.WIND_CHARGE || type == EntityTypeIds.BREEZE_WIND_CHARGE) {
            box.offset(0, -0.15F, 0);
        }
        if (BoundingBoxSize.spansBelowPosition(player, this))
            box.offset(0, -BoundingBoxSize.getHeight(player, this), 0);
        return box;
    }

    public List<SimpleCollisionBox> getPossibleMovementCollisionBoxCandidates() {
        if (passengerPhysicalBox != null && riding != null) return List.of(passengerPhysicalBox.copy());
        List<SimpleCollisionBox> candidates = new ArrayList<>();
        if (oldPacketLocation != null) {
            candidates.addAll(oldPacketLocation.getPossibleMovementLocations());
        }
        candidates.addAll(newPacketLocation.getPossibleMovementLocations());
        return candidates;
    }

    /** The number of {@link #getPossibleMovementCollisionBoxCandidates()}. */
    public int possibleMovementCollisionBoxCandidateCount() {
        if (passengerPhysicalBox != null && riding != null) return 1;
        int count = newPacketLocation.possibleMovementLocationCount();
        return oldPacketLocation == null ? count : count + oldPacketLocation.possibleMovementLocationCount();
    }

    /** Writes the {@code index}th of {@link #getPossibleMovementCollisionBoxCandidates()} into {@code out}. */
    public SimpleCollisionBox possibleMovementCollisionBoxCandidate(int index, SimpleCollisionBox out) {
        if (passengerPhysicalBox != null && riding != null) return out.set(passengerPhysicalBox);
        if (oldPacketLocation != null) {
            int old = oldPacketLocation.possibleMovementLocationCount();
            if (index < old) return oldPacketLocation.possibleMovementLocation(index, out);
            index -= old;
        }
        return newPacketLocation.possibleMovementLocation(index, out);
    }

    /** Entity data, which the client has applied once it answers provenAt. */
    public void onData(int provenAt) {
        dataProvenAt = Math.max(dataProvenAt, provenAt);
    }

    /**
     * Whether a client that answered {@code received} shows this entity at rest in the one box
     * getExactMovementCollisionBox holds, with every entity data the server sent applied.
     */
    public boolean hasCertainBox(int received) {
        return riding == null
                && received >= dataProvenAt
                && !hasInexactPosition()
                && unprovenUnbundledPaths == 0
                && replacedPoses == 0
                && Float.isNaN(replacedScale)
                && getExactMovementCollisionBox() != null;
    }

    public SimpleCollisionBox getExactMovementCollisionBox() {
        if (passengerPhysicalBox != null && riding != null) return passengerPhysicalBox.copy();
        if (newPacketLocation == null || oldPacketLocation != null) {
            return null;
        }

        List<SimpleCollisionBox> locations = newPacketLocation.getPossibleMovementLocations();
        return locations.size() == 1 ? locations.getFirst() : null;
    }

    public long getClientTickOrder() {
        return clientTickOrder;
    }

    /** Whether {@code world} holds the chunk column containing {@code position}. */
    public boolean isInLoadedChunk(CompensatedWorld world, Vec3 position) {
        long epoch = world.chunkEpoch();
        if (position != loadedChunkPosition || world != loadedChunkWorld || epoch != loadedChunkEpoch) {
            loadedChunk = world.isChunkLoaded(CultMath.floor(position.x) >> 4, CultMath.floor(position.z) >> 4);
            loadedChunkPosition = position;
            loadedChunkWorld = world;
            loadedChunkEpoch = epoch;
        }
        return loadedChunk;
    }

    public void setClientTickOrder(long clientTickOrder) {
        this.clientTickOrder = clientTickOrder;
    }

    public List<SimpleCollisionBox> getPossibleMovementCollisionBoxCandidatesAfterClientTick(boolean tickingReliably) {
        return getPossibleMovementCollisionBoxCandidates();
    }

    public List<SimpleCollisionBox> getProcessedMovementCollisionBoxCandidatesAfterClientTick(boolean tickingReliably) {
        return getPossibleMovementCollisionBoxCandidates();
    }

    public PacketEntity getRiding() {
        return riding;
    }

    public void addPotionEffect(MovementEffect effect, int amplifier) {
        if (potionsMap == null) {
            potionsMap = new HashMap<>();
        }
        potionsMap.put(effect, amplifier);
    }

    public void removePotionEffect(MovementEffect effect) {
        if (potionsMap == null) return;
        potionsMap.remove(effect);
    }
}
