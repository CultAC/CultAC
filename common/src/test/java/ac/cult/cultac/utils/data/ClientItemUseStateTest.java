package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.inventory.ItemUtil;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientItemUseStateTest {
    @Test
    void completionRequiresTheSameOffhandStackObjectUntilTheNextEntityTick() {
        var state = new ClientItemUseState();
        var food = food();
        var offhand = new ClientItemUseState.Completions(
                List.of(new ClientItemUseState.Completion(Hand.OFF_HAND, 40)), false);
        state.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
        assertEquals(offhand, state.completions(hotbar(SimItemStack.EMPTY), food, 0, false));
        var replacement = food.copy();
        assertEquals(
                new ClientItemUseState.Completions(List.of(), true),
                state.completions(hotbar(SimItemStack.EMPTY), replacement, 0, false));
        state.prepare(0, 3, ClientVersion.V_1_21_2, SimItemStack.EMPTY, replacement, hotbar(SimItemStack.EMPTY));
        assertEquals(offhand, state.completions(hotbar(SimItemStack.EMPTY), replacement, 0, false));
    }

    @Test
    void unconfirmedHotbarCompletesAnyEqualStack() {
        var state = new ClientItemUseState();
        var first = food();
        var second = food();
        var hotbar = List.of(first, second, first, first, first, first, first, first, first);
        state.start(Hand.MAIN_HAND, first, ClientItemUseState.duration(first), true);
        state.prepare(0, 3, ClientVersion.V_1_21_2, first, SimItemStack.EMPTY, hotbar);
        var completions = state.completions(hotbar, SimItemStack.EMPTY, 0, true);
        assertFalse(completions.skipped());
        assertEquals(9, completions.completions().size());
        assertEquals(
                new ClientItemUseState.Completion(Hand.MAIN_HAND, 1),
                completions.completions().get(1));
        // An empty slot is a possible silent stop: completion may do nothing.
        var mixed = List.of(first, second, SimItemStack.EMPTY, first, first, first, first, first, first);
        state.prepare(1, 3, ClientVersion.V_1_21_2, first, SimItemStack.EMPTY, mixed);
        assertTrue(state.completions(mixed, SimItemStack.EMPTY, 0, true).skipped());
    }

    @Test
    void confirmedHotbarCompletesOnlyTheSelectedSlot() {
        var state = new ClientItemUseState();
        var hotbar = new ArrayList<SimItemStack>();
        for (int slot = 0; slot < 9; slot++) hotbar.add(food());
        state.start(Hand.MAIN_HAND, hotbar.get(4), ClientItemUseState.duration(hotbar.get(4)), true);
        state.prepare(0, 3, ClientVersion.V_1_21_2, hotbar.get(4), SimItemStack.EMPTY, hotbar);
        assertEquals(
                new ClientItemUseState.Completions(
                        List.of(new ClientItemUseState.Completion(Hand.MAIN_HAND, 4)), false),
                state.completions(hotbar, SimItemStack.EMPTY, 4, false));
    }

    @Test
    void uncertainStartRequiresNoSlowdown() {
        var state = new ClientItemUseState();
        var food = food();
        state.start(Hand.OFF_HAND, food, 32, false);
        var snapshot =
                state.prepare(0, 3, ClientVersion.V_1_21_2, SimItemStack.EMPTY, food, hotbar(SimItemStack.EMPTY));
        assertEquals(1, snapshot.legalMultiplier());
        assertFalse(snapshot.definitelyUsing());
        assertEquals(Hand.OFF_HAND, state.hand());
        assertFalse(snapshot.required());
    }

    @Test
    void equalStacksKeepOneCandidateEach() {
        var state = new ClientItemUseState();
        var hotbar = new ArrayList<SimItemStack>();
        for (int slot = 0; slot < 9; slot++) hotbar.add(food());
        state.start(Hand.MAIN_HAND, hotbar.getFirst(), 32, false);
        state.serverFlags(true, Hand.MAIN_HAND, hotbar.getFirst(), SimItemStack.EMPTY, hotbar);
        var expected = new ArrayList<ClientItemUseState.Completion>();
        for (int slot = 0; slot < 9; slot++) expected.add(new ClientItemUseState.Completion(Hand.MAIN_HAND, slot));
        for (int tick = 0; tick < 3; tick++) {
            var snapshot =
                    state.prepare(tick, 3, ClientVersion.V_1_21_2, hotbar.getFirst(), SimItemStack.EMPTY, hotbar);
            assertEquals(new ClientItemUseState.Snapshot(1, tick, 3, 0.2F, 0.2F, false, true, true), snapshot);
            assertEquals(
                    new ClientItemUseState.Completions(expected, false),
                    state.completions(hotbar, SimItemStack.EMPTY, 0, true));
            assertEquals(Hand.MAIN_HAND, state.hand());
            assertTrue(state.handKnown());
        }
    }

    private static SimItemStack food() {
        return ItemUtil.modelItems().stack("minecraft:golden_apple", 1);
    }

    private static List<SimItemStack> hotbar(SimItemStack stack) {
        return Collections.nCopies(9, stack);
    }

    @Test
    void everySupportedClientKeepsConsumptionActiveUntilTheServerCompletion() {
        for (ClientVersion version : ClientVersion.values()) {
            if (version.isOlderThan(ClientVersion.V_1_21_2)) continue;
            var state = new ClientItemUseState();
            var food = food();
            state.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
            for (int tick = 0; tick < 100; tick++) {
                var snapshot = state.prepare(tick, 4, version, SimItemStack.EMPTY, food, hotbar(SimItemStack.EMPTY));
                assertEquals(0.2F, snapshot.legalMultiplier(), 1e-6);
                assertTrue(snapshot.required());
                assertTrue(snapshot.definitelyUsing());
            }
            state.release(); // Player.handleEntityEvent(9), not a guessed client duration.
            assertFalse(state.prepare(100, 5, version, SimItemStack.EMPTY, food, hotbar(SimItemStack.EMPTY))
                    .required());
        }
    }

    @Test
    void unreportedDifferentItemCanStopLocallyWithoutForgivingTheUseObligation() {
        var state = new ClientItemUseState();
        var food = food();
        state.start(Hand.MAIN_HAND, food, ClientItemUseState.duration(food), true);
        var snapshot = state.prepare(
                0, 3, ClientVersion.V_1_21_2, food, SimItemStack.EMPTY, List.of(food, SimItemStack.EMPTY));
        assertEquals(1, snapshot.legalMultiplier());
        assertEquals(0.2F, snapshot.requiredMultiplier());
        assertTrue(snapshot.required());
        assertFalse(snapshot.definitelyUsing());
        // The silently stopped candidate survives after scroll restores the original slot.
        assertEquals(
                1,
                state.prepare(1, 3, ClientVersion.V_1_21_2, food, SimItemStack.EMPTY, hotbar(food))
                        .legalMultiplier());
    }

    @Test
    void sameItemSlotChangeOnlyClosesTheServerMainHandObligation() {
        var state = new ClientItemUseState();
        var food = food();
        state.start(Hand.MAIN_HAND, food, ClientItemUseState.duration(food), true);
        state.claimedSlotChanged();
        var snapshot = state.prepare(0, 3, ClientVersion.V_1_21_2, food, SimItemStack.EMPTY, hotbar(food));
        assertTrue(snapshot.definitelyUsing());
        assertEquals(0.2F, snapshot.legalMultiplier());
        assertFalse(snapshot.required());
    }

    @Test
    void mainHandSlotChangesDoNotReleaseOffhandUse() {
        var state = new ClientItemUseState();
        var food = food();
        state.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
        state.claimedSlotChanged();
        assertTrue(state.prepare(0, 3, ClientVersion.V_1_21_2, SimItemStack.EMPTY, food, hotbar(SimItemStack.EMPTY))
                .required());
    }

    @Test
    void repeatedMetadataDoesNotReplaceAnAlreadyActiveLocalHand() {
        var state = new ClientItemUseState();
        var food = food();
        state.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
        state.serverFlags(true, Hand.MAIN_HAND, food, food, hotbar(food));
        assertEquals(Hand.OFF_HAND, state.hand());
        assertTrue(state.prepare(0, 3, ClientVersion.V_1_21_2, food, food, hotbar(food))
                .definitelyUsing());
    }

    @Test
    void sprintPermissionIsIndependentOfTheSpeedMultiplierAndVersion() {
        var food = food();
        food.components(food.components()
                .with(
                        "minecraft:use_effects",
                        JsonParser.parseString("{\"can_sprint\":true,\"speed_multiplier\":0.1}")));
        var state = new ClientItemUseState();
        state.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
        var modern = state.prepare(0, 1, ClientVersion.V_1_21_11, SimItemStack.EMPTY, food, hotbar(SimItemStack.EMPTY));
        assertTrue(modern.canSprint());
        assertEquals(0.1F, modern.legalMultiplier());
        var legacy = state.prepare(1, 1, ClientVersion.V_1_21_2, SimItemStack.EMPTY, food, hotbar(SimItemStack.EMPTY));
        assertFalse(legacy.canSprint());
        assertEquals(0.2F, legacy.legalMultiplier());
    }

    @Test
    void releaseAfterMovementCannotRetroactivelyClearTheTravelSnapshot() {
        var state = new ClientItemUseState();
        var food = food();
        state.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
        var before = state.prepare(0, 1, ClientVersion.V_1_21_2, food, food, hotbar(food));
        state.release();
        assertSame(before, state.prepare(0, 2, ClientVersion.V_1_21_2, food, food, hotbar(food)));
        assertFalse(state.prepare(1, 2, ClientVersion.V_1_21_2, food, food, hotbar(food))
                .required());
    }

    @Test
    void brushCallbackCanReleaseWithoutSendingTheGameModeReleasePacket() {
        var brush = ItemUtil.modelItems().stack("minecraft:brush", 1);
        var state = new ClientItemUseState();
        state.start(Hand.OFF_HAND, brush, ClientItemUseState.duration(brush), true);
        var snapshot = state.prepare(0, 1, ClientVersion.V_1_21_2, food(), brush, hotbar(food()));
        assertEquals(1, snapshot.legalMultiplier());
        assertTrue(snapshot.required());
    }
}
