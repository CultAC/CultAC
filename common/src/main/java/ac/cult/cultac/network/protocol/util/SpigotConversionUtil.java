package ac.cult.cultac.network.protocol.util;

import ac.cult.cultac.utils.nmsutil.NmsBlockTags;
import org.bukkit.block.data.BlockData;
import net.minecraft.network.HashedStack;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class SpigotConversionUtil {
    private static final Method BLOCK_STATE_TO_BLOCK_DATA = resolveBlockStateToBlockData();
    private static final Method NMS_ITEM_TO_BUKKIT_COPY = resolveNmsItemToBukkitCopy();

    private SpigotConversionUtil() {
    }

    public static net.minecraft.world.item.ItemStack toNmsItemStack(ItemStack stack) {
        return stack == null ? net.minecraft.world.item.ItemStack.EMPTY : CraftItemStack.asNMSCopy(stack);
    }

    /**
     * Equivalent to {@code toNmsItemStack(stack).getItem()} without copying the stack and its
     * component map: empty stacks (AIR or amount <= 0) map to AIR, as {@code ItemStack.EMPTY} does.
     */
    public static net.minecraft.world.item.Item toNmsItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return net.minecraft.world.item.Items.AIR;
        }
        net.minecraft.world.item.Item item = CraftMagicNumbers.getItem(stack.getType());
        return item == null ? net.minecraft.world.item.Items.AIR : item;
    }

    public static ItemStack fromNmsItemStack(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.empty();
        }

        try {
            return (ItemStack) NMS_ITEM_TO_BUKKIT_COPY.invoke(null, stack);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to access the Paper ItemStack copy method", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Paper ItemStack copy failed", cause);
        }
    }

    private static Method resolveNmsItemToBukkitCopy() {
        // Paper 26.3 replaced asBukkitCopy(ItemStack) with asBukkitCopy(ItemInstance).
        // Resolve either signature without linking older runtimes to ItemInstance.
        // Keep copy semantics: a mirror would retain the mutable packet stack.
        for (Method method : CraftItemStack.class.getMethods()) {
            if (method.getName().equals("asBukkitCopy")
                    && Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(net.minecraft.world.item.ItemStack.class)
                    && ItemStack.class.isAssignableFrom(method.getReturnType())) {
                return method;
            }
        }
        throw new IllegalStateException("Unable to resolve Paper ItemStack to Bukkit copy method");
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
