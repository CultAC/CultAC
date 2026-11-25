package ac.cult.cultac.bedrock.bridge;

import java.lang.reflect.Field;
import java.util.Objects;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.BlockBreakHandler;
import org.jetbrains.annotations.Nullable;

/** The dig BlockBreakHandler holds open for a Bedrock client. */
final class GeyserBlockBreaking {
    private static final @Nullable Field SERVER_SIDE = field("serverSideBlockBreaking");
    private static final @Nullable Field ITEM = field("currentItemStack");

    private GeyserBlockBreaking() {}

    /**
     * BlockBreakHandler#tick continues the open dig and sends the server nothing, unless Geyser breaks the block itself
     * (serverSideBlockBreaking), which finishes the dig, or the held item is no longer the dig's (sameItemStack), which
     * restarts it. False when the handler cannot be read.
     */
    static boolean tickSendsNothing(GeyserSession session, BlockBreakHandler breaking) {
        if (SERVER_SIDE == null || ITEM == null) return false;
        try {
            if (SERVER_SIDE.getBoolean(breaking)) return false;
            GeyserItemStack dug = (GeyserItemStack) ITEM.get(breaking);
            if (dug == null) return false;
            GeyserItemStack held = session.getPlayerInventory().getItemInHand();
            if (dug.isEmpty() && held.isEmpty()) return true;
            return dug.getJavaId() == held.getJavaId() && Objects.equals(held.getComponents(), dug.getComponents());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    private static @Nullable Field field(String name) {
        try {
            Field field = BlockBreakHandler.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }
}
