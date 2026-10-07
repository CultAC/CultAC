package ac.cult.cultac.utils.latency;

import ac.cult.cultac.utils.inventory.InventoryItem;
import ac.cult.cultac.utils.inventory.inventory.MenuType;
import ac.cult.cultac.utils.latency.MerchantOfferSelection.Offer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class PredictedResultSlotValidator {
    private PredictedResultSlotValidator() {}

    record ResultAllowance(int material, long amount) {}

    static int resultSlot(MenuType menuType) {
        return switch (menuType) {
            case STONECUTTER -> 1;
            case ANVIL, GRINDSTONE, MERCHANT, CARTOGRAPHY_TABLE -> 2;
            case LOOM -> 3;
            default -> -1;
        };
    }

    static ResultAllowance dialogResultAllowance(
            MenuType menuType,
            int clickedSlot,
            List<InventoryItem> beforeSlots,
            InventoryItem beforeCarried,
            List<InventoryItem> afterSlots,
            InventoryItem afterCarried,
            List<Offer> merchantOffers,
            int selectedOffer,
            boolean sameResultComponents) {
        int resultSlot = resultSlot(menuType);
        if (resultSlot < 0 || resultSlot >= beforeSlots.size() || resultSlot >= afterSlots.size()) {
            return null;
        }

        InventoryItem beforeResult = beforeSlots.get(resultSlot);
        InventoryItem afterResult = afterSlots.get(resultSlot);
        if (menuType != MenuType.MERCHANT) {
            if (isEmpty(beforeResult) || !isEmpty(afterResult) && !sameResultComponents) {
                return null;
            }
            afterResult = beforeResult;
        }
        InventoryItem result = !isEmpty(afterResult) ? afterResult : beforeResult;
        if (isEmpty(result) && clickedSlot == resultSlot) {
            if (menuType != MenuType.MERCHANT) {
                return null;
            }
            result = findClaimedResult(
                    menuType, beforeSlots, beforeCarried, afterSlots, afterCarried, merchantOffers, selectedOffer);
        }
        if (isEmpty(result)
                || !isSaneDialogResult(menuType, beforeSlots, afterSlots, result, merchantOffers, selectedOffer)) {
            return null;
        }

        long credit = materialCount(afterSlots, afterCarried, result.item())
                - materialCount(beforeSlots, beforeCarried, result.item());
        if (credit <= 0) {
            return null;
        }
        if (clickedSlot == resultSlot && !inputsConsumed(menuType, beforeSlots, afterSlots)) {
            return null;
        }
        if (clickedSlot != resultSlot
                && amountOf(afterResult, result.item()) <= amountOf(beforeResult, result.item())) {
            return null;
        }

        return new ResultAllowance(result.item(), credit);
    }

    private static boolean isSaneDialogResult(
            MenuType menuType,
            List<InventoryItem> beforeSlots,
            List<InventoryItem> afterSlots,
            InventoryItem result,
            List<Offer> merchantOffers,
            int selectedOffer) {
        return isSaneDialogResult(menuType, beforeSlots, result, merchantOffers, selectedOffer)
                || isSaneDialogResult(menuType, afterSlots, result, merchantOffers, selectedOffer);
    }

    static boolean isSaneDialogResult(
            MenuType menuType,
            List<InventoryItem> slots,
            InventoryItem result,
            List<Offer> merchantOffers,
            int selectedOffer) {
        if (slots == null || slots.isEmpty() || isEmpty(result)) {
            return false;
        }

        InventoryItem input = slots.get(0);
        return switch (menuType) {
            case STONECUTTER -> !isEmpty(input) && input.isBlockItem() && result.isBlockItem();
            case ANVIL -> sameMaterial(input, result) && result.count() <= input.count();
            case GRINDSTONE -> slots.size() >= 2 && saneGrindstoneResult(slots, result);
            case CARTOGRAPHY_TABLE -> slots.size() >= 2 && saneCartographyResult(slots, result);
            case LOOM ->
                slots.size() >= 2
                        && sameMaterial(input, result)
                        && input.key().endsWith("_banner")
                        && !isEmpty(slots.get(1))
                        && slots.get(1).key().endsWith("_dye")
                        && result.count() == 1;
            case MERCHANT -> MerchantOfferSelection.matchesResult(slots, merchantOffers, selectedOffer, result);
            default -> false;
        };
    }

    private static InventoryItem findClaimedResult(
            MenuType menuType,
            List<InventoryItem> beforeSlots,
            InventoryItem beforeCarried,
            List<InventoryItem> afterSlots,
            InventoryItem afterCarried,
            List<Offer> merchantOffers,
            int selectedOffer) {
        List<InventoryItem> candidates = new java.util.ArrayList<>(afterSlots.size() + 1);
        candidates.add(afterCarried);
        candidates.addAll(afterSlots);
        for (InventoryItem candidate : candidates) {
            if (isEmpty(candidate)) {
                continue;
            }

            long gained = materialCount(afterSlots, afterCarried, candidate.item())
                    - materialCount(beforeSlots, beforeCarried, candidate.item());
            if (gained <= 0) {
                continue;
            }

            long consumedSameMaterial = consumedInputAmount(menuType, beforeSlots, afterSlots, candidate.item());
            InventoryItem claimedResult =
                    candidate.withCount((int) Math.min(Integer.MAX_VALUE, gained + consumedSameMaterial));
            if (isSaneDialogResult(menuType, beforeSlots, afterSlots, claimedResult, merchantOffers, selectedOffer)) {
                return claimedResult;
            }
        }
        return InventoryItem.EMPTY;
    }

    static boolean isClientClaimPlausible(
            List<InventoryItem> beforeSlots,
            InventoryItem beforeCarried,
            List<InventoryItem> afterSlots,
            InventoryItem afterCarried,
            boolean allowCreativeCreation) {
        return isClientClaimPlausible(
                beforeSlots, beforeCarried, afterSlots, afterCarried, allowCreativeCreation, -1, 0);
    }

    static boolean isClientClaimPlausible(
            List<InventoryItem> beforeSlots,
            InventoryItem beforeCarried,
            List<InventoryItem> afterSlots,
            InventoryItem afterCarried,
            boolean allowCreativeCreation,
            int creditedMaterial,
            long creditedAmount) {
        if (beforeSlots.size() != afterSlots.size()) {
            return false;
        }
        if (allowCreativeCreation) {
            return true;
        }

        Map<Integer, Long> beforeCounts = new HashMap<>();
        Map<Integer, Long> afterCounts = new HashMap<>();
        if (!addStacks(beforeCounts, beforeSlots) || !addStack(beforeCounts, beforeCarried)) {
            return false;
        }
        if (!addStacks(afterCounts, afterSlots) || !addStack(afterCounts, afterCarried)) {
            return false;
        }

        for (Map.Entry<Integer, Long> entry : afterCounts.entrySet()) {
            long credit = entry.getKey() == creditedMaterial ? Math.max(0, creditedAmount) : 0;
            if (entry.getValue() > beforeCounts.getOrDefault(entry.getKey(), 0L) + credit) {
                return false;
            }
        }
        return true;
    }

    private static boolean addStacks(Map<Integer, Long> counts, List<InventoryItem> stacks) {
        for (InventoryItem stack : stacks) {
            if (!addStack(counts, stack)) {
                return false;
            }
        }
        return true;
    }

    private static boolean addStack(Map<Integer, Long> counts, InventoryItem stack) {
        if (isEmpty(stack)) {
            return true;
        }
        if (stack.count() <= 0 || stack.item() == 0) {
            return false;
        }

        counts.merge(stack.item(), (long) stack.count(), Long::sum);
        return true;
    }

    static boolean costMatches(InventoryItem cost, InventoryItem stack) {
        return MerchantOfferSelection.costMatches(cost, stack);
    }

    private static boolean saneGrindstoneResult(List<InventoryItem> slots, InventoryItem result) {
        int available = 0;
        for (int slot = 0; slot < 2; slot++) {
            InventoryItem input = slots.get(slot);
            if (sameMaterial(input, result)
                    || !isEmpty(input) && input.is("minecraft:enchanted_book") && result.is("minecraft:book")) {
                available += input.count();
            }
        }
        return available > 0 && result.count() <= available;
    }

    private static boolean saneCartographyResult(List<InventoryItem> slots, InventoryItem result) {
        InventoryItem map = slots.get(0);
        InventoryItem additional = slots.get(1);
        if (isEmpty(map)
                || isEmpty(additional)
                || !map.is("minecraft:filled_map")
                || !result.is("minecraft:filled_map")) {
            return false;
        }
        return additional.is("minecraft:map")
                ? result.count() == 2
                : (additional.is("minecraft:paper") || additional.is("minecraft:glass_pane")) && result.count() == 1;
    }

    private static boolean sameMaterial(InventoryItem first, InventoryItem second) {
        return !isEmpty(first) && !isEmpty(second) && first.item() == second.item();
    }

    private static boolean inputsConsumed(MenuType menuType, List<InventoryItem> before, List<InventoryItem> after) {
        int inputSlots = dialogInputSlots(menuType);
        long beforeAmount = 0;
        long afterAmount = 0;
        for (int slot = 0; slot < inputSlots && slot < before.size() && slot < after.size(); slot++) {
            beforeAmount += isEmpty(before.get(slot)) ? 0 : before.get(slot).count();
            afterAmount += isEmpty(after.get(slot)) ? 0 : after.get(slot).count();
        }
        return afterAmount < beforeAmount;
    }

    private static long consumedInputAmount(
            MenuType menuType, List<InventoryItem> before, List<InventoryItem> after, int material) {
        int inputSlots = dialogInputSlots(menuType);
        long beforeAmount = 0;
        long afterAmount = 0;
        for (int slot = 0; slot < inputSlots && slot < before.size() && slot < after.size(); slot++) {
            beforeAmount += amountOf(before.get(slot), material);
            afterAmount += amountOf(after.get(slot), material);
        }
        return Math.max(0, beforeAmount - afterAmount);
    }

    private static int dialogInputSlots(MenuType menuType) {
        return switch (menuType) {
            case STONECUTTER -> 1;
            case ANVIL, GRINDSTONE, MERCHANT, CARTOGRAPHY_TABLE -> 2;
            case LOOM -> 3;
            default -> 0;
        };
    }

    private static long materialCount(List<InventoryItem> slots, InventoryItem carried, int material) {
        long count = amountOf(carried, material);
        for (InventoryItem slot : slots) {
            count += amountOf(slot, material);
        }
        return count;
    }

    private static int amountOf(InventoryItem stack, int material) {
        return material >= 0 && !isEmpty(stack) && stack.item() == material ? stack.count() : 0;
    }

    private static boolean isEmpty(InventoryItem stack) {
        return stack == null || stack.isEmpty();
    }
}
