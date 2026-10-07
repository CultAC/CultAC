package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.packet.InventoryPackets.MerchantOffer;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.inventory.inventory.AbstractContainerMenu;
import ac.cult.cultac.utils.inventory.slot.Slot;
import java.util.List;

final class PredictedMerchantInventory {
    private static final int PAYMENT_A_SLOT = 0;
    private static final int PAYMENT_B_SLOT = 1;
    private static final int RESULT_SLOT = 2;
    private static final int PLAYER_SLOT_START = 3;
    private static final int PLAYER_SLOT_END = 39;

    private PredictedMerchantInventory() {}

    static void mirrorTrade(AbstractContainerMenu menu, List<MerchantOffer> offers, int selectedTrade) {
        if (menu == null || offers == null || menu.getSlots().size() < PLAYER_SLOT_END) {
            return;
        }

        updateResultSlot(menu, offers, selectedTrade);
    }

    static void mirrorTradeSelection(AbstractContainerMenu menu, List<MerchantOffer> offers, int selectedTrade) {
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

            if (isEmpty(menu.getSlot(PAYMENT_A_SLOT).getItem())
                    && isEmpty(menu.getSlot(PAYMENT_B_SLOT).getItem())) {
                MerchantOffer offer = offers.get(selectedTrade);
                moveFromInventoryToPaymentSlot(menu, PAYMENT_A_SLOT, offer.costA());
                if (!isEmpty(offer.costB())) {
                    moveFromInventoryToPaymentSlot(menu, PAYMENT_B_SLOT, offer.costB());
                }
            }
        }

        updateResultSlot(menu, offers, selectedTrade);
    }

    private static boolean movePaymentSlotBackToInventory(AbstractContainerMenu menu, int paymentSlot) {
        Slot slot = menu.getSlot(paymentSlot);
        if (slot == null) {
            return false;
        }

        SimItemStack stack = slot.getItem();
        if (isEmpty(stack)) {
            return true;
        }

        if (!moveItemStackTo(menu, stack, PLAYER_SLOT_START, PLAYER_SLOT_END, true)) {
            return false;
        }

        slot.set(isEmpty(stack) ? SimItemStack.EMPTY : stack);
        return true;
    }

    private static boolean moveItemStackTo(
            AbstractContainerMenu menu, SimItemStack stack, int startSlot, int endSlot, boolean backwards) {
        boolean moved = false;
        int index = backwards ? endSlot - 1 : startSlot;

        if (stack.getMaxStackSize() > 1) {
            while (!isEmpty(stack) && inRange(index, startSlot, endSlot, backwards)) {
                Slot slot = menu.getSlot(index);
                if (slot != null) {
                    SimItemStack existing = slot.getItem();
                    if (!isEmpty(existing) && ItemUtil.isSameItemSameTags(stack, existing)) {
                        int combined = existing.getCount() + stack.getCount();
                        int max = slot.getMaxStackSize(existing);
                        if (combined <= max) {
                            stack.setCount(0);
                            existing.setCount(combined);
                            slot.set(existing);
                            moved = true;
                        } else if (existing.getCount() < max) {
                            int movedAmount = max - existing.getCount();
                            stack.setCount(stack.getCount() - movedAmount);
                            existing.setCount(max);
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
                    slot.set(ItemUtil.split(stack, Math.min(stack.getCount(), slot.getMaxStackSize(stack))));
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

    private static void moveFromInventoryToPaymentSlot(
            AbstractContainerMenu menu, int paymentSlotIndex, SimItemStack cost) {
        Slot paymentSlot = menu.getSlot(paymentSlotIndex);
        if (paymentSlot == null || isEmpty(cost)) {
            return;
        }

        for (int index = PLAYER_SLOT_START; index < PLAYER_SLOT_END; index++) {
            Slot sourceSlot = menu.getSlot(index);
            if (sourceSlot == null) {
                continue;
            }

            SimItemStack source = sourceSlot.getItem();
            if (isEmpty(source)
                    || !MerchantOfferSelection.costMatches(
                            ItemUtil.inventoryValue(cost), ItemUtil.inventoryValue(source))) {
                continue;
            }

            SimItemStack payment = paymentSlot.getItem();
            if (!isEmpty(payment) && !ItemUtil.isSameItemSameTags(source, payment)) {
                continue;
            }

            int paymentAmount = isEmpty(payment) ? 0 : payment.getCount();
            int movedAmount = Math.min(source.getMaxStackSize() - paymentAmount, source.getCount());
            if (movedAmount <= 0) {
                continue;
            }

            SimItemStack newPayment = ItemUtil.copy(source);
            newPayment.setCount(paymentAmount + movedAmount);
            source.setCount(source.getCount() - movedAmount);
            sourceSlot.set(isEmpty(source) ? SimItemStack.EMPTY : source);
            paymentSlot.set(newPayment);

            if (newPayment.getCount() >= source.getMaxStackSize()) {
                break;
            }
        }
    }

    private static void updateResultSlot(AbstractContainerMenu menu, List<MerchantOffer> offers, int selectedTrade) {
        Slot resultSlot = menu.getSlot(RESULT_SLOT);
        if (resultSlot == null) {
            return;
        }

        SimItemStack buyA;
        SimItemStack buyB;
        if (isEmpty(menu.getSlot(PAYMENT_A_SLOT).getItem())) {
            buyA = menu.getSlot(PAYMENT_B_SLOT).getItem();
            buyB = SimItemStack.EMPTY;
        } else {
            buyA = menu.getSlot(PAYMENT_A_SLOT).getItem();
            buyB = menu.getSlot(PAYMENT_B_SLOT).getItem();
        }

        MerchantOffer offer = findSatisfiedOffer(offers, buyA, buyB, selectedTrade);
        if (offer == null) {
            offer = findSatisfiedOffer(offers, buyB, buyA, selectedTrade);
        }

        resultSlot.set(offer == null ? SimItemStack.EMPTY : ItemUtil.copy(offer.result()));
    }

    private static MerchantOffer findSatisfiedOffer(
            List<MerchantOffer> offers, SimItemStack buyA, SimItemStack buyB, int selectedTrade) {
        int index = MerchantOfferSelection.findSatisfiedOfferIndex(
                values(offers), ItemUtil.inventoryValue(buyA), ItemUtil.inventoryValue(buyB), selectedTrade);
        return index < 0 ? null : offers.get(index);
    }

    /** Temporary storage edge; the selection and conservation rules receive owned values. */
    static List<MerchantOfferSelection.Offer> values(List<MerchantOffer> offers) {
        return offers == null
                ? null
                : offers.stream()
                        .map(offer -> offer == null
                                ? null
                                : new MerchantOfferSelection.Offer(
                                        ItemUtil.inventoryValue(offer.costA()), ItemUtil.inventoryValue(offer.costB()),
                                        ItemUtil.inventoryValue(offer.result()), offer.outOfStock()))
                        .toList();
    }

    private static boolean isEmpty(SimItemStack stack) {
        return stack == null || stack.isEmpty();
    }
}
