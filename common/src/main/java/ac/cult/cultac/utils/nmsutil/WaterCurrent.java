package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.FluidTags;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.runner.PredictionScratch;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;

public final class WaterCurrent {
    private WaterCurrent() {}

    public static Vec3 calculateWaterCurrent(
            CultPlayer player, SimulationContext context, Vec3 position, Vec3 velocityBeforeCurrent) {
        return calculateWaterCurrent(player, context, position, velocityBeforeCurrent, false);
    }

    /**
     * The water current at {@code position}; with {@code beforeRecentChanges}, read with every recently changed cell
     * at the first state its recent changes replaced.
     */
    public static Vec3 calculateWaterCurrent(
            CultPlayer player,
            SimulationContext context,
            Vec3 position,
            Vec3 velocityBeforeCurrent,
            boolean beforeRecentChanges) {
        SimpleCollisionBox fluidInteractionBox =
                getWaterCurrentInteractionBox(scratchBox(player), player, context, position);
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
            return calculateFluidCurrent(
                    player,
                    fluidInteractionBox,
                    false,
                    velocityBeforeCurrent,
                    FluidTags.WATER,
                    0.014D,
                    beforeRecentChanges);
        }

        return calculateFluidCurrent(
                player, fluidInteractionBox, true, velocityBeforeCurrent, FluidTags.WATER, 0.014D, beforeRecentChanges);
    }

    public static Vec3 calculateLavaCurrent(
            CultPlayer player, SimulationContext context, Vec3 position, Vec3 velocityBeforeCurrent) {
        return calculateLavaCurrent(player, context, position, velocityBeforeCurrent, false);
    }

    /** {@link #calculateWaterCurrent(CultPlayer, SimulationContext, Vec3, Vec3, boolean)} for lava. */
    public static Vec3 calculateLavaCurrent(
            CultPlayer player,
            SimulationContext context,
            Vec3 position,
            Vec3 velocityBeforeCurrent,
            boolean beforeRecentChanges) {
        PacketEntity vehicle = context.getVehicle();
        if (vehicle instanceof ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus) {
            return null;
        }
        SimpleCollisionBox entityBox = getWaterCurrentInteractionBox(scratchBox(player), player, context, position);
        double scale = player.compensatedWorld.hasFastLava() ? 0.007D : 0.0023333333333333335D;
        return calculateFluidCurrent(
                player, entityBox, vehicle == null, velocityBeforeCurrent, FluidTags.LAVA, scale, beforeRecentChanges);
    }

    /**
     * Whether a recent change of {@code fluid} touched a cell the current at {@code position} reads: the cells of the
     * fluid interaction box, their horizontal neighbors and the cells above and below them.
     */
    public static boolean hasRecentChanges(
            CultPlayer player, SimulationContext context, Vec3 position, FluidTags fluid) {
        SimpleCollisionBox box = getWaterCurrentInteractionBox(player, context, position);
        SimpleCollisionBox read = new SimpleCollisionBox(
                box.minX - 1.0D, box.minY - 1.0D, box.minZ - 1.0D, box.maxX + 1.0D, box.maxY + 1.0D, box.maxZ + 1.0D);
        return fluid == FluidTags.WATER
                ? player.compensatedWorld.hasRecentClientWaterChanges(read)
                : player.compensatedWorld.hasRecentClientLavaChanges(read);
    }

    public static Vec3 calculateNonPlayerWaterCurrent(
            CultPlayer player, SimpleCollisionBox entityBox, Vec3 velocityBeforeCurrent) {
        return calculateWaterCurrent(player, entityBox, false, velocityBeforeCurrent);
    }

    public static SimpleCollisionBox getWaterCurrentInteractionBox(
            CultPlayer player, SimulationContext context, Vec3 position) {
        return getWaterCurrentInteractionBox(new SimpleCollisionBox(), player, context, position);
    }

    /** The player's scratch box, read only within one current computation, or a fresh box without one. */
    private static SimpleCollisionBox scratchBox(CultPlayer player) {
        PredictionScratch scratch = PredictionScratch.of(player);
        return scratch == null ? new SimpleCollisionBox() : scratch.fluidBox;
    }

    /** {@link #getWaterCurrentInteractionBox(CultPlayer, SimulationContext, Vec3)} written into {@code out}. */
    public static SimpleCollisionBox getWaterCurrentInteractionBox(
            SimpleCollisionBox out, CultPlayer player, SimulationContext context, Vec3 position) {
        PacketEntity vehicle = context.getVehicle();
        if (vehicle != null) {
            return GetBoundingBox.getPacketEntityBoundingBox(out, player, position.x, position.y, position.z, vehicle);
        }

        // MCP-Reborn Entity#getFluidInteractionBox deflates the entity's
        // current bounding box. For players, that box comes from the exact
        // tick pose already chosen before Entity#baseTick updates fluid
        // interaction, so water current must sample the frozen simulation pose
        // rather than the mutable live CultPlayer pose.
        return GetBoundingBox.getBoundingBoxFromPosAndSize(
                out,
                position.x,
                position.y,
                position.z,
                context.getPose().width * context.getScale(),
                context.getPose().height * context.getScale());
    }

    private static Vec3 calculateWaterCurrent(
            CultPlayer player, SimpleCollisionBox entityBox, boolean playerEntity, Vec3 velocityBeforeCurrent) {
        return calculateFluidCurrent(
                player, entityBox, playerEntity, velocityBeforeCurrent, FluidTags.WATER, 0.014D, false);
    }

    private static Vec3 calculateFluidCurrent(
            CultPlayer player,
            SimpleCollisionBox entityBox,
            boolean playerEntity,
            Vec3 velocityBeforeCurrent,
            FluidTags fluidTag,
            double scale,
            boolean beforeRecentChanges) {
        if (!beforeRecentChanges && !FluidCurrent.mayTouchFluid(player, entityBox)) return null;
        FluidCurrent current = new FluidCurrent();
        return current.compute(
                        player,
                        entityBox,
                        playerEntity,
                        velocityBeforeCurrent.x,
                        velocityBeforeCurrent.z,
                        fluidTag,
                        scale,
                        beforeRecentChanges)
                ? new Vec3(current.x, current.y, current.z)
                : null;
    }
}
