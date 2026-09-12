package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/** Vanilla liquid-presence, body-depth, and eye queries deliberately have different bounds. */
public final class ClientFluidQueries {
    private static final TagKey<EntityType<?>> CAN_FLOAT_WHILE_RIDDEN = NmsIdentifierUtil.tagKey(
            Registries.ENTITY_TYPE, "minecraft:can_float_while_ridden");
    private static final TagKey<Fluid> ENTITY_FLOATABLE = NmsIdentifierUtil.tagKey(
            Registries.FLUID, "minecraft:entity_floatable");

    private ClientFluidQueries() {}

    public static boolean usesTagBasedFluidRules(CultPlayer player) {
        return !player.isBedrockMovement() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3);
    }

    public static boolean is(CultPlayer player, FluidState state, TagKey<Fluid> tag) {
        return state.is(tag);
    }

    /** Classify one compensated cell without changing the caller's body-query bounds. */
    public static Sample sample(CultPlayer player, BlockData block, BlockPos pos) {
        if (usesTagBasedFluidRules(player)) {
            FluidState fluid = player.compensatedWorld.getFluidState(pos);
            if (fluid.isEmpty()) return Sample.EMPTY;
            return new Sample(fluid.is(FluidTags.WATER),
                    fluid.is(FluidTags.LAVA),
                    fluid.getHeight(player.compensatedWorld, pos));
        }
        // Older clients and Bedrock retain the existing material and height rules,
        // including waterlogged blocks and the amount/9 height calculation.
        if (NmsBlockTags.isWater(block)) {
            return new Sample(true, false, player.compensatedWorld.getWaterFluidLevelAt(pos));
        }
        if (block.getMaterial() == Material.LAVA) {
            return new Sample(false, true, player.compensatedWorld.getLavaFluidLevelAt(pos));
        }
        return Sample.EMPTY;
    }

    public record Sample(boolean water, boolean lava, double height) {
        private static final Sample EMPTY = new Sample(false, false, 0.0D);
    }

    public static boolean eyesInWater(CultPlayer player, SimpleCollisionBox body, Vec3 feet, double eyeY) {
        if (usesTagBasedFluidRules(player)) {
            return eyesInside(player, body, feet, eyeY, FluidTags.WATER);
        }
        double legacyEyeY = eyeY - 0.1111111119389534D;
        return legacyEyeY < (float) Math.floor(legacyEyeY)
                + player.compensatedWorld.getWaterFluidLevelAt(feet.x, legacyEyeY, feet.z);
    }

    public static DesyncStatus floatWhileRidden(CultPlayer player, SimulationContext context,
                                               boolean inWater, double waterDepth, boolean waterTimingUncertain) {
        var vehicle = context.getVehicle();
        if (vehicle == null) return DesyncStatus.FALSE;
        // LivingEntity#floatInLiquidWhileRidden reads the pre-travel body depth.
        // 26.3 selects both the entity and the fluid through synchronized tags.
        if (usesTagBasedFluidRules(player)) {
            return DesyncStatus.fromBoolean(vehicle.type.builtInRegistryHolder().is(CAN_FLOAT_WHILE_RIDDEN)
                    && depth(player, context.getFromMinimumExtent(), ENTITY_FLOATABLE) > 0.4D);
        }
        if (!EntityTypeUtil.canFloatWhileRidden(vehicle.type) || !(waterDepth > 0.4D)) return DesyncStatus.FALSE;
        return waterTimingUncertain ? DesyncStatus.UNKNOWN : DesyncStatus.fromBoolean(inWater);
    }

    public static int liquidMaximum(ClientVersion version, double maximum) {
        return version.isNewerThanOrEquals(ClientVersion.V_26_3)
                ? (int) Math.floor(maximum) : (int) Math.ceil(maximum) - 1;
    }

    public static boolean containsAnyLiquid(BlockGetter world, SimpleCollisionBox box, ClientVersion version) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(box.minX); x <= liquidMaximum(version, box.maxX); x++) {
            for (int y = (int) Math.floor(box.minY); y <= liquidMaximum(version, box.maxY); y++) {
                for (int z = (int) Math.floor(box.minZ); z <= liquidMaximum(version, box.maxZ); z++) {
                    if (!world.getFluidState(pos.set(x, y, z)).isEmpty()) return true;
                }
            }
        }
        return false;
    }

    public static double depth(CultPlayer player, SimpleCollisionBox entityBox, TagKey<Fluid> tag) {
        SimpleCollisionBox box = entityBox.copy().expand(-0.001D);
        double depth = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(box.minX); x < Math.ceil(box.maxX); x++) {
            for (int y = (int) Math.floor(box.minY); y < Math.ceil(box.maxY); y++) {
                for (int z = (int) Math.floor(box.minZ); z < Math.ceil(box.maxZ); z++) {
                    FluidState fluid = player.compensatedWorld.getFluidState(pos.set(x, y, z));
                    if (fluid.isEmpty() || !fluid.is(tag)) continue;
                    double top = (double) y + fluid.getHeight(player.compensatedWorld, pos);
                    if (top >= box.minY) depth = Math.max(depth, top - entityBox.minY);
                }
            }
        }
        return depth;
    }

    public static boolean eyesInside(CultPlayer player, SimpleCollisionBox body, Vec3 feet, double eyeY, TagKey<Fluid> tag) {
        SimpleCollisionBox box = body.copy().expand(-0.001D);
        int x = (int) Math.floor(feet.x), z = (int) Math.floor(feet.z);
        if (x < Math.floor(box.minX) || x >= Math.ceil(box.maxX)
                || z < Math.floor(box.minZ) || z >= Math.ceil(box.maxZ)) return false;
        for (int y = (int) Math.floor(box.minY); y < Math.ceil(box.maxY); y++) {
            BlockPos pos = new BlockPos(x, y, z);
            FluidState fluid = player.compensatedWorld.getFluidState(pos);
            if (fluid.isEmpty() || !fluid.is(tag)) continue;
            float ordinaryHeight = fluid.getHeight(player.compensatedWorld, pos);
            if ((double) y + ordinaryHeight < box.minY || eyeY < y) continue;
            BlockPos above = pos.above();
            float eyeHeight = fluid.isSource() && player.compensatedWorld.getBlockState(above)
                    .isFaceSturdy(player.compensatedWorld, above, Direction.DOWN) ? 1.0F : ordinaryHeight;
            if (eyeY <= (double) y + eyeHeight) return true;
        }
        return false;
    }
}
