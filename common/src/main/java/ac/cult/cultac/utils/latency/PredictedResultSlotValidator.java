package ac.cult.cultac.utils.latency;

import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.inventory.inventory.MenuType;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class PredictedResultSlotValidator {
    private PredictedResultSlotValidator() {
    }

    record MerchantOfferSnapshot(ItemStack costA, ItemStack costB, ItemStack result, boolean outOfStock) {
        static MerchantOfferSnapshot fromNms(net.minecraft.world.item.trading.MerchantOffer offer) {
            return new MerchantOfferSnapshot(
                    SpigotConversionUtil.fromNmsItemStack(offer.getCostA()),
                    SpigotConversionUtil.fromNmsItemStack(offer.getCostB()),
                    SpigotConversionUtil.fromNmsItemStack(offer.getResult()),
                    offer.isOutOfStock()
            );
        }
    }

    record ResultAllowance(Material material, long amount) {
    }

    static ResultAllowance dialogResultAllowance(
            MenuType menuType,
            int clickedSlot,
            List<ItemStack> beforeSlots,
            ItemStack beforeCarried,
            List<ItemStack> afterSlots,
            ItemStack afterCarried,
            List<MerchantOfferSnapshot> merchantOffers,
            int selectedMerchantOffer
    ) {
        int resultSlot = switch (menuType) {
            case STONECUTTER -> 1;
            case ANVIL, GRINDSTONE, MERCHANT, CARTOGRAPHY_TABLE -> 2;
            case LOOM -> 3;
            default -> -1;
        };
        if (resultSlot < 0 || resultSlot >= beforeSlots.size() || resultSlot >= afterSlots.size()) {
            return null;
        }

        ItemStack beforeResult = beforeSlots.get(resultSlot);
        ItemStack afterResult = afterSlots.get(resultSlot);
        if (menuType != MenuType.MERCHANT) {
            if (isEmpty(beforeResult)
                    || !isEmpty(afterResult) && !ItemUtil.isSameItemSameTags(beforeResult, afterResult)) {
                return null;
            }
            afterResult = beforeResult;
        }
        ItemStack result = !isEmpty(afterResult) ? afterResult : beforeResult;
        if (isEmpty(result) && clickedSlot == resultSlot) {
            if (menuType != MenuType.MERCHANT) {
                return null;
            }
            result = findClaimedResult(
                    menuType,
                    beforeSlots,
                    beforeCarried,
                    afterSlots,
                    afterCarried,
                    merchantOffers,
                    selectedMerchantOffer);
        }
        if (isEmpty(result) || !isSaneDialogResult(
                menuType, beforeSlots, afterSlots, result, merchantOffers, selectedMerchantOffer)) {
            return null;
        }

        long credit = materialCount(afterSlots, afterCarried, result.getType())
                - materialCount(beforeSlots, beforeCarried, result.getType());
        if (credit <= 0) {
            return null;
        }
        if (clickedSlot == resultSlot && !inputsConsumed(menuType, beforeSlots, afterSlots)) {
            return null;
        }
        if (clickedSlot != resultSlot
                && amountOf(afterResult, result.getType()) <= amountOf(beforeResult, result.getType())) {
            return null;
        }

        return new ResultAllowance(result.getType(), credit);
    }

    private static boolean isSaneDialogResult(
            MenuType menuType,
            List<ItemStack> beforeSlots,
            List<ItemStack> afterSlots,
            ItemStack result,
            List<MerchantOfferSnapshot> merchantOffers,
            int selectedMerchantOffer
    ) {
        return isSaneDialogResult(menuType, beforeSlots, result, merchantOffers, selectedMerchantOffer)
                || isSaneDialogResult(menuType, afterSlots, result, merchantOffers, selectedMerchantOffer);
    }

    static boolean isSaneDialogResult(
            MenuType menuType,
            List<ItemStack> slots,
            ItemStack result,
            List<MerchantOfferSnapshot> merchantOffers,
            int selectedMerchantOffer
    ) {
        if (slots == null || slots.isEmpty() || isEmpty(result)) {
            return false;
        }

        ItemStack input = slots.get(0);
        return switch (menuType) {
            case STONECUTTER -> !isEmpty(input) && input.getType().isBlock() && result.getType().isBlock();
            case ANVIL -> sameMaterial(input, result) && result.getAmount() <= input.getAmount();
            case GRINDSTONE -> slots.size() >= 2 && saneGrindstoneResult(slots, result);
            case CARTOGRAPHY_TABLE -> slots.size() >= 2 && saneCartographyResult(slots, result);
            case LOOM -> slots.size() >= 2
                    && sameMaterial(input, result)
                    && input.getType().name().endsWith("_BANNER")
                    && !isEmpty(slots.get(1))
                    && slots.get(1).getType().name().endsWith("_DYE")
                    && result.getAmount() == 1;
            case MERCHANT -> PredictedMerchantInventory.matchesResult(
                    slots, merchantOffers, selectedMerchantOffer, result);
            default -> false;
        };
    }

    private static ItemStack findClaimedResult(
            MenuType menuType,
            List<ItemStack> beforeSlots,
            ItemStack beforeCarried,
            List<ItemStack> afterSlots,
            ItemStack afterCarried,
            List<MerchantOfferSnapshot> merchantOffers,
            int selectedMerchantOffer
    ) {
        List<ItemStack> candidates = new java.util.ArrayList<>(afterSlots.size() + 1);
        candidates.add(afterCarried);
        candidates.addAll(afterSlots);
        for (ItemStack candidate : candidates) {
            if (isEmpty(candidate)) {
                continue;
            }

            long gained = materialCount(afterSlots, afterCarried, candidate.getType())
                    - materialCount(beforeSlots, beforeCarried, candidate.getType());
            if (gained <= 0) {
                continue;
            }

            long consumedSameMaterial = consumedInputAmount(
                    menuType, beforeSlots, afterSlots, candidate.getType());
            ItemStack claimedResult = ItemUtil.copy(candidate);
            claimedResult.setAmount((int) Math.min(Integer.MAX_VALUE, gained + consumedSameMaterial));
            if (isSaneDialogResult(
                    menuType,
                    beforeSlots,
                    afterSlots,
                    claimedResult,
                    merchantOffers,
                    selectedMerchantOffer)) {
                return claimedResult;
            }
        }
        return ItemStack.empty();
    }

    static boolean isClientClaimPlausible(
            List<ItemStack> beforeSlots,
            ItemStack beforeCarried,
            List<ItemStack> afterSlots,
            ItemStack afterCarried,
            boolean allowCreativeCreation
    ) {
        return isClientClaimPlausible(
                beforeSlots,
                beforeCarried,
                afterSlots,
                afterCarried,
                allowCreativeCreation,
                null,
                0);
    }

    static boolean isClientClaimPlausible(
            List<ItemStack> beforeSlots,
            ItemStack beforeCarried,
            List<ItemStack> afterSlots,
            ItemStack afterCarried,
            boolean allowCreativeCreation,
            Material creditedMaterial,
            long creditedAmount
    ) {
        if (beforeSlots.size() != afterSlots.size()) {
            return false;
        }
        if (allowCreativeCreation) {
            return true;
        }

        Map<Material, Long> beforeCounts = new EnumMap<>(Material.class);
        Map<Material, Long> afterCounts = new EnumMap<>(Material.class);
        if (!addStacks(beforeCounts, beforeSlots) || !addStack(beforeCounts, beforeCarried)) {
            return false;
        }
        if (!addStacks(afterCounts, afterSlots) || !addStack(afterCounts, afterCarried)) {
            return false;
        }

        for (Map.Entry<Material, Long> entry : afterCounts.entrySet()) {
            long credit = entry.getKey() == creditedMaterial ? Math.max(0, creditedAmount) : 0;
            if (entry.getValue() > beforeCounts.getOrDefault(entry.getKey(), 0L) + credit) {
                return false;
            }
        }
        return true;
    }

    private static boolean addStacks(Map<Material, Long> counts, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (!addStack(counts, stack)) {
                return false;
            }
        }
        return true;
    }

    private static boolean addStack(Map<Material, Long> counts, ItemStack stack) {
        if (isEmpty(stack)) {
            return true;
        }
        if (stack.getAmount() <= 0 || stack.getType() == null || stack.getType() == Material.AIR) {
            return false;
        }

        counts.merge(stack.getType(), (long) stack.getAmount(), Long::sum);
        return true;
    }

    static boolean costMatches(ItemStack cost, ItemStack stack) {
        return !isEmpty(cost) && !isEmpty(stack) && cost.getType() == stack.getType();
    }

    private static boolean saneGrindstoneResult(List<ItemStack> slots, ItemStack result) {
        int available = 0;
        for (int slot = 0; slot < 2; slot++) {
            ItemStack input = slots.get(slot);
            if (sameMaterial(input, result)
                    || !isEmpty(input)
                    && input.getType() == Material.ENCHANTED_BOOK
                    && result.getType() == Material.BOOK) {
                available += input.getAmount();
            }
        }
        return available > 0 && result.getAmount() <= available;
    }

    private static boolean saneCartographyResult(List<ItemStack> slots, ItemStack result) {
        ItemStack map = slots.get(0);
        ItemStack additional = slots.get(1);
        if (isEmpty(map) || isEmpty(additional) || map.getType() != Material.FILLED_MAP
                || result.getType() != Material.FILLED_MAP) {
            return false;
        }
        return additional.getType() == Material.MAP
                ? result.getAmount() == 2
                : (additional.getType() == Material.PAPER || additional.getType() == Material.GLASS_PANE)
                && result.getAmount() == 1;
    }

    private static boolean sameMaterial(ItemStack first, ItemStack second) {
        return !isEmpty(first) && !isEmpty(second) && first.getType() == second.getType();
    }

    private static boolean inputsConsumed(MenuType menuType, List<ItemStack> before, List<ItemStack> after) {
        int inputSlots = dialogInputSlots(menuType);
        long beforeAmount = 0;
        long afterAmount = 0;
        for (int slot = 0; slot < inputSlots && slot < before.size() && slot < after.size(); slot++) {
            beforeAmount += isEmpty(before.get(slot)) ? 0 : before.get(slot).getAmount();
            afterAmount += isEmpty(after.get(slot)) ? 0 : after.get(slot).getAmount();
        }
        return afterAmount < beforeAmount;
    }

    private static long consumedInputAmount(
            MenuType menuType,
            List<ItemStack> before,
            List<ItemStack> after,
            Material material
    ) {
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

    private static long materialCount(List<ItemStack> slots, ItemStack carried, Material material) {
        long count = amountOf(carried, material);
        for (ItemStack slot : slots) {
            count += amountOf(slot, material);
        }
        return count;
    }

    private static int amountOf(ItemStack stack, Material material) {
        return material != null && !isEmpty(stack) && stack.getType() == material ? stack.getAmount() : 0;
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty();
    }
}
