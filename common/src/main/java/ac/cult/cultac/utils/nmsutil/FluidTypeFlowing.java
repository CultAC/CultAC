package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.player.CultPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.IceBlock;
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

    private static boolean solidFace(CultPlayer player, BlockPos pos, Direction direction, FluidState source) {
        var state = player.compensatedWorld.getBlockState(pos);
        return !state.getFluidState().getType().isSame(source.getType()) && !(state.getBlock() instanceof IceBlock)
                && state.isFaceSturdy(player.compensatedWorld, pos, direction);
    }
}
