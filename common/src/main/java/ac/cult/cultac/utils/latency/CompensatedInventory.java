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
    private int lastClickServerTick;
    private int clicksThisServerTick;
    private int maxClicksPerTick = 3;
    public int stateID = 0; // Player inventory state ID. Don't mess up the last sent state ID by changing it.
    // What the server sent or silently accepted; resynchronization resends only from this.
    private final ServerInventoryView serverView = new ServerInventoryView();
    private boolean lastClickMirrored;
    private int observedWindowId;
    private AbstractContainerMenu observedMenu;
    private List<MerchantOffer> merchantOffers = List.of();
    private int selectedMerchantOffer = 0;

    public CompensatedInventory(CultPlayer playerData) {
        super(playerData);

        InventoryStorage storage =
                new ac.cult.cultac.utils.lists.CorrectingPlayerInventoryStorage(playerData, Inventory.STORAGE_SIZE);
        inventory = new Inventory(playerData, storage);

        menu = inventory;
        observedMenu = inventory;
    }

    /** Outbound server values never include local item-use/placement predictions. */
    public SimItemStack serverSlotForCorrection(int slot) {
        SimItemStack item = serverView.playerSlot(slot);
        return item == null ? ItemUtil.copy(inventory.getInventoryStorage().getItem(slot)) : item;
    }

    /** The server's value for a player storage slot as last sent or silently accepted, or null if unknown. */
    public SimItemStack serverViewSlot(int storageSlot) {
        return serverView.playerSlot(storageSlot);
    }

    public int serverStateId() {
        return serverView.playerStateId();
    }

    /** The server's value for a player storage slot once every sent inventory packet is applied, else null. */
    public SimItemStack settledServerSlot(int storageSlot) {
        return serverView.settled() ? serverView.playerSlot(storageSlot) : null;
    }

    /** The container the server most recently opened for this player; 0 is its own inventory. */
    public int serverWindowId() {
        return observedWindowId;
    }

    public boolean hasPendingServerInventory() {
        return !serverView.settled();
    }

    /** Resends the server's value for a player storage slot, applied to the model at its proof. */
    public void correctPlayerSlot(int storageSlot) {
        SimItemStack item = serverView.playerSlot(storageSlot);
        if (item == null) return;
        BlockPredictionAckSender.sendCorrections(
                player,
                0,
                false,
                List.of(),
                List.of(new InventoryPackets.Slot(0, serverView.playerStateId(), storageSlot, item)),
                null);
    }

    public void applySlotCorrection(InventoryPackets.Slot correction) {
        Slot slot;
        if (correction.windowId() == 0) {
            stateID = correction.stateId();
            slot = inventory.getSlot(correction.slot());
        } else if (correction.windowId() == openWindowID) {
            slot = menu.getSlot(correction.slot());
        } else return;
        if (slot != null) {
            slot.set(ItemUtil.copy(correction.item()));
            confirmHotbarSlot(slot);
        }
    }

    public void applyCursorCorrection(SimItemStack item) {
        setClientCarried(ItemUtil.copy(item));
    }

    private void observeServerSlot(boolean container, int index, Slot slot, SimItemStack item) {
        if (slot == null) return;
        if (slot.isBackedBy(inventory.getInventoryStorage())) {
            serverView.playerSlot(slot.getContainerSlot(), item);
        } else if (container) {
            serverView.containerSlot(index, item);
        }
    }

    /** A slot value the server itself sent, as opposed to a click claim it accepted silently. */
    private void observeSentServerSlot(boolean container, int index, Slot slot, SimItemStack item) {
        observeServerSlot(container, index, slot, item);
        if (slot != null && slot.isBackedBy(inventory.getInventoryStorage()))
            player.itemUseBurst.serverSlotSent(slot.getContainerSlot(), item);
    }

    private SimItemStack serverMenuSlot(boolean container, int index, Slot slot) {
        if (slot == null) return null;
        if (slot.isBackedBy(inventory.getInventoryStorage())) return serverView.playerSlot(slot.getContainerSlot());
        return container ? serverView.containerSlot(index) : null;
    }

    @Override
    public void onReload(ac.grim.grimac.api.config.ConfigManager config) {
        maxClicksPerTick = config.getIntElse("max-clicks-per-tick", 3);
    }

    /**
     * Called after all click checks for a cancelled click. The client applied the click locally
     * (MultiPlayerGameMode#handleContainerInput) and the server never saw it, so the server will not
     * correct it. Resend the server's values for every slot the click touched, and the cursor.
     */
    public void resyncCancelledClick(InventoryClick click) {
        player.itemUseBurst.clickCancelled();
        if (player.isBedrockMovement() || click.windowId() != observedWindowId) return;
        boolean container = click.windowId() != 0;
        AbstractContainerMenu target = container ? observedMenu : inventory;
        int stateId = container ? serverView.containerStateId() : serverView.playerStateId();
        var touched = new java.util.LinkedHashSet<>(click.changedSlots().keySet());
        if (click.slot() >= 0) touched.add(click.slot());
        List<InventoryPackets.Slot> slots = new ArrayList<>();
        for (int index : touched) {
            SimItemStack item = serverMenuSlot(container, index, target.getSlot(index));
            if (item != null) slots.add(new InventoryPackets.Slot(click.windowId(), stateId, index, item));
        }
        BlockPredictionAckSender.sendCorrections(player, 0, false, List.of(), slots, serverView.carried());
    }

    /**
     * Resends every known slot of the server's open menu and its cursor from the observed server
     * view. Owner thread only. Returns the slots sent.
     */
    public int resyncFromServerView() {
        if (player.isBedrockMovement()) return 0;
        boolean container = observedWindowId != 0;
        AbstractContainerMenu target = container ? observedMenu : inventory;
        int stateId = container ? serverView.containerStateId() : serverView.playerStateId();
        List<InventoryPackets.Slot> slots = new ArrayList<>();
        for (int index = 0; index < target.getSlots().size(); index++) {
            SimItemStack item = serverMenuSlot(container, index, target.getSlot(index));
            if (item != null) slots.add(new InventoryPackets.Slot(observedWindowId, stateId, index, item));
        }
        BlockPredictionAckSender.sendCorrections(player, 0, false, List.of(), slots, serverView.carried());
        return slots.size();
    }

    /** Called after all click checks for a click forwarded to the server. */
    public void onClickForwarded(InventoryClick click) {
        if (player.isBedrockMovement() || click.windowId() != observedWindowId) return;
        // ServerGamePacketListenerImpl#handleContainerClick records the client's claimed slots as the
        // remote state and corrects only differences, so a click it accepts is never echoed back.
        boolean known = lastClickMirrored && click.windowId() == openWindowID;
        boolean container = click.windowId() != 0;
        AbstractContainerMenu target = container ? observedMenu : inventory;
        for (int index : click.changedSlots().keySet()) {
            Slot claimed = known ? menu.getSlot(index) : null;
            observeServerSlot(container, index, target.getSlot(index), claimed == null ? null : claimed.getItem());
        }
        serverView.carried(known ? menu.getCarried() : null);
    }

    private void deferServerInventoryTask(PacketSendEvent<?> event, Runnable task) {
        serverView.packetSent();
        deferClientboundInventoryTask(event, () -> {
            try {
                task.run();
            } finally {
                serverView.packetApplied();
            }
        });
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
        if (player.isBedrockMovement()) {
            if (event.isCancelled()) return;
        } else {
            int currentTick = ac.cult.cultac.CultAPI.INSTANCE.getTickManager().currentTick;
            if (lastClickServerTick != currentTick) {
                clicksThisServerTick = 0;
                lastClickServerTick = currentTick;
            }
            if (maxClicksPerTick > 0 && clicksThisServerTick >= maxClicksPerTick) {
                event.setCancelled(true);
            } else if (packet.clickType() != WindowClickType.PICKUP_ALL
                    && packet.clickType() != WindowClickType.QUICK_CRAFT
                    && (packet.clickType() != WindowClickType.QUICK_MOVE
                            || menu.getCarried().isEmpty()
                            || packet.button() != 0)) {
                clicksThisServerTick++;
            }
        }
        // MultiPlayerGameMode#handleContainerInput applies the click before sending it.
        // Cancelling server processing does not undo that local prediction. Keep validating
        // and mirroring it until an authoritative clientbound inventory update arrives.

        // Only changes of item type can reset Player#attackStrengthTicker. Use the existing
        // validated inventory prediction, not the packet's unvalidated changed-slot claims.
        String[] hotbarBefore = new String[9];
        for (int slot = 0; slot < 9; slot++) {
            hotbarBefore[slot] = inventory
                    .getInventoryStorage()
                    .getItem(Inventory.HOTBAR_OFFSET + slot)
                    .itemKey();
        }
        boolean mirrored = applyContainerClick(packet);
        lastClickMirrored = mirrored;
        if (!player.isBedrockMovement()) {
            // Retain the possible reset if the local click could not be reconstructed. The
            // selected slot can trail the client by one tick, so compare every hotbar slot.
            boolean changed = !mirrored;
            for (int slot = 0; slot < 9 && !changed; slot++) {
                changed = !hotbarBefore[slot].equals(inventory
                        .getInventoryStorage()
                        .getItem(Inventory.HOTBAR_OFFSET + slot)
                        .itemKey());
            }
            if (!mirrored) {
                for (int slot = 0; slot < 9; slot++) player.attackStrength.onUnconfirmedMainHandChange(slot);
            } else if (changed) {
                player.attackStrength.onPossibleReset();
            }
        }
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
        SimItemStack clickedObject = SimItemStack.EMPTY;
        SimItemStack clickedBefore = SimItemStack.EMPTY;
        SimItemStack offhandBefore = ItemUtil.copy(getOffHand());
        if (click.slot() >= 0) {
            Slot clickedSlot = menu.getSlot(click.slot());
            if (clickedSlot != null) {
                clickedObject = clickedSlot.getItem();
                clickedBefore = ItemUtil.copy(clickedObject);
            }
        }

        click.changedSlots().forEach((slot, item) -> {
            SimItemStack resolved = afterSlots.get(slot);
            menu.getSlot(slot).set(resolved);
        });
        mirrorImplicitOffhandSwap(click, clickedObject, clickedBefore, offhandBefore);
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
        SimItemStack previous = getHeldItem();
        SimItemStack held = ItemUtil.copy(previous);
        if (wholeStack || held.isEmpty() || held.getCount() <= 1) {
            inventory.setHeldItem(null);
        } else {
            held.setCount(held.getCount() - 1);
            inventory.setHeldItem(held);
            carryPossibleChange(previous, held);
        }
    }

    /** A modelled copy of a possibly consumed stack is still unproven against the client's contents. */
    private void carryPossibleChange(SimItemStack previous, SimItemStack replacement) {
        if (!replacement.isEmpty() && player.clientItemUse.possiblyChanged(previous))
            player.clientItemUse.markPossiblyChanged(replacement);
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
        if (player.usesModernJavaItemUse() && player.packetStateData.lastSlotSelected != slot)
            player.clientItemUse.claimedSlotChanged();
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
            // ServerGamePacketListenerImpl#handleSetCreativeModeSlot sets the remote slot without echoing.
            observeServerSlot(false, slot, inventory.getSlot(slot), stack);
        }
    }

    @CultPacketHandler
    public void onContainerClose(
            PacketReceiveEvent<ac.cult.cultac.protocol.packet.serverbound.ServerboundContainerClose> event,
            CultPlayer player,
            ac.cult.cultac.protocol.packet.serverbound.ServerboundContainerClose packet) {
        if (!event.isCancelled()) {
            // The server's container closes when it handles this packet.
            observedWindowId = 0;
            observedMenu = inventory;
            serverView.containerClosed();
        }
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
            InventoryClick click, SimItemStack clickedObject, SimItemStack clickedBefore, SimItemStack offhandBefore) {
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
        SimItemStack offhandObject = getOffHand();
        inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, offhandAfter);
        // The click proves the clicked slot's contents, not the offhand inferred from them.
        carryPossibleChange(clickedObject, offhandAfter);
        carryPossibleChange(offhandObject, offhandAfter);
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
        observedWindowId = packet.containerId();
        observedMenu = menuFromOpenScreenType(menuType);
        serverView.containerOpened(observedMenu.getSlots().size());
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
        observedWindowId = packet.containerId();
        observedMenu = menuFromMountScreen(packet.inventoryColumns());
        serverView.containerOpened(observedMenu.getSlots().size());
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

    @CultPacketHandler
    public void onContainerClose(
            PacketSendEvent<ac.cult.cultac.protocol.packet.clientbound.ClientboundContainerClose> event,
            CultPlayer player,
            ac.cult.cultac.protocol.packet.clientbound.ClientboundContainerClose packet) {
        observedWindowId = 0;
        observedMenu = inventory;
        serverView.containerClosed();
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
        if (items.windowId() == 0) {
            serverView.playerStateId(items.stateId());
            for (int i = 0; i < slots.size(); i++) observeSentServerSlot(false, i, inventory.getSlot(i), slots.get(i));
            if (observedWindowId == 0) serverView.carried(items.carriedItem());
        } else if (items.windowId() == observedWindowId) {
            if (observedMenu.getSlots().size() != slots.size()) {
                observedMenu = contentMenu;
                serverView.containerOpened(slots.size());
            }
            serverView.containerStateId(items.stateId());
            for (int i = 0; i < slots.size(); i++)
                observeSentServerSlot(true, i, observedMenu.getSlot(i), slots.get(i));
            serverView.carried(items.carriedItem());
        }

        if (items.windowId() == 0) { // Player inventory
            deferServerInventoryTask(event, () -> {
                stateID = items.stateId();
                for (int i = 0; i < slots.size(); i++) {
                    Slot slot = inventory.getSlot(i);
                    slot.set(slots.get(i));
                    confirmHotbarSlot(slot);
                }
                setClientCarried(items.carriedItem());
            });
        } else {
            deferServerInventoryTask(event, () -> {
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
                        confirmHotbarSlot(menuSlot);
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
            serverView.playerSlot(storageSlot, packet.item());
            player.itemUseBurst.serverSlotSent(storageSlot, packet.item());
            deferServerInventoryTask(event, () -> {
                inventory.getInventoryStorage().setItem(storageSlot, packet.item());
                player.attackStrength.confirmHotbarSlot(storageSlot - Inventory.HOTBAR_OFFSET);
            });
        }
    }

    @CultPacketHandler
    public void onSetCursorItem(
            PacketSendEvent<InventoryPackets.Cursor> event, CultPlayer player, InventoryPackets.Cursor packet) {
        serverView.carried(packet.item());
        deferServerInventoryTask(event, () -> setClientCarried(packet.item()));
    }

    @CultPacketHandler
    public void onSetHeldSlot(
            PacketSendEvent<ClientboundSetHeldSlot> event, CultPlayer player, ClientboundSetHeldSlot packet) {
        int slot = packet.slot();
        if (slot >= 0 && slot <= 8) {
            deferClientboundInventoryTask(event, () -> {
                inventory.selected = slot;
                // A clientbound selected-slot packet changes the client's inventory.
                // It does not prove the server has stopped its outstanding use.
                player.packetStateData.lastSlotSelected = slot;
            });
        }
    }

    @CultPacketHandler
    public void onContainerSetSlot(
            PacketSendEvent<InventoryPackets.Slot> event, CultPlayer player, InventoryPackets.Slot slotData) {
        if (slotData.windowId() == 0) {
            serverView.playerStateId(slotData.stateId());
            observeSentServerSlot(false, slotData.slot(), inventory.getSlot(slotData.slot()), slotData.item());
        } else if (slotData.windowId() == observedWindowId) {
            serverView.containerStateId(slotData.stateId());
            observeSentServerSlot(true, slotData.slot(), observedMenu.getSlot(slotData.slot()), slotData.item());
        }
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
                    confirmHotbarSlot(slot);
                }
            } else if (slotData.windowId() == openWindowID) { // Opened inventory (if not valid, client crashes)
                Slot s = menu.getSlot(slotData.slot());
                if (s != null) {
                    s.set(slotData.item());
                    confirmHotbarSlot(s);
                    if (serverContainerType == MenuType.MERCHANT && (slotData.slot() == 0 || slotData.slot() == 1)) {
                        PredictedMerchantInventory.mirrorTrade(menu, merchantOffers, selectedMerchantOffer);
                    }
                } else {
                    LogUtil.error(player.getName() + " tried to set slot " + slotData.slot() + " in window "
                            + openWindowID + " | type=" + serverContainerType);
                }
            }
        };

        deferServerInventoryTask(event, task);
    }

    private boolean isApplicableContainerMirror(int windowId) {
        return isApplicableContainerMirror(openWindowID, windowId);
    }

    private void confirmHotbarSlot(Slot slot) {
        if (slot.isBackedBy(inventory.getInventoryStorage())) {
            player.attackStrength.confirmHotbarSlot(slot.getContainerSlot() - Inventory.HOTBAR_OFFSET);
        }
    }

    static boolean isApplicableContainerMirror(int openWindowId, int windowId) {
        return windowId == 0 || windowId == openWindowId;
    }
}
