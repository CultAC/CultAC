package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import org.bukkit.inventory.ItemStack;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;

public class PacketPlayerAttack {

    //LOW
    @CultPacketHandler
    public void onInteract(PacketReceiveEvent event, CultPlayer player, ServerboundInteractPacket packet) {
        handleInteractPacket(player, NmsPacketUtil.readInteract(packet));
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttack(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        handleInteractPacket(player, NmsPacketUtil.readAttack(packet));
    }

    @CultPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, CultPlayer player, ServerboundClientTickEndPacket packet) {
        player.minPlayerAttackSlow = 0;
    }

    private void handleInteractPacket(CultPlayer player, NmsPacketUtil.InteractData interact) {
        if (interact != null) {
            if (interact.action() == NmsPacketUtil.InteractAction.ATTACK) {
                ItemStack heldItem = player.getInventory().getHeldItem();
                PacketEntity entity = player.compensatedEntities.getEntity(interact.entityId());

                if (entity != null && (!(entity.type instanceof LivingEntity) || entity.type == EntityTypesCompat.PLAYER)) {
                    boolean hasKnockbackSword = heldItem != null && heldItem.getEnchantmentLevel(Enchantment.KNOCKBACK) > 0;

                    player.maxPlayerAttackSlow += 1;

                    // Players with knockback enchantments always get slowed.
                    if (hasKnockbackSword) {
                        player.minPlayerAttackSlow += 1;
                    }
                }
            } else if (interact.action() == NmsPacketUtil.InteractAction.INTERACT) {
                // Interacting with a horse in versions 1.13- will cause the client to
                // set the player's rotation to the horse's rotation (modern PacketPlayerAttack)
                if (player.compensatedEntities.getEntity(interact.entityId()) instanceof PacketEntityHorse
                        && player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_13)) {
                    player.packetStateData.horseInteractCausedForcedRotation = true;
                }
            }
        }
    }

}
