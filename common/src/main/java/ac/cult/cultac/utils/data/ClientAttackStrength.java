package ac.cult.cultac.utils.data;

import ac.cult.blocksim.data.ItemComponents;
import ac.cult.blocksim.engine.SimItemStack;

/**
 * Bounds on the Java client's Player#attackStrengthTicker. Player#tick increments it once per client tick and resets
 * it when the main-hand item changes to a different item; MultiPlayerGameMode#attack and Player#onAttack reset it.
 * A main-hand swing may be a Minecraft#startAttack miss, which resets it, and the claimed held item can trail the
 * client's selected slot by a tick, so those only lower the minimum. Ticks are counted at ServerboundClientTickEnd.
 */
public final class ClientAttackStrength {
    private static final int SATURATED = 1 << 20;

    private int minTicker;
    private int maxTicker;
    private boolean possibleMainHandChange;
    private int unconfirmedHotbarSlots;
    // Player#lastItemInMainHand
    private SimItemStack lastMainHand = SimItemStack.EMPTY;

    /**
     * Player#tick: the ticker increments, then a different main-hand item resets it. A slot the client claimed this
     * tick may have been selected by a hotbar key in the previous tick, which reset there and incremented here. Any
     * other change may be the server confirming a client-side change from an earlier tick, so it only lowers the
     * minimum.
     */
    public void onClientTick(SimItemStack mainHand, boolean claimedSlotChanged) {
        minTicker = Math.min(minTicker + 1, SATURATED);
        maxTicker = Math.min(maxTicker + 1, SATURATED);
        // A proven use-completion packet can replace the last item. Player#tick, not the packet handler,
        // resets the counter. An attack before that tick still samples the previous counter.
        if (possibleMainHandChange) minTicker = 0;
        possibleMainHandChange = false;
        if (!lastMainHand.matches(mainHand)) {
            if (!isSameItem(lastMainHand, mainHand)) {
                minTicker = 0;
                if (claimedSlotChanged) maxTicker = Math.min(maxTicker, 1);
            }
            lastMainHand = mainHand.copy();
        }
    }

    /** A different claimed main-hand item before this tick's Player#tick may have reset in the previous tick. */
    public void onClaimedMainHand(SimItemStack mainHand) {
        if (!isSameItem(lastMainHand, mainHand)) {
            minTicker = 0;
            possibleMainHandChange = true;
        }
    }

    /** MultiPlayerGameMode#attack after Player#attack, or Player#onAttack for a STAB. */
    public void onReset() {
        minTicker = 0;
        maxTicker = 0;
    }

    /**
     * A reset this model cannot prove: a main-hand swing (Minecraft#startAttack miss), ABORT_DESTROY_BLOCK (only
     * MultiPlayerGameMode#stopDestroyBlock resets), or a main-hand change the client makes before the server confirms
     * it (instant item use or inventory clicks).
     */
    public void onPossibleReset() {
        minTicker = 0;
    }

    /** A client-visible item replacement whose effect on the selected hand is not yet provable. */
    public void onPossibleMainHandChange() {
        possibleMainHandChange = true;
    }

    /** A local replacement not yet reconstructed by compensated inventory. */
    public void onUnconfirmedMainHandChange(int slot) {
        onPossibleReset();
        onPossibleMainHandChange();
        markUnconfirmedHotbarSlot(slot);
    }

    public void markUnconfirmedHotbarSlot(int slot) {
        if (slot >= 0 && slot < 9) unconfirmedHotbarSlots |= 1 << slot;
    }

    public void confirmHotbarSlot(int slot) {
        if (slot >= 0 && slot < 9) unconfirmedHotbarSlots &= ~(1 << slot);
    }

    public boolean hasUnconfirmedHotbarChange() {
        return unconfirmedHotbarSlots != 0;
    }

    /** Item#finishUsingItem -> Consumable#onConsume -> ItemStack's UseRemainder, on modern Java clients. */
    public static boolean changesItemOnUseCompletion(SimItemStack stack, boolean infiniteMaterials) {
        if (infiniteMaterials
                || stack.isEmpty()
                || stack.getCount() != 1
                || !stack.components().has("minecraft:consumable")) return false;
        var remainder = ItemComponents.useRemainder(stack.components());
        return remainder == null || !remainder.itemKey().equals(stack.itemKey());
    }

    /** A new LocalPlayer starts with a zero ticker and an empty lastItemInMainHand. */
    public void reset() {
        onReset();
        possibleMainHandChange = false;
        unconfirmedHotbarSlots = 0;
        lastMainHand = SimItemStack.EMPTY;
    }

    /**
     * Whether Player#getAttackStrengthScale(0.5F) > 0.9F is possible with the shortest delay. The maximum only holds
     * when every client tick is counted, which requires ServerboundClientTickEnd.
     */
    public boolean canBeFullStrength(float minDelay) {
        return scale(maxTicker, minDelay) > 0.9F;
    }

    /** Whether Player#getAttackStrengthScale(0.5F) > 0.9F holds even with the longest delay. */
    public boolean isFullStrength(float maxDelay) {
        return scale(minTicker, maxDelay) > 0.9F;
    }

    // Player#getCurrentItemAttackStrengthDelay
    public static float delay(double attackSpeed) {
        return (float) (1.0 / attackSpeed * 20.0);
    }

    // Player#getAttackStrengthScale
    private static float scale(int ticker, float delay) {
        return Math.min(Math.max((ticker + 0.5F) / delay, 0.0F), 1.0F);
    }

    // ItemStack#isSameItem
    private static boolean isSameItem(SimItemStack first, SimItemStack second) {
        return first.itemKey().equals(second.itemKey());
    }
}
