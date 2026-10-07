package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.utils.inventory.InventoryItem;
import ac.cult.cultac.utils.inventory.inventory.MenuType;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryValidationTest {
    private static InventoryItem item(String key, int count) {
        return new InventoryItem(
                ModelRegistryData.load(ProtocolVersion.V26_3)
                        .registry("minecraft:item")
                        .id(key),
                count);
    }

    @Test
    void conservationIncludesTheCursorAndRejectsCreationOfAnotherMaterial() {
        var diamonds = item("minecraft:diamond", 2);
        var iron = item("minecraft:iron_ingot", 3);
        var before = List.of(diamonds, iron);
        var after = List.of(InventoryItem.EMPTY, iron);
        assertTrue(PredictedResultSlotValidator.isClientClaimPlausible(
                before, InventoryItem.EMPTY, after, diamonds, false));
        assertFalse(PredictedResultSlotValidator.isClientClaimPlausible(
                before, InventoryItem.EMPTY, after, diamonds.withCount(3), false));
        assertFalse(PredictedResultSlotValidator.isClientClaimPlausible(
                before, InventoryItem.EMPTY, after, diamonds.withCount(3), false, iron.item(), 1));
        assertTrue(PredictedResultSlotValidator.isClientClaimPlausible(
                before, InventoryItem.EMPTY, after, diamonds.withCount(3), false, diamonds.item(), 1));
    }

    @Test
    void conservationAccumulatesCountsAboveTheIntegerLimit() {
        var diamonds = item("minecraft:diamond", Integer.MAX_VALUE);
        var before = List.of(diamonds, diamonds);
        assertTrue(PredictedResultSlotValidator.isClientClaimPlausible(
                before, InventoryItem.EMPTY, List.of(diamonds, InventoryItem.EMPTY), diamonds, false));
        assertFalse(PredictedResultSlotValidator.isClientClaimPlausible(
                before, InventoryItem.EMPTY, before, diamonds, false));
    }

    @Test
    void stonecutterCreditRequiresConsumptionAndCompatibleResultComponents() {
        var stone = item("minecraft:stone", 2);
        var slab = item("minecraft:stone_slab", 2);
        var before = List.of(stone, slab);
        var after = List.of(stone.withCount(1), slab);
        var allowance = PredictedResultSlotValidator.dialogResultAllowance(
                MenuType.STONECUTTER, 1, before, InventoryItem.EMPTY, after, slab, List.of(), 0, true);
        assertEquals(new PredictedResultSlotValidator.ResultAllowance(slab.item(), 2), allowance);
        assertNull(PredictedResultSlotValidator.dialogResultAllowance(
                MenuType.STONECUTTER, 1, before, InventoryItem.EMPTY, before, slab, List.of(), 0, true));
        assertNull(PredictedResultSlotValidator.dialogResultAllowance(
                MenuType.STONECUTTER, 1, before, InventoryItem.EMPTY, after, slab, List.of(), 0, false));
    }

    @Test
    void merchantSelectionSearchesWithZeroHintAndPinsPositiveHints() {
        var emeralds = item("minecraft:emerald", 3);
        var bread = item("minecraft:bread", 1);
        var offers = List.of(
                new MerchantOfferSelection.Offer(emeralds, InventoryItem.EMPTY, bread, true),
                new MerchantOfferSelection.Offer(emeralds, InventoryItem.EMPTY, bread, false));
        assertEquals(1, MerchantOfferSelection.findSatisfiedOfferIndex(offers, emeralds, InventoryItem.EMPTY, 0));
        assertEquals(1, MerchantOfferSelection.findSatisfiedOfferIndex(offers, emeralds, InventoryItem.EMPTY, 1));
        assertEquals(
                -1,
                MerchantOfferSelection.findSatisfiedOfferIndex(offers, emeralds.withCount(2), InventoryItem.EMPTY, 1));
        assertEquals(-1, MerchantOfferSelection.findSatisfiedOfferIndex(offers, emeralds, bread, 0));
        assertTrue(
                MerchantOfferSelection.matchesResult(List.of(InventoryItem.EMPTY, emeralds, bread), offers, 0, bread));
        assertFalse(MerchantOfferSelection.matchesResult(
                List.of(InventoryItem.EMPTY, emeralds, bread), offers, 0, bread.withCount(2)));
    }
}
