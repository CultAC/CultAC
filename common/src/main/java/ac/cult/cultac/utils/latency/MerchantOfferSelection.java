package ac.cult.cultac.utils.latency;

import ac.cult.cultac.utils.inventory.InventoryItem;
import java.util.List;

/** The existing compensated trade selection rules, evaluated on owned item/count facts. */
final class MerchantOfferSelection {
    record Offer(InventoryItem costA, InventoryItem costB, InventoryItem result, boolean outOfStock) {}

    private MerchantOfferSelection() {}

    static int findSatisfiedOfferIndex(List<Offer> offers, InventoryItem buyA, InventoryItem buyB, int selectedTrade) {
        // Vanilla treats hint 0 as "search all offers" and only pins positive hints.
        if (selectedTrade > 0 && selectedTrade < offers.size())
            return satisfiedBy(offers.get(selectedTrade), buyA, buyB) ? selectedTrade : -1;
        for (int index = 0; index < offers.size(); index++)
            if (satisfiedBy(offers.get(index), buyA, buyB)) return index;
        return -1;
    }

    static boolean matchesResult(
            List<InventoryItem> slots, List<Offer> offers, int selectedTrade, InventoryItem result) {
        if (slots == null || slots.size() < 3 || offers == null || empty(result)) return false;
        int index = findSatisfiedOfferIndex(offers, slots.get(0), slots.get(1), selectedTrade);
        if (index < 0) index = findSatisfiedOfferIndex(offers, slots.get(1), slots.get(0), selectedTrade);
        if (index < 0) return false;
        var offer = offers.get(index);
        return offer.result().item() == result.item() && offer.result().count() == result.count();
    }

    static boolean costMatches(InventoryItem cost, InventoryItem stack) {
        return !empty(cost) && !empty(stack) && cost.item() == stack.item();
    }

    private static boolean satisfiedBy(Offer offer, InventoryItem buyA, InventoryItem buyB) {
        if (offer == null
                || offer.outOfStock()
                || !costMatches(offer.costA(), buyA)
                || buyA.count() < offer.costA().count()) return false;
        return empty(offer.costB())
                ? empty(buyB)
                : costMatches(offer.costB(), buyB)
                        && buyB.count() >= offer.costB().count();
    }

    private static boolean empty(InventoryItem item) {
        return item == null || item.isEmpty();
    }
}
