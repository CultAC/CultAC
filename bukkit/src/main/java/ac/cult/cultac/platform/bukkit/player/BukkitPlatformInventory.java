package ac.cult.cultac.platform.bukkit.player;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.platform.api.player.PlatformInventory;
import lombok.RequiredArgsConstructor;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

@RequiredArgsConstructor
public class BukkitPlatformInventory implements PlatformInventory {

    private final @NotNull Player bukkitPlayer;

    @Override
    public SimItemStack getStack(int bukkitSlot, int vanillaSlot) {
        return capture(CraftItemStack.asNMSCopy(bukkitPlayer.getInventory().getItem(bukkitSlot)));
    }

    @Override
    public SimItemStack getMainHand() {
        return capture(CraftItemStack.asNMSCopy(bukkitPlayer.getInventory().getItemInMainHand()));
    }

    @Override
    public SimItemStack getOffHand() {
        return capture(CraftItemStack.asNMSCopy(bukkitPlayer.getInventory().getItemInOffHand()));
    }

    private net.minecraft.core.RegistryAccess captureSource;
    private BukkitItemCapture itemCapture;

    private SimItemStack capture(net.minecraft.world.item.ItemStack stack) {
        var access = net.minecraft.server.MinecraftServer.getServer().registryAccess();
        if (captureSource != access) {
            itemCapture = new BukkitItemCapture(
                    access,
                    ac.cult.cultac.protocol.ProtocolVersion.of(net.minecraft.SharedConstants.getProtocolVersion()));
            captureSource = access;
        }
        return itemCapture.stack(stack);
    }
}
