package ac.cult.cultac.utils.data;

import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.BoatTransform;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.enums.BoatEntityStatus;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

public class BoatData {
    public record BoatStatusSample(BoatEntityStatus status, double waterLevel, float landFriction) {
    }

    public boolean nullifyNextY = false;
    public double lastYd = 0.0D;
    public double midTickY;
    public float landFriction;
    public BoatEntityStatus status;
    public BoatEntityStatus oldStatus;
    public double waterLevel;
    public float nextVehicleHoriz = 0f;
    public float nextVehicleForward = 0f;
    public boolean nextVehicleJump = false;
    public float vehicleHoriz = 0f;
    public float vehicleForward = 0f;
    public boolean vehicleJump = false;
    public float deltaRotation = 0f;
    public SimpleCollisionBox fluidInteractionBox;
    public boolean oldStatusMayBeInAir;
    private int controlledBoatEntityId = Integer.MIN_VALUE;

    public void useNextInputForBoatTick() {
        vehicleHoriz = nextVehicleHoriz;
        vehicleForward = nextVehicleForward;
        vehicleJump = nextVehicleJump;
    }

    public boolean usesWaterEntryPositionSnap() {
        return usesWaterEntryPositionSnap(oldStatus, status);
    }

    public boolean mayUseWaterEntryPositionSnap() {
        return usesWaterEntryPositionSnap()
                || (oldStatusMayBeInAir
                && status != BoatEntityStatus.IN_AIR
                && status != BoatEntityStatus.ON_LAND);
    }

    public Vec3 adjustStuckSpeedMultiplierForCurrentTick(CultPlayer player, SimulationContext context, Vec3 multiplier) {
        if (multiplier == null || !BoatTransform.usesProvenWaterEntryPositionSnap(player, context)) {
            return multiplier;
        }

        // MCP-Reborn AbstractBoat#floatBoat only nulls deltaMovement.y in the
        // exact water-entry snap branch after Level#noCollision accepts the
        // vertical snap. Only that proven branch removes carried stuck-speed
        // influence from the tick's Y movement; a blocked snap keeps the full
        // multiplier for the later Entity#move call.
        return new Vec3(multiplier.x, 1.0D, multiplier.z);
    }

    private static boolean usesWaterEntryPositionSnap(BoatEntityStatus oldStatus, BoatEntityStatus status) {
        return oldStatus == BoatEntityStatus.IN_AIR
                && status != BoatEntityStatus.IN_AIR
                && status != BoatEntityStatus.ON_LAND;
    }

    public void tick(CultPlayer player) {
        PacketEntity vehicle = player.compensatedEntities.getSelf().getRiding();
        if (vehicle != null && EntityTypeUtil.isBoat(vehicle.type)) {
            SimpleCollisionBox boatBox = getControlledBoatCurrentBox(player, vehicle);
            fluidInteractionBox = boatBox.copy();
            int boatEntityId = vehicle.getEntityId();
            if (controlledBoatEntityId != boatEntityId) {
                // MCP-Reborn AbstractBoat stores deltaRotation, status, oldStatus,
                // waterLevel, landFriction, and lastYd on the boat entity. When
                // local control switches to a different boat, none of those fields
                // can leak from the old controlled boat.
                //
                // MCP-Reborn LocalPlayer#tick sends ServerboundMoveVehiclePacket
                // from the controlled root before ClientLevel#tickPassenger runs
                // LocalPlayer#rideTick to copy the latest key state into
                // AbstractBoat#setInput. The first controlled packet for a newly
                // mounted/switched boat therefore still uses that boat's exact
                // pre-switch current input state, not the latest staged raw
                // key bitset.
                controlledBoatEntityId = boatEntityId;
                deltaRotation = 0f;
                status = vehicle.boatStatus;
                oldStatus = null;
                waterLevel = 0.0D;
                landFriction = 0.0F;
                lastYd = 0.0D;
                if (hasCompletedPassengerTickAfterObservedMount(player, boatEntityId)) {
                    // MCP-Reborn ClientLevel ticks the root boat before passenger
                    // rideTick, and LocalPlayer#rideTick copies the latest keys
                    // into AbstractBoat#setInput after LocalPlayer#tick sends the
                    // vehicle packet. If a tick-end packet after the mount
                    // transaction has already arrived, that passenger copy has
                    // completed and the next root boat tick starts from the staged
                    // input, not the boat's pre-mount zero fields.
                    vehicleHoriz = nextVehicleHoriz;
                    vehicleForward = nextVehicleForward;
                    vehicleJump = nextVehicleJump;
                } else {
                    vehicleHoriz = 0.0F;
                    vehicleForward = 0.0F;
                    vehicleJump = false;
                }
            }

            midTickY = 0;
            nullifyNextY = false;
            oldStatusMayBeInAir = false;

            // MCP-Reborn AbstractBoat#tick copies status to oldStatus, then
            // samples getStatus() from the current pre-move bounding box.
            oldStatus = status;
            BoatStatusSample sample = sampleStatus(player, boatBox);
            status = sample.status();
            waterLevel = sample.waterLevel();
            if (sample.landFriction() > 0.0F) {
                landFriction = sample.landFriction();
            }
        } else {
            fluidInteractionBox = null;
            oldStatusMayBeInAir = false;
        }
    }

    private static boolean hasCompletedPassengerTickAfterObservedMount(CultPlayer player, int boatEntityId) {
        Integer serverVehicle = player.compensatedEntities.vehicles.serverPlayerVehicle;
        Integer serverVehicleTransaction = player.compensatedEntities.vehicles.serverPlayerVehicleTransaction;
        return serverVehicle != null
                && serverVehicle == boatEntityId
                && serverVehicleTransaction != null
                && player.packetStateData.lastClientTickEndTransaction >= serverVehicleTransaction;
    }

    private static SimpleCollisionBox getControlledBoatCurrentBox(CultPlayer player, PacketEntity vehicle) {
        if (vehicle.clientPhysicalPosition != null && vehicle.clientPhysicalPositionExact) {
            Vec3 position = vehicle.clientPhysicalPosition;
            return GetBoundingBox.getPacketEntityBoundingBox(player, position.x, position.y, position.z, vehicle);
        }

        return vehicle.getPossibleCollisionBoxes().copy();
    }

    public static BoatStatusSample sampleStatus(CultPlayer player, SimpleCollisionBox box) {
        BoatEntityStatus underwaterStatus = isUnderwater(player, box);
        if (underwaterStatus != null) {
            return new BoatStatusSample(underwaterStatus, box.maxY, 0.0F);
        }

        double waterLevel = getInWaterLevel(player, box);
        if (waterLevel > -Double.MAX_VALUE) {
            return new BoatStatusSample(BoatEntityStatus.IN_WATER, waterLevel, 0.0F);
        }

        float groundFriction = getGroundFriction(player, box);
        if (groundFriction > 0.0F) {
            return new BoatStatusSample(BoatEntityStatus.ON_LAND, -Double.MAX_VALUE, groundFriction);
        }

        return new BoatStatusSample(BoatEntityStatus.IN_AIR, -Double.MAX_VALUE, 0.0F);
    }

    public static float getGroundFriction(CultPlayer player, SimpleCollisionBox axisalignedbb) {
        SimpleCollisionBox axisalignedbb1 = new SimpleCollisionBox(
                axisalignedbb.minX,
                axisalignedbb.minY - 0.001D,
                axisalignedbb.minZ,
                axisalignedbb.maxX,
                axisalignedbb.minY,
                axisalignedbb.maxZ,
                false
        );
        int i = (int) (Math.floor(axisalignedbb1.minX) - 1);
        int j = (int) (Math.ceil(axisalignedbb1.maxX) + 1);
        int k = (int) (Math.floor(axisalignedbb1.minY) - 1);
        int l = (int) (Math.ceil(axisalignedbb1.maxY) + 1);
        int i1 = (int) (Math.floor(axisalignedbb1.minZ) - 1);
        int j1 = (int) (Math.ceil(axisalignedbb1.maxZ) + 1);

        float f = 0.0F;
        int k1 = 0;

        for (int l1 = i; l1 < j; ++l1) {
            for (int i2 = i1; i2 < j1; ++i2) {
                int j2 = (l1 != i && l1 != j - 1 ? 0 : 1) + (i2 != i1 && i2 != j1 - 1 ? 0 : 1);
                if (j2 != 2) {
                    for (int k2 = k; k2 < l; ++k2) {
                        if (j2 <= 0 || k2 != k && k2 != l - 1) {
                            BlockData blockData = player.compensatedWorld.getBlockDataAt(l1, k2, i2);
                            Material blockMaterial = blockData.getMaterial();

                            if (blockMaterial != Material.LILY_PAD && ClientBlockShapes.movement(
                                    player,
                                    blockData,
                                    l1,
                                    k2,
                                    i2)
                                    .isIntersected(axisalignedbb1)) {
                                f += BlockProperties.getMaterialFriction(blockMaterial);
                                ++k1;
                            }
                        }
                    }
                }
            }
        }

        return k1 == 0 ? 0.0F : f / (float) k1;
    }

    private static BoatEntityStatus isUnderwater(CultPlayer player, SimpleCollisionBox axisalignedbb) {
        double d0 = axisalignedbb.maxY + 0.001D;
        int i = CultMath.floor(axisalignedbb.minX);
        int j = CultMath.ceil(axisalignedbb.maxX);
        int k = CultMath.floor(axisalignedbb.maxY);
        int l = CultMath.ceil(d0);
        int i1 = CultMath.floor(axisalignedbb.minZ);
        int j1 = CultMath.ceil(axisalignedbb.maxZ);
        boolean flag = false;

        for (int k1 = i; k1 < j; ++k1) {
            for (int l1 = k; l1 < l; ++l1) {
                for (int i2 = i1; i2 < j1; ++i2) {
                    double level = player.compensatedWorld.getWaterFluidLevelAt(k1, l1, i2);
                    if (d0 < l1 + level) {
                        if (!player.compensatedWorld.isWaterSourceBlock(k1, l1, i2)) {
                            return BoatEntityStatus.UNDER_FLOWING_WATER;
                        }

                        flag = true;
                    }
                }
            }
        }

        return flag ? BoatEntityStatus.UNDER_WATER : null;
    }

    private static double getInWaterLevel(CultPlayer cultPlayer, SimpleCollisionBox axisalignedbb) {
        int i = CultMath.floor(axisalignedbb.minX);
        int j = CultMath.ceil(axisalignedbb.maxX);
        int k = CultMath.floor(axisalignedbb.minY);
        int l = CultMath.ceil(axisalignedbb.minY + 0.001D);
        int i1 = CultMath.floor(axisalignedbb.minZ);
        int j1 = CultMath.ceil(axisalignedbb.maxZ);
        boolean flag = false;
        double waterLevel = -Double.MAX_VALUE;

        for (int k1 = i; k1 < j; ++k1) {
            for (int l1 = k; l1 < l; ++l1) {
                for (int i2 = i1; i2 < j1; ++i2) {
                    double level = cultPlayer.compensatedWorld.getWaterFluidLevelAt(k1, l1, i2);
                    if (level > 0) {
                        float f = (float) ((float) l1 + level);
                        waterLevel = Math.max(f, waterLevel);
                        flag |= axisalignedbb.minY < (double) f;
                    }
                }
            }
        }

        return flag ? waterLevel : -Double.MAX_VALUE;
    }
}
