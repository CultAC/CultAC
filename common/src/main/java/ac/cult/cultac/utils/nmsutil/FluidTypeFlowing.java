package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.ViaClientBlockShapeMappings;
import ac.cult.cultac.network.protocol.ClientVersion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.bukkit.util.Vector;

public class FluidTypeFlowing {
    private static final TagKey<Block> BLOCKS_FLUID_FLOW = NmsIdentifierUtil.tagKey(
            Registries.BLOCK, "minecraft:blocks_fluid_flow");

    public static Vector getFlow(CultPlayer player, int originalX, int originalY, int originalZ) {
        BlockPos pos = new BlockPos(originalX, originalY, originalZ);
        FluidState fluidState = player.compensatedWorld.getFluidState(pos);
        if (fluidState.isEmpty()) return new Vector();

        Vec3 flow = flow(player, pos, fluidState);
        return new Vector(flow.x, flow.y, flow.z);
    }

    public static Vec3 flow(CultPlayer player, BlockPos pos, FluidState state) {
        if (!player.isBedrockMovement() && player.getClientVersion().isOlderThan(ClientVersion.V_1_13)) {
            return legacyFlow(player, pos, state);
        }
        if (!ClientFluidQueries.usesTagBasedFluidRules(player)) {
            return state.getFlow(player.compensatedWorld, pos);
        }
        if (!(state.getType() instanceof FlowingFluid)) return Vec3.ZERO;
        double x = 0, z = 0;
        var world = player.compensatedWorld;
        // RC1 FlowingFluid#getFlow: float height differences accumulate into doubles.
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.relative(direction);
            FluidState other = world.getFluidState(neighbor);
            if (!other.isEmpty() && !other.getType().isSame(state.getType())) continue;
            float height = other.getOwnHeight();
            float distance = 0;
            if (height == 0) {
                if (!world.getBlockState(neighbor).getBlock().builtInRegistryHolder().is(BLOCKS_FLUID_FLOW)) {
                    other = world.getFluidState(neighbor.below());
                    if (other.isEmpty() || other.getType().isSame(state.getType())) {
                        height = other.getOwnHeight();
                        if (height > 0) distance = state.getOwnHeight() - (height - 0.8888889F);
                    }
                }
            } else if (height > 0) {
                distance = state.getOwnHeight() - height;
            }
            if (distance != 0) {
                x += direction.getStepX() * distance;
                z += direction.getStepZ() * distance;
            }
        }
        Vec3 flow = new Vec3(x, 0, z);
        if (state.getValue(FlowingFluid.FALLING)) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos neighbor = pos.relative(direction);
                if (solidFace(player, neighbor, direction, state) || solidFace(player, neighbor.above(), direction, state)) {
                    flow = flow.normalize().add(0, -6, 0);
                    break;
                }
            }
        }
        return flow.normalize();
    }

    // Pre-flattening BlockLiquid uses integer metadata differences before normalization.
    private static Vec3 legacyFlow(CultPlayer player, BlockPos pos, FluidState state) {
        if (state.isEmpty()) return Vec3.ZERO;
        int level = legacyLevel(state, state);
        Vec3 flow = Vec3.ZERO;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.relative(direction);
            int other = legacyLevel(player.compensatedWorld.getFluidState(neighbor), state);
            int distance = 0;
            if (other < 0) {
                var block = player.compensatedWorld.getBlockState(neighbor);
                if ((block.getBlock() == net.minecraft.world.level.block.Blocks.COBWEB || !ViaClientBlockShapeMappings.legacyMaterialIsSolid(player, block))) {
                    other = legacyLevel(player.compensatedWorld.getFluidState(neighbor.below()), state);
                    if (other >= 0) distance = other - (level - 8);
                }
            } else distance = other - level;
            flow = flow.add(direction.getStepX() * distance, 0, direction.getStepZ() * distance);
        }
        if (state.getValue(FlowingFluid.FALLING)) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos neighbor = pos.relative(direction);
                if (legacySolid(player, neighbor, state) || legacySolid(player, neighbor.above(), state)) {
                    flow = flow.normalize().add(0, -6, 0);
                    break;
                }
            }
        }
        return flow.normalize();
    }

    private static int legacyLevel(FluidState state, FluidState source) {
        if (state.isEmpty() || !state.getType().isSame(source.getType())) return -1;
        return state.getValue(FlowingFluid.FALLING) ? 0 : 8 - state.getAmount();
    }

    private static boolean legacySolid(CultPlayer player, BlockPos pos, FluidState source) {
        var state = player.compensatedWorld.getBlockState(pos);
        Block block = state.getBlock();
        if (player.compensatedWorld.getFluidState(pos).getType().isSame(source.getType()) || block instanceof IceBlock) {
            return false;
        }
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_12)) {
            return ViaClientBlockShapeMappings.legacyMaterialIsSolid(player, state);
        }

        // 1.12 BlockLiquid#causesDownwardCurrent excludes stairs and every
        // Block#isExceptBlockForAttachWithPiston entry before testing face shape.
        if (block instanceof StairBlock || block instanceof LeavesBlock || block instanceof ShulkerBoxBlock
                || block instanceof TrapDoorBlock || block instanceof StainedGlassBlock
                || block == Blocks.BEACON || block == Blocks.CAULDRON || block == Blocks.GLASS
                || block == Blocks.GLOWSTONE || block == Blocks.SEA_LANTERN
                || block == Blocks.PISTON || block == Blocks.STICKY_PISTON || block == Blocks.PISTON_HEAD) {
            return false;
        }
        // Only horizontal faces are queried here. In 1.12 those are SOLID for
        // full cubes (including double slabs) and soul sand. Snow layers remain
        // UNDEFINED horizontally even at eight layers; their modern box differs.
        return block == Blocks.SOUL_SAND || !(block instanceof SnowLayerBlock)
                && state.isCollisionShapeFullBlock(player.compensatedWorld, pos);
    }

    private static boolean solidFace(CultPlayer player, BlockPos pos, Direction direction, FluidState source) {
        var state = player.compensatedWorld.getBlockState(pos);
        return !state.getFluidState().getType().isSame(source.getType()) && !(state.getBlock() instanceof IceBlock)
                && state.isFaceSturdy(player.compensatedWorld, pos, direction);
    }
}
