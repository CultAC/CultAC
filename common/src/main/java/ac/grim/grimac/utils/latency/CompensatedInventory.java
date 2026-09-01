package ac.grim.grimac.utils.latency;

import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;
import ac.grim.grimac.utils.blockplace.NmsBlockPlaceResolver;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.inventory.EquipmentType;
import ac.grim.grimac.utils.inventory.Inventory;
import ac.grim.grimac.utils.inventory.inventory.AbstractContainerMenu;
import ac.grim.grimac.utils.inventory.inventory.GenericContainerMenu;
import ac.grim.grimac.utils.inventory.inventory.MenuType;
import ac.grim.grimac.utils.inventory.inventory.WindowClickType;
import ac.grim.grimac.utils.inventory.slot.Slot;
import ac.grim.grimac.utils.inventory.ItemUtil;
import ac.grim.grimac.utils.nmsutil.EntityTypeUtil;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.event.PacketSendEvent;
import ac.grim.grimac.utils.inventory.InventoryStorage;
import ac.grim.grimac.utils.nmsutil.EntityTypesCompat;
import org.bukkit.inventory.ItemStack;
import ac.grim.grimac.network.protocol.util.SpigotConversionUtil;
import lombok.Getter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import org.bukkit.GameMode;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.component.BundleContents;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Updated to support modern 1.17 protocol
public class CompensatedInventory extends GrimProcessor implements CheckListener {
    // "Temporarily" public for debugging
    public Inventory inventory;
    // "Temporarily" public for debugging
    public AbstractContainerMenu menu;
    // Mojang uses player inventory slot 40 as the SWAP button for offhand clicks.
    // Grim's compensated storage keeps the protocol offhand slot at 45.
    private static final int NMS_OFFHAND_SWAP_BUTTON = 40;
    public int openWindowID = 0;
    public int stateID = 0; // Player inventory state ID. Don't mess up the last sent state ID by changing it.
    private List<PredictedResultSlotValidator.MerchantOfferSnapshot> merchantOffers = List.of();
    private int selectedMerchantOffer = 0;

    public CompensatedInventory(GrimPlayer playerData) {
        super(playerData);

        InventoryStorage storage = new InventoryStorage(Inventory.STORAGE_SIZE);
        inventory = new Inventory(playerData, storage);

        menu = inventory;
    }

    private void setClientCarried(ItemStack stack) {
        menu.setCarried(stack == null ? ItemStack.empty() : stack);
    }

    private void deferClientboundInventoryTask(PacketSendEvent event, Runnable task) {
        GrimPlayer.TrackedTransaction transaction = player.createTrackedTransactionPacketForDeferredSend();
        if (transaction == null) {
            task.run();
            return;
        }

        player.latencyUtils.addRealTimeTask(transaction.transaction(), task);
        // PacketSendEvent tasks run after the complete outbound group has been forwarded. Sending
        // the proof there keeps a ping out of the middle of a vanilla bundle while still placing it
        // after the inventory packet on the ordered clientbound stream.
        event.getTasksAfterSend().add(() -> {
            player.user.writePacket(transaction.packet());
            player.markTrackedTransactionPacketSent(transaction);
        });
    }

    public ItemStack getHandItem(InteractionHand handType) {
        return handType == InteractionHand.MAIN_HAND ? getHeldItem() : getOffHand();
    }

    public ItemStack getHeldItem() {
        ItemStack item = inventory.getHeldItem();
        return item == null ? ItemStack.empty() : item;
    }

    public ItemStack getOffHand() {
        ItemStack item = inventory.getOffhand();
        return item == null ? ItemStack.empty() : item;
    }

    public boolean hasClientSelectedHandItem(Material material) {
        return getClientSelectedHeldItem().getType() == material
                || getOffHand().getType() == material;
    }

    public boolean hasClientSelectableHandItem(Material material) {
        if (getOffHand().getType() == material) {
            return true;
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack item = inventory.getInventoryStorage().getItem(Inventory.HOTBAR_OFFSET + slot);
            if (item.getType() == material) {
                return true;
            }
        }
        return false;
    }

    public ItemStack getClientSelectedHeldItem() {
        int selected = player.packetStateData.lastSlotSelected;
        if (selected < 0 || selected > 8) {
            selected = inventory.selected;
        }

        ItemStack item = inventory.getInventoryStorage().getItem(Inventory.HOTBAR_OFFSET + selected);
        return item == null ? ItemStack.empty() : item;
    }

    public ItemStack getHelmet() {
        ItemStack item = inventory.getHelmet();
        return item == null ? ItemStack.empty() : item;
    }

    public ItemStack getChestplate() {
        ItemStack item = inventory.getChestplate();
        return item == null ? ItemStack.empty() : item;
    }

    public ItemStack getLeggings() {
        ItemStack item = inventory.getLeggings();
        return item == null ? ItemStack.empty() : item;
    }

    public ItemStack getBoots() {
        ItemStack item = inventory.getBoots();
        return item == null ? ItemStack.empty() : item;
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
            NmsPacketUtil.ContainerClickData click,
            List<ItemStack> beforeSlots,
            ItemStack beforeCarried,
            List<ItemStack> afterSlots,
            ItemStack afterCarried,
            PredictedResultSlotValidator.ResultAllowance allowance
    ) {
        // Some menus do not expose the offhand slot, but vanilla still uses button 40 for
        // offhand swaps. Include the compensated offhand stack so conservation sees both sides.
        if (isImplicitOffhandSwap(click, afterSlots.size())) {
            List<ItemStack> beforeWithOffhand = new ArrayList<>(beforeSlots);
            List<ItemStack> afterWithOffhand = new ArrayList<>(afterSlots);
            ItemStack offhandBefore = ItemUtil.copy(getOffHand());
            beforeWithOffhand.add(offhandBefore);
            afterWithOffhand.add(inferOffhandAfterSwap(beforeSlots.get(click.slot()), offhandBefore, afterSlots.get(click.slot())));
            beforeSlots = beforeWithOffhand;
            afterSlots = afterWithOffhand;
        }

        return PredictedResultSlotValidator.isClientClaimPlausible(
                beforeSlots,
                beforeCarried,
                afterSlots,
                afterCarried,
                permitsCreativeCreation(click, player.gamemode),
                allowance == null ? null : allowance.material(),
                allowance == null ? 0 : allowance.amount()
        );
    }

    static boolean permitsCreativeCreation(NmsPacketUtil.ContainerClickData click, GameMode gameMode) {
        return gameMode == GameMode.CREATIVE
                && (click.clickType() == WindowClickType.CLONE
                || click.clickType() == WindowClickType.QUICK_CRAFT && (click.button() >> 2 & 3) == 2);
    }

    private boolean isImplicitOffhandSwap(NmsPacketUtil.ContainerClickData click, int menuSlotCount) {
        return click.clickType() == WindowClickType.SWAP
                && click.button() == NMS_OFFHAND_SWAP_BUTTON
                && click.slot() >= 0
                && click.slot() < menuSlotCount
                && !menuExposesOffhand(menu)
                && click.changedSlots().containsKey(click.slot());
    }

    private static List<ItemStack> copyMenuSlots(AbstractContainerMenu menu) {
        List<ItemStack> slots = new ArrayList<>(menu.getSlots().size());
        for (Slot slot : menu.getSlots()) {
            slots.add(ItemUtil.copy(slot.getItem()));
        }
        return slots;
    }

    private List<ItemStack> resolveChangedSlots(
            NmsPacketUtil.ContainerClickData click,
            List<ItemStack> beforeSlots,
            ItemStack beforeCarried
    ) {
        List<ItemStack> afterSlots = new ArrayList<>(beforeSlots);
        ItemStack clickedSource = click.slot() >= 0 && click.slot() < beforeSlots.size()
                ? beforeSlots.get(click.slot())
                : ItemStack.empty();
        for (Map.Entry<Integer, ItemStack> entry : click.changedSlots().entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= afterSlots.size()) {
                return null;
            }
            afterSlots.set(slot, resolveClaimedStack(
                    entry.getValue(),
                    beforeSlots.get(slot),
                    beforeSlots,
                    clickedSource,
                    beforeCarried));
        }
        return afterSlots;
    }

    private ItemStack resolveAfterCarried(
            NmsPacketUtil.ContainerClickData click,
            List<ItemStack> beforeSlots,
            ItemStack beforeCarried
    ) {
        ItemStack clickedSource = click.slot() >= 0 && click.slot() < beforeSlots.size()
                ? beforeSlots.get(click.slot())
                : ItemStack.empty();
        return resolveClaimedStack(
                click.carriedItem(),
                beforeCarried,
                beforeSlots,
                clickedSource,
                ItemStack.empty());
    }

    private ItemStack resolveClaimedStack(
            ItemStack claim,
            ItemStack previous,
            List<ItemStack> beforeSlots,
            ItemStack primarySource,
            ItemStack secondarySource
    ) {
        if (claim == null || claim.isEmpty()) {
            return ItemStack.empty();
        }

        for (ItemStack candidate : new ItemStack[]{previous, primarySource, secondarySource}) {
            ItemStack resolved = copyWithAmountIfMaterialMatches(candidate, claim.getType(), claim.getAmount());
            if (!resolved.isEmpty()) {
                return resolved;
            }
        }

        for (ItemStack candidate : beforeSlots) {
            ItemStack candidateCopy = copyWithAmountIfMaterialMatches(candidate, claim.getType(), claim.getAmount());
            if (!candidateCopy.isEmpty()) {
                return candidateCopy;
            }
        }

        return ItemUtil.copy(claim);
    }

    private static ItemStack copyWithAmountIfMaterialMatches(ItemStack source, Material material, int amount) {
        if (source == null || source.isEmpty() || material == null || source.getType() != material || amount <= 0) {
            return ItemStack.empty();
        }
        ItemStack copy = ItemUtil.copy(source);
        copy.setAmount(amount);
        return copy;
    }

    @GrimPacketHandler
    public void onContainerClick(PacketReceiveEvent event, GrimPlayer player, ServerboundContainerClickPacket packet) {
        if (event.isCancelled()) {
            return;
        }

        NmsPacketUtil.ContainerClickData click = NmsPacketUtil.readContainerClick(packet);

        // How is this possible? Maybe transaction splitting.
        if (click.windowId() != openWindowID) {
            return;
        }

        if (mirrorBundleClick(click)) {
            return;
        }

        List<ItemStack> beforeSlots = copyMenuSlots(menu);
        ItemStack beforeCarried = ItemUtil.copy(menu.getCarried());
        List<ItemStack> afterSlots = resolveChangedSlots(click, beforeSlots, beforeCarried);
        if (afterSlots == null) {
            return;
        }
        ItemStack afterCarried = resolveAfterCarried(click, beforeSlots, beforeCarried);

        PredictedResultSlotValidator.ResultAllowance allowance = PredictedResultSlotValidator.dialogResultAllowance(
                serverContainerType,
                click.slot(),
                beforeSlots,
                beforeCarried,
                afterSlots,
                afterCarried,
                merchantOffers,
                selectedMerchantOffer);
        if (!isClientClickClaimSane(
                click,
                beforeSlots,
                beforeCarried,
                afterSlots,
                afterCarried,
                allowance)) {
            return;
        }

        // The client already ran the same menu click code and sent the changed slot set.
        // The server treats this as remote prediction state, so Grim mirrors it only after a conservation check.
        ItemStack clickedBefore = ItemStack.empty();
        ItemStack offhandBefore = ItemUtil.copy(getOffHand());
        if (click.slot() >= 0) {
            Slot clickedSlot = menu.getSlot(click.slot());
            if (clickedSlot != null) {
                clickedBefore = ItemUtil.copy(clickedSlot.getItem());
            }
        }

        click.changedSlots().forEach((slot, item) -> {
            ItemStack resolved = afterSlots.get(slot);
            menu.getSlot(slot).set(resolved);
        });
        mirrorImplicitOffhandSwap(click, clickedBefore, offhandBefore);
        setClientCarried(afterCarried);
        if (serverContainerType == MenuType.MERCHANT && click.slot() != 2) {
            PredictedMerchantInventory.mirrorTrade(menu, merchantOffers, selectedMerchantOffer);
        }
    }

    private boolean mirrorBundleClick(NmsPacketUtil.ContainerClickData click) {
        if (click.clickType() != WindowClickType.PICKUP
                || (click.button() != 0 && click.button() != 1)
                || click.slot() < 0) {
            return false;
        }

        Slot slot = menu.getSlot(click.slot());
        if (slot == null) {
            return false;
        }

        ItemStack clickedBefore = ItemUtil.copy(slot.getItem());
        ItemStack carriedBefore = ItemUtil.copy(menu.getCarried());
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

    private BundleClickResult predictBundleClick(Slot slot, ItemStack clickedBefore, ItemStack carriedBefore, boolean primary) {
        net.minecraft.world.item.ItemStack clicked = SpigotConversionUtil.toNmsItemStack(clickedBefore);
        net.minecraft.world.item.ItemStack carried = SpigotConversionUtil.toNmsItemStack(carriedBefore);

        if (hasBundleContents(carried)) {
            BundleContents.Mutable mutable = new BundleContents.Mutable(carried.get(DataComponents.BUNDLE_CONTENTS));
            if (primary && !clicked.isEmpty()) {
                mutable.tryInsert(clicked);
                carried.set(DataComponents.BUNDLE_CONTENTS, mutable.toImmutable());
                return new BundleClickResult(SpigotConversionUtil.fromNmsItemStack(clicked), SpigotConversionUtil.fromNmsItemStack(carried));
            }
            if (!primary && clicked.isEmpty()) {
                net.minecraft.world.item.ItemStack removed = mutable.removeOne();
                if (removed == null) {
                    carried.set(DataComponents.BUNDLE_CONTENTS, mutable.toImmutable());
                    return new BundleClickResult(ItemStack.empty(), SpigotConversionUtil.fromNmsItemStack(carried));
                }
                ItemStack removedBukkit = SpigotConversionUtil.fromNmsItemStack(removed);
                if (slot.mayPlace(removedBukkit)) {
                    clicked = removed;
                } else {
                    mutable.tryInsert(removed);
                }
                carried.set(DataComponents.BUNDLE_CONTENTS, mutable.toImmutable());
                return new BundleClickResult(SpigotConversionUtil.fromNmsItemStack(clicked), SpigotConversionUtil.fromNmsItemStack(carried));
            }
        }

        if (hasBundleContents(clicked)) {
            BundleContents.Mutable mutable = new BundleContents.Mutable(clicked.get(DataComponents.BUNDLE_CONTENTS));
            if (primary && !carried.isEmpty()) {
                mutable.tryInsert(carried);
                clicked.set(DataComponents.BUNDLE_CONTENTS, mutable.toImmutable());
                return new BundleClickResult(SpigotConversionUtil.fromNmsItemStack(clicked), SpigotConversionUtil.fromNmsItemStack(carried));
            }
            if (!primary && carried.isEmpty()) {
                net.minecraft.world.item.ItemStack removed = mutable.removeOne();
                if (removed != null) {
                    carried = removed;
                }
                clicked.set(DataComponents.BUNDLE_CONTENTS, mutable.toImmutable());
                return new BundleClickResult(SpigotConversionUtil.fromNmsItemStack(clicked), SpigotConversionUtil.fromNmsItemStack(carried));
            }
        }

        return null;
    }

    private boolean bundlePredictionMatchesPacket(NmsPacketUtil.ContainerClickData click, BundleClickResult result) {
        ItemStack changedSlot = click.changedSlots().get(click.slot());
        boolean slotChanged = !sameStack(menu.getSlot(click.slot()).getItem(), result.slot());
        if (slotChanged != click.changedSlots().containsKey(click.slot())) {
            return false;
        }
        return (!slotChanged || sameMaterialAndAmount(changedSlot, result.slot()))
                && sameMaterialAndAmount(click.carriedItem(), result.carried());
    }

    private static boolean isBundle(ItemStack stack) {
        return hasBundleContents(SpigotConversionUtil.toNmsItemStack(stack));
    }

    private static boolean hasBundleContents(net.minecraft.world.item.ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.has(DataComponents.BUNDLE_CONTENTS);
    }

    private static boolean sameMaterialAndAmount(ItemStack first, ItemStack second) {
        if (first == null || first.isEmpty()) {
            return second == null || second.isEmpty();
        }
        return second != null && !second.isEmpty()
                && first.getType() == second.getType()
                && first.getAmount() == second.getAmount();
    }

    private record BundleClickResult(ItemStack slot, ItemStack carried) {
    }

    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket")
    public void onSelectBundleItem(PacketReceiveEvent event, GrimPlayer player, Packet<?> packet) {
        mirrorSelectedBundleItem(packet);
    }

    @GrimPacketHandler
    public void onSelectTrade(PacketReceiveEvent event, GrimPlayer player, ServerboundSelectTradePacket packet) {
        if (event.isCancelled() || serverContainerType != MenuType.MERCHANT) {
            return;
        }

        selectedMerchantOffer = packet.getItem();
        PredictedMerchantInventory.mirrorTradeSelection(menu, merchantOffers, selectedMerchantOffer);
    }

    @GrimPacketHandler
    public void onUseItem(PacketReceiveEvent event, GrimPlayer player, ServerboundUseItemPacket packet) {
        if (event.isCancelled()) {
            return;
        }

        NmsPacketUtil.UseItemData item = NmsPacketUtil.readUseItem(packet);
        ItemStack use = item.hand() == InteractionHand.MAIN_HAND ? player.getInventory().getHeldItem() : player.getInventory().getOffHand();
        if (mirrorEquipmentUse(item.hand(), use)) {
            return;
        }

        NmsBlockPlaceResolver.applyClientSideUseItem(player, item.hand(), item.yaw(), item.pitch());
    }

    @GrimPacketHandler
    public void onInteract(PacketReceiveEvent event, GrimPlayer player, ServerboundInteractPacket packet) {
        if (event.isCancelled()) {
            return;
        }

        NmsPacketUtil.InteractData interact = NmsPacketUtil.readInteract(packet);
        if (interact.action() == NmsPacketUtil.InteractAction.ATTACK) {
            return;
        }

        mirrorEntityBucketInteraction(interact);
    }

    private void mirrorEntityBucketInteraction(NmsPacketUtil.InteractData interact) {
        PacketEntity entity = player.compensatedEntities.getEntity(interact.entityId());
        if (entity == null || entity.isDead) {
            return;
        }

        ItemStack held = getHandItem(interact.hand());
        if (held == null || held.isEmpty()) {
            return;
        }

        ItemStack filledBucket = entityInteractionBucketResult(entity, held.getType());
        if (filledBucket == null || filledBucket.isEmpty()) {
            return;
        }

        ItemStack handAfter;
        List<ItemStack> addedItems;
        if (held.getAmount() <= 1) {
            handAfter = filledBucket;
            addedItems = List.of();
        } else {
            handAfter = ItemUtil.copy(held);
            handAfter.setAmount(held.getAmount() - 1);
            addedItems = List.of(filledBucket);
        }

        applyClientSideUseItemOnResult(interact.hand(), handAfter, addedItems);
    }

    private ItemStack entityInteractionBucketResult(PacketEntity entity, Material heldType) {
        if (heldType == Material.BUCKET && !entity.isBaby && isMilkable(entity.type)) {
            return new ItemStack(Material.MILK_BUCKET, 1);
        }

        if (heldType != Material.WATER_BUCKET) {
            return null;
        }

        Material bucket = bucketMaterialFor(entity.type);
        return bucket == null ? null : new ItemStack(bucket, 1);
    }

    private static boolean isMilkable(EntityType<?> type) {
        return type == EntityTypesCompat.COW
                || type == EntityTypesCompat.MOOSHROOM
                || type == EntityTypesCompat.GOAT;
    }

    private static Material bucketMaterialFor(EntityType<?> type) {
        if (type == EntityTypesCompat.COD) return Material.COD_BUCKET;
        if (type == EntityTypesCompat.SALMON) return Material.SALMON_BUCKET;
        if (type == EntityTypesCompat.PUFFERFISH) return Material.PUFFERFISH_BUCKET;
        if (type == EntityTypesCompat.TROPICAL_FISH) return Material.TROPICAL_FISH_BUCKET;
        if (type == EntityTypesCompat.AXOLOTL) return Material.AXOLOTL_BUCKET;
        if (type == EntityTypesCompat.TADPOLE) return Material.TADPOLE_BUCKET;
        return null;
    }

    private boolean mirrorEquipmentUse(InteractionHand hand, ItemStack use) {
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

        inventory.getInventoryStorage().setItem(handStorageSlot(hand), ItemUtil.copy(getByEquipmentType(equipmentType)));
        inventory.getInventoryStorage().setItem(slot, ItemUtil.copy(use));
        return true;
    }

    private ItemStack getByEquipmentType(EquipmentType type) {
        return switch (type) {
            case HEAD -> getHelmet();
            case CHEST -> getChestplate();
            case LEGS -> getLeggings();
            case FEET -> getBoots();
            case OFFHAND -> getOffHand();
            case MAINHAND -> getHeldItem();
            default -> ItemStack.empty();
        };
    }

    private int handStorageSlot(InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND
                ? inventory.selected + Inventory.HOTBAR_OFFSET
                : Inventory.SLOT_OFFHAND;
    }

    public void applyClientSideUseItemOnResult(InteractionHand hand, ItemStack handAfter, List<ItemStack> addedItems) {
        ItemStack before = getHandItem(hand);
        if (!sameStack(before, handAfter)) {
            inventory.getInventoryStorage().setItem(handStorageSlot(hand), handAfter);
        }

        if (addedItems != null) {
            for (ItemStack addedItem : addedItems) {
                if (addedItem == null || addedItem.isEmpty()) {
                    continue;
                }
                inventory.add(ItemUtil.copy(addedItem));
            }
        }
    }

    private static boolean sameStack(ItemStack first, ItemStack second) {
        if (first == null || first.isEmpty()) {
            return second == null || second.isEmpty();
        }
        return second != null
                && !second.isEmpty()
                && first.getAmount() == second.getAmount()
                && ItemUtil.isSameItemSameTags(first, second);
    }

    @GrimPacketHandler
    public void onPlayerAction(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerActionPacket packet) {
        NmsPacketUtil.PlayerActionData dig = NmsPacketUtil.readPlayerAction(packet);

        if (dig.action() == Action.DROP_ITEM) {
            ItemStack heldItem = ItemUtil.copy(getHeldItem());
            if (heldItem != null) {
                heldItem.setAmount(heldItem.getAmount() - 1);
                if (heldItem.getAmount() <= 0) {
                    heldItem = null;
                }
            }
            inventory.setHeldItem(heldItem);
        }

        if (dig.action() == Action.DROP_ALL_ITEMS) {
            inventory.setHeldItem(null);
        }
    }

    @GrimPacketHandler
    public void onSetCarriedItem(PacketReceiveEvent event, GrimPlayer player, ServerboundSetCarriedItemPacket packet) {
        // Stop people from spamming the server with an out-of-bounds exception
        if (packet.getSlot() < 0 || packet.getSlot() > 8) return;
        if (inventory.selected != packet.getSlot()) {
            player.packetStateData.carriedItemChangedThisClientTick = true;
        }
        inventory.selected = packet.getSlot();
        player.packetStateData.lastSlotSelected = packet.getSlot();
        player.compensatedEntities.vehicles.markItemControlledVehicleControlSwitch();
    }

    @GrimPacketHandler
    public void onSetCreativeModeSlot(PacketReceiveEvent event, GrimPlayer player, ServerboundSetCreativeModeSlotPacket packet) {
        if (event.isCancelled() || player.gamemode != GameMode.CREATIVE) return;

        boolean valid = packet.slotNum() >= 1 && packet.slotNum() <= 45;

        if (valid) {
            ItemStack itemStack = SpigotConversionUtil.fromNmsItemStack(packet.itemStack());
            player.getInventory().inventory.getSlot(packet.slotNum()).set(itemStack);
        }
    }

    @GrimPacketHandler
    public void onContainerClose(PacketReceiveEvent event, GrimPlayer player, ServerboundContainerClosePacket packet) {
        menu = inventory;
        openWindowID = 0;
        serverContainerType = MenuType.CRAFTING;
        menu.setCarried(ItemStack.empty()); // Reset carried item
        merchantOffers = List.of();
        selectedMerchantOffer = 0;
    }

    private void mirrorImplicitOffhandSwap(NmsPacketUtil.ContainerClickData click, ItemStack clickedBefore, ItemStack offhandBefore) {
        if (click.clickType() != WindowClickType.SWAP || click.button() != NMS_OFFHAND_SWAP_BUTTON || click.slot() < 0) {
            return;
        }
        if (menuExposesOffhand(menu) || !click.changedSlots().containsKey(click.slot())) {
            return;
        }

        Slot clickedSlot = menu.getSlot(click.slot());
        if (clickedSlot == null) {
            return;
        }

        ItemStack clickedAfter = ItemUtil.copy(clickedSlot.getItem());
        ItemStack offhandAfter = inferOffhandAfterSwap(clickedBefore, offhandBefore, clickedAfter);
        inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, offhandAfter);
    }

    private void mirrorSelectedBundleItem(Packet<?> packet) {
        int slotId = NmsPacketUtil.intValue(packet, "slotId");
        if (slotId < 0 || slotId >= menu.getSlots().size()) {
            return;
        }

        Slot slot = menu.getSlot(slotId);
        if (slot == null) {
            return;
        }

        net.minecraft.world.item.ItemStack item = SpigotConversionUtil.toNmsItemStack(slot.getItem());
        if (!item.has(DataComponents.BUNDLE_CONTENTS)) {
            return;
        }

        BundleItem.toggleSelectedItem(item, NmsPacketUtil.intValue(packet, "selectedItemIndex"));
        slot.set(SpigotConversionUtil.fromNmsItemStack(item));
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

    private ItemStack inferOffhandAfterSwap(ItemStack clickedBefore, ItemStack offhandBefore, ItemStack clickedAfter) {
        ItemStack clicked = ItemUtil.copy(clickedBefore);
        ItemStack source = ItemUtil.copy(offhandBefore);
        ItemStack target = ItemUtil.copy(clickedAfter);

        if (source.isEmpty()) {
            return target.isEmpty() ? clicked : ItemStack.empty();
        }

        if (!ItemUtil.isSameItemSameTags(source, target)) {
            return source;
        }

        int remaining = source.getAmount() - target.getAmount();
        if (remaining > 0) {
            ItemStack leftover = ItemUtil.copy(source);
            leftover.setAmount(remaining);
            return leftover;
        }

        return clicked.isEmpty() ? ItemStack.empty() : clicked;
    }

    public void onBlockPlace(BlockPlace place) {
        if (player.gamemode != GameMode.CREATIVE && place.getItemStack().getType() != Material.POWDER_SNOW_BUCKET) {
            int slot = place.getHand() == InteractionHand.MAIN_HAND
                    ? inventory.selected + Inventory.HOTBAR_OFFSET
                    : Inventory.SLOT_OFFHAND;
            place.getItemStack().setAmount(place.getItemStack().getAmount() - 1);
            inventory.getInventoryStorage().setItem(slot, place.getItemStack());
        }
    }

    @Getter private MenuType serverContainerType = MenuType.CRAFTING;

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
            case ANVIL, FURNACE, BLAST_FURNACE, SMOKER, GRINDSTONE, MERCHANT, CARTOGRAPHY_TABLE -> new GenericContainerMenu(player, inventory, 3);
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

    @GrimPacketHandler
    public void onOpenScreen(PacketSendEvent event, GrimPlayer player, ClientboundOpenScreenPacket packet) {
        // Not 1:1 MCP, based on Wiki.VG to be simpler as we need less logic...
        // For example, we don't need permanent storage, only storing data until the client closes the window
        // We also don't need a lot of server-sided only logic
        MenuType menuType = MenuType.fromNms(packet.getType());
        // There doesn't seem to be a check against using 0 as the window ID - let's consider that an invalid packet
        // It will probably mess up a TON of logic both client and server sided, so don't do that!
        deferClientboundInventoryTask(event, () -> {
            this.serverContainerType = menuType;
            openWindowID = packet.getContainerId();
            menu = menuFromOpenScreenType(menuType);
            merchantOffers = List.of();
            selectedMerchantOffer = 0;
        });
    }

    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ClientboundMountScreenOpenPacket")
    public void onMountScreenOpen(PacketSendEvent event, GrimPlayer player, Packet<?> packet) {
        NmsPacketUtil.MountScreenOpenData data = NmsPacketUtil.readMountScreenOpen(packet);
        deferClientboundInventoryTask(event, () -> {
            PacketEntity mount = player.compensatedEntities.getEntity(data.entityId());
            if (mount == null || (!EntityTypeUtil.isHorseFamily(mount.type)
                    && !EntityTypeUtil.isType(mount.type, "nautilus")
                    && !EntityTypeUtil.isType(mount.type, "zombie_nautilus"))) {
                return;
            }
            serverContainerType = MenuType.UNKNOWN;
            menu = menuFromMountScreen(data.inventoryColumns());
            openWindowID = data.containerId();
            merchantOffers = List.of();
            selectedMerchantOffer = 0;
        });
    }

    @GrimPacketHandler
    public void onMerchantOffers(PacketSendEvent event, GrimPlayer player, ClientboundMerchantOffersPacket packet) {
        List<PredictedResultSlotValidator.MerchantOfferSnapshot> offers = new ArrayList<>(packet.getOffers().size());
        for (net.minecraft.world.item.trading.MerchantOffer offer : packet.getOffers()) {
            offers.add(PredictedResultSlotValidator.MerchantOfferSnapshot.fromNms(offer));
        }
        List<PredictedResultSlotValidator.MerchantOfferSnapshot> immutableOffers = List.copyOf(offers);
        int containerId = packet.getContainerId();
        deferClientboundInventoryTask(event, () -> {
            if (containerId == openWindowID && serverContainerType == MenuType.MERCHANT) {
                merchantOffers = immutableOffers;
                selectedMerchantOffer = 0;
                PredictedMerchantInventory.mirrorTrade(menu, merchantOffers, selectedMerchantOffer);
            }
        });
    }

    @GrimPacketHandler
    public void onContainerClose(PacketSendEvent event, GrimPlayer player, ClientboundContainerClosePacket packet) {
        // Disregard provided window ID, client doesn't care...
        // We need to do this because the client doesn't send a packet when closing the window
        deferClientboundInventoryTask(event, () -> {
            openWindowID = 0;
            serverContainerType = MenuType.CRAFTING;
            menu = inventory;
            menu.setCarried(ItemStack.empty()); // Reset carried item
            merchantOffers = List.of();
            selectedMerchantOffer = 0;
        });
    }

    @GrimPacketHandler
    public void onContainerSetContent(PacketSendEvent event, GrimPlayer player, ClientboundContainerSetContentPacket packet) {
        NmsPacketUtil.ContainerSetContentData items = NmsPacketUtil.readContainerContents(packet);

        List<ItemStack> slots = items.items();
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

    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket")
    public void onSetPlayerInventory(PacketSendEvent event, GrimPlayer player, Packet<?> packet) {
        int storageSlot = directPlayerInventorySlotToStorageSlot(NmsPacketUtil.intValue(packet, "slot"));
        if (storageSlot != -1) {
            deferClientboundInventoryTask(event, () -> inventory.getInventoryStorage().setItem(
                    storageSlot,
                    SpigotConversionUtil.fromNmsItemStack((net.minecraft.world.item.ItemStack) NmsPacketUtil.invokeNoArg(packet, "contents"))));
        }
    }

    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket")
    public void onSetCursorItem(PacketSendEvent event, GrimPlayer player, Packet<?> packet) {
        deferClientboundInventoryTask(event, () ->
                setClientCarried(SpigotConversionUtil.fromNmsItemStack(
                        (net.minecraft.world.item.ItemStack) NmsPacketUtil.invokeNoArg(packet, "contents"))));
    }

    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket")
    public void onSetHeldSlot(PacketSendEvent event, GrimPlayer player, Packet<?> packet) {
        int slot = NmsPacketUtil.intValue(packet, "slot", "getSlot");
        if (slot >= 0 && slot <= 8) {
            deferClientboundInventoryTask(event, () -> {
                inventory.selected = slot;
                player.packetStateData.lastSlotSelected = slot;
            });
        }
    }

    @GrimPacketHandler
    public void onContainerSetSlot(PacketSendEvent event, GrimPlayer player, ClientboundContainerSetSlotPacket packet) {
        NmsPacketUtil.ContainerSetSlotData slotData = NmsPacketUtil.readContainerSetSlot(packet);

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
                // and not in their survival inventory tab. Otherwise, when window ID is 0, it can edit any slot in the player's inventory.
                if (slotData.slot() >= 0 && slotData.slot() <= 45) {
                    Slot slot = inventory.getSlot(slotData.slot());
                    slot.set(slotData.item());
                }
            } else if (slotData.windowId() == openWindowID) { // Opened inventory (if not valid, client crashes)
                Slot s = menu.getSlot(slotData.slot());
                if (s != null) { s.set(slotData.item());
                    if (serverContainerType == MenuType.MERCHANT
                            && (slotData.slot() == 0 || slotData.slot() == 1)) {
                        PredictedMerchantInventory.mirrorTrade(menu, merchantOffers, selectedMerchantOffer);
                    }
                } else {
                    LogUtil.error(player.getName() + " tried to set slot " + slotData.slot()
                            + " in window " + openWindowID + " | type=" + serverContainerType);
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
