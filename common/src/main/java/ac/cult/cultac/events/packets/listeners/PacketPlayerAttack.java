package ac.cult.cultac.events.packets.listeners;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;

public class PacketPlayerAttack {

    // LOW
    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        handleInteractPacket(player, packet);
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        player.minPlayerAttackSlow = 0;
    }

    private void handleInteractPacket(CultPlayer player, ServerboundInteract interact) {
        if (interact != null) {
            if (interact.action() == InteractAction.ATTACK) {
                SimItemStack heldItem = player.getInventory().getHeldItem();
                PacketEntity entity = player.compensatedEntities.getEntity(interact.entityId());

                if (entity != null) {
                    boolean hasKnockbackSword = heldItem != null
                            && ac.cult.cultac.utils.inventory.ItemUtil.enchantmentLevel(heldItem, "minecraft:knockback")
                                    > 0;

                    player.maxPlayerAttackSlow += 1;

                    // Players with knockback enchantments always get slowed.
                    if (hasKnockbackSword) {
                        player.minPlayerAttackSlow += 1;
                    }
                }
            } else if (interact.action() == InteractAction.INTERACT) {
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
