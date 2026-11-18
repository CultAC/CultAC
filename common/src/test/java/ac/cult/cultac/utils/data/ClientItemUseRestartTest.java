package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.nbt.CanonicalSnbt;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.inventory.ItemUtil;
import com.google.gson.JsonParser;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A client that sends use_item before it has handled entity event 9 of the use it holds starts the next use on its own.
 * A restarted consumable may slow its travel until its use duration has passed; any other restarted use until the
 * client has handled the server's answer to that use_item.
 */
class ClientItemUseRestartTest {
    private static final int SEQUENCE = 7;
    private static final ClientVersion VERSION = ClientVersion.V_1_21_11;

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
        state.restarted(Hand.MAIN_HAND, ItemUtil.modelItems().stack("minecraft:stone", 1), SEQUENCE, 0);
        state.restarted(Hand.MAIN_HAND, SimItemStack.EMPTY, SEQUENCE, 0);
        state.release();
        state.completionHandled();
        assertEquals(List.of(1.0F), prepare(state, food).multipliers());
    }

    /** The cap counts a 32-tick use as the server's LivingEntity#updateUsingItem does: its first 31 ticks slowed. */
    @Test
    void aRestartedConsumableEndsWhenItsUseDurationHasPassed() {
        var food = food();
        var state = restartedAndCompleted(food, 10);
        assertEquals(List.of(1.0F, 0.2F), prepare(state, food, 10).multipliers());
        assertEquals(List.of(1.0F, 0.2F), prepare(state, food, 40).multipliers());
        assertEquals(List.of(1.0F), prepare(state, food, 41).multipliers());
        assertEquals(0, state.openRestarts());
    }

    /** Item#getUseDuration reads the consumable component of the stack, not a fixed count. */
    @Test
    void aRestartedConsumableUsesItsOwnComponentsDuration() {
        var quick = food();
        quick.setComponent("minecraft:consumable", CanonicalSnbt.parse("{consume_seconds:0.5f}"));
        assertEquals(10, ClientItemUseState.duration(quick));
        var state = restartedAndCompleted(quick, 0);
        assertEquals(List.of(1.0F, 0.2F), prepare(state, quick, 8).multipliers());
        assertEquals(List.of(1.0F), prepare(state, quick, 9).multipliers());
    }

    @Test
    void theServersAnswerKeepsARestartedConsumable() {
        var food = food();
        var state = restartedAndCompleted(food);
        state.serverAnswered(SEQUENCE);
        assertEquals(List.of(1.0F, 0.2F), prepare(state, food, 1).multipliers());
    }

    @Test
    void theServersAnswerToTheUseItemEndsARestartedUseHeldUntilARelease() {
        var bow = bow();
        var state = restartedAndCompleted(bow);
        state.serverAnswered(SEQUENCE - 1);
        assertEquals(List.of(1.0F, 0.2F), prepare(state, bow, 0).multipliers());
        state.serverAnswered(SEQUENCE);
        assertEquals(List.of(1.0F), prepare(state, bow, 1).multipliers());
    }

    /** A trident's own use duration precedes its consumable component, so its restart is held until a release. */
    @Test
    void aTridentWithAConsumableComponentRestartsAsAUseHeldUntilARelease() {
        var trident = ItemUtil.modelItems().stack("minecraft:trident", 1);
        trident.setComponent("minecraft:consumable", CanonicalSnbt.parse("{consume_seconds:0.5f}"));
        var state = restartedAndCompleted(trident);
        assertEquals(List.of(1.0F, 0.2F), prepare(state, trident, 0).multipliers());
        state.serverAnswered(SEQUENCE);
        assertEquals(List.of(1.0F), prepare(state, trident, 1).multipliers());
    }

    /** One entry per hand and item; the latest use_item replaces it and starts its timer again. */
    @Test
    void manyUseItemsDuringOneCompletionKeepOneRestartPerHandAndItem() {
        var food = food();
        var state = new ClientItemUseState();
        state.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
        state.completionSent();
        int last = 1000;
        for (int sequence = 1; sequence <= last; sequence++) {
            int tick = sequence == last ? 5 : 0;
            state.restarted(Hand.OFF_HAND, food, sequence, tick);
            state.restarted(Hand.MAIN_HAND, food, sequence, tick);
        }
        assertEquals(2, state.openRestarts());
        state.release();
        state.completionHandled();
        state.serverAnswered(last);
        assertEquals(2, state.openRestarts());
        assertEquals(List.of(1.0F, 0.2F), prepare(state, food, 35).multipliers());
        assertEquals(List.of(1.0F), prepare(state, food, 36).multipliers());
        assertEquals(0, state.openRestarts());
    }

    @Test
    void aReleaseKeepsARestartedConsumable() {
        var food = food();
        var state = restartedAndCompleted(food);
        state.clientReleased();
        assertEquals(List.of(1.0F, 0.2F), prepare(state, food).multipliers());
    }

    @Test
    void aReleaseEndsARestartedUseHeldUntilARelease() {
        var bow = bow();
        var state = restartedAndCompleted(bow);
        state.clientReleased();
        assertEquals(List.of(1.0F), prepare(state, bow).multipliers());
    }

    @Test
    void aRespawnEndsTheRestart() {
        var food = food();
        var state = restartedAndCompleted(food);
        state.reset();
        assertEquals(List.of(1.0F), prepare(state, food).multipliers());
    }

    /** LocalPlayer#canStartSprinting requires !isSlowDueToUsingItem. */
    @Test
    void aSprintStartEndsTheRestart() {
        var food = food();
        var state = restartedAndCompleted(food);
        state.startedSprinting(VERSION);
        assertEquals(List.of(1.0F), prepare(state, food).multipliers());
        var bow = bow();
        var held = restartedAndCompleted(bow);
        held.startedSprinting(VERSION);
        assertEquals(List.of(1.0F), prepare(held, bow).multipliers());
    }

    /** UseEffects#canSprint lets the client start sprinting while it uses the item. */
    @Test
    void aSprintStartKeepsTheRestartOfAnItemThatAllowsSprinting() {
        var food = food();
        food.components(food.components()
                .with(
                        "minecraft:use_effects",
                        JsonParser.parseString("{\"can_sprint\":true,\"speed_multiplier\":0.5}")));
        var state = restartedAndCompleted(food);
        state.startedSprinting(VERSION);
        assertEquals(List.of(1.0F, 0.5F), prepare(state, food).multipliers());
    }

    @Test
    void aSlotChangeKeepsARestartedConsumable() {
        var food = food();
        var main = mainHandRestart(food);
        main.claimedSlotChanged();
        assertEquals(
                List.of(1.0F, 0.2F),
                main.prepare(1, 3, VERSION, food, SimItemStack.EMPTY, hotbar(food))
                        .multipliers());
    }

    @Test
    void aSlotChangeEndsAMainHandRestartHeldUntilARelease() {
        var bow = bow();
        var offhand = restartedAndCompleted(bow);
        offhand.claimedSlotChanged();
        assertEquals(List.of(1.0F, 0.2F), prepare(offhand, bow).multipliers());

        var main = mainHandRestart(bow);
        main.claimedSlotChanged();
        assertEquals(
                List.of(1.0F),
                main.prepare(1, 3, VERSION, bow, SimItemStack.EMPTY, hotbar(bow))
                        .multipliers());
    }

    @Test
    void aHandThatHoldsAnotherItemKeepsARestartedConsumable() {
        var food = food();
        var state = restartedAndCompleted(food);
        assertEquals(
                List.of(1.0F, 0.2F),
                prepare(state, ItemUtil.modelItems().stack("minecraft:stone", 1))
                        .multipliers());
    }

    @Test
    void aHandThatHoldsAnotherItemAddsNoMultiplierForAUseHeldUntilARelease() {
        var bow = bow();
        var state = restartedAndCompleted(bow);
        assertEquals(
                List.of(1.0F),
                prepare(state, ItemUtil.modelItems().stack("minecraft:stone", 1))
                        .multipliers());
        assertEquals(List.of(1.0F, 0.2F), prepare(state, bow, 1).multipliers());
    }

    @Test
    void aRestartOfTheLastItemKeepsItsMultiplierOnceTheHandIsEmpty() {
        var state = restartedAndCompleted(food());
        assertEquals(List.of(1.0F, 0.2F), prepare(state, SimItemStack.EMPTY).multipliers());
        state.serverAnswered(SEQUENCE);
        assertEquals(List.of(1.0F, 0.2F), prepare(state, SimItemStack.EMPTY, 1).multipliers());
    }

    @Test
    void aRestartAddsNothingWhileTheHeldUseAlreadySlowsByItsMultiplier() {
        var state = new ClientItemUseState();
        var food = food();
        state.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
        state.completionSent();
        state.restarted(Hand.OFF_HAND, food, SEQUENCE, 0);
        assertEquals(List.of(0.2F), prepare(state, food).multipliers());
    }

    private static ClientItemUseState restartedAndCompleted(SimItemStack restartedStack) {
        return restartedAndCompleted(restartedStack, 0);
    }

    /** A held offhand food use whose completion is in flight, restarted with {@code restartedStack}, then the completion handled. */
    private static ClientItemUseState restartedAndCompleted(SimItemStack restartedStack, int tick) {
        var state = new ClientItemUseState();
        var food = food();
        state.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
        state.completionSent();
        assertTrue(state.restartsBeforeCompletion());
        state.restarted(Hand.OFF_HAND, restartedStack, SEQUENCE, tick);
        state.release();
        state.completionHandled();
        assertFalse(state.restartsBeforeCompletion());
        return state;
    }

    /** A held main-hand food use whose completion is in flight, restarted with {@code restartedStack} in tick 0. */
    private static ClientItemUseState mainHandRestart(SimItemStack restartedStack) {
        var main = new ClientItemUseState();
        var food = food();
        main.start(Hand.MAIN_HAND, food, ClientItemUseState.duration(food), true);
        main.completionSent();
        assertTrue(main.restartsBeforeCompletion());
        main.restarted(Hand.MAIN_HAND, restartedStack, SEQUENCE, 0);
        main.release();
        main.completionHandled();
        assertEquals(
                List.of(1.0F, 0.2F),
                main.prepare(0, 3, VERSION, restartedStack, SimItemStack.EMPTY, hotbar(restartedStack))
                        .multipliers());
        return main;
    }

    private static ClientItemUseState.Snapshot prepare(ClientItemUseState state, SimItemStack off) {
        return prepare(state, off, 0);
    }

    private static ClientItemUseState.Snapshot prepare(ClientItemUseState state, SimItemStack off, int tick) {
        return state.prepare(tick, 3, VERSION, SimItemStack.EMPTY, off, hotbar());
    }

    private static SimItemStack food() {
        return ItemUtil.modelItems().stack("minecraft:golden_apple", 2);
    }

    /** No consumable component: LivingEntity holds its use until a release. */
    private static SimItemStack bow() {
        return ItemUtil.modelItems().stack("minecraft:bow", 1);
    }

    private static List<SimItemStack> hotbar() {
        return hotbar(SimItemStack.EMPTY);
    }

    private static List<SimItemStack> hotbar(SimItemStack stack) {
        return Collections.nCopies(9, stack);
    }
}
