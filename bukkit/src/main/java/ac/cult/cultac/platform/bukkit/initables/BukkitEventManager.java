package ac.cult.cultac.platform.bukkit.initables;

import ac.cult.cultac.events.bukkit.PlaceEvents;
import ac.cult.cultac.events.bukkit.QuitEvent;
import ac.cult.cultac.manager.init.start.StartableInitable;
import ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin;
import org.bukkit.Bukkit;

public class BukkitEventManager implements StartableInitable {
    public void start() {
        Bukkit.getPluginManager().registerEvents(new PlaceEvents(), CultACBukkitLoaderPlugin.LOADER);
        Bukkit.getPluginManager().registerEvents(new QuitEvent(), CultACBukkitLoaderPlugin.LOADER);
    }
}
