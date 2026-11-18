package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.inventory.ItemUtil;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A client that sends use_item before it has handled entity event 9 of the use it holds starts the next use on its own
 * and may travel slowed by it until it has handled the server's answer to that use_item.
 */
class ClientItemUseRestartTest {
    private static final int SEQUENCE = 7;

    @Test
    void aRestartAddsOnlyItsMultiplierToTheSnapshot() {
        var food = food();
        var restarted = restartedAndCompleted(food);
        var completed = new ClientItemUseState();
        completed.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
        completed.release();
        var snapshot = prepare(restarted, food);
        var base = prepare(completed, food);
        assertEquals(List.of(1.0F, 0.2F), snapshot.multipliers());
        assertEquals(List.of(1.0F), base.multipliers());
        assertEquals(base.session(), snapshot.session());
        assertEquals(base.legalMultiplier(), snapshot.legalMultiplier());
        assertEquals(base.requiredMultiplier(), snapshot.requiredMultiplier());
        assertEquals(base.slowestMultiplier(), snapshot.slowestMultiplier());
        assertEquals(base.canSprint(), snapshot.canSprint());
        assertEquals(base.required(), snapshot.required());
        assertEquals(base.definitelyUsing(), snapshot.definitelyUsing());
        assertEquals(base.possiblyUsing(), snapshot.possiblyUsing());
        assertEquals(base.possiblyBlocksSprint(), snapshot.possiblyBlocksSprint());
    }

    @Test
    void aUseItemWithoutACompletionInFlightStartsNoRestart() {
        var state = new ClientItemUseState();
        var food = food();
        state.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
        assertFalse(state.restartsBeforeCompletion());
        state.completionSent();
        state.release();
        state.completionHandled();
        assertFalse(state.restartsBeforeCompletion());
    }

    @Test
    void aCompletionWithoutAHeldUseStartsNoRestart() {
        var state = new ClientItemUseState();
        state.completionSent();
        assertFalse(state.restartsBeforeCompletion());
        state.completionHandled();
        state.completionHandled();
        state.start(Hand.OFF_HAND, food(), 32, true);
        assertFalse(state.restartsBeforeCompletion());
    }

    @Test
    void anItemWithoutAUseDurationStartsNoRestart() {
        var state = new ClientItemUseState();
        var food = food();
        state.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
        state.completionSent();
        state.restarted(Hand.MAIN_HAND, ItemUtil.modelItems().stack("minecraft:stone", 1), SEQUENCE);
        state.restarted(Hand.MAIN_HAND, SimItemStack.EMPTY, SEQUENCE);
        state.release();
        state.completionHandled();
        assertEquals(List.of(1.0F), prepare(state, food).multipliers());
    }

    @Test
    void theServersAnswerToTheUseItemEndsTheRestart() {
        var food = food();
        var state = restartedAndCompleted(food);
        state.serverAnswered(SEQUENCE - 1);
        assertEquals(List.of(1.0F, 0.2F), prepare(state, food, 0).multipliers());
        state.serverAnswered(SEQUENCE);
        assertEquals(List.of(1.0F), prepare(state, food, 1).multipliers());
    }

    /** Acknowledgements are cumulative, so the answer to the highest sequence of a hand and item ends every earlier one. */
    @Test
    void manyUseItemsDuringOneCompletionKeepOneRestartPerHandAndItem() {
        var food = food();
        var state = new ClientItemUseState();
        state.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
        state.completionSent();
        int last = 1000;
        for (int sequence = 1; sequence <= last; sequence++) {
            state.restarted(Hand.OFF_HAND, food, sequence);
            state.restarted(Hand.MAIN_HAND, food, sequence);
        }
        assertEquals(2, state.openRestarts());
        state.release();
        state.completionHandled();
        state.serverAnswered(last - 1);
        assertEquals(2, state.openRestarts());
        assertEquals(List.of(1.0F, 0.2F), prepare(state, food, 0).multipliers());
        state.serverAnswered(last);
        assertEquals(0, state.openRestarts());
        assertEquals(List.of(1.0F), prepare(state, food, 1).multipliers());
    }

    @Test
    void aReleaseEndsTheRestart() {
        var food = food();
        var state = restartedAndCompleted(food);
        state.clientReleased();
        assertEquals(List.of(1.0F), prepare(state, food).multipliers());
    }

    @Test
    void aRespawnEndsTheRestart() {
        var food = food();
        var state = restartedAndCompleted(food);
        state.reset();
        assertEquals(List.of(1.0F), prepare(state, food).multipliers());
    }

    @Test
    void aSlotChangeEndsAMainHandRestartOnly() {
        var food = food();
        var offhand = restartedAndCompleted(food);
        offhand.claimedSlotChanged();
        assertEquals(List.of(1.0F, 0.2F), prepare(offhand, food).multipliers());

        var main = new ClientItemUseState();
        main.start(Hand.MAIN_HAND, food, ClientItemUseState.duration(food), true);
        main.completionSent();
        assertTrue(main.restartsBeforeCompletion());
        main.restarted(Hand.MAIN_HAND, food, SEQUENCE);
        main.release();
        main.completionHandled();
        assertEquals(
                List.of(1.0F, 0.2F),
                main.prepare(0, 3, ClientVersion.V_1_21_11, food, SimItemStack.EMPTY, hotbar(food))
                        .multipliers());
        main.claimedSlotChanged();
        assertEquals(
                List.of(1.0F),
                main.prepare(1, 3, ClientVersion.V_1_21_11, food, SimItemStack.EMPTY, hotbar(food))
                        .multipliers());
    }

    @Test
    void aHandThatHoldsAnotherItemAddsNoRestartMultiplier() {
        var food = food();
        var state = restartedAndCompleted(food);
        assertEquals(List.of(1.0F), prepare(state, ItemUtil.modelItems().stack("minecraft:stone", 1)).multipliers());
        assertEquals(List.of(1.0F, 0.2F), prepare(state, food, 1).multipliers());
    }

    @Test
    void aRestartOfTheLastItemKeepsItsMultiplierOnceTheHandIsEmpty() {
        var state = restartedAndCompleted(food());
        assertEquals(List.of(1.0F, 0.2F), prepare(state, SimItemStack.EMPTY).multipliers());
        state.serverAnswered(SEQUENCE);
        assertEquals(List.of(1.0F), prepare(state, SimItemStack.EMPTY, 1).multipliers());
    }

    @Test
    void aRestartAddsNothingWhileTheHeldUseAlreadySlowsByItsMultiplier() {
        var state = new ClientItemUseState();
        var food = food();
        state.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
        state.completionSent();
        state.restarted(Hand.OFF_HAND, food, SEQUENCE);
        assertEquals(List.of(0.2F), prepare(state, food).multipliers());
    }

    /** A held offhand use whose completion is in flight, restarted, then the completion handled. */
    private static ClientItemUseState restartedAndCompleted(SimItemStack food) {
        var state = new ClientItemUseState();
        state.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
        state.completionSent();
        assertTrue(state.restartsBeforeCompletion());
        state.restarted(Hand.OFF_HAND, food, SEQUENCE);
        state.release();
        state.completionHandled();
        assertFalse(state.restartsBeforeCompletion());
        return state;
    }

    private static ClientItemUseState.Snapshot prepare(ClientItemUseState state, SimItemStack off) {
        return prepare(state, off, 0);
    }

    private static ClientItemUseState.Snapshot prepare(ClientItemUseState state, SimItemStack off, int tick) {
        return state.prepare(tick, 3, ClientVersion.V_1_21_11, SimItemStack.EMPTY, off, hotbar());
    }

    private static SimItemStack food() {
        return ItemUtil.modelItems().stack("minecraft:golden_apple", 2);
    }

    private static List<SimItemStack> hotbar() {
        return hotbar(SimItemStack.EMPTY);
    }

    private static List<SimItemStack> hotbar(SimItemStack stack) {
        return Collections.nCopies(9, stack);
    }
}
