package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.inventory.ItemUtil;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The multiplier of a use this state held, kept for the client ticks after its end. */
class ClientItemUseEndedTest {
    private static final ClientVersion MODERN = ClientVersion.V_1_21_11;

    @Test
    void anEndedUseKeepsItsMultiplierForItsWindow() {
        var food = food();
        var state = using(food);
        state.release();
        for (int tick = 1; tick <= 11; tick++) assertEquals(0.2F, ended(state, tick, food), "tick " + tick);
        assertEquals(1.0F, ended(state, 12, food));
        assertEquals(1.0F, ended(state, 13, food));
    }

    @Test
    void theTicksCountFromTheFirstTickAfterTheEnd() {
        var food = food();
        var state = using(food);
        state.release();
        assertEquals(1.0F, state.prepare(0, 1, MODERN, SimItemStack.EMPTY, food, hotbar()).endedMultiplier());
        assertEquals(0.2F, ended(state, 40, food));
        assertEquals(0.2F, ended(state, 50, food));
        assertEquals(1.0F, ended(state, 51, food));
    }

    @Test
    void anObligationAloneIsAHeldUse() {
        var food = food();
        var state = new ClientItemUseState();
        state.serverFlags(true, Hand.OFF_HAND, SimItemStack.EMPTY, food, hotbar());
        state.prepare(0, 1, MODERN, SimItemStack.EMPTY, SimItemStack.EMPTY, hotbar());
        state.release();
        assertEquals(0.2F, ended(state, 1, food));
    }

    @Test
    void aReleaseWithoutAHeldUseOpensNothing() {
        var state = new ClientItemUseState();
        state.release();
        assertEquals(1.0F, ended(state, 1, food()));
    }

    @Test
    void aLaterReleaseWithoutAHeldUseKeepsTheEndedUse() {
        var food = food();
        var state = using(food);
        state.release();
        assertEquals(0.2F, ended(state, 1, food));
        state.release();
        assertEquals(0.2F, ended(state, 2, food));
        assertEquals(0.2F, ended(state, 11, food));
        assertEquals(1.0F, ended(state, 12, food));
    }

    @Test
    void aUseHeldAgainReplacesTheEndedUse() {
        var food = food();
        var state = using(food);
        state.release();
        assertEquals(0.2F, ended(state, 1, food));
        state.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
        assertEquals(1.0F, ended(state, 2, food));
        state.release();
        for (int tick = 3; tick <= 13; tick++) assertEquals(0.2F, ended(state, tick, food), "tick " + tick);
        assertEquals(1.0F, ended(state, 14, food));
    }

    @Test
    void aNewLocalPlayerEndsTheEndedUse() {
        var food = food();
        var state = using(food);
        state.release();
        assertEquals(0.2F, ended(state, 1, food));
        state.reset();
        assertEquals(1.0F, ended(state, 2, food));
    }

    @Test
    void clientsBefore12111KeepNoEndedUse() {
        var food = food();
        var state = using(food);
        state.release();
        assertEquals(
                1.0F,
                state.prepare(1, 1, ClientVersion.V_1_21_2, SimItemStack.EMPTY, food, hotbar()).endedMultiplier());
    }

    @Test
    void anEndedUseLeavesTheLegalAndRequiredMultipliers() {
        var food = food();
        var state = using(food);
        state.release();
        var snapshot = state.prepare(1, 1, MODERN, SimItemStack.EMPTY, food, hotbar());
        assertEquals(1.0F, snapshot.legalMultiplier());
        assertEquals(1.0F, snapshot.requiredMultiplier());
        assertEquals(1.0F, snapshot.slowestMultiplier());
    }

    private static ClientItemUseState using(SimItemStack food) {
        var state = new ClientItemUseState();
        state.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
        state.prepare(0, 1, MODERN, SimItemStack.EMPTY, food, hotbar());
        return state;
    }

    private static float ended(ClientItemUseState state, int tick, SimItemStack off) {
        return state.prepare(tick, 1, MODERN, SimItemStack.EMPTY, off, hotbar()).endedMultiplier();
    }

    private static SimItemStack food() {
        return ItemUtil.modelItems().stack("minecraft:golden_apple", 2);
    }

    private static List<SimItemStack> hotbar() {
        return Collections.nCopies(9, SimItemStack.EMPTY);
    }
}
