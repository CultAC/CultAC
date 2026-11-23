package ac.cult.cultac.checks.impl.multiactions;

import static ac.cult.cultac.checks.impl.multiactions.InventoryActions.ACTION_ATTACK;
import static ac.cult.cultac.checks.impl.multiactions.InventoryActions.ACTION_INTERACT;
import static ac.cult.cultac.checks.impl.multiactions.InventoryActions.ACTION_SPECTATE_ENTITY;
import static ac.cult.cultac.checks.impl.multiactions.InventoryActions.ACTION_STAB;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.FormerName;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.*;
import ac.cult.cultac.protocol.value.*;

@CheckData(
        name = "InventoryD",
        formerNames = @FormerName(name = "MultiActionsI", until = 2),
        stableKey = "cult.multiactions.inventory_attack",
        description = "Interacted with an entity while in an inventory")
public class MultiActionsI extends Check implements CheckListener {
    public MultiActionsI(CultPlayer player) {
        super(player);
    }

    private boolean flagAction(int action) {
        return InventoryActions.reportedTogether(this) ? flag(InventoryActions.V.write(verbose(), action)) : flag();
    }

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        int action = packet.action() == InteractAction.ATTACK ? ACTION_ATTACK : ACTION_INTERACT;
        if (player.openWindow.mustBeOpen() && flagAction(action) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
            player.closeInventorySafely();
        }
    }

    @CultPacketHandler
    public void onSpectate(
            PacketReceiveEvent<ServerboundSpectatorAction> event,
            CultPlayer player,
            ServerboundSpectatorAction packet) {
        if (player.openWindow.mustBeOpen() && flagAction(ACTION_SPECTATE_ENTITY) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
            player.closeInventorySafely();
        }
    }

    @CultPacketHandler
    public void onStab(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        if (packet.action() == PlayerAction.STAB
                && player.openWindow.mustBeOpen()
                && flagAction(ACTION_STAB)
                && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
            player.closeInventorySafely();
        }
    }
}
