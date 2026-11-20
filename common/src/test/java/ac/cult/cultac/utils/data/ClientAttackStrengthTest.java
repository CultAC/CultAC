package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.utils.inventory.ItemUtil;
import org.junit.jupiter.api.Test;

class ClientAttackStrengthTest {
    // Diamond sword: 4.0 base attack speed with a -2.4 mainhand modifier.
    private static final float SWORD_DELAY = ClientAttackStrength.delay(4.0 + -2.4000000953674316);

    private static SimItemStack item(String key) {
        return ItemUtil.modelItems().stack(key, 1);
    }

    private static ClientAttackStrength holding(SimItemStack mainHand, int ticks) {
        ClientAttackStrength strength = new ClientAttackStrength();
        strength.onClientTick(mainHand, false);
        strength.onReset();
        for (int i = 0; i < ticks; i++) strength.onClientTick(mainHand, false);
        return strength;
    }

    @Test
    void swordNeedsElevenTicksSinceTheReset() {
        SimItemStack sword = item("minecraft:diamond_sword");
        // (10 + 0.5) / 12.5 = 0.84; (11 + 0.5) / 12.5 = 0.92
        assertFalse(holding(sword, 10).canBeFullStrength(SWORD_DELAY));
        assertTrue(holding(sword, 11).canBeFullStrength(SWORD_DELAY));
        assertTrue(holding(sword, 11).isFullStrength(SWORD_DELAY));
    }

    @Test
    void possibleMissResetOnlyLowersTheMinimum() {
        SimItemStack sword = item("minecraft:diamond_sword");
        ClientAttackStrength strength = holding(sword, 20);
        strength.onPossibleReset();
        assertTrue(strength.canBeFullStrength(SWORD_DELAY));
        assertFalse(strength.isFullStrength(SWORD_DELAY));
    }

    @Test
    void claimedItemChangeMayHaveResetInThePreviousTick() {
        SimItemStack sword = item("minecraft:diamond_sword");
        SimItemStack axe = item("minecraft:diamond_axe");
        ClientAttackStrength strength = holding(sword, 20);
        // SetCarriedItem arrives at the start of the tick after a hotbar key changed the slot.
        strength.onClaimedMainHand(axe);
        assertTrue(strength.canBeFullStrength(SWORD_DELAY));
        assertFalse(strength.isFullStrength(SWORD_DELAY));
        // Player#tick resets on the different item, here or in the previous tick.
        strength.onClientTick(axe, true);
        for (int i = 0; i < 10; i++) strength.onClientTick(axe, false);
        // At most 11 ticks (reset one tick earlier), at least 10.
        assertTrue(strength.canBeFullStrength(SWORD_DELAY));
        assertFalse(strength.isFullStrength(SWORD_DELAY));
    }

    @Test
    void serverConfirmedChangeKeepsTheMaximum() {
        SimItemStack food = item("minecraft:golden_apple");
        ClientAttackStrength strength = holding(food, 20);
        // LivingEntity#completeUsingItem emptied the hand earlier; the slot update only now arrives.
        strength.onClientTick(SimItemStack.EMPTY, false);
        float fistDelay = ClientAttackStrength.delay(4.0);
        assertTrue(strength.canBeFullStrength(fistDelay));
        assertFalse(strength.isFullStrength(fistDelay));
    }

    @Test
    void sameItemWithNewComponentsDoesNotReset() {
        SimItemStack sword = item("minecraft:diamond_sword");
        ClientAttackStrength strength = holding(sword, 20);
        strength.onClientTick(sword.copyWithCount(2), false);
        assertTrue(strength.isFullStrength(SWORD_DELAY));
    }

    @Test
    void completionReplacementResetsAtPlayerTickAndSurvivesAnInterveningAttack() {
        SimItemStack food = item("minecraft:golden_apple");
        ClientAttackStrength strength = holding(food, 20);
        strength.onPossibleMainHandChange();
        // Player#attack before Player#tick samples the original ticker, even after the completion packet.
        assertTrue(strength.isFullStrength(SWORD_DELAY));
        strength.onClientTick(food, false);
        assertFalse(strength.isFullStrength(SWORD_DELAY));
        assertTrue(strength.canBeFullStrength(SWORD_DELAY));

        strength.onPossibleMainHandChange();
        strength.onReset();
        strength.onClientTick(food, false);
        float fastDelay = ClientAttackStrength.delay(20.0);
        assertFalse(strength.isFullStrength(fastDelay));
        assertTrue(strength.canBeFullStrength(fastDelay));
        strength.reset();
        strength.onClientTick(food, false);
        strength.onReset();
        strength.onClientTick(food, false);
        assertTrue(strength.isFullStrength(fastDelay));
    }

    @Test
    void completionOnlyChangesItemForTheLastConsumableWithADifferentRemainder() {
        SimItemStack apple = item("minecraft:golden_apple");
        SimItemStack honey = item("minecraft:honey_bottle");
        assertTrue(ClientAttackStrength.changesItemOnUseCompletion(apple, false));
        assertTrue(ClientAttackStrength.changesItemOnUseCompletion(honey, false));
        assertFalse(ClientAttackStrength.changesItemOnUseCompletion(apple.copyWithCount(2), false));
        assertFalse(ClientAttackStrength.changesItemOnUseCompletion(honey, true));
        assertFalse(ClientAttackStrength.changesItemOnUseCompletion(item("minecraft:bow"), false));
        assertFalse(ClientAttackStrength.changesItemOnUseCompletion(SimItemStack.EMPTY, false));
        honey.components(honey.components()
                .with("minecraft:use_remainder", new com.google.gson.JsonPrimitive("minecraft:honey_bottle")));
        assertFalse(ClientAttackStrength.changesItemOnUseCompletion(honey, false));
    }
}
