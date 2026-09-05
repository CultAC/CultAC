package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

public final class WaterCurrent {
    private WaterCurrent() {
    }

    public static Vec3 calculateWaterCurrent(CultPlayer player, SimulationContext context, Vec3 position, Vec3 velocityBeforeCurrent) {
        SimpleCollisionBox fluidInteractionBox = getWaterCurrentInteractionBox(player, context, position);
        PacketEntity vehicle = context.getVehicle();
        if (vehicle != null) {
            if (EntityTypeUtil.isBoat(vehicle.type)) {
                return null;
            }

            // MCP-Reborn Entity#baseTick calls updateFluidInteraction on the
            // controlled root vehicle before LivingEntity#travelRidden, and
            // EntityFluidInteraction.Tracker#applyCurrentTo uses the non-player
            // current rule for that vehicle entity. Mounted non-boat motion in
            // water therefore starts from the vehicle box and normalized current,
            // not from the passenger player's box/current path.
            return calculateWaterCurrent(player, fluidInteractionBox, false, velocityBeforeCurrent);
        }

        return calculateWaterCurrent(player, fluidInteractionBox, true, velocityBeforeCurrent);
    }

    public static Vec3 calculateLavaCurrent(CultPlayer player, SimulationContext context, Vec3 position, Vec3 velocityBeforeCurrent) {
        PacketEntity vehicle = context.getVehicle();
        if (vehicle instanceof ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus) {
            return null;
        }
        SimpleCollisionBox entityBox = getWaterCurrentInteractionBox(player, context, position);
        double scale = player.compensatedWorld.hasFastLava() ? 0.007D : 0.0023333333333333335D;
        return calculateFluidCurrent(player, entityBox, vehicle == null, velocityBeforeCurrent, FluidTags.LAVA, scale);
    }

    public static Vec3 calculateNonPlayerWaterCurrent(CultPlayer player, SimpleCollisionBox entityBox, Vec3 velocityBeforeCurrent) {
        return calculateWaterCurrent(player, entityBox, false, velocityBeforeCurrent);
    }

    public static Vec3 calculateTouchedNonPlayerWaterCurrent(CultPlayer player, SimpleCollisionBox entityBox) {
        SimpleCollisionBox fluidBox = entityBox.copy().expand(-0.001D);
        int minX = (int) Math.floor(fluidBox.minX);
        int minY = (int) Math.floor(fluidBox.minY);
        int minZ = (int) Math.floor(fluidBox.minZ);
        int maxX = (int) Math.ceil(fluidBox.maxX) - 1;
        int maxY = (int) Math.ceil(fluidBox.maxY) - 1;
        int maxZ = (int) Math.ceil(fluidBox.maxZ) - 1;

        Vec3 accumulatedCurrent = Vec3.ZERO;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mutablePos.set(x, y, z);
                    FluidState fluidState = player.compensatedWorld.getFluidState(mutablePos);
                    if (!fluidState.is(FluidTags.WATER)) {
                        continue;
                    }

                    double fluidTop = y + fluidState.getHeight(player.compensatedWorld, mutablePos);
                    if (fluidTop < fluidBox.minY) {
                        continue;
                    }

                    Vec3 flow = fluidState.getFlow(player.compensatedWorld, mutablePos);
                    if (flow.lengthSqr() >= 1.0E-5F) {
                        accumulatedCurrent = accumulatedCurrent.add(flow);
                    }
                }
            }
        }

        if (accumulatedCurrent.lengthSqr() < 1.0E-5F) {
            return null;
        }
        return accumulatedCurrent.normalize().scale(0.014D);
    }

    public static SimpleCollisionBox getWaterCurrentInteractionBox(CultPlayer player, SimulationContext context, Vec3 position) {
        PacketEntity vehicle = context.getVehicle();
        if (vehicle != null) {
            return GetBoundingBox.getPacketEntityBoundingBox(player, position.x, position.y, position.z, vehicle);
        }

        // MCP-Reborn Entity#getFluidInteractionBox deflates the entity's
        // current bounding box. For players, that box comes from the exact
        // tick pose already chosen before Entity#baseTick updates fluid
        // interaction, so water current must sample the frozen simulation pose
        // rather than the mutable live CultPlayer pose.
        return GetBoundingBox.getBoundingBoxFromPosAndSize(
                position.x,
                position.y,
                position.z,
                context.getPose().width * context.getScale(),
                context.getPose().height * context.getScale()
        );
    }

    private static Vec3 calculateWaterCurrent(CultPlayer player, SimpleCollisionBox entityBox, boolean playerEntity, Vec3 velocityBeforeCurrent) {
        return calculateFluidCurrent(player, entityBox, playerEntity, velocityBeforeCurrent, FluidTags.WATER, 0.014D);
    }

    private static Vec3 calculateFluidCurrent(CultPlayer player, SimpleCollisionBox entityBox, boolean playerEntity,
                                              Vec3 velocityBeforeCurrent, net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> fluidTag,
                                              double scale) {
        SimpleCollisionBox fluidBox = entityBox.copy().expand(-0.001D);

        int minX = (int) Math.floor(fluidBox.minX);
        int minY = (int) Math.floor(fluidBox.minY);
        int minZ = (int) Math.floor(fluidBox.minZ);
        int maxX = (int) Math.ceil(fluidBox.maxX) - 1;
        int maxY = (int) Math.ceil(fluidBox.maxY) - 1;
        int maxZ = (int) Math.ceil(fluidBox.maxZ) - 1;

        Vec3 accumulatedCurrent = Vec3.ZERO;
        int currentCount = 0;
        double height = 0.0;
        double entityMinY = entityBox.minY;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mutablePos.set(x, y, z);
                    FluidState fluidState = player.compensatedWorld.getFluidState(mutablePos);
                    if (!fluidState.is(fluidTag)) {
                        continue;
                    }

                    double fluidTop = y + fluidState.getHeight(player.compensatedWorld, mutablePos);
                    if (fluidTop < fluidBox.minY) {
                        continue;
                    }

                    height = Math.max(fluidTop - entityMinY, height);
                    Vec3 flow = fluidState.getFlow(player.compensatedWorld, mutablePos);
                    if (height < 0.4D) {
                        flow = flow.scale(height);
                    }

                    accumulatedCurrent = accumulatedCurrent.add(flow);
                    currentCount++;
                }
            }
        }

        if (currentCount == 0 || accumulatedCurrent.lengthSqr() < 1.0E-5F) {
            return null;
        }

        // MCP-Reborn EntityFluidInteraction.Tracker#applyCurrentTo averages water
        // current only for Player entities. Other entities, including boats,
        // normalize accumulated current before scaling it by 0.014.
        Vec3 current = (playerEntity ? accumulatedCurrent.scale(1.0 / currentCount) : accumulatedCurrent.normalize()).scale(scale);
        if (Math.abs(velocityBeforeCurrent.x) < 0.003 && Math.abs(velocityBeforeCurrent.z) < 0.003 && current.length() < 0.0045000000000000005) {
            current = current.normalize().scale(0.0045000000000000005);
        }
        return current;
    }
}
