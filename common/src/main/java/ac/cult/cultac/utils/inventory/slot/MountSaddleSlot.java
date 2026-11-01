package ac.cult.cultac.utils.inventory.slot;

import ac.cult.blocksim.data.ItemComponents;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.inventory.InventoryStorage;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import java.util.List;

/**
 * Slot 0 of HorseInventoryMenu and NautilusInventoryMenu: an ArmorSlot over Mob#createEquipmentSlotContainer for
 * EquipmentSlot.SADDLE. Every set, whether from a click, a set_slot or a set_content, equips the client's mount, and
 * the slot shows the mount's saddle whatever set it last.
 */
public final class MountSaddleSlot extends Slot {
    private final CultPlayer player;
    private final PacketEntity mount;

    public MountSaddleSlot(CultPlayer player, PacketEntity mount) {
        super(new SaddleContainer(player, mount), 0);
        this.player = player;
        this.mount = mount;
    }

    /** Mob#createEquipmentSlotContainer: one slot reading and equipping the mount's EquipmentSlot.SADDLE. */
    private static final class SaddleContainer extends InventoryStorage {
        private final CultPlayer player;
        private final PacketEntity mount;

        SaddleContainer(CultPlayer player, PacketEntity mount) {
            super(1);
            this.player = player;
            this.mount = mount;
        }

        @Override
        public SimItemStack getItem(int index) {
            return mount.saddleItem;
        }

        @Override
        public void setItem(int index, SimItemStack stack) {
            SimItemStack item = stack == null ? SimItemStack.EMPTY : stack;
            int id = mount.getEntityId();
            if (player.compensatedEntities.getEntity(id) == mount) {
                player.compensatedEntities.updateEntityEquipment(
                        id, List.of(new InventoryPackets.EquipmentEntry(EquipmentSlot.SADDLE, item)));
            } else {
                mount.saddleItem = item;
            }
        }
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    /** ArmorSlot#mayPlace: LivingEntity#isEquippableInSlot for EquipmentSlot.SADDLE. */
    @Override
    public boolean mayPlace(SimItemStack stack) {
        ItemComponents.Equippable equippable = ItemComponents.equippable(stack.components());
        return equippable != null
                && equippable.slot().equals("saddle")
                && canUseSaddleSlot()
                && canBeEquippedBy(equippable.allowedEntities());
    }

    /** AbstractHorse#canUseSlot and AbstractNautilus#canUseSlot; Horse, SkeletonHorse, ZombieHorse and Llama allow any. */
    private boolean canUseSaddleSlot() {
        int type = mount.type;
        if (type == EntityTypeIds.HORSE
                || type == EntityTypeIds.SKELETON_HORSE
                || type == EntityTypeIds.ZOMBIE_HORSE
                || type == EntityTypeIds.LLAMA
                || EntityTypeUtil.isType(type, "trader_llama")) {
            return true;
        }
        if (mount instanceof PacketEntityHorse horse) {
            return horse.actionAlive && !horse.isBaby && horse.isTamed();
        }
        if (mount instanceof PacketEntityNautilus nautilus) {
            return nautilus.actionAlive && !nautilus.isBaby && nautilus.isTame;
        }
        return false;
    }

    /** Equippable#canBeEquippedBy against the client's entity type tags. */
    private boolean canBeEquippedBy(ItemComponents.HolderSet allowedEntities) {
        if (allowedEntities == null) {
            return true;
        }
        String key = EntityTypeUtil.modelType(mount.type).key();
        if (allowedEntities.tag() == null) {
            return allowedEntities.entries().contains(key);
        }
        return ClientFluidQueries.clientTags(player)
                .entities()
                .getOrDefault(allowedEntities.tag(), List.of())
                .contains(key);
    }
}
