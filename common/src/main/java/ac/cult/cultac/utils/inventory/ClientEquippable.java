package ac.cult.cultac.utils.inventory;

import ac.cult.blocksim.data.ItemComponents;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import java.util.List;

/** The client's EQUIPPABLE component checks for an entity's equipment slots. */
public final class ClientEquippable {
    private ClientEquippable() {}

    /**
     * LivingEntity#isEquippableInSlot apart from the entity's own canUseSlot, which callers check: an EQUIPPABLE
     * component that targets {@code slot} and admits {@code entity}'s type. A stack without one fits only the main
     * hand.
     */
    public static boolean fitsSlot(CultPlayer player, PacketEntity entity, SimItemStack stack, EquipmentSlot slot) {
        ItemComponents.Equippable equippable = ItemComponents.equippable(stack.components());
        if (equippable == null) return slot == EquipmentSlot.MAINHAND;
        return slot.getName().equals(equippable.slot())
                && canBeEquippedBy(player, entity, equippable.allowedEntities());
    }

    /** Equippable#canBeEquippedBy against the client's entity type tags. */
    private static boolean canBeEquippedBy(
            CultPlayer player, PacketEntity entity, ItemComponents.HolderSet allowedEntities) {
        if (allowedEntities == null) {
            return true;
        }
        String key = EntityTypeUtil.modelType(entity.type).key();
        if (allowedEntities.tag() == null) {
            return allowedEntities.entries().contains(key);
        }
        return ClientFluidQueries.clientTags(player)
                .entities()
                .getOrDefault(allowedEntities.tag(), List.of())
                .contains(key);
    }
}
