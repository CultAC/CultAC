package ac.cult.cultac.platform.bukkit.manager;

import ac.cult.cultac.platform.api.manager.ItemResetHandler;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.value.Hand;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.world.entity.LivingEntity;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.Nullable;

public class BukkitItemResetHandler implements ItemResetHandler {
    private static final Method setLivingEntityFlag;

    @Override
    public void resetItemUsage(@Nullable PlatformPlayer player) {
        if (player == null) return;
        Player bukkitPlayer = (Player) player.getNative();
        try {
            setLivingEntityFlag.invoke(((CraftLivingEntity) bukkitPlayer).getHandle(), 1, false);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
        bukkitPlayer.clearActiveItem();
    }

    @Override
    public @Nullable Hand getItemUsageHand(@Nullable PlatformPlayer player) {
        if (player == null) return null;
        Player bukkitPlayer = (Player) player.getNative();
        if (!bukkitPlayer.isHandRaised()) return null;
        return bukkitPlayer.getHandRaised() == EquipmentSlot.OFF_HAND ? Hand.OFF_HAND : Hand.MAIN_HAND;
    }

    @Override
    public boolean isUsingItem(@Nullable PlatformPlayer player) {
        return player != null && ((Player) player.getNative()).isHandRaised();
    }

    static {
        try {
            setLivingEntityFlag = LivingEntity.class.getDeclaredMethod("setLivingEntityFlag", int.class, boolean.class);
            setLivingEntityFlag.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}
