package ac.cult.cultac.utils.lists;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.inventory.InventoryStorage;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The player's inventory model, corrected against the server's observed inventory.
 *
 * <p>Cult mirrors client predictions (clicks, placements, item use) into this model. Vanilla's
 * container synchronizer corrects only slots where the server's contents differ from what it
 * believes the client holds, so a prediction the server rejects without a correction leaves the
 * client desynchronized (MC-99075). Once every inventory packet already sent has been applied, the
 * server's observed contents are authoritative; a slot that still differs is resent from them.
 * Nothing here reads the live server inventory.
 */
public class CorrectingPlayerInventoryStorage extends InventoryStorage {
    // Ticks to wait after a change so the server's response to the same prediction has been sent.
    private static final int SETTLE_TICKS = 5;
    private static final long RECENT_LOCAL_USE_MILLIS = 1000;

    private final CultPlayer player;
    // Slot ID -> tick at which a recently changed slot is compared with the server
    private final Map<Integer, Integer> pendingFinalizedSlot = new ConcurrentHashMap<>();

    public CorrectingPlayerInventoryStorage(CultPlayer player, int size) {
        super(size);
        this.player = player;
    }

    @Override
    public void setItem(int item, SimItemStack stack) {
        pendingFinalizedSlot.put(item, CultAPI.INSTANCE.getTickManager().currentTick + SETTLE_TICKS);
        super.setItem(item, stack);
    }

    /** Owner thread only: compares and corrects through the outbound proof path. */
    public void tickServerView() {
        if (player.isBedrockMovement()) {
            pendingFinalizedSlot.clear();
            return;
        }

        int tickID = CultAPI.INSTANCE.getTickManager().currentTick;
        for (Iterator<Map.Entry<Integer, Integer>> it =
                        pendingFinalizedSlot.entrySet().iterator();
                it.hasNext(); ) {
            Map.Entry<Integer, Integer> entry = it.next();
            if (entry.getValue() <= tickID && checkThatServerIsSynced(entry.getKey())) {
                it.remove();
            }
        }

        // Every five ticks, check one more slot so in-place stack changes are also reconciled.
        // Skip right after a local placement or use: its stack change may be shrunk in place, and the
        // server's echo of an accepted change can still be on its way.
        boolean recentLocalUse = System.currentTimeMillis() - player.lastBlockPlaceUseItem < RECENT_LOCAL_USE_MILLIS;
        if (tickID % 5 == 0 && !recentLocalUse) {
            int slotToCheck = (tickID / 5) % getSize();
            if (!pendingFinalizedSlot.containsKey(slotToCheck)) {
                checkThatServerIsSynced(slotToCheck);
            }
        }
    }

    /** Returns false while server packets are still in flight, so the check is retried. */
    private boolean checkThatServerIsSynced(int slot) {
        SimItemStack serverside = player.getInventory().settledServerSlot(slot);
        if (serverside == null) {
            return !player.getInventory().hasPendingServerInventory();
        }
        SimItemStack existing = getItem(slot);
        if ((existing.getItem() != serverside.getItem() || existing.getCount() != serverside.getCount())
                && !CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
            player.getInventory().correctPlayerSlot(slot);
        }
        return true;
    }
}
