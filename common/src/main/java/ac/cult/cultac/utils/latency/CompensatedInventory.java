package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.ItemDefinition;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.network.packet.InventoryPackets.Content;
import ac.cult.cultac.network.packet.InventoryPackets.CreativeSlot;
import ac.cult.cultac.network.packet.InventoryPackets.MerchantOffer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMountScreenOpen;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHeldSlot;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSelectBundleItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSelectTrade;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.inventory.EquipmentType;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.InventoryStorage;
import ac.cult.cultac.utils.inventory.ItemTypes;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.inventory.inventory.AbstractContainerMenu;
import ac.cult.cultac.utils.inventory.inventory.GenericContainerMenu;
import ac.cult.cultac.utils.inventory.inventory.MenuType;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import ac.cult.cultac.utils.inventory.slot.Slot;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Getter;

// Updated to support modern 1.17 protocol
public class CompensatedInventory extends CultProcessor implements CheckListener {
    // "Temporarily" public for debugging
    public Inventory inventory;
    // "Temporarily" public for debugging
    public AbstractContainerMenu menu;
    // Mojang uses player inventory slot 40 as the SWAP button for offhand clicks.
    // Cult's compensated storage keeps the protocol offhand slot at 45.
    private static final int NMS_OFFHAND_SWAP_BUTTON = 40;
    public int openWindowID = 0;
    public int stateID = 0; // Player inventory state ID. Don't mess up the last sent state ID by changing it.
    private List<MerchantOffer> merchantOffers = List.of();
    private int selectedMerchantOffer = 0;

    public CompensatedInventory(CultPlayer playerData) {
        super(playerData);

        InventoryStorage storage = new InventoryStorage(Inventory.STORAGE_SIZE);
        inventory = new Inventory(playerData, storage);

        menu = inventory;
    }

    private void setClientCarried(SimItemStack stack) {
        menu.setCarried(stack == null ? SimItemStack.EMPTY : stack);
    }

    public void applyBedrockSlots(int windowId, java.util.Map<Integer, SimItemStack> changed) {
        if (windowId != 0 && windowId != openWindowID) return;
        var target = windowId == 0 ? inventory : menu;
        changed.forEach((index, item) -> {
            if (index >= 0 && index < target.getSlots().size())
                target.getSlot(index).set(item);
        });
    }

    public void applyBedrockCursor(SimItemStack item) {
        setClientCarried(item);
    }

    private void deferClientboundInventoryTask(PacketSendEvent<?> event, Runnable task) {
        CultPlayer.TrackedTransaction transaction = player.createTrackedTransactionPacketForDeferredSend();
        if (transaction == null) {
            task.run();
            return;
        }

        player.latencyUtils.addRealTimeTask(transaction.transaction(), task);
        if (!player.isBedrockMovement() && player.supportsBundles()) {
            // ChannelPacketHandler bundles a replacement group and preserves an
            // enclosing vanilla bundle. Putting the proof in that group makes
            // ClientPacketListener#handleBundlePacket process the update and ping
            // together, before the client can emit another tick or inventory click.
            event.getWritesAfterSend().add(transaction.packet());
            event.getTasksAfterSend().add(() -> player.markTrackedTransactionPacketSent(transaction));
            return;
        }

        // PacketSendEvent<?> tasks run after the complete outbound group has been forwarded. Sending
        // the proof there keeps a ping out of the middle of a vanilla bundle while still placing it
        // after the inventory packet on the ordered clientbound stream.
        event.getTasksAfterSend().add(() -> {
            player.user.write(transaction.packet());
            player.markTrackedTransactionPacketSent(transaction);
        });
    }

    public SimItemStack getHandItem(Hand handType) {
        return handType == Hand.MAIN_HAND ? getHeldItem() : getOffHand();
    }

    public SimItemStack getHeldItem() {
        SimItemStack item = inventory.getHeldItem();
        return item == null ? SimItemStack.EMPTY : item;
    }

    public SimItemStack getOffHand() {
        SimItemStack item = inventory.getOffhand();
        return item == null ? SimItemStack.EMPTY : item;
    }

    public boolean hasClientSelectedHandItem(ItemDefinition material) {
        return getClientSelectedHeldItem().getItem() == material || getOffHand().getItem() == material;
    }

    public boolean hasClientSelectableHandItem(ItemDefinition material) {
        if (getOffHand().getItem() == material) {
            return true;
        }
        for (int slot = 0; slot < 9; slot++) {
            SimItemStack item = inventory.getInventoryStorage().getItem(Inventory.HOTBAR_OFFSET + slot);
            if (item.getItem() == material) {
                return true;
            }
        }
        return false;
    }

    public SimItemStack getClientSelectedHeldItem() {
        int selected = player.packetStateData.lastSlotSelected;
        if (selected < 0 || selected > 8) {
            selected = inventory.selected;
        }

        SimItemStack item = inventory.getInventoryStorage().getItem(Inventory.HOTBAR_OFFSET + selected);
        return item == null ? SimItemStack.EMPTY : item;
    }

    public SimItemStack getHelmet() {
        SimItemStack item = inventory.getHelmet();
        return item == null ? SimItemStack.EMPTY : item;
    }

    public SimItemStack getChestplate() {
        SimItemStack item = inventory.getChestplate();
        return item == null ? SimItemStack.EMPTY : item;
    }

    public SimItemStack getLeggings() {
        SimItemStack item = inventory.getLeggings();
        return item == null ? SimItemStack.EMPTY : item;
    }

    public SimItemStack getBoots() {
        SimItemStack item = inventory.getBoots();
        return item == null ? SimItemStack.EMPTY : item;
    }

    static int directPlayerInventorySlotToStorageSlot(int slot) {
        if (slot < 0) {
            return -1;
        }
        if (slot < 9) {
            return Inventory.HOTBAR_OFFSET + slot;
        }
        if (slot < 36) {
            return slot;
        }
        return switch (slot) {
            case 36 -> Inventory.SLOT_BOOTS;
            case 37 -> Inventory.SLOT_LEGGINGS;
            case 38 -> Inventory.SLOT_CHESTPLATE;
            case 39 -> Inventory.SLOT_HELMET;
            case 40 -> Inventory.SLOT_OFFHAND;
            case 41 -> Inventory.SLOT_BODY;
            case 42 -> Inventory.SLOT_SADDLE;
            default -> -1;
        };
    }

    private boolean isClientClickClaimSane(
            InventoryClick click,
            List<SimItemStack> beforeSlots,
            SimItemStack beforeCarried,
            List<SimItemStack> afterSlots,
            SimItemStack afterCarried,
            PredictedResultSlotValidator.ResultAllowance allowance) {
        // Some menus do not expose the offhand slot, but vanilla still uses button 40 for
        // offhand swaps. Include the compensated offhand stack so conservation sees both sides.
        if (isImplicitOffhandSwap(click, afterSlots.size())) {
            List<SimItemStack> beforeWithOffhand = new ArrayList<>(beforeSlots);
            List<SimItemStack> afterWithOffhand = new ArrayList<>(afterSlots);
            SimItemStack offhandBefore = ItemUtil.copy(getOffHand());
            beforeWithOffhand.add(offhandBefore);
            afterWithOffhand.add(
                    inferOffhandAfterSwap(beforeSlots.get(click.slot()), offhandBefore, afterSlots.get(click.slot())));
            beforeSlots = beforeWithOffhand;
            afterSlots = afterWithOffhand;
        }

        return PredictedResultSlotValidator.isClientClaimPlausible(
                inventoryValues(beforeSlots),
                ItemUtil.inventoryValue(beforeCarried),
                inventoryValues(afterSlots),
                ItemUtil.inventoryValue(afterCarried),
                permitsCreativeCreation(click, player.gamemode),
                allowance == null ? -1 : allowance.material(),
                allowance == null ? 0 : allowance.amount());
    }

    private static List<ac.cult.cultac.utils.inventory.InventoryItem> inventoryValues(List<SimItemStack> slots) {
        return slots.stream().map(ItemUtil::inventoryValue).toList();
    }

    static boolean permitsCreativeCreation(InventoryClick click, GameMode gameMode) {
        return gameMode == GameMode.CREATIVE
                && (click.clickType() == WindowClickType.CLONE
                        || click.clickType() == WindowClickType.QUICK_CRAFT && (click.button() >> 2 & 3) == 2);
    }

    private boolean isImplicitOffhandSwap(InventoryClick click, int menuSlotCount) {
        return click.clickType() == WindowClickType.SWAP
                && click.button() == NMS_OFFHAND_SWAP_BUTTON
                && click.slot() >= 0
                && click.slot() < menuSlotCount
                && !menuExposesOffhand(menu)
                && click.changedSlots().containsKey(click.slot());
    }

    private static List<SimItemStack> copyMenuSlots(AbstractContainerMenu menu) {
        List<SimItemStack> slots = new ArrayList<>(menu.getSlots().size());
        for (Slot slot : menu.getSlots()) {
            slots.add(ItemUtil.copy(slot.getItem()));
        }
        return slots;
    }

    private List<SimItemStack> resolveChangedSlots(
            InventoryClick click,
            List<SimItemStack> beforeSlots,
            SimItemStack beforeCarried,
            java.util.function.BiPredicate<Integer, SimItemStack> matches) {
        List<SimItemStack> afterSlots = new ArrayList<>(beforeSlots);
        SimItemStack clickedSource = click.slot() >= 0 && click.slot() < beforeSlots.size()
                ? beforeSlots.get(click.slot())
                : SimItemStack.EMPTY;
        for (Map.Entry<Integer, SimItemStack> entry : click.changedSlots().entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= afterSlots.size()) {
                return null;
            }
            SimItemStack resolved = resolveClaimedStack(
                    entry.getValue(),
                    beforeSlots.get(slot),
                    beforeSlots,
                    clickedSource,
                    beforeCarried,
                    candidate -> matches.test(slot, candidate));
            if (resolved == null) return null;
            afterSlots.set(slot, resolved);
        }
        return afterSlots;
    }

    private SimItemStack resolveAfterCarried(
            InventoryClick click,
            List<SimItemStack> beforeSlots,
            SimItemStack beforeCarried,
            java.util.function.BiPredicate<Integer, SimItemStack> matches) {
        SimItemStack clickedSource = click.slot() >= 0 && click.slot() < beforeSlots.size()
                ? beforeSlots.get(click.slot())
                : SimItemStack.EMPTY;
        return resolveClaimedStack(
                click.carriedItem(),
                beforeCarried,
                beforeSlots,
                clickedSource,
                SimItemStack.EMPTY,
                candidate -> matches.test(-1, candidate));
    }

    private SimItemStack resolveClaimedStack(
            SimItemStack claim,
            SimItemStack previous,
            List<SimItemStack> beforeSlots,
            SimItemStack primarySource,
            SimItemStack secondarySource,
            java.util.function.Predicate<SimItemStack> matches) {
        if (claim == null || claim.isEmpty()) {
            return SimItemStack.EMPTY;
        }

        for (SimItemStack candidate : new SimItemStack[] {previous, primarySource, secondarySource}) {
            SimItemStack resolved = copyWithAmountIfMaterialMatches(candidate, claim.getItem(), claim.getCount());
            if (!resolved.isEmpty() && matches.test(resolved)) {
                return resolved;
            }
        }

        for (SimItemStack candidate : beforeSlots) {
            SimItemStack candidateCopy = copyWithAmountIfMaterialMatches(candidate, claim.getItem(), claim.getCount());
            if (!candidateCopy.isEmpty() && matches.test(candidateCopy)) {
                return candidateCopy;
            }
        }

        return matches.test(claim) ? ItemUtil.copy(claim) : null;
    }

    private static SimItemStack copyWithAmountIfMaterialMatches(
            SimItemStack source, ItemDefinition material, int amount) {
        if (source == null || source.isEmpty() || material == null || source.getItem() != material || amount <= 0) {
            return SimItemStack.EMPTY;
        }
        SimItemStack copy = ItemUtil.copy(source);
        copy.setCount(amount);
        return copy;
    }

    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent<InventoryClick> event, CultPlayer player, InventoryClick packet) {
        if (event.isCancelled()) {
            return;
        }

        applyContainerClick(packet);
    }

    public boolean applyContainerClick(InventoryClick click) {
        var matches = click.matches();
        if (click.windowId() != openWindowID) {
            return false;
        }

        if (mirrorBundleClick(click)) {
            return true;
        }

        List<SimItemStack> beforeSlots = copyMenuSlots(menu);
        SimItemStack beforeCarried = ItemUtil.copy(menu.getCarried());
        List<SimItemStack> afterSlots = resolveChangedSlots(click, beforeSlots, beforeCarried, matches);
        if (afterSlots == null) {
            return false;
        }
        SimItemStack afterCarried = resolveAfterCarried(click, beforeSlots, beforeCarried, matches);
        if (afterCarried == null) return false;

        int resultSlot = PredictedResultSlotValidator.resultSlot(serverContainerType);
        boolean sameResultComponents = resultSlot >= 0
                && resultSlot < beforeSlots.size()
                && resultSlot < afterSlots.size()
                && ItemUtil.isSameItemSameTags(beforeSlots.get(resultSlot), afterSlots.get(resultSlot));
        PredictedResultSlotValidator.ResultAllowance allowance = PredictedResultSlotValidator.dialogResultAllowance(
                serverContainerType,
                click.slot(),
                inventoryValues(beforeSlots),
                ItemUtil.inventoryValue(beforeCarried),
                inventoryValues(afterSlots),
                ItemUtil.inventoryValue(afterCarried),
                PredictedMerchantInventory.values(merchantOffers),
                selectedMerchantOffer,
                sameResultComponents);
        if (!isClientClickClaimSane(click, beforeSlots, beforeCarried, afterSlots, afterCarried, allowance)) {
            return false;
        }

        // The client already ran the same menu click code and sent the changed slot set.
        // The server treats this as remote prediction state, so Cult mirrors it only after a conservation check.
        SimItemStack clickedBefore = SimItemStack.EMPTY;
        SimItemStack offhandBefore = ItemUtil.copy(getOffHand());
        if (click.slot() >= 0) {
            Slot clickedSlot = menu.getSlot(click.slot());
            if (clickedSlot != null) {
                clickedBefore = ItemUtil.copy(clickedSlot.getItem());
            }
        }

        click.changedSlots().forEach((slot, item) -> {
            SimItemStack resolved = afterSlots.get(slot);
            menu.getSlot(slot).set(resolved);
        });
        mirrorImplicitOffhandSwap(click, clickedBefore, offhandBefore);
        setClientCarried(afterCarried);
        if (serverContainerType == MenuType.MERCHANT && click.slot() != 2) {
            PredictedMerchantInventory.mirrorTrade(menu, merchantOffers, selectedMerchantOffer);
        }
        return true;
    }

    private boolean mirrorBundleClick(InventoryClick click) {
        if (click.clickType() != WindowClickType.PICKUP
                || (click.button() != 0 && click.button() != 1)
                || click.slot() < 0) {
            return false;
        }

        Slot slot = menu.getSlot(click.slot());
        if (slot == null) {
            return false;
        }

        SimItemStack clickedBefore = ItemUtil.copy(slot.getItem());
        SimItemStack carriedBefore = ItemUtil.copy(menu.getCarried());
        if (!isBundle(clickedBefore) && !isBundle(carriedBefore)) {
            return false;
        }

        BundleClickResult result = predictBundleClick(slot, clickedBefore, carriedBefore, click.button() == 0);
        if (result == null || !bundlePredictionMatchesPacket(click, result)) {
            return false;
        }

        slot.set(result.slot());
        menu.setCarried(result.carried());
        return true;
    }

    private BundleClickResult predictBundleClick(
            Slot slot, SimItemStack clickedBefore, SimItemStack carriedBefore, boolean primary) {
        SimItemStack clicked = clickedBefore.copy();
        SimItemStack carried = carriedBefore.copy();

        if (hasBundleContents(carried)) {
            var mutable = new ac.cult.blocksim.engine.MutableBundleContents(
                    carried.components().bundle(), ItemUtil.modelItems());
            if (primary && !clicked.isEmpty()) {
                mutable.tryInsert(clicked);
                carried.bundleContents(mutable.toImmutable());
                return new BundleClickResult(clicked, carried);
            }
            if (!primary && clicked.isEmpty()) {
                ac.cult.blocksim.engine.SimItemStack removed = mutable.removeOne();
                if (removed == null) {
                    carried.bundleContents(mutable.toImmutable());
                    return new BundleClickResult(SimItemStack.EMPTY, carried);
                }
                SimItemStack removedBukkit = removed;
                if (slot.mayPlace(removedBukkit)) {
                    clicked = removed;
                } else {
                    mutable.tryInsert(removed);
                }
                carried.bundleContents(mutable.toImmutable());
                return new BundleClickResult(clicked, carried);
            }
        }

        if (hasBundleContents(clicked)) {
            var mutable = new ac.cult.blocksim.engine.MutableBundleContents(
                    clicked.components().bundle(), ItemUtil.modelItems());
            if (primary && !carried.isEmpty()) {
                mutable.tryInsert(carried);
                clicked.bundleContents(mutable.toImmutable());
                return new BundleClickResult(clicked, carried);
            }
            if (!primary && carried.isEmpty()) {
                ac.cult.blocksim.engine.SimItemStack removed = mutable.removeOne();
                if (removed != null) {
                    carried = removed;
                }
                clicked.bundleContents(mutable.toImmutable());
                return new BundleClickResult(clicked, carried);
            }
        }

        return null;
    }

    private boolean bundlePredictionMatchesPacket(InventoryClick click, BundleClickResult result) {
        SimItemStack changedSlot = click.changedSlots().get(click.slot());
        boolean slotChanged = !sameStack(menu.getSlot(click.slot()).getItem(), result.slot());
        if (slotChanged != click.changedSlots().containsKey(click.slot())) {
            return false;
        }
        return (!slotChanged || sameMaterialAndAmount(changedSlot, result.slot()))
                && sameMaterialAndAmount(click.carriedItem(), result.carried());
    }

    private static boolean isBundle(SimItemStack stack) {
        return hasBundleContents(stack);
    }

    private static boolean hasBundleContents(ac.cult.blocksim.engine.SimItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.components().has("minecraft:bundle_contents");
    }

    private static boolean sameMaterialAndAmount(SimItemStack first, SimItemStack second) {
        if (first == null || first.isEmpty()) {
            return second == null || second.isEmpty();
        }
        return second != null
                && !second.isEmpty()
                && first.getItem() == second.getItem()
                && first.getCount() == second.getCount();
    }

    private record BundleClickResult(SimItemStack slot, SimItemStack carried) {}

    @CultPacketHandler
    public void onSelectBundleItem(
            PacketReceiveEvent<ServerboundSelectBundleItem> event,
            CultPlayer player,
            ServerboundSelectBundleItem packet) {
        selectBundleItem(packet.slotId(), packet.selectedItemIndex());
    }

    @CultPacketHandler
    public void onSelectTrade(
            PacketReceiveEvent<ServerboundSelectTrade> event, CultPlayer player, ServerboundSelectTrade packet) {
        if (!event.isCancelled()) selectTrade(packet.offer());
    }

    public void selectTrade(int offer) {
        if (serverContainerType != MenuType.MERCHANT) return;
        selectedMerchantOffer = offer;
        PredictedMerchantInventory.mirrorTradeSelection(menu, merchantOffers, selectedMerchantOffer);
    }

    // Bedrock equipment dispatch is independent of Java's queued block actions.
    public void useItem(Hand hand, float yaw, float pitch) {
        mirrorEquipmentUse(hand, getHandItem(hand));
    }

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        if (event.isCancelled()) {
            return;
        }

        if (packet.action() == InteractAction.ATTACK) {
            return;
        }

        mirrorEntityBucketInteraction(packet);
    }

    private void mirrorEntityBucketInteraction(ServerboundInteract interact) {
        PacketEntity entity = player.compensatedEntities.getEntity(interact.entityId());
        if (entity == null || entity.isDead) {
            return;
        }

        Hand hand = interact.hand();
        SimItemStack held = getHandItem(hand);
        if (held == null || held.isEmpty()) {
            return;
        }

        SimItemStack filledBucket = entityInteractionBucketResult(entity, held.getItem());
        if (filledBucket == null || filledBucket.isEmpty()) {
            return;
        }

        SimItemStack handAfter;
        List<SimItemStack> addedItems;
        if (held.getCount() <= 1) {
            handAfter = filledBucket;
            addedItems = List.of();
        } else {
            handAfter = ItemUtil.copy(held);
            handAfter.setCount(held.getCount() - 1);
            addedItems = List.of(filledBucket);
        }

        applyClientSideUseItemOnResult(hand, handAfter, addedItems);
    }

    private SimItemStack entityInteractionBucketResult(PacketEntity entity, ItemDefinition heldType) {
        if (heldType == ItemTypes.BUCKET && !entity.isBaby && isMilkable(entity.type)) {
            return ItemUtil.of(ItemTypes.MILK_BUCKET, 1);
        }

        if (heldType != ItemTypes.WATER_BUCKET) {
            return null;
        }

        ItemDefinition bucket = bucketMaterialFor(entity.type);
        return bucket == null ? null : ItemUtil.of(bucket, 1);
    }

    private static boolean isMilkable(int type) {
        return type == EntityTypeIds.COW || type == EntityTypeIds.MOOSHROOM || type == EntityTypeIds.GOAT;
    }

    private static ItemDefinition bucketMaterialFor(int type) {
        if (type == EntityTypeIds.COD) return ItemTypes.COD_BUCKET;
        if (type == EntityTypeIds.SALMON) return ItemTypes.SALMON_BUCKET;
        if (type == EntityTypeIds.PUFFERFISH) return ItemTypes.PUFFERFISH_BUCKET;
        if (type == EntityTypeIds.TROPICAL_FISH) return ItemTypes.TROPICAL_FISH_BUCKET;
        if (type == EntityTypeIds.AXOLOTL) return ItemTypes.AXOLOTL_BUCKET;
        if (type == EntityTypeIds.TADPOLE) return ItemTypes.TADPOLE_BUCKET;
        return null;
    }

    private boolean mirrorEquipmentUse(Hand hand, SimItemStack use) {
        EquipmentType equipmentType = EquipmentType.getEquipmentSlotForItem(use);
        if (equipmentType == null) {
            return false;
        }

        int slot;
        switch (equipmentType) {
            case HEAD:
                slot = Inventory.SLOT_HELMET;
                break;
            case CHEST:
                slot = Inventory.SLOT_CHESTPLATE;
                break;
            case LEGS:
                slot = Inventory.SLOT_LEGGINGS;
                break;
            case FEET:
                slot = Inventory.SLOT_BOOTS;
                break;
            default:
                return false;
        }

        inventory
                .getInventoryStorage()
                .setItem(handStorageSlot(hand), ItemUtil.copy(getByEquipmentType(equipmentType)));
        inventory.getInventoryStorage().setItem(slot, ItemUtil.copy(use));
        return true;
    }

    private SimItemStack getByEquipmentType(EquipmentType type) {
        return switch (type) {
            case HEAD -> getHelmet();
            case CHEST -> getChestplate();
            case LEGS -> getLeggings();
            case FEET -> getBoots();
            case OFFHAND -> getOffHand();
            case MAINHAND -> getHeldItem();
            default -> SimItemStack.EMPTY;
        };
    }

    private int handStorageSlot(Hand hand) {
        return hand == Hand.MAIN_HAND ? inventory.selected + Inventory.HOTBAR_OFFSET : Inventory.SLOT_OFFHAND;
    }

    public void applyClientSideUseItemOnResult(Hand hand, SimItemStack handAfter, List<SimItemStack> addedItems) {
        SimItemStack before = getHandItem(hand);
        if (!sameStack(before, handAfter)) {
            inventory.getInventoryStorage().setItem(handStorageSlot(hand), handAfter);
        }

        if (addedItems != null) {
            for (SimItemStack addedItem : addedItems) {
                if (addedItem == null || addedItem.isEmpty()) {
                    continue;
                }
                inventory.add(ItemUtil.copy(addedItem));
            }
        }
    }

    private static boolean sameStack(SimItemStack first, SimItemStack second) {
        if (first == null || first.isEmpty()) {
            return second == null || second.isEmpty();
        }
        return second != null
                && !second.isEmpty()
                && first.getCount() == second.getCount()
                && ItemUtil.isSameItemSameTags(first, second);
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        PlayerAction action = packet.action();
        if (action == PlayerAction.DROP_ITEM || action == PlayerAction.DROP_ALL_ITEMS)
            dropHeldItem(action == PlayerAction.DROP_ALL_ITEMS);
    }

    public void dropHeldItem(boolean wholeStack) {
        SimItemStack held = ItemUtil.copy(getHeldItem());
        if (wholeStack || held.isEmpty() || held.getCount() <= 1) {
            inventory.setHeldItem(null);
        } else {
            held.setCount(held.getCount() - 1);
            inventory.setHeldItem(held);
        }
    }

    @CultPacketHandler
    public void onSetCarriedItem(
            PacketReceiveEvent<ServerboundSetCarriedItem> event, CultPlayer player, ServerboundSetCarriedItem packet) {
        selectHotbarSlot(packet.slot());
    }

    public void selectHotbarSlot(int slot) {
        if (slot < 0 || slot > 8) return;
        if (inventory.selected != slot) player.packetStateData.carriedItemChangedThisClientTick = true;
        inventory.selected = slot;
        player.packetStateData.lastSlotSelected = slot;
        player.compensatedEntities.vehicles.markItemControlledVehicleControlSwitch();
    }

    @CultPacketHandler
    public void onSetCreativeModeSlot(PacketReceiveEvent<CreativeSlot> event, CultPlayer player, CreativeSlot packet) {
        if (!event.isCancelled()) setCreativeSlot(packet.slot(), packet.item());
    }

    public void setCreativeSlot(int slot, SimItemStack stack) {
        if (player.gamemode == GameMode.CREATIVE && slot >= 1 && slot <= 45) {
            inventory.getSlot(slot).set(ItemUtil.copy(stack));
        }
    }

    @CultPacketHandler("serverbound.container_close")
    public void onContainerClose(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        closeContainer();
    }

    public void closeContainer() {
        menu = inventory;
        openWindowID = 0;
        serverContainerType = MenuType.CRAFTING;
        menu.setCarried(SimItemStack.EMPTY);
        merchantOffers = List.of();
        selectedMerchantOffer = 0;
    }

    private void mirrorImplicitOffhandSwap(
            InventoryClick click, SimItemStack clickedBefore, SimItemStack offhandBefore) {
        if (click.clickType() != WindowClickType.SWAP
                || click.button() != NMS_OFFHAND_SWAP_BUTTON
                || click.slot() < 0) {
            return;
        }
        if (menuExposesOffhand(menu) || !click.changedSlots().containsKey(click.slot())) {
            return;
        }

        Slot clickedSlot = menu.getSlot(click.slot());
        if (clickedSlot == null) {
            return;
        }

        SimItemStack clickedAfter = ItemUtil.copy(clickedSlot.getItem());
        SimItemStack offhandAfter = inferOffhandAfterSwap(clickedBefore, offhandBefore, clickedAfter);
        inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, offhandAfter);
    }

    public void selectBundleItem(int slotId, int selectedItemIndex) {
        if (slotId < 0 || slotId >= menu.getSlots().size()) {
            return;
        }

        Slot slot = menu.getSlot(slotId);
        if (slot == null) {
            return;
        }

        SimItemStack item = slot.getItem().copy();
        if (!item.components().has("minecraft:bundle_contents")) {
            return;
        }

        var mutable = new ac.cult.blocksim.engine.MutableBundleContents(
                item.components().bundle(), ItemUtil.modelItems());
        mutable.toggleSelectedItem(selectedItemIndex);
        item.bundleContents(mutable.toImmutable());
        slot.set(item);
    }

    private boolean menuExposesOffhand(AbstractContainerMenu sourceMenu) {
        if (sourceMenu == null) {
            return false;
        }

        for (Slot slot : sourceMenu.getSlots()) {
            if (slot.isBackedBy(inventory.getInventoryStorage()) && slot.getContainerSlot() == Inventory.SLOT_OFFHAND) {
                return true;
            }
        }
        return false;
    }

    private SimItemStack inferOffhandAfterSwap(
            SimItemStack clickedBefore, SimItemStack offhandBefore, SimItemStack clickedAfter) {
        SimItemStack clicked = ItemUtil.copy(clickedBefore);
        SimItemStack source = ItemUtil.copy(offhandBefore);
        SimItemStack target = ItemUtil.copy(clickedAfter);

        if (source.isEmpty()) {
            return target.isEmpty() ? clicked : SimItemStack.EMPTY;
        }

        if (!ItemUtil.isSameItemSameTags(source, target)) {
            return source;
        }

        int remaining = source.getCount() - target.getCount();
        if (remaining > 0) {
            SimItemStack leftover = ItemUtil.copy(source);
            leftover.setCount(remaining);
            return leftover;
        }

        return clicked.isEmpty() ? SimItemStack.EMPTY : clicked;
    }

    public void onBlockPlace(BlockPlace place) {
        if (player.gamemode != GameMode.CREATIVE && place.getItemStack().getItem() != ItemTypes.POWDER_SNOW_BUCKET) {
            int slot = place.getHand() == Hand.MAIN_HAND
                    ? inventory.selected + Inventory.HOTBAR_OFFSET
                    : Inventory.SLOT_OFFHAND;
            place.getItemStack().setCount(place.getItemStack().getCount() - 1);
            inventory.getInventoryStorage().setItem(slot, place.getItemStack());
        }
    }

    @Getter
    private MenuType serverContainerType = MenuType.CRAFTING;

    private AbstractContainerMenu menuFromContentSlots(int slotCount) {
        if (slotCount <= 0) {
            return new GenericContainerMenu(player, inventory, 0, false);
        }

        // The packet is the client-visible source of truth. Vanilla menus that expose the
        // player inventory append the 27 inventory slots and 9 hotbar slots after menu slots.
        boolean includesPlayerInventory = slotCount >= 36;
        int containerSlots = includesPlayerInventory ? slotCount - 36 : slotCount;
        return new GenericContainerMenu(player, inventory, containerSlots, includesPlayerInventory);
    }

    private AbstractContainerMenu menuFromOpenScreenType(MenuType menuType) {
        return switch (menuType) {
            case GENERIC_9x1 -> new GenericContainerMenu(player, inventory, 9);
            case GENERIC_9x2 -> new GenericContainerMenu(player, inventory, 18);
            case GENERIC_9x3, SHULKER_BOX -> new GenericContainerMenu(player, inventory, 27);
            case GENERIC_9x4 -> new GenericContainerMenu(player, inventory, 36);
            case GENERIC_9x5 -> new GenericContainerMenu(player, inventory, 45);
            case GENERIC_9x6 -> new GenericContainerMenu(player, inventory, 54);
            case GENERIC_3x3 -> new GenericContainerMenu(player, inventory, 9);
            case CRAFTER_3x3 -> new GenericContainerMenu(player, inventory, 9, 1);
            case CRAFTING -> new GenericContainerMenu(player, inventory, 10);
            case ANVIL, FURNACE, BLAST_FURNACE, SMOKER, GRINDSTONE, MERCHANT, CARTOGRAPHY_TABLE ->
                new GenericContainerMenu(player, inventory, 3);
            case SMITHING, LOOM -> new GenericContainerMenu(player, inventory, 4);
            case BEACON -> new GenericContainerMenu(player, inventory, 1);
            case BREWING_STAND -> new GenericContainerMenu(player, inventory, 5);
            case ENCHANTMENT, STONECUTTER -> new GenericContainerMenu(player, inventory, 2);
            case HOPPER -> new GenericContainerMenu(player, inventory, 5);
            case LECTERN -> new GenericContainerMenu(player, inventory, 1, false);
            case UNKNOWN -> new GenericContainerMenu(player, inventory, 0, false);
        };
    }

    private AbstractContainerMenu menuFromMountScreen(int inventoryColumns) {
        return new GenericContainerMenu(player, inventory, 2 + Math.max(0, inventoryColumns) * 3);
    }

    @CultPacketHandler
    public void onOpenScreen(
            PacketSendEvent<ClientboundOpenScreen> event, CultPlayer player, ClientboundOpenScreen packet) {
        // Not 1:1 MCP, based on Wiki.VG to be simpler as we need less logic...
        // For example, we don't need permanent storage, only storing data until the client closes the window
        // We also don't need a lot of server-sided only logic
        MenuType menuType = MenuType.fromRegistryKey(packet.menuType());
        // There doesn't seem to be a check against using 0 as the window ID - let's consider that an invalid packet
        // It will probably mess up a TON of logic both client and server sided, so don't do that!
        deferClientboundInventoryTask(event, () -> {
            this.serverContainerType = menuType;
            openWindowID = packet.containerId();
            menu = menuFromOpenScreenType(menuType);
            merchantOffers = List.of();
            selectedMerchantOffer = 0;
        });
    }

    @CultPacketHandler
    public void onMountScreenOpen(
            PacketSendEvent<ClientboundMountScreenOpen> event, CultPlayer player, ClientboundMountScreenOpen packet) {
        deferClientboundInventoryTask(event, () -> {
            PacketEntity mount = player.compensatedEntities.getEntity(packet.entityId());
            if (mount == null
                    || (!EntityTypeUtil.isHorseFamily(mount.type)
                            && !EntityTypeUtil.isType(mount.type, "nautilus")
                            && !EntityTypeUtil.isType(mount.type, "zombie_nautilus"))) {
                return;
            }
            serverContainerType = MenuType.UNKNOWN;
            menu = menuFromMountScreen(packet.inventoryColumns());
            openWindowID = packet.containerId();
            merchantOffers = List.of();
            selectedMerchantOffer = 0;
        });
    }

    @CultPacketHandler
    public void onMerchantOffers(
            PacketSendEvent<InventoryPackets.Offers> event, CultPlayer player, InventoryPackets.Offers packet) {
        List<MerchantOffer> immutableOffers = packet.offers();
        int containerId = packet.windowId();
        deferClientboundInventoryTask(event, () -> {
            if (containerId == openWindowID && serverContainerType == MenuType.MERCHANT) {
                merchantOffers = immutableOffers;
                selectedMerchantOffer = 0;
                PredictedMerchantInventory.mirrorTrade(menu, merchantOffers, selectedMerchantOffer);
            }
        });
    }

    @CultPacketHandler("clientbound.container_close")
    public void onContainerClose(PacketSendEvent<Opaque> event, CultPlayer player, Opaque packet) {
        // Disregard provided window ID, client doesn't care...
        // We need to do this because the client doesn't send a packet when closing the window
        deferClientboundInventoryTask(event, () -> {
            openWindowID = 0;
            serverContainerType = MenuType.CRAFTING;
            menu = inventory;
            menu.setCarried(SimItemStack.EMPTY); // Reset carried item
            merchantOffers = List.of();
            selectedMerchantOffer = 0;
        });
    }

    @CultPacketHandler
    public void onContainerSetContent(PacketSendEvent<Content> event, CultPlayer player, Content items) {
        List<SimItemStack> slots = items.items();
        AbstractContainerMenu contentMenu = items.windowId() == 0 ? inventory : menuFromContentSlots(slots.size());

        if (items.windowId() == 0) { // Player inventory
            deferClientboundInventoryTask(event, () -> {
                stateID = items.stateId();
                for (int i = 0; i < slots.size(); i++) {
                    Slot slot = inventory.getSlot(i);
                    slot.set(slots.get(i));
                }
                setClientCarried(items.carriedItem());
            });
        } else {
            deferClientboundInventoryTask(event, () -> {
                if (!isApplicableContainerMirror(items.windowId())) {
                    return;
                }

                if (menu.getSlots().size() != slots.size()) {
                    menu = contentMenu;
                }
                for (int i = 0; i < slots.size(); i++) {
                    Slot menuSlot = menu.getSlot(i);
                    if (menuSlot != null) {
                        menuSlot.set(slots.get(i));
                    }
                }
                setClientCarried(items.carriedItem());
                if (serverContainerType == MenuType.MERCHANT && slots.size() <= 2) {
                    PredictedMerchantInventory.mirrorTrade(menu, merchantOffers, selectedMerchantOffer);
                }
            });
        }
    }

    @CultPacketHandler
    public void onSetPlayerInventory(
            PacketSendEvent<InventoryPackets.PlayerInventory> event,
            CultPlayer player,
            InventoryPackets.PlayerInventory packet) {
        int storageSlot = directPlayerInventorySlotToStorageSlot(packet.slot());
        if (storageSlot != -1) {
            deferClientboundInventoryTask(
                    event, () -> inventory.getInventoryStorage().setItem(storageSlot, packet.item()));
        }
    }

    @CultPacketHandler
    public void onSetCursorItem(
            PacketSendEvent<InventoryPackets.Cursor> event, CultPlayer player, InventoryPackets.Cursor packet) {
        deferClientboundInventoryTask(event, () -> setClientCarried(packet.item()));
    }

    @CultPacketHandler
    public void onSetHeldSlot(
            PacketSendEvent<ClientboundSetHeldSlot> event, CultPlayer player, ClientboundSetHeldSlot packet) {
        int slot = packet.slot();
        if (slot >= 0 && slot <= 8) {
            deferClientboundInventoryTask(event, () -> {
                inventory.selected = slot;
                player.packetStateData.lastSlotSelected = slot;
            });
        }
    }

    @CultPacketHandler
    public void onContainerSetSlot(
            PacketSendEvent<InventoryPackets.Slot> event, CultPlayer player, InventoryPackets.Slot slotData) {
        Runnable task = () -> {
            if (!isApplicableContainerMirror(slotData.windowId())) {
                return;
            }
            if (slotData.windowId() == 0) {
                stateID = slotData.stateId();
            }
            if (slotData.windowId() == 0) {
                // This packet can only be used to edit the hotbar and offhand of the player's inventory if
                // window ID is set to 0 (slots 36 through 45) if the player is in creative, with their inventory open,
                // and not in their survival inventory tab. Otherwise, when window ID is 0, it can edit any slot in the
                // player's inventory.
                if (slotData.slot() >= 0 && slotData.slot() <= 45) {
                    Slot slot = inventory.getSlot(slotData.slot());
                    slot.set(slotData.item());
                }
            } else if (slotData.windowId() == openWindowID) { // Opened inventory (if not valid, client crashes)
                Slot s = menu.getSlot(slotData.slot());
                if (s != null) {
                    s.set(slotData.item());
                    if (serverContainerType == MenuType.MERCHANT && (slotData.slot() == 0 || slotData.slot() == 1)) {
                        PredictedMerchantInventory.mirrorTrade(menu, merchantOffers, selectedMerchantOffer);
                    }
                } else {
                    LogUtil.error(player.getName() + " tried to set slot " + slotData.slot() + " in window "
                            + openWindowID + " | type=" + serverContainerType);
                }
            }
        };

        deferClientboundInventoryTask(event, task);
    }

    private boolean isApplicableContainerMirror(int windowId) {
        return isApplicableContainerMirror(openWindowID, windowId);
    }

    static boolean isApplicableContainerMirror(int openWindowId, int windowId) {
        return windowId == 0 || windowId == openWindowId;
    }
}
