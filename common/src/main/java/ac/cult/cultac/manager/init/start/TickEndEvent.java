package ac.cult.cultac.manager.init.start;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.movement.GhostBlockMitigator;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.lists.HookedListWrapper;
import ac.cult.cultac.network.protocol.util.reflection.Reflection;
import ac.cult.cultac.network.protocol.util.SpigotReflectionUtil;
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
            if (player.isDisabled()) { continue; } // If we aren't active don't spam extra transactions
            player.runSafely(() -> player.checkManager.getSimulationProcessor().processQueuedAuthoredInput());
            player.runSafely(() -> player.onEndOfTickEvent());
            final GhostBlockMitigator ghostBlockMitigator = player.getGhostBlockMitigator();
            ghostBlockMitigator.onEndOfTickEvent(); player.getServerStateNoSlow().tick();
        }
    }

    @Override
    public void start() {
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
