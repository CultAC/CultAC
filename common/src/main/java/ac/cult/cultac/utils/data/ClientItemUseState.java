package ac.cult.cultac.utils.data;

import ac.cult.blocksim.data.ItemComponents;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.value.Hand;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * LocalPlayer.startedUsingItem and the outstanding use obligation are different state.
 * LivingEntity.updatingUsingItem can silently clear the former without clearing the latter.
 * All transitions run on the ordered player packet stream, never the Bukkit tick thread.
 */
public final class ClientItemUseState {
    public record Snapshot(
            long session,
            int tick,
            int proof,
            float legalMultiplier,
            float requiredMultiplier,
            boolean canSprint,
            boolean required,
            boolean definitelyUsing) {
        public static final Snapshot NONE = new Snapshot(0, -1, -1, 1, 1, true, false, false);
    }

    public record ActiveUse(Hand hand, String itemKey) {}

    private record Use(Hand hand, SimItemStack stack, int remaining, SimItemStack boundStack) {}

    /** One LivingEntity.completeUsingItem outcome: the used hand and its native inventory slot. */
    public record Completion(Hand hand, int slot) {}

    /** Client outcomes of a completion event; {@code skipped} is a client that completes nothing. */
    public record Completions(List<Completion> completions, boolean skipped) {}

    private List<Use> candidates = List.of();
    private boolean inactive = true;
    private Use obligation;
    private long session;
    private int lastPreparedTick = Integer.MIN_VALUE;
    private Snapshot prepared = Snapshot.NONE;
    private final Set<SimItemStack> possiblyChanged = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean possiblyFull;

    /**
     * An uncertain start keeps the candidate while the client may equally not be using; the
     * server may equally have refused it, so it opens no use obligation.
     */
    public void start(Hand hand, SimItemStack stack, int remainingTicks, boolean certain) {
        if (stack.isEmpty()) return;
        // A predicted successful use is LocalPlayer.startUsingItem, not a server flag assignment.
        if (!inactive) return;
        Use use = new Use(hand, stack.copy(), remainingTicks, stack);
        candidates = List.of(use);
        inactive = !certain;
        if (!certain) return;
        obligation = use;
        session++;
    }

    public void serverFlags(boolean active, Hand hand, SimItemStack main, SimItemStack off, List<SimItemStack> hotbar) {
        if (!active) {
            release();
            return;
        }
        var next = new ArrayList<>(candidates);
        boolean couldNotStart = false;
        if (inactive) {
            for (SimItemStack stack : hand == Hand.MAIN_HAND ? hotbar : List.of(off)) {
                // LivingEntity.startUsingItem cannot start an empty hand, and an
                // unproven local completion may have emptied or replaced this stack.
                if (stack.isEmpty() || possiblyChanged(stack)) couldNotStart = true;
                if (!stack.isEmpty()) merge(next, new Use(hand, stack.copy(), duration(stack), stack));
            }
        }
        candidates = List.copyOf(next);
        inactive = couldNotStart || candidates.isEmpty();
        // Repeated metadata calls LocalPlayer's callback even when the byte is unchanged,
        // but does not restart an already active local hand or reset its timer.
        SimItemStack held = hand == Hand.MAIN_HAND ? main : off;
        if (obligation == null && !held.isEmpty()) {
            obligation = new Use(hand, held.copy(), duration(held), held);
            session++;
        }
    }

    public void release() {
        candidates = List.of();
        inactive = true;
        obligation = null;
        // Do not rewrite prepared: a later release cannot excuse this tick's earlier movement.
    }

    public void claimedSlotChanged() {
        // ServerGamePacketListenerImpl.handleSetCarriedItem stops MAIN_HAND use only.
        // The local client compares item types at its entity tick, not slot numbers.
        if (obligation != null && obligation.hand() == Hand.MAIN_HAND) obligation = null;
    }

    public Snapshot prepare(
            int tick,
            int proof,
            ClientVersion version,
            SimItemStack main,
            SimItemStack off,
            List<SimItemStack> hotbar) {
        if (lastPreparedTick == tick) return prepared;
        lastPreparedTick = tick;
        var next = new ArrayList<Use>();
        for (Use use : candidates) {
            // Minecraft.tick syncs the selected slot BEFORE handleKeybinds. Selection
            // during keybinds can therefore be unreported for this tick (and scroll
            // can restore it before the next sync). Only inventory-proven stacks apply.
            for (SimItemStack held : use.hand() == Hand.MAIN_HAND ? hotbar : List.of(off)) {
                if (!sameItem(use.stack(), held)) {
                    inactive = true;
                    continue;
                }
                merge(next, new Use(use.hand(), held.copy(), nextRemaining(use.remaining()), held));
                // BrushItem.onUseTick directly releases when its pre-travel ray misses.
                // The movement packet's look is sent AFTER that callback, so it cannot
                // prove the callback's earlier ray hit a block.
                if (held.is("minecraft:brush")) inactive = true;
            }
        }
        candidates = List.copyOf(next);
        if (candidates.isEmpty()) inactive = true;
        if (obligation != null) {
            SimItemStack held = obligation.hand() == Hand.MAIN_HAND ? main : off;
            if (sameItem(obligation.stack(), held))
                obligation = new Use(obligation.hand(), held.copy(), nextRemaining(obligation.remaining()), held);
        }
        float legal = inactive ? 1 : 0;
        boolean canSprint = inactive;
        for (Use use : candidates) {
            var effects = effects(use.stack(), version);
            legal = Math.max(legal, effects.speedMultiplier());
            canSprint |= effects.canSprint();
        }
        float required =
                obligation == null ? 1 : effects(obligation.stack(), version).speedMultiplier();
        prepared = new Snapshot(
                session,
                tick,
                proof,
                legal,
                required,
                canSprint,
                obligation != null && required < 1,
                !inactive && !candidates.isEmpty());
        return prepared;
    }

    public boolean definitelyUsing() {
        return !inactive && !candidates.isEmpty();
    }

    /**
     * LivingEntity.completeUsingItem runs only while the client is locally using, and only
     * for the same stack object bound at its last entity tick; otherwise it releases. Hotbar
     * slots other than the reported one are realities only while a hotbar change is unconfirmed.
     */
    public Completions completions(
            List<SimItemStack> hotbar, SimItemStack off, int selected, boolean unconfirmedHotbar) {
        var completions = new LinkedHashSet<Completion>();
        boolean skipped = inactive || candidates.isEmpty();
        for (Use use : candidates) {
            if (use.hand() == Hand.OFF_HAND) {
                if (use.boundStack() == off) completions.add(new Completion(Hand.OFF_HAND, 40));
                else skipped = true;
            }
        }
        if (candidates.stream().anyMatch(use -> use.hand() == Hand.MAIN_HAND)) {
            for (int slot = 0; slot < hotbar.size(); slot++) {
                if (slot != selected && !unconfirmedHotbar) continue;
                SimItemStack held = hotbar.get(slot);
                if (candidates.stream().anyMatch(use -> use.hand() == Hand.MAIN_HAND && use.boundStack() == held))
                    completions.add(new Completion(Hand.MAIN_HAND, slot));
                else skipped = true;
            }
        }
        return new Completions(List.copyOf(completions), skipped);
    }

    /** The client may hold different contents in this stack's slot after an unproven completion. */
    public void markPossiblyChanged(SimItemStack stack) {
        possiblyChanged.add(stack);
    }

    public boolean possiblyChanged(SimItemStack stack) {
        return possiblyChanged.contains(stack);
    }

    /** Received slot contents replace the stack object, which ends its stamp. */
    public void retainPossiblyChanged(List<SimItemStack> hotbar, SimItemStack off) {
        if (possiblyChanged.isEmpty()) return;
        Set<SimItemStack> held = Collections.newSetFromMap(new IdentityHashMap<>());
        held.addAll(hotbar);
        held.add(off);
        possiblyChanged.retainAll(held);
    }

    public void markPossiblyFull() {
        possiblyFull = true;
    }

    public boolean possiblyFull() {
        return possiblyFull;
    }

    /** ClientboundSetHealth sets FoodData's level, ending every local completion alternative. */
    public void foodReceived() {
        possiblyFull = false;
    }

    public Snapshot preparedAt(int tick) {
        return lastPreparedTick == tick ? prepared : null;
    }

    public boolean handKnown() {
        if (candidates.isEmpty()) return false;
        Hand hand = candidates.getFirst().hand();
        return candidates.stream().allMatch(use -> use.hand() == hand);
    }

    public Hand hand() {
        return candidates.isEmpty() ? Hand.MAIN_HAND : candidates.getFirst().hand();
    }

    /** The hand and item type every use candidate agrees on; null when there is no candidate or they differ. */
    public @Nullable ActiveUse activeUse() {
        if (candidates.isEmpty()) return null;
        Use first = candidates.getFirst();
        String itemKey = first.stack().itemKey();
        for (Use use : candidates) {
            if (use.hand() != first.hand() || !use.stack().itemKey().equals(itemKey)) return null;
        }
        return new ActiveUse(first.hand(), itemKey);
    }

    public boolean hasObligation() {
        return obligation != null;
    }

    public float obligationMultiplier(ClientVersion version) {
        return obligation == null ? 1 : effects(obligation.stack(), version).speedMultiplier();
    }

    public void reset() {
        release();
        lastPreparedTick = Integer.MIN_VALUE;
        prepared = Snapshot.NONE;
    }

    /** A LocalPlayer starts with fresh FoodData; no completion alternative carries over. */
    public void onNewLocalPlayer() {
        possiblyFull = false;
    }

    private static boolean sameItem(SimItemStack first, SimItemStack second) {
        return first.itemKey().equals(second.itemKey());
    }

    private static int nextRemaining(int remaining) {
        return remaining == Integer.MIN_VALUE ? remaining : remaining - 1;
    }

    public static int duration(SimItemStack stack) {
        // Item overrides precede generic components, including a consumable
        // component attached to a trident. Unknown instrument holders are not
        // assigned a guessed duration; action prediction supplies their timer.
        if (stack.is("minecraft:trident")
                || stack.is("minecraft:bow")
                || stack.is("minecraft:crossbow")
                || stack.is("minecraft:shield")) return 72000;
        if (stack.is("minecraft:spyglass")) return 1200;
        if (stack.is("minecraft:brush") || stack.is("minecraft:bundle")) return 200;
        if (stack.is("minecraft:goat_horn")) return Integer.MIN_VALUE;
        var consumable = ItemComponents.consumable(stack.components());
        if (consumable != null) return consumable.ticks();
        return stack.components().has("minecraft:blocks_attacks")
                        || stack.components().has("minecraft:kinetic_weapon")
                ? 72000
                : 0;
    }

    static ItemComponents.UseEffects effects(SimItemStack stack, ClientVersion version) {
        return version.isNewerThanOrEquals(ClientVersion.V_1_21_11)
                ? ItemComponents.useEffects(stack.components())
                : ItemComponents.UseEffects.DEFAULT;
    }

    private static void merge(List<Use> into, Use candidate) {
        // Remaining ticks never complete consumption client-side. Each hand and bound stack
        // object holds one Use with the longest timer; completion compares that object,
        // which keeps equal stacks in two slots separate.
        for (int i = 0; i < into.size(); i++) {
            Use old = into.get(i);
            if (old.hand() == candidate.hand() && old.boundStack() == candidate.boundStack()) {
                if (candidate.remaining() > old.remaining()) into.set(i, candidate);
                return;
            }
        }
        into.add(candidate);
    }
}
