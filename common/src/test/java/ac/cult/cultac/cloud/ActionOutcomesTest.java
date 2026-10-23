package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.cloud.capture.ActionRef;
import ac.cult.cultac.cloud.intake.v3.Outcome;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundContainerClose;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHeldSlot;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ActionOutcomesTest {
    private static final long LAG = TimeUnit.MILLISECONDS.toNanos(400);
    private static final long DUE = ActionOutcomes.ANSWER_NANOS;
    private static final int MAIN = 36;
    private static final int OFF = 45;

    private static final SimItemStack TOTEM = item("minecraft:totem_of_undying");
    private static final SimItemStack SWORD = item("minecraft:diamond_sword");

    private final Map<Integer, SimItemStack> server = new HashMap<>();
    private final List<Outcome> settled = new ArrayList<>();
    private final List<ActionRef> named = new ArrayList<>();
    private final List<Long> seen = new ArrayList<>();
    private final ActionOutcomes actions = new ActionOutcomes(
            (outcome, action, nanos) -> {
                settled.add(outcome);
                named.add(action);
                seen.add(nanos);
            },
            server::get);

    private static SimItemStack item(String key) {
        return ItemUtil.modelItems().stack(key, 1);
    }

    /** The server updates {@code slot} to {@code item}, as CultAC applies it to the server's view. */
    private void update(int slot, SimItemStack item) {
        server.put(slot, item);
        actions.sent(new InventoryPackets.Slot(0, 1, slot, item), 0);
    }

    private void hands(SimItemStack main, SimItemStack off) {
        server.put(MAIN, main);
        server.put(OFF, off);
    }

    @Test
    void aSwapIsAppliedOnceBothHandsReadSwapped() {
        hands(SWORD, TOTEM);
        actions.swap(null, 0, false, 0, 0);
        update(OFF, SWORD);
        assertTrue(settled.isEmpty());
        update(MAIN, TOTEM);
        assertEquals(List.of(Outcome.Status.STATUS_APPLIED), statuses());
        assertEquals(Outcome.Type.TYPE_SWAP, settled.get(0).getType());
        assertEquals(0L, seen.get(0));
    }

    @Test
    void aSwapAnsweredBehindALaggingServerIsStillApplied() {
        hands(SWORD, TOTEM);
        actions.swap(null, 0, false, 0, 0);
        actions.expire(LAG);
        update(OFF, SWORD);
        update(MAIN, TOTEM);
        assertEquals(List.of(Outcome.Status.STATUS_APPLIED), statuses());
    }

    @Test
    void aSwapTheServerNeverAnswersHasNoOutcome() {
        hands(SWORD, TOTEM);
        actions.swap(null, 0, false, 0, 0);
        actions.expire(DUE);
        update(OFF, SWORD);
        update(MAIN, TOTEM);
        assertTrue(settled.isEmpty());
    }

    @Test
    void aSwapCultacCancelsIsDropped() {
        hands(SWORD, TOTEM);
        actions.swap(null, 0, true, 0, 0);
        assertEquals(List.of(Outcome.Status.STATUS_DROPPED), statuses());
        assertEquals(Outcome.Join.JOIN_CULTAC, settled.get(0).getJoin());
    }

    @Test
    void aHandUpdatedToAnythingElseLeavesTheSwapUnanswered() {
        hands(SWORD, TOTEM);
        actions.swap(null, 0, false, 0, 0);
        update(OFF, SimItemStack.EMPTY);
        update(OFF, SWORD);
        update(MAIN, TOTEM);
        assertTrue(settled.isEmpty());
    }

    @Test
    void swapsInFlightTogetherGetNoOutcome() {
        hands(SWORD, TOTEM);
        actions.swap(null, 0, false, 0, 0);
        actions.swap(null, 0, false, 0, 1);
        actions.swap(null, 0, false, 0, DUE);
        update(OFF, SWORD);
        update(MAIN, TOTEM);
        assertTrue(settled.isEmpty());

        actions.swap(null, 0, false, 0, 2 * DUE + 1);
        update(OFF, TOTEM);
        update(MAIN, SWORD);
        assertEquals(List.of(Outcome.Status.STATUS_APPLIED), statuses());
    }

    @Test
    void aSwapAfterAnAppliedOneIsAwaitedAtOnce() {
        hands(SWORD, TOTEM);
        actions.swap(null, 0, false, 0, 0);
        update(OFF, SWORD);
        update(MAIN, TOTEM);
        actions.swap(null, 0, false, 0, 1);
        update(OFF, TOTEM);
        update(MAIN, SWORD);
        assertEquals(List.of(Outcome.Status.STATUS_APPLIED, Outcome.Status.STATUS_APPLIED), statuses());
    }

    @Test
    void aClickUnderAPendingSwapLeavesItUnanswered() {
        hands(SWORD, TOTEM);
        actions.swap(null, 0, false, 0, 0);
        actions.handsMoved();
        update(OFF, SWORD);
        update(MAIN, TOTEM);
        assertTrue(settled.isEmpty());
    }

    @Test
    void equalHandsOrAnotherOpenMenuLeaveNothingToAwait() {
        hands(TOTEM, TOTEM);
        actions.swap(null, 0, false, 0, 0);
        hands(SWORD, TOTEM);
        actions.swap(null, 0, false, 3, DUE);
        update(OFF, SWORD);
        update(MAIN, TOTEM);
        assertTrue(settled.isEmpty());
    }

    @Test
    void aPopNamesTheHandWhoseTotemShrinks() {
        hands(TOTEM, TOTEM);
        actions.popped(0, 5);
        update(OFF, TOTEM);
        assertTrue(settled.isEmpty(), "an update that leaves the main hand whole names nothing");
        update(MAIN, SimItemStack.EMPTY);
        assertEquals(1, settled.size());
        Outcome pop = settled.get(0);
        assertEquals(Outcome.Type.TYPE_POP, pop.getType());
        assertEquals(Outcome.Status.STATUS_APPLIED, pop.getStatus());
        assertEquals(Outcome.Hand.HAND_MAIN, pop.getHand());
        assertEquals(5L, seen.get(0));
    }

    @Test
    void anOffHandPopShrinkingBehindALaggingServerNamesTheOffHand() {
        hands(SWORD, TOTEM);
        actions.popped(0, 0);
        actions.expire(LAG);
        update(OFF, SimItemStack.EMPTY);
        assertEquals(Outcome.Hand.HAND_OFF, settled.get(0).getHand());
    }

    @Test
    void aPopWhoseTotemNeverShrinksGoesOutWithoutAHand() {
        hands(TOTEM, SWORD);
        actions.popped(0, 0);
        actions.expire(DUE - 1);
        assertTrue(settled.isEmpty());
        actions.expire(DUE);
        assertEquals(1, settled.size());
        assertFalse(settled.get(0).hasHand());
        update(MAIN, SimItemStack.EMPTY);
        assertEquals(1, settled.size());
    }

    @Test
    void aPopWithoutProtectionInEitherHandGoesOutAtOnceWithoutAHand() {
        hands(SWORD, SimItemStack.EMPTY);
        actions.popped(0, 0);
        assertEquals(1, settled.size());
        assertFalse(settled.get(0).hasHand());
        assertNull(named.get(0));
    }

    @Test
    void popsStayBoundedAndScansStayShortWithoutTickEnds() {
        AtomicInteger reads = new AtomicInteger();
        ActionOutcomes counted = new ActionOutcomes((outcome, action, nanos) -> settled.add(outcome), slot -> {
            reads.incrementAndGet();
            return server.get(slot);
        });
        hands(TOTEM, SWORD);
        for (int i = 0; i < 10_000; i++) counted.popped(0, i);
        assertEquals(10_000 - ActionOutcomes.MAX_POPS, settled.size(), "the oldest go without their hand");
        assertTrue(settled.stream().noneMatch(Outcome::hasHand));
        reads.set(0);
        for (int i = 0; i < 100; i++) counted.sent(new InventoryPackets.Slot(0, 1, OFF, SWORD), 10_000);
        assertEquals(100 * ActionOutcomes.MAX_POPS, reads.get());

        counted.sent(new InventoryPackets.Slot(0, 1, OFF, SWORD), 10_000 + DUE);
        assertEquals(10_000, settled.size(), "an update past the answer time expires them first");
        reads.set(0);
        counted.sent(new InventoryPackets.Slot(0, 1, OFF, SWORD), 10_000 + DUE);
        assertEquals(0, reads.get());
    }

    @Test
    void aPopIsExpiredWhenTheNextOneIsSeen() {
        hands(TOTEM, SWORD);
        actions.popped(0, 0);
        actions.popped(0, DUE);
        assertEquals(1, settled.size());
        assertFalse(settled.get(0).hasHand());
        assertEquals(0L, seen.get(0));
    }

    @Test
    void aPopFromAnEndedSegmentNeverSettlesInTheNext() {
        hands(TOTEM, TOTEM);
        actions.popped(0, 0);
        actions.forget();
        update(MAIN, SimItemStack.EMPTY);
        actions.expire(DUE);
        assertTrue(settled.isEmpty());

        hands(TOTEM, SWORD);
        actions.popped(0, DUE);
        update(MAIN, SimItemStack.EMPTY);
        assertEquals(
                List.of(Outcome.Hand.HAND_MAIN),
                settled.stream().map(Outcome::getHand).toList());
    }

    @Test
    void aSwapFromAnEndedSegmentLeavesTheNextOneAwaited() {
        hands(SWORD, TOTEM);
        actions.swap(null, 0, false, 0, 0);
        actions.forget();
        actions.swap(null, 0, false, 0, 1);
        update(OFF, SWORD);
        update(MAIN, TOTEM);
        assertEquals(List.of(Outcome.Status.STATUS_APPLIED), statuses());
        assertEquals(1L, seen.get(0));
    }

    @Test
    void aClickTheServerLeavesUncorrectedIsAppliedWhenItsMarkerIsAnswered() {
        actions.click(null, click(0, 36, SWORD), false, 0, false, 7);
        update(36, SWORD);
        assertTrue(actions.unmarked());
        int marker = actions.marker();
        assertFalse(actions.unmarked());
        assertTrue(settled.isEmpty());

        actions.answered(marker);
        assertEquals(List.of(Outcome.Status.STATUS_APPLIED), statuses());
        assertEquals(Outcome.Type.TYPE_CLICK, settled.get(0).getType());
        assertEquals(Outcome.Join.JOIN_TIMEOUT, settled.get(0).getJoin());
        assertEquals(7L, seen.get(0));
    }

    @Test
    void aSlotCursorOrContentsContradictingTheClaimsRejectTheClick() {
        actions.click(null, click(0, 36, SWORD), false, 0, false, 1);
        update(36, TOTEM);
        actions.click(null, click(0, 36, SWORD), false, 0, false, 2);
        actions.sent(new InventoryPackets.Cursor(SWORD), 0);
        actions.click(null, click(0, 36, SWORD), false, 0, false, 3);
        actions.sent(new InventoryPackets.Content(0, 4, List.of(SimItemStack.EMPTY), SimItemStack.EMPTY), 0);
        actions.click(null, click(0, 1, SWORD), false, 0, false, 4);
        List<SimItemStack> contents = new ArrayList<>(List.of(SimItemStack.EMPTY, TOTEM));
        actions.sent(new InventoryPackets.Content(0, 5, contents, SimItemStack.EMPTY), 0);

        assertEquals(List.of(1L, 2L, 4L), seen);
        assertEquals(Outcome.Status.STATUS_REJECTED, settled.get(0).getStatus());
        assertEquals(Outcome.Join.JOIN_KEY, settled.get(0).getJoin());
        actions.answered(actions.marker());
        assertEquals(List.of(1L, 2L, 4L, 3L), seen, "contents that agree with every claim reject nothing");
        assertEquals(Outcome.Status.STATUS_APPLIED, settled.get(3).getStatus());
    }

    @Test
    void aCorrectionRejectsOnlyTheEarliestClickItContradicts() {
        actions.click(null, click(0, 36, SWORD), false, 0, false, 1);
        actions.click(null, click(0, 36, SimItemStack.EMPTY), false, 0, false, 2);
        update(36, TOTEM);
        actions.answered(actions.marker());
        assertEquals(List.of(Outcome.Status.STATUS_REJECTED, Outcome.Status.STATUS_APPLIED), statuses());
        assertEquals(List.of(1L, 2L), seen);
    }

    @Test
    void updatesOfAnotherMenuOrUnclaimedSlotsRejectNothing() {
        actions.click(null, click(2, 0, SWORD), false, 2, false, 1);
        actions.sent(new InventoryPackets.Slot(0, 1, 0, TOTEM), 0);
        actions.sent(new InventoryPackets.Slot(2, 1, 5, TOTEM), 0);
        actions.answered(actions.marker());
        assertEquals(List.of(Outcome.Status.STATUS_APPLIED), statuses());
    }

    @Test
    void aHeldSlotTheServerSetsBackRejectsTheEarliestChangeToAnotherSlot() {
        actions.held(null, 3, false, false, 1);
        actions.held(null, 5, false, false, 2);
        actions.sent(new ClientboundSetHeldSlot(0), 0);
        actions.answered(actions.marker());
        assertEquals(List.of(Outcome.Status.STATUS_REJECTED, Outcome.Status.STATUS_APPLIED), statuses());
        assertEquals(List.of(1L, 2L), seen);
        assertEquals(Outcome.Type.TYPE_HELD, settled.get(0).getType());

        actions.held(null, 3, false, false, 3);
        actions.held(null, 5, false, false, 4);
        actions.sent(new ClientboundSetHeldSlot(3), 0);
        actions.answered(actions.marker());
        assertEquals(List.of(1L, 2L, 4L, 3L), seen, "the server kept 3 and refused 5");
    }

    @Test
    void clicksAndHeldChangesCultacCancelsAreDroppedAtOnce() {
        actions.click(null, click(0, 36, SWORD), true, 0, false, 1);
        actions.held(null, 2, true, false, 2);
        assertEquals(List.of(Outcome.Status.STATUS_DROPPED, Outcome.Status.STATUS_DROPPED), statuses());
        assertEquals(Outcome.Type.TYPE_CLICK, settled.get(0).getType());
        assertEquals(Outcome.Type.TYPE_HELD, settled.get(1).getType());
        assertEquals(Outcome.Join.JOIN_CULTAC, settled.get(1).getJoin());
        assertFalse(actions.unmarked());
    }

    @Test
    void actionsTheServerIgnoresSilentlyGetNoOutcome() {
        actions.click(null, click(1, 36, SWORD), false, 0, false, 1);
        actions.click(null, click(0, 36, SWORD), false, 0, true, 2);
        actions.held(null, 2, false, true, 3);
        assertFalse(actions.unmarked());

        actions.click(null, click(0, 36, SWORD), false, 0, false, 4);
        actions.held(null, 2, false, false, 5);
        actions.sent(new ClientboundContainerClose(0), 0);
        actions.answered(actions.marker());
        assertEquals(List.of(5L), seen, "a menu change leaves the clicks before it unknown");
    }

    @Test
    void oneMarkerCoversEveryActionBeforeItAndAnAnswerSettlesEarlierMarkers() {
        actions.held(null, 1, false, false, 1);
        actions.held(null, 2, false, false, 2);
        int first = actions.marker();
        actions.held(null, 3, false, false, 3);
        int second = actions.marker();
        actions.held(null, 4, false, false, 4);
        assertTrue(actions.unmarked());

        actions.answered(first);
        assertEquals(List.of(1L, 2L), seen);
        actions.answered(second);
        assertEquals(List.of(1L, 2L, 3L), seen);
        actions.answered(actions.marker());
        assertEquals(List.of(1L, 2L, 3L, 4L), seen);

        actions.held(null, 5, false, false, 5);
        int third = actions.marker();
        actions.answered(second);
        assertEquals(4, seen.size(), "a stale answer settles nothing after it");
        actions.forget();
        actions.answered(third);
        assertEquals(4, seen.size());
    }

    @Test
    void theOldestAwaitedActionGoesWithoutAnOutcomePastTheBound() {
        for (int i = 0; i <= ActionOutcomes.MAX_AWAITED; i++) actions.held(null, i % 9, false, false, i);
        actions.answered(actions.marker());
        assertEquals(ActionOutcomes.MAX_AWAITED, seen.size());
        assertEquals(1L, seen.get(0));
    }

    private static InventoryClick click(int window, int slot, SimItemStack claimed) {
        return new InventoryClick(
                window, 1, slot, 0, WindowClickType.PICKUP, Map.of(slot, claimed), SimItemStack.EMPTY);
    }

    private List<Outcome.Status> statuses() {
        return settled.stream().map(Outcome::getStatus).toList();
    }
}
