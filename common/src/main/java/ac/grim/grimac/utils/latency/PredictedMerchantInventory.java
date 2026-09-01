package ac.grim.grimac.utils.latency;

import ac.grim.grimac.utils.inventory.ItemUtil;
import ac.grim.grimac.utils.inventory.inventory.AbstractContainerMenu;
import ac.grim.grimac.utils.inventory.slot.Slot;
import org.bukkit.inventory.ItemStack;

import java.util.List;

final class PredictedMerchantInventory {
    private static final int PAYMENT_A_SLOT = 0;
    private static final int PAYMENT_B_SLOT = 1;
    private static final int RESULT_SLOT = 2;
    private static final int PLAYER_SLOT_START = 3;
    private static final int PLAYER_SLOT_END = 39;

    private PredictedMerchantInventory() {
    }

    static void mirrorTrade(AbstractContainerMenu menu, List<PredictedResultSlotValidator.MerchantOfferSnapshot> offers, int selectedTrade) {
        if (menu == null || offers == null || menu.getSlots().size() < PLAYER_SLOT_END) {
            return;
        }

        updateResultSlot(menu, offers, selectedTrade);
    }

    static void mirrorTradeSelection(AbstractContainerMenu menu, List<PredictedResultSlotValidator.MerchantOfferSnapshot> offers, int selectedTrade) {
        if (menu == null || offers == null || menu.getSlots().size() < PLAYER_SLOT_END) {
            return;
        }

        if (selectedTrade >= 0 && selectedTrade < offers.size()) {
            if (!movePaymentSlotBackToInventory(menu, PAYMENT_A_SLOT)) {
                updateResultSlot(menu, offers, selectedTrade);
                return;
            }
            if (!movePaymentSlotBackToInventory(menu, PAYMENT_B_SLOT)) {
                updateResultSlot(menu, offers, selectedTrade);
                return;
            }

            if (isEmpty(menu.getSlot(PAYMENT_A_SLOT).getItem()) && isEmpty(menu.getSlot(PAYMENT_B_SLOT).getItem())) {
                PredictedResultSlotValidator.MerchantOfferSnapshot offer = offers.get(selectedTrade);
                moveFromInventoryToPaymentSlot(menu, PAYMENT_A_SLOT, offer.costA());
                if (!isEmpty(offer.costB())) {
                    moveFromInventoryToPaymentSlot(menu, PAYMENT_B_SLOT, offer.costB());
                }
            }
        }

        updateResultSlot(menu, offers, selectedTrade);
    }

    static boolean matchesResult(
            List<ItemStack> slots,
            List<PredictedResultSlotValidator.MerchantOfferSnapshot> offers,
            int selectedTrade,
            ItemStack result
    ) {
        if (slots == null || slots.size() < 3 || offers == null || isEmpty(result)) {
            return false;
        }

        PredictedResultSlotValidator.MerchantOfferSnapshot offer = findSatisfiedOffer(
                offers, slots.get(PAYMENT_A_SLOT), slots.get(PAYMENT_B_SLOT), selectedTrade);
        if (offer == null) {
            offer = findSatisfiedOffer(
                    offers, slots.get(PAYMENT_B_SLOT), slots.get(PAYMENT_A_SLOT), selectedTrade);
        }
        return offer != null
                && offer.result().getType() == result.getType()
                && offer.result().getAmount() == result.getAmount();
    }

    private static boolean movePaymentSlotBackToInventory(AbstractContainerMenu menu, int paymentSlot) {
        Slot slot = menu.getSlot(paymentSlot);
        if (slot == null) {
            return false;
        }

        ItemStack stack = slot.getItem();
        if (isEmpty(stack)) {
            return true;
        }

        if (!moveItemStackTo(menu, stack, PLAYER_SLOT_START, PLAYER_SLOT_END, true)) {
            return false;
        }

        slot.set(isEmpty(stack) ? ItemStack.empty() : stack);
        return true;
    }

    private static boolean moveItemStackTo(AbstractContainerMenu menu, ItemStack stack, int startSlot, int endSlot, boolean backwards) {
        boolean moved = false;
        int index = backwards ? endSlot - 1 : startSlot;

        if (stack.getMaxStackSize() > 1) {
            while (!isEmpty(stack) && inRange(index, startSlot, endSlot, backwards)) {
                Slot slot = menu.getSlot(index);
                if (slot != null) {
                    ItemStack existing = slot.getItem();
                    if (!isEmpty(existing) && ItemUtil.isSameItemSameTags(stack, existing)) {
                        int combined = existing.getAmount() + stack.getAmount();
                        int max = slot.getMaxStackSize(existing);
                        if (combined <= max) {
                            stack.setAmount(0);
                            existing.setAmount(combined);
                            slot.set(existing);
                            moved = true;
                        } else if (existing.getAmount() < max) {
                            int movedAmount = max - existing.getAmount();
                            stack.setAmount(stack.getAmount() - movedAmount);
                            existing.setAmount(max);
                            slot.set(existing);
                            moved = true;
                        }
                    }
                }
                index += backwards ? -1 : 1;
            }
        }

        if (!isEmpty(stack)) {
            index = backwards ? endSlot - 1 : startSlot;
            while (inRange(index, startSlot, endSlot, backwards)) {
                Slot slot = menu.getSlot(index);
                if (slot != null && isEmpty(slot.getItem()) && slot.mayPlace(stack)) {
                    slot.set(ItemUtil.split(stack, Math.min(stack.getAmount(), slot.getMaxStackSize(stack))));
                    moved = true;
                    break;
                }
                index += backwards ? -1 : 1;
            }
        }

        return moved;
    }

    private static boolean inRange(int index, int startSlot, int endSlot, boolean backwards) {
        return backwards ? index >= startSlot : index < endSlot;
    }

    private static void moveFromInventoryToPaymentSlot(AbstractContainerMenu menu, int paymentSlotIndex, ItemStack cost) {
        Slot paymentSlot = menu.getSlot(paymentSlotIndex);
        if (paymentSlot == null || isEmpty(cost)) {
            return;
        }

        for (int index = PLAYER_SLOT_START; index < PLAYER_SLOT_END; index++) {
            Slot sourceSlot = menu.getSlot(index);
            if (sourceSlot == null) {
                continue;
            }

            ItemStack source = sourceSlot.getItem();
            if (isEmpty(source) || !PredictedResultSlotValidator.costMatches(cost, source)) {
                continue;
            }

            ItemStack payment = paymentSlot.getItem();
            if (!isEmpty(payment) && !ItemUtil.isSameItemSameTags(source, payment)) {
                continue;
            }

            int paymentAmount = isEmpty(payment) ? 0 : payment.getAmount();
            int movedAmount = Math.min(source.getMaxStackSize() - paymentAmount, source.getAmount());
            if (movedAmount <= 0) {
                continue;
            }

            ItemStack newPayment = ItemUtil.copy(source);
            newPayment.setAmount(paymentAmount + movedAmount);
            source.setAmount(source.getAmount() - movedAmount);
            sourceSlot.set(isEmpty(source) ? ItemStack.empty() : source);
            paymentSlot.set(newPayment);

            if (newPayment.getAmount() >= source.getMaxStackSize()) {
                break;
            }
        }
    }

    private static void updateResultSlot(AbstractContainerMenu menu, List<PredictedResultSlotValidator.MerchantOfferSnapshot> offers, int selectedTrade) {
        Slot resultSlot = menu.getSlot(RESULT_SLOT);
        if (resultSlot == null) {
            return;
        }

        ItemStack buyA;
        ItemStack buyB;
        if (isEmpty(menu.getSlot(PAYMENT_A_SLOT).getItem())) {
            buyA = menu.getSlot(PAYMENT_B_SLOT).getItem();
            buyB = ItemStack.empty();
        } else {
            buyA = menu.getSlot(PAYMENT_A_SLOT).getItem();
            buyB = menu.getSlot(PAYMENT_B_SLOT).getItem();
        }

        PredictedResultSlotValidator.MerchantOfferSnapshot offer = findSatisfiedOffer(offers, buyA, buyB, selectedTrade);
        if (offer == null) {
            offer = findSatisfiedOffer(offers, buyB, buyA, selectedTrade);
        }

        resultSlot.set(offer == null ? ItemStack.empty() : ItemUtil.copy(offer.result()));
    }

    private static PredictedResultSlotValidator.MerchantOfferSnapshot findSatisfiedOffer(
            List<PredictedResultSlotValidator.MerchantOfferSnapshot> offers,
            ItemStack buyA,
            ItemStack buyB,
            int selectedTrade
    ) {
        int index = findSatisfiedOfferIndex(offers, buyA, buyB, selectedTrade);
        return index < 0 ? null : offers.get(index);
    }

    private static int findSatisfiedOfferIndex(
            List<PredictedResultSlotValidator.MerchantOfferSnapshot> offers,
            ItemStack buyA,
            ItemStack buyB,
            int selectedTrade
    ) {
        // Vanilla treats hint 0 as "search all offers" and only pins positive hints.
        if (selectedTrade > 0 && selectedTrade < offers.size()) {
            PredictedResultSlotValidator.MerchantOfferSnapshot offer = offers.get(selectedTrade);
            return satisfiedBy(offer, buyA, buyB) ? selectedTrade : -1;
        }

        for (int index = 0; index < offers.size(); index++) {
            if (satisfiedBy(offers.get(index), buyA, buyB)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean satisfiedBy(PredictedResultSlotValidator.MerchantOfferSnapshot offer, ItemStack buyA, ItemStack buyB) {
        if (offer == null
                || offer.outOfStock()
                || !PredictedResultSlotValidator.costMatches(offer.costA(), buyA)
                || buyA.getAmount() < offer.costA().getAmount()) {
            return false;
        }
        return isEmpty(offer.costB())
                ? isEmpty(buyB)
                : PredictedResultSlotValidator.costMatches(offer.costB(), buyB)
                && buyB.getAmount() >= offer.costB().getAmount();
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty();
    }
}
