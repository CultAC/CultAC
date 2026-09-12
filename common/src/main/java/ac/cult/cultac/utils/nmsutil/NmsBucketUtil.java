package ac.cult.cultac.utils.nmsutil;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Preserves native bucket behavior across the nullable Player-to-LivingEntity API change. */
public final class NmsBucketUtil {
    private static final Method CONTENT_GETTER;
    private static final Field CONTENT_FIELD;
    private static final Method EMPTY = resolveUserMethod(BucketItem.class, "emptyContents",
            Level.class, BlockPos.class, BlockHitResult.class);
    private static final Method PICKUP = resolveUserMethod(BucketPickup.class, "pickupBlock",
            LevelAccessor.class, BlockPos.class, BlockState.class);

    static {
        Method getter = null;
        Field field = null;
        try {
            try {
                getter = BucketItem.class.getMethod("getContent");
            } catch (NoSuchMethodException legacy) {
                field = BucketItem.class.getDeclaredField("content");
                field.setAccessible(true);
            }
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
        CONTENT_GETTER = getter;
        CONTENT_FIELD = field;
    }

    private NmsBucketUtil() {
    }

    private static Method resolveUserMethod(Class<?> owner, String name, Class<?>... arguments) {
        Class<?>[] parameters = new Class<?>[arguments.length + 1];
        System.arraycopy(arguments, 0, parameters, 1, arguments.length);
        parameters[0] = LivingEntity.class;
        try {
            try {
                return owner.getMethod(name, parameters);
            } catch (NoSuchMethodException legacy) {
                parameters[0] = Player.class;
                return owner.getMethod(name, parameters);
            }
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    public static Fluid content(BucketItem bucket) {
        try {
            return (Fluid) (CONTENT_GETTER == null ? CONTENT_FIELD.get(bucket) : CONTENT_GETTER.invoke(bucket));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to read native bucket content", exception);
        }
    }

    public static boolean emptyContents(BucketItem bucket, Level level, BlockPos pos, BlockHitResult hit) {
        try {
            return (boolean) EMPTY.invoke(bucket, null, level, pos, hit);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Native bucket placement failed", exception);
        }
    }

    public static ItemStack pickupBlock(BucketPickup bucket, LevelAccessor level, BlockPos pos, BlockState state) {
        try {
            return (ItemStack) PICKUP.invoke(bucket, null, level, pos, state);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Native bucket pickup failed", exception);
        }
    }
}
