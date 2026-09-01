package ac.grim.grimac.platform.bukkit.player;

import ac.grim.grimac.platform.api.player.PlatformInventory;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

@RequiredArgsConstructor
public class BukkitPlatformInventory implements PlatformInventory {

    private final @NotNull Player bukkitPlayer;

    @Override
    public ItemStack getStack(int bukkitSlot, int vanillaSlot) {
        return bukkitPlayer.getInventory().getItem(bukkitSlot);
    }
}
