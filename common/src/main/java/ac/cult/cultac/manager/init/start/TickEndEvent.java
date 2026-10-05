package ac.cult.cultac.manager.init.start;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.movement.GhostBlockMitigator;
import ac.cult.cultac.platform.api.Platform;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.lists.HookedListWrapper;
import ac.cult.cultac.network.protocol.util.reflection.Reflection;
import ac.cult.cultac.network.protocol.util.SpigotReflectionUtil;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

// Copied from: https://github.com/ThomasOM/Pledge/blob/master/src/main/java/dev/thomazz/pledge/inject/ServerInjector.java
@SuppressWarnings(value = {"unchecked", "deprecated"})
public class TickEndEvent implements StartableInitable {
    boolean hasTicked = true;

    private static void fireEndOfTick() {
        for (CultPlayer player : CultAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            fireEndOfTick(player);
        }
    }

    // Folia has no server-wide tick: each region ticks its own players' connections and then fires
    // ServerTickEndEvent on its own thread, so end the tick only for the players this region owns.
    private static void fireEndOfRegionTick() {
        for (CultPlayer player : CultAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            Player bukkitPlayer = player.bukkitPlayer;
            if (bukkitPlayer == null || !Bukkit.isOwnedByCurrentRegion(bukkitPlayer)) continue;
            fireEndOfTick(player);
        }
    }

    private static void fireEndOfTick(CultPlayer player) {
        if (player.isDisabled()) { return; } // If we aren't active don't spam extra transactions
        player.runSafely(() -> player.onEndOfTickEvent());
        final GhostBlockMitigator ghostBlockMitigator = player.getGhostBlockMitigator();
        ghostBlockMitigator.onEndOfTickEvent();
        player.getServerStateNoSlow().tick();
    }

    @Override
    public void start() {
        if (CultAPI.INSTANCE.getPlatform() == Platform.FOLIA) {
            // folia always flushes, no point trying to optimize this
            Bukkit.getPluginManager().registerEvent(ServerTickEndEvent.class, new Listener() {}, EventPriority.MONITOR,
                    (listener, event) -> {
                        // If there is an error, the region crashes. Don't crash the region.
                        try {
                            fireEndOfRegionTick();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }, CultAPI.INSTANCE.getPlugin());
            return;
        }

        // Inject so we can add the final transaction pre-flush event
        try {
            Object connection = SpigotReflectionUtil.getMinecraftServerConnectionInstance();

            Field connectionsList = Reflection.getField(connection.getClass(), List.class, 1);
            List<Object> endOfTickObject = (List<Object>) connectionsList.get(connection);

            // Use a list wrapper to check when the size method is called
            // Unsure why synchronized is needed because the object itself gets synchronized
            // but whatever.  At least plugins can't break it, I guess.
            //
            // Pledge injects into another list, so we should be safe injecting into this one
            List<?> wrapper = Collections.synchronizedList(new HookedListWrapper<Object>(endOfTickObject) {
                @Override
                public void onIterator() {
                    hasTicked = true;
                    // If there is an error, the server crashes. Don't crash the server.
                    try {
                        fireEndOfTick();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });

            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) unsafeField.get(null);
            unsafe.putObject(connection, unsafe.objectFieldOffset(connectionsList), wrapper);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
    }
}
