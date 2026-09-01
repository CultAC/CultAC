package ac.grim.grimac.platform.bukkit.initables;

import ac.grim.grimac.events.bukkit.PlaceEvents;
import ac.grim.grimac.events.bukkit.QuitEvent;
import ac.grim.grimac.manager.init.start.StartableInitable;
import ac.grim.grimac.platform.bukkit.GrimACBukkitLoaderPlugin;
import org.bukkit.Bukkit;

public class BukkitEventManager implements StartableInitable {
    public void start() {
        Bukkit.getPluginManager().registerEvents(new PlaceEvents(), GrimACBukkitLoaderPlugin.LOADER);
        Bukkit.getPluginManager().registerEvents(new QuitEvent(), GrimACBukkitLoaderPlugin.LOADER);
    }
}
