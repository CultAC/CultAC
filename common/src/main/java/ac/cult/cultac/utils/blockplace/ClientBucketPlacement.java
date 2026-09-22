package ac.cult.cultac.utils.blockplace;

import ac.cult.cultac.utils.nmsutil.NmsBucketUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/** Vanilla BucketItem#emptyContents, without fork world configuration, events, or entities. */
final class ClientBucketPlacement {
    private static final Method ENVIRONMENT = environmentMethod();

    private ClientBucketPlacement() {
    }

    static boolean empty(Fluid fluid, Level level, BlockPos pos, @Nullable BlockHitResult hit, boolean secondaryUse) {
        if (!(fluid instanceof FlowingFluid flowing)) return false;
        BlockState state = level.getBlockState(pos);
        boolean replace = state.canBeReplaced(fluid);
        boolean accepts = replace || state.getBlock() instanceof LiquidBlockContainer container
                && NmsBucketUtil.canPlaceLiquid(container, level, pos, state, fluid);
        if (!(state.isAir() || accepts && (!secondaryUse || hit == null))) {
            return hit != null && empty(fluid, level, hit.getBlockPos().relative(hit.getDirection()), null, secondaryUse);
        }
        // Read only the detached level's environment, just as the native path did.
        if (fluid == Fluids.WATER && waterEvaporates(level, pos)) return true;
        if (state.getBlock() instanceof LiquidBlockContainer container && fluid == Fluids.WATER) {
            container.placeLiquid(level, pos, state, flowing.getSource(false));
            return true;
        }
        // Replacement drops, sounds and game events have no client block-state effect.
        return level.setBlock(pos, fluid.defaultFluidState().createLegacyBlock(), 11) || state.getFluidState().isSource();
    }

    private static @Nullable Method environmentMethod() {
        try {
            return Level.class.getMethod("environmentAttributes");
        } catch (NoSuchMethodException olderRuntime) {
            return null;
        }
    }

    // TODO: This method literally does nothing useful because we are reading an empty level
    private static boolean waterEvaporates(Level level, BlockPos pos) {
        try {
            if (ENVIRONMENT == null) {
                return (boolean) level.dimensionType().getClass().getMethod("ultraWarm").invoke(level.dimensionType());
            }
            // Environment attributes replaced DimensionType.ultraWarm in 1.21.11.
            // Reflect the reader's API because its return type changed again in 26.x.
            Class<?> attributes = Class.forName("net.minecraft.world.attribute.EnvironmentAttributes");
            Class<?> attribute = Class.forName("net.minecraft.world.attribute.EnvironmentAttribute");
            Object waterEvaporates = attributes.getField("WATER_EVAPORATES").get(null);
            return (boolean) ENVIRONMENT.getReturnType().getMethod("getValue", attribute, BlockPos.class)
                    .invoke(ENVIRONMENT.invoke(level), waterEvaporates, pos);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to read detached water evaporation behavior", exception);
        }
    }
}
