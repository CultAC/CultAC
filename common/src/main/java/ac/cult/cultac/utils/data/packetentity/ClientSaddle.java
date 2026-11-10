package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.utils.inventory.ClientEquippable;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;

/**
 * Mob#isSaddled as the Java client reads it from a mount's EquipmentSlot.SADDLE, kept in the mount's hasSaddle.
 * Pig, Strider, AbstractHorse and AbstractNautilus#getControllingPassenger read it each time, so a change of the
 * saddle, health, age, tame state or entity type tags can move control without new equipment.
 */
public final class ClientSaddle {
    private ClientSaddle() {}

    /**
     * Mob#isSaddled: 1.21.5 tests only Mob#hasItemInSlot. From 1.21.6 Mob#hasValidEquippableItemForSlot also requires
     * LivingEntity#isEquippableInSlot, the item's EQUIPPABLE component for this mount and the mount's canUseSlot.
     * Geyser marks a Bedrock mount saddled from the occupied slot.
     */
    public static boolean isSaddled(CultPlayer player, PacketEntity mount) {
        SimItemStack saddle = mount.saddleItem;
        if (saddle.isEmpty()) return false;
        if (player.isBedrockMovement() || player.getClientVersion().isOlderThan(ClientVersion.V_1_21_6)) return true;
        return canUseSaddleSlot(player, mount)
                && ClientEquippable.fitsSlot(player, mount, saddle, EquipmentSlot.SADDLE);
    }

    /**
     * LivingEntity#canUseSlot(EquipmentSlot.SADDLE). Pig and Strider open it to a live adult, AbstractHorse and
     * AbstractNautilus to a live tamed adult. Horse and Llama, which TraderLlama extends, open every slot, as
     * SkeletonHorse and ZombieHorse do from 1.21.11.
     */
    public static boolean canUseSaddleSlot(CultPlayer player, PacketEntity mount) {
        int type = mount.type;
        if (type == EntityTypeIds.HORSE || type == EntityTypeIds.LLAMA || EntityTypeUtil.isType(type, "trader_llama"))
            return true;
        if ((type == EntityTypeIds.SKELETON_HORSE || type == EntityTypeIds.ZOMBIE_HORSE)
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_11)) return true;
        boolean liveAdult = mount.actionAlive && !mount.isBaby;
        if (mount instanceof PacketEntityHorse horse) return liveAdult && horse.isTamed();
        if (mount instanceof PacketEntityNautilus nautilus) return liveAdult && nautilus.isTame;
        return mount instanceof PacketEntityRideable && liveAdult;
    }

    /** Sets the mount's hasSaddle from {@link #isSaddled}, after its saddle equipment changed. */
    public static void update(CultPlayer player, PacketEntity mount) {
        boolean saddled = isSaddled(player, mount);
        if (mount instanceof PacketEntityRideable rideable) rideable.hasSaddle = saddled;
        if (mount instanceof PacketEntityHorse horse) horse.hasSaddle = saddled;
        if (mount instanceof PacketEntityNautilus nautilus) nautilus.hasSaddle = saddled;
    }

    /**
     * Re-reads Mob#isSaddled after a health, age, tame or tag change and opens the control switch the vehicle's next
     * tick makes when it flips. Before 1.21.5 the wire syncs the saddle as a flag that hasSaddle holds, and Bedrock's
     * reads only the occupied slot, so neither depends on these.
     */
    public static void refresh(CultPlayer player, PacketEntity mount) {
        if (player.isBedrockMovement()
                || !WatchableIndexUtil.forModel(player.user.getCultConnection()).saddleEquipment()) return;
        if (isSaddled(player, mount) == hasSaddle(mount)) return;
        var vehicles = player.compensatedEntities.vehicles;
        boolean controlled = vehicles.canClientAuthoritativelyMoveVisibleRoot(mount);
        update(player, mount);
        vehicles.markSaddleControlSwitch(mount, controlled);
    }

    /** The isSaddled inputs entity data can change, so a metadata packet refreshes only a mount it changed. */
    public static int metadataInputs(PacketEntity mount) {
        boolean tame = mount instanceof PacketEntityHorse horse
                ? horse.isTamed()
                : mount instanceof PacketEntityNautilus nautilus && nautilus.isTame;
        return (mount.actionAlive ? 1 : 0) | (mount.isBaby ? 2 : 0) | (tame ? 4 : 0);
    }

    private static boolean hasSaddle(PacketEntity mount) {
        if (mount instanceof PacketEntityRideable rideable) return rideable.hasSaddle;
        if (mount instanceof PacketEntityHorse horse) return horse.hasSaddle;
        return mount instanceof PacketEntityNautilus nautilus && nautilus.hasSaddle;
    }
}
