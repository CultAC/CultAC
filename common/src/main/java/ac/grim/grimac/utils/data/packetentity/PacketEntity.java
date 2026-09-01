// This file was designed and is an original check for GrimAC
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
package ac.grim.grimac.utils.data.packetentity;

import ac.grim.grimac.network.packet.PacketCodecUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.BoatData;
import ac.grim.grimac.utils.data.ReachInterpolationData;
import ac.grim.grimac.utils.enums.BoatEntityStatus;
import ac.grim.grimac.utils.nmsutil.EntityTypesCompat;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import ac.grim.grimac.utils.nmsutil.EntityTypeUtil;
import net.minecraft.world.phys.Vec3;
import lombok.Getter;
import net.minecraft.world.entity.EntityType;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

// You may not copy this check unless your anticheat is licensed under GPL
public class PacketEntity {
    public Vec3 desyncClientPos;

    public EntityType type;

    public PacketEntity riding;
    public List<PacketEntity> passengers = new ArrayList<>(0);
    public boolean isDead = false;
    public boolean isBaby = false;
    public boolean hasGravity = true;
    public double gravity = 0.08D;
    public boolean noAI = false;
    public float scale = 1.0f;
    public boolean onGround = false;
    public Vec3 deltaMovement = Vec3.ZERO;
    public Vec3 clientPhysicalPosition;
    public boolean clientPhysicalPositionExact = true;
    public float clientPhysicalYaw = 0.0F;
    public float clientPhysicalPitch = 0.0F;

    public ReachInterpolationData oldPacketLocation;
    public ReachInterpolationData newPacketLocation;
    private long clientTickOrder = Long.MAX_VALUE;

    public HashMap<PotionEffectType, Integer> potionsMap = null;
    public BoatEntityStatus boatStatus = null;

    @Getter private final int entityId;

    public PacketEntity(EntityType type, int entityId) { this.entityId = entityId;
        this.type = type;
    }

    public PacketEntity(GrimPlayer grimPlayer, int entityId, EntityType type, double x, double y, double z) {
        this(type, entityId);
        Vec3 spawnPosition = new Vec3(x, y, z);
        this.desyncClientPos = spawnPosition;
        this.clientPhysicalPosition = spawnPosition;
        SimpleCollisionBox spawnBox = GetBoundingBox.getPacketEntityBoundingBox(grimPlayer, x, y, z, this);
        boolean ridingInVehicle = grimPlayer.compensatedEntities.getSelf().inVehicle();
        this.newPacketLocation = new ReachInterpolationData(grimPlayer, spawnBox, x, y, z, !ridingInVehicle, this, false);
    }

    public EntityType getType() {
        return type;
    }

    public boolean isLivingEntity() {
        return EntityTypeUtil.isLiving(type);
    }

    public boolean isMinecart() {
        return EntityTypeUtil.isMinecart(type);
    }

    public boolean isHorse() {
        return EntityTypeUtil.isHorseFamily(type);
    }

    public boolean isAgeable() {
        return EntityTypeUtil.isAgeable(type);
    }

    public boolean isBoat() {
        return EntityTypeUtil.isBoat(type);
    }

    public boolean isAnimal() {
        return EntityTypeUtil.isAnimal(type);
    }

    public boolean isSize() {
        return type == EntityTypesCompat.PHANTOM || type == EntityTypesCompat.SLIME || type == EntityTypesCompat.MAGMA_CUBE;
    }

    public boolean isStrider() { return type == EntityTypesCompat.STRIDER; }

    // Set the old packet location to the new one
    // Set the new packet location to the updated packet location
    public void onFirstTransaction(boolean relative, boolean hasPos, double relX, double relY, double relZ, GrimPlayer player) {
        onFirstTransaction(relative, hasPos, relX, relY, relZ, null, null, player, true);
    }

    public void onFirstTransaction(boolean relative, boolean hasPos, double relX, double relY, double relZ,
                                   @Nullable Float yaw, @Nullable Float pitch, GrimPlayer player) {
        onFirstTransaction(relative, hasPos, relX, relY, relZ, yaw, pitch, player, true);
    }

    public void onBundleFirstTransaction(boolean relative, boolean hasPos, double relX, double relY, double relZ, GrimPlayer player) {
        onBundleFirstTransaction(relative, hasPos, relX, relY, relZ, null, null, player);
    }

    public void onBundleFirstTransaction(boolean relative, boolean hasPos, double relX, double relY, double relZ,
                                         @Nullable Float yaw, @Nullable Float pitch, GrimPlayer player) {
        // MCP-Reborn ClientPacketListener#handleBundlePacket handles bundled
        // sub-packets in order. The first bundled ClientboundPingPacket proves
        // that any later client movement was produced after the packet handler
        // reached this entity movement, but an external delimiter can still cut
        // the bundle short. Keep old and new packet boxes possible until the
        // post-entity ping closes that protocol-valid ambiguity.
        onFirstTransaction(relative, hasPos, relX, relY, relZ, yaw, pitch, player, false);
    }

    private void onFirstTransaction(boolean relative, boolean hasPos, double relX, double relY, double relZ,
                                    @Nullable Float yaw, @Nullable Float pitch,
                                    GrimPlayer player, boolean uncertainInitialSteps) {
        applyEntityPositionUpdate(relative, hasPos, relX, relY, relZ);
        if (desyncClientPos == null) {
            return;
        }

        float targetYaw = interpolationTargetYaw(yaw);
        float targetPitch = interpolationTargetPitch(pitch);
        if (matchesActiveInterpolationTarget(desyncClientPos.x, desyncClientPos.y, desyncClientPos.z, targetYaw, targetPitch)) {
            return;
        }

        SimpleCollisionBox startingLocation = interpolationStartingLocation(player);
        boolean ridingInVehicle = player.compensatedEntities.getSelf().inVehicle();
        this.oldPacketLocation = newPacketLocation;
        this.newPacketLocation = new ReachInterpolationData(player, startingLocation,
                desyncClientPos.x, desyncClientPos.y, desyncClientPos.z,
                clientPhysicalYaw, clientPhysicalPitch, targetYaw, targetPitch,
                !ridingInVehicle, this, uncertainInitialSteps);
    }

    private void applyEntityPositionUpdate(boolean relative, boolean hasPos, double relX, double relY, double relZ) {
        if (!hasPos) {
            return;
        }
        desyncClientPos = relative
                ? PacketCodecUtil.decodeRelativeEntityPosition(desyncClientPos, relX, relY, relZ)
                : new Vec3(relX, relY, relZ);
    }

    public void onBundleTransaction(boolean relative, boolean hasPos, double relX, double relY, double relZ, GrimPlayer player) {
        onBundleTransaction(relative, hasPos, relX, relY, relZ, null, null, player);
    }

    public void onBundleTransaction(boolean relative, boolean hasPos, double relX, double relY, double relZ,
                                    @Nullable Float yaw, @Nullable Float pitch, GrimPlayer player) {
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

        SimpleCollisionBox startingLocation = interpolationStartingLocation(player);
        float targetYaw = interpolationTargetYaw(yaw);
        float targetPitch = interpolationTargetPitch(pitch);
        if (matchesActiveInterpolationTarget(desyncClientPos.x, desyncClientPos.y, desyncClientPos.z, targetYaw, targetPitch)) {
            return;
        }
        this.oldPacketLocation = null;
        this.newPacketLocation = new ReachInterpolationData(player, startingLocation,
                desyncClientPos.x, desyncClientPos.y, desyncClientPos.z,
                clientPhysicalYaw, clientPhysicalPitch, targetYaw, targetPitch,
                !player.compensatedEntities.getSelf().inVehicle(), this, false);
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

    public boolean matchesActiveInterpolationTarget(double x, double y, double z,
                                                    @Nullable Float yaw, @Nullable Float pitch) {
        return matchesActiveInterpolationTarget(x, y, z, interpolationTargetYaw(yaw), interpolationTargetPitch(pitch));
    }

    private boolean matchesActiveInterpolationTarget(double x, double y, double z, float yaw, float pitch) {
        if (!hasExactActiveInterpolationTarget()) {
            return false;
        }

        Vec3 target = positionFromCollisionBox(newPacketLocation.getTargetLocation());
        return matchesPosition(target, x, y, z)
                && Float.compare(newPacketLocation.getTargetYaw(), yaw) == 0
                && Float.compare(newPacketLocation.getTargetPitch(), pitch) == 0;
    }

    private boolean matchesPosition(Vec3 position, double x, double y, double z) {
        return matchesCoordinate(position.x, x)
                && matchesCoordinate(position.y, y)
                && matchesCoordinate(position.z, z);
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

    private SimpleCollisionBox interpolationStartingLocation(GrimPlayer player) {
        if (hasUsableMountedPhysicalPosition(player)) {
            return GetBoundingBox.getPacketEntityBoundingBox(player, clientPhysicalPosition.x, clientPhysicalPosition.y, clientPhysicalPosition.z, this);
        }

        return newPacketLocation == null
                ? GetBoundingBox.getPacketEntityBoundingBox(player, desyncClientPos.x, desyncClientPos.y, desyncClientPos.z, this)
                : newPacketLocation.getPossibleMovementLocationCombined();
    }

    public void onBundledPositionSyncTransaction(double x, double y, double z, GrimPlayer player) {
        onBundledPositionSyncTransaction(x, y, z, null, null, player);
    }

    public void onBundledPositionSyncTransaction(double x, double y, double z,
                                                 @Nullable Float yaw, @Nullable Float pitch,
                                                 GrimPlayer player) {
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

    // If the old and new packet location are split, we need to combine bounding boxes
    public void onMovement(GrimPlayer player, boolean tickingReliably) {
        carryInterpolationTargetByLocalPhysics(player);
        SimpleCollisionBox exactAfterClientTick = exactInterpolatedLocationAfterClientTick(player);
        float exactYawAfterClientTick = exactYawAfterClientTick();
        float exactPitchAfterClientTick = exactPitchAfterClientTick();
        boolean exactRotationAfterClientTick = exactRotationAfterClientTick();
        newPacketLocation.tickMovement(oldPacketLocation == null, tickingReliably);
        alignExactInterpolatedLocation(exactAfterClientTick);
        if (exactRotationAfterClientTick) {
            newPacketLocation.setExactRotation(exactYawAfterClientTick, exactPitchAfterClientTick);
            clientPhysicalYaw = exactYawAfterClientTick;
            clientPhysicalPitch = exactPitchAfterClientTick;
        }
        if (exactAfterClientTick != null) {
            clientPhysicalPosition = positionFromCollisionBox(exactAfterClientTick);
            clientPhysicalPositionExact = true;
        } else {
            clientPhysicalPositionExact = false;
        }

        // Handle uncertainty of second transaction spanning over multiple ticks
        if (oldPacketLocation != null) {
            oldPacketLocation.tickMovement(true, tickingReliably);
            newPacketLocation.updatePossibleStartingLocation(oldPacketLocation.getPossibleMovementLocationCombined());
        }

    }

    public void tickPacketInterpolationPreservingPhysical(GrimPlayer player, Vec3 physicalPosition) {
        tickPacketInterpolationPreservingPhysical(player, physicalPosition, clientPhysicalYaw, clientPhysicalPitch);
    }

    public void tickPacketInterpolationPreservingPhysical(GrimPlayer player, Vec3 physicalPosition, float physicalYaw, float physicalPitch) {
        tickPacketInterpolationPreservingPhysical(player, physicalPosition, physicalYaw, physicalPitch, true);
    }

    private void tickPacketInterpolationPreservingPhysical(GrimPlayer player,
                                                           Vec3 physicalPosition,
                                                           float physicalYaw,
                                                           float physicalPitch,
                                                           boolean carryTarget) {
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
            newPacketLocation.updatePossibleStartingLocation(oldPacketLocation.getPossibleMovementLocationCombined());
        }

        this.clientPhysicalPosition = physicalPosition;
        this.clientPhysicalPositionExact = true;
        this.clientPhysicalYaw = physicalYaw;
        this.clientPhysicalPitch = physicalPitch;
    }

    public void tickPacketInterpolationPreservingPhysical(GrimPlayer player, Vec3 physicalPosition, ReachInterpolationData interpolationData) {
        tickPacketInterpolationPreservingPhysical(player, physicalPosition, interpolationData, clientPhysicalYaw, clientPhysicalPitch);
    }

    public void tickPacketInterpolationPreservingPhysical(GrimPlayer player,
                                                          Vec3 physicalPosition,
                                                          ReachInterpolationData interpolationData,
                                                          float physicalYaw,
                                                          float physicalPitch) {
        this.oldPacketLocation = null;
        this.newPacketLocation = interpolationData.copy();
        tickPacketInterpolationPreservingPhysical(player, physicalPosition, physicalYaw, physicalPitch);
    }

    private void carryInterpolationTargetByLocalPhysics(GrimPlayer player) {
        carryInterpolationTargetByLocalPhysics(player, newPacketLocation);
    }

    private void carryInterpolationTargetByLocalPhysics(GrimPlayer player, ReachInterpolationData interpolationData) {
        if (interpolationData == null
                || !interpolationData.hasActiveInterpolationTarget()
                || !hasUsableMountedPhysicalPosition(player)) {
            return;
        }

        SimpleCollisionBox currentInterpolationLocation = interpolationData.getExactMovementLocation();
        if (currentInterpolationLocation == null) {
            return;
        }
        Vec3 previousInterpolationPosition = positionFromCollisionBox(currentInterpolationLocation);
        Vec3 physicalDelta = clientPhysicalPosition.subtract(previousInterpolationPosition);
        interpolationData.shiftRootVehicleTargetIfCollisionFree(player, this, physicalDelta);
        interpolationData.shiftTargetRotationByPhysical(clientPhysicalYaw, clientPhysicalPitch);
        syncDesyncClientPosToInterpolationTarget(interpolationData);
    }

    private void syncDesyncClientPosToInterpolationTarget(ReachInterpolationData interpolationData) {
        if (interpolationData == newPacketLocation && interpolationData.hasActiveInterpolationTarget()) {
            this.desyncClientPos = positionFromCollisionBox(interpolationData.getTargetLocation());
        }
    }

    private SimpleCollisionBox exactInterpolatedLocationAfterClientTick(GrimPlayer player) {
        if (newPacketLocation == null
                || !hasUsableMountedPhysicalPosition(player)
                || oldPacketLocation != null) {
            return newPacketLocation == null ? null : newPacketLocation.getExactMovementLocationAfterClientTick();
        }

        SimpleCollisionBox physicalLocation = GetBoundingBox.getPacketEntityBoundingBox(
                player,
                clientPhysicalPosition.x,
                clientPhysicalPosition.y,
                clientPhysicalPosition.z,
                this
        );
        SimpleCollisionBox exactFromPhysical = newPacketLocation.getExactMovementLocationAfterClientTickFromPhysical(physicalLocation);
        return exactFromPhysical == null
                ? newPacketLocation.getExactMovementLocationAfterClientTick()
                : exactFromPhysical;
    }

    private boolean exactRotationAfterClientTick() {
        return newPacketLocation != null
                && oldPacketLocation == null
                && newPacketLocation.hasActiveInterpolationTarget()
                && newPacketLocation.getExactInterpolationStep() >= 0;
    }

    private boolean hasUsableMountedPhysicalPosition(GrimPlayer player) {
        return clientPhysicalPosition != null
                && (clientPhysicalPositionExact || this == player.compensatedEntities.vehicles.getVelocityMovementVehicle());
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

    public void updateBoatStatus(GrimPlayer player) {
        if (isBoat() && newPacketLocation != null) {
            // MCP-Reborn AbstractBoat#tick updates status even for client-side
            // non-controlled boats before deciding whether local physics runs.
            boatStatus = BoatData.sampleStatus(player, getPossibleMovementCollisionBoxes()).status();
        }
    }

    public boolean hasPassenger(PacketEntity entity) {
        return passengers.contains(entity);
    }

    public void mount(PacketEntity vehicle) {
        if (riding != null) eject();
        vehicle.passengers.add(this);
        riding = vehicle;
    }

    public void eject() {
        if (riding != null) {
            riding.passengers.remove(this);
        }
        this.riding = null;
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
    }

    public void setPositionRaw(SimpleCollisionBox box, float yaw, float pitch) {
        setPositionRaw(box);
        setClientPhysicalRotation(yaw, pitch);
        this.newPacketLocation.setCurrentAndTargetRotation(yaw, pitch);
    }

    public void setClientPhysicalRotation(float yaw, float pitch) {
        this.clientPhysicalYaw = yaw;
        this.clientPhysicalPitch = pitch;
    }

    private Vec3 positionFromCollisionBox(SimpleCollisionBox box) {
        return new Vec3(
                (box.maxX - box.minX) / 2 + box.minX,
                box.minY,
                (box.maxZ - box.minZ) / 2 + box.minZ
        );
    }

    public void refreshDimensions(GrimPlayer player) {
        if (desyncClientPos == null) {
            return;
        }
        setPositionRaw(GetBoundingBox.getPacketEntityBoundingBox(player, desyncClientPos.x, desyncClientPos.y, desyncClientPos.z, this));
    }

    public SimpleCollisionBox getPossibleCollisionBoxes() {
        if (oldPacketLocation == null) {
            return newPacketLocation.getPossibleLocationCombined();
        }

        return ReachInterpolationData.combineCollisionBox(oldPacketLocation.getPossibleLocationCombined(), newPacketLocation.getPossibleLocationCombined());
    }

    public SimpleCollisionBox getPossibleMovementCollisionBoxes() {
        if (oldPacketLocation == null) {
            return newPacketLocation.getPossibleMovementLocationCombined();
        }

        return ReachInterpolationData.combineCollisionBox(oldPacketLocation.getPossibleMovementLocationCombined(), newPacketLocation.getPossibleMovementLocationCombined());
    }

    public List<SimpleCollisionBox> getPossibleMovementCollisionBoxCandidates() {
        List<SimpleCollisionBox> candidates = new ArrayList<>();
        if (oldPacketLocation != null) {
            candidates.addAll(oldPacketLocation.getPossibleMovementLocations());
        }
        candidates.addAll(newPacketLocation.getPossibleMovementLocations());
        return candidates;
    }

    public SimpleCollisionBox getExactMovementCollisionBox() {
        if (newPacketLocation == null || oldPacketLocation != null) {
            return null;
        }

        List<SimpleCollisionBox> locations = newPacketLocation.getPossibleMovementLocations();
        return locations.size() == 1 ? locations.getFirst() : null;
    }

    public long getClientTickOrder() {
        return clientTickOrder;
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

    public void addPotionEffect(PotionEffectType effect, int amplifier) {
        if (potionsMap == null) {
            potionsMap = new HashMap<>();
        }
        potionsMap.put(effect, amplifier);
    }

    public void removePotionEffect(PotionEffectType effect) {
        if (potionsMap == null) return;
        potionsMap.remove(effect);
    }
}
