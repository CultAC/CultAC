package ac.grim.grimac.network.protocol.util;

import ac.grim.grimac.utils.nmsutil.NmsBlockTags;
import org.bukkit.block.data.BlockData;
import net.minecraft.network.HashedStack;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class SpigotConversionUtil {
    private static final Method BLOCK_STATE_TO_BLOCK_DATA = resolveBlockStateToBlockData();

    private SpigotConversionUtil() {
    }

    public static net.minecraft.world.item.ItemStack toNmsItemStack(ItemStack stack) {
        return stack == null ? net.minecraft.world.item.ItemStack.EMPTY : CraftItemStack.asNMSCopy(stack);
    }

    public static ItemStack fromNmsItemStack(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.empty();
        }

        return CraftItemStack.asBukkitCopy(stack);
    }

    public static ItemStack fromHashedStack(HashedStack stack) {
        if (stack == null || stack == HashedStack.EMPTY) {
            return ItemStack.empty();
        }
        if (!(stack instanceof HashedStack.ActualItem actualItem)) {
            return ItemStack.empty();
        }

        return fromNmsItemStack(new net.minecraft.world.item.ItemStack(actualItem.item(), actualItem.count()));
    }

    public static BlockData fromBukkitBlockData(BlockData data) {
        if (data == null) {
            return Material.AIR.createBlockData();
        }
        return fromNmsBlockState(NmsBlockTags.toNmsState(data)).clone();
    }

    public static BlockData fromNmsBlockState(net.minecraft.world.level.block.state.BlockState state) {
        try {
            return (BlockData) BLOCK_STATE_TO_BLOCK_DATA.invoke(state);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to access the Paper BlockState conversion method", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Paper BlockState conversion failed", cause);
        }
    }

    private static Method resolveBlockStateToBlockData() {
        Class<?> stateClass = net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase.class;
        for (String name : new String[]{"asBlockData", "createCraftBlockData"}) {
            try {
                Method method = stateClass.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Continue to the name used by the other supported Paper server generations.
            }
        }
        throw new IllegalStateException("Unable to resolve Paper BlockState to BlockData conversion method");
    }
}
