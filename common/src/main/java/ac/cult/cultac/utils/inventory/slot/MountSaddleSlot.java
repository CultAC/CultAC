package ac.cult.cultac.utils.inventory.slot;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.utils.data.packetentity.ClientSaddle;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.inventory.ClientEquippable;
import ac.cult.cultac.utils.inventory.InventoryStorage;
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
        return canUseSaddleSlot() && ClientEquippable.fitsSlot(player, mount, stack, EquipmentSlot.SADDLE);
    }

    /** The mount's LivingEntity#canUseSlot for EquipmentSlot.SADDLE, which Mob#isSaddled reads too. */
    private boolean canUseSaddleSlot() {
        return ClientSaddle.canUseSaddleSlot(player, mount);
    }
}
