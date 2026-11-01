package ac.cult.cultac.cloud;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.cloud.capture.ActionRef;
import ac.cult.cultac.cloud.intake.v3.Outcome;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundContainerClose;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHeldSlot;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.InventoryClick;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * What the server did with the player's clicks, held-slot changes and hand swaps, and which hand each totem pop shown to
 * them used, read from the server's view of their inventory as the server's own updates go out. The client does not
 * predict a swap: one the server applies comes back as both hands' slot updates at the player's next tick
 * ({@code ServerPlayer#tick}), one a plugin cancels as nothing ({@code ServerGamePacketListenerImpl#handlePlayerAction}),
 * so a swap is reported applied once both hands read swapped and never rejected. A pop shrinks the first hand holding
 * death protection, main before off ({@code LivingEntity#checkTotemDeathProtection}), and that hand's update follows at
 * the player's next tick.
 *
 * <p>The client does predict clicks and held-slot changes, and the server answers one it takes with nothing. While it
 * handles the packet it corrects a click whose claimed slots or cursor differ from its own result
 * ({@code ServerGamePacketListenerImpl#handleContainerClick}) and a held-slot change it refuses with the slot it keeps
 * ({@code ServerGamePacketListenerImpl#handleSetCarriedItem}). Each correction rejects the earliest awaited action it
 * contradicts; an action still awaited when the backend answers the marker sent behind it was applied. Only touched on
 * the player's event loop.
 */
final class ActionOutcomes {
    /** How long a swap waits for both hands to read swapped, and a pop for its totem to shrink. */
    static final long ANSWER_NANOS = TimeUnit.SECONDS.toNanos(2);

    private static final String DEATH_PROTECTION = "minecraft:death_protection";

    /** Where settled outcomes go: the action they name, null for none, and when they were seen. */
    interface Sink {
        void outcome(Outcome outcome, ActionRef action, long nanos);
    }

    /** A swap the server took, with both hands as the server had them before it. */
    private record Swap(ActionRef action, long nanos, int mainSlot, SimItemStack main, SimItemStack off) {}

    /** A pop awaiting the shrink of the totem in {@code slot}. */
    private record Pop(long nanos, Outcome.Hand hand, int slot, SimItemStack totem) {}

    /** Clicks and held-slot changes awaited at most; past it the oldest goes without an outcome. */
    static final int MAX_AWAITED = 64;

    /** Pops awaiting their shrink at most; past it the oldest goes out without its hand. */
    static final int MAX_POPS = 16;

    /** A click, with its claims, or a held-slot change to {@code held}, the server took and has not answered. */
    private static final class Awaited {
        final ActionRef action;
        final long nanos;
        final InventoryClick click;
        final int held;
        boolean marked;
        int marker;

        Awaited(ActionRef action, long nanos, InventoryClick click, int held) {
            this.action = action;
            this.nanos = nanos;
            this.click = click;
            this.held = held;
        }

        Outcome.Type type() {
            return click == null ? Outcome.Type.TYPE_HELD : Outcome.Type.TYPE_CLICK;
        }
    }

    private final Sink sink;
    /** The server's value for a player storage slot, null when unknown. */
    private final IntFunction<SimItemStack> serverSlots;

    private final ArrayDeque<Pop> pops = new ArrayDeque<>();
    private Swap swap;
    /** A swap the server took may still have hand updates in flight, which a later swap must not claim. */
    private boolean swapUnsettled;

    private long lastSwapNanos;

    /** Oldest first; the marked ones lead, in the order of their markers. */
    private final ArrayDeque<Awaited> awaited = new ArrayDeque<>();

    private int nextMarker;

    ActionOutcomes(Sink sink, IntFunction<SimItemStack> serverSlots) {
        this.sink = sink;
        this.serverSlots = serverSlots;
    }

    /**
     * A hand swap CultAC handled. It is awaited only from a settled view of the hands: no other swap pending or recent,
     * the server's own inventory open, and hands that differ.
     */
    void swap(ActionRef action, int selected, boolean cancelled, int serverWindow, long nanos) {
        if (cancelled) {
            sink.outcome(dropped(Outcome.Type.TYPE_SWAP), action, nanos);
            return;
        }
        boolean settled = swap == null && (!swapUnsettled || nanos - lastSwapNanos >= ANSWER_NANOS);
        swap = null;
        swapUnsettled = true;
        lastSwapNanos = nanos;
        if (!settled || serverWindow != 0) return;
        int mainSlot = Inventory.HOTBAR_OFFSET + selected;
        SimItemStack main = serverSlots.apply(mainSlot);
        SimItemStack off = serverSlots.apply(Inventory.SLOT_OFFHAND);
        if (main == null || off == null || same(main, off)) return;
        swap = new Swap(action, nanos, mainSlot, main, off);
    }

    /** A click or held-slot change the server takes, which can move the hands under a pending swap. */
    void handsMoved() {
        swap = null;
    }

    /**
     * A click CultAC handled. The server drops a click on a menu other than the one it has open, or from a player who is
     * dead or asleep, without a word, so such a click gets no outcome.
     */
    void click(
            ActionRef action, InventoryClick click, boolean cancelled, int serverWindow, boolean immobile, long nanos) {
        if (cancelled) sink.outcome(dropped(Outcome.Type.TYPE_CLICK), action, nanos);
        else if (click.windowId() == serverWindow && !immobile) await(new Awaited(action, nanos, click, -1));
    }

    /** A change of the held slot to hotbar {@code slot} CultAC handled; one while dead or asleep gets no outcome. */
    void held(ActionRef action, int slot, boolean cancelled, boolean immobile, long nanos) {
        if (cancelled) sink.outcome(dropped(Outcome.Type.TYPE_HELD), action, nanos);
        else if (!immobile) await(new Awaited(action, nanos, null, slot));
    }

    private void await(Awaited action) {
        if (awaited.size() >= MAX_AWAITED) awaited.poll();
        awaited.add(action);
    }

    /** Whether an awaited action has no marker behind it yet. */
    boolean unmarked() {
        Awaited last = awaited.peekLast();
        return last != null && !last.marked;
    }

    /** The marker to send behind every awaited action that has none. */
    int marker() {
        int marker = nextMarker++;
        for (Awaited action : awaited) {
            if (action.marked) continue;
            action.marked = true;
            action.marker = marker;
        }
        return marker;
    }

    /** The backend answered {@code marker}: every action still awaited behind it or an earlier one was applied. */
    void answered(int marker) {
        while (!awaited.isEmpty() && awaited.peek().marked && awaited.peek().marker - marker <= 0) {
            Awaited applied = awaited.poll();
            sink.outcome(
                    outcome(applied.type(), Outcome.Status.STATUS_APPLIED, Outcome.Join.JOIN_TIMEOUT),
                    applied.action,
                    applied.nanos);
        }
    }

    /** The capture left the backend that would answer: awaited actions, pops and swaps go without outcomes. */
    void forget() {
        awaited.clear();
        pops.clear();
        swap = null;
        swapUnsettled = false;
    }

    /** A totem pop shown on the player's own entity, as the server's update that names its hand awaits. */
    void popped(int selected, long nanos) {
        expire(nanos);
        if (pops.size() >= MAX_POPS) sink.outcome(pop(null), null, pops.poll().nanos());
        int mainSlot = Inventory.HOTBAR_OFFSET + selected;
        SimItemStack main = serverSlots.apply(mainSlot);
        SimItemStack off = serverSlots.apply(Inventory.SLOT_OFFHAND);
        if (protects(main)) pops.add(new Pop(nanos, Outcome.Hand.HAND_MAIN, mainSlot, main));
        else if (protects(off)) pops.add(new Pop(nanos, Outcome.Hand.HAND_OFF, Inventory.SLOT_OFFHAND, off));
        else sink.outcome(pop(null), null, nanos);
    }

    /** A clientbound packet from the server, after CultAC has applied it to the server's view, at {@code nanos}. */
    void sent(Object packet, long nanos) {
        if (!awaited.isEmpty()) corrected(packet);
        if (!(packet instanceof InventoryPackets.Slot
                || packet instanceof InventoryPackets.Content
                || packet instanceof InventoryPackets.PlayerInventory)) return;
        expire(nanos);
        if (swap != null) swapUpdated();
        Iterator<Pop> waiting = pops.iterator();
        while (waiting.hasNext()) {
            Pop pop = waiting.next();
            if (shrunk(pop.totem(), serverSlots.apply(pop.slot()))) {
                waiting.remove();
                sink.outcome(pop(pop.hand()), null, pop.nanos());
            }
        }
    }

    private void corrected(Object packet) {
        if (packet instanceof ClientboundOpenScreen || packet instanceof ClientboundContainerClose) {
            awaited.removeIf(action -> action.click != null);
            return;
        }
        Iterator<Awaited> waiting = awaited.iterator();
        while (waiting.hasNext()) {
            Awaited action = waiting.next();
            if (!contradicts(action, packet)) continue;
            waiting.remove();
            sink.outcome(
                    outcome(action.type(), Outcome.Status.STATUS_REJECTED, Outcome.Join.JOIN_KEY),
                    action.action,
                    action.nanos);
            return;
        }
    }

    private static boolean contradicts(Awaited action, Object packet) {
        InventoryClick click = action.click;
        if (click == null) return packet instanceof ClientboundSetHeldSlot held && held.slot() != action.held;
        return switch (packet) {
            case InventoryPackets.Slot slot ->
                slot.windowId() == click.windowId() && contradicts(click, slot.slot(), slot.item());
            case InventoryPackets.Content content -> {
                if (content.windowId() != click.windowId()) yield false;
                for (int slot = 0; slot < content.items().size(); slot++) {
                    if (contradicts(click, slot, content.items().get(slot))) yield true;
                }
                yield !same(content.carriedItem(), click.carriedItem());
            }
            case InventoryPackets.Cursor cursor -> !same(cursor.item(), click.carriedItem());
            default -> false;
        };
    }

    private static boolean contradicts(InventoryClick click, int slot, SimItemStack item) {
        SimItemStack claimed = click.changedSlots().get(slot);
        return claimed != null && !same(claimed, item);
    }

    private void swapUpdated() {
        SimItemStack main = serverSlots.apply(swap.mainSlot());
        SimItemStack off = serverSlots.apply(Inventory.SLOT_OFFHAND);
        boolean mainSwapped = same(main, swap.off());
        boolean offSwapped = same(off, swap.main());
        if (mainSwapped && offSwapped) {
            sink.outcome(
                    outcome(Outcome.Type.TYPE_SWAP, Outcome.Status.STATUS_APPLIED, Outcome.Join.JOIN_KEY),
                    swap.action(),
                    swap.nanos());
            swap = null;
            swapUnsettled = false;
        } else if (!mainSwapped && !same(main, swap.main()) || !offSwapped && !same(off, swap.off())) {
            swap = null;
        }
    }

    /** Gives up on answers older than {@link #ANSWER_NANOS} as of {@code now}: a pop goes out without its hand. */
    void expire(long now) {
        if (swap != null && now - swap.nanos() >= ANSWER_NANOS) swap = null;
        while (!pops.isEmpty() && now - pops.peek().nanos() >= ANSWER_NANOS) {
            sink.outcome(pop(null), null, pops.poll().nanos());
        }
    }

    private static Outcome outcome(Outcome.Type type, Outcome.Status status, Outcome.Join join) {
        return Outcome.newBuilder()
                .setType(type)
                .setStatus(status)
                .setJoin(join)
                .build();
    }

    private static Outcome dropped(Outcome.Type type) {
        return Outcome.newBuilder()
                .setType(type)
                .setStatus(Outcome.Status.STATUS_DROPPED)
                .setJoin(Outcome.Join.JOIN_CULTAC)
                .setReason(Outcome.Reason.REASON_CULTAC)
                .build();
    }

    private static Outcome pop(Outcome.Hand hand) {
        Outcome.Builder outcome =
                Outcome.newBuilder().setType(Outcome.Type.TYPE_POP).setStatus(Outcome.Status.STATUS_APPLIED);
        if (hand != null) outcome.setHand(hand);
        return outcome.build();
    }

    private static boolean protects(SimItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.components().has(DEATH_PROTECTION);
    }

    private static boolean shrunk(SimItemStack before, SimItemStack after) {
        if (after == null) return false;
        if (before.getCount() == 1) return after.isEmpty();
        return !after.isEmpty()
                && after.itemKey().equals(before.itemKey())
                && after.getCount() == before.getCount() - 1;
    }

    /** Whether two stacks hold the same item and count. */
    private static boolean same(SimItemStack first, SimItemStack second) {
        boolean firstEmpty = first == null || first.isEmpty();
        boolean secondEmpty = second == null || second.isEmpty();
        if (firstEmpty || secondEmpty) return firstEmpty == secondEmpty;
        return first.itemKey().equals(second.itemKey()) && first.getCount() == second.getCount();
    }
}
