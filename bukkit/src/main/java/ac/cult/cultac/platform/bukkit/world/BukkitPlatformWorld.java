package ac.cult.cultac.platform.bukkit.world;

import ac.cult.cultac.platform.api.world.PlatformWorld;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

public record BukkitPlatformWorld(@NotNull World bukkitWorld) implements PlatformWorld {

    @Override
    public String getName() {
        return bukkitWorld.getName();
    }

    @Override
    public @NotNull UUID getUID() {
        return this.bukkitWorld.getUID();
    }

    @Override
    public boolean isLoaded() {
        return Bukkit.getWorld(bukkitWorld.getUID()) != null;
    }
}
