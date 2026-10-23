package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSpectatorAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "MultiActionsG",
        stableKey = "cult.multiactions.action_while_rowing",
        description = "Attacking or using items while rowing a boat",
        experimental = true)
public class MultiActionsG extends BlockPlaceCheck {
    private static final Verbose V = Verbose.of("action=interact")
            .or("action=attack")
            .or("action=spectateEntity")
            .or("action=use")
            .or("action=place"); // shape index == ACTION_* value

    private static final int ACTION_INTERACT = 0;
    private static final int ACTION_ATTACK = 1;
    private static final int ACTION_SPECTATE_ENTITY = 2;
    private static final int ACTION_USE = 3;
    private static final int ACTION_PLACE = 4;

    public MultiActionsG(CultPlayer player) {
        super(player);
    }

    private Verbose.Writer writeAction(int action) {
        return V.write(verbose(), action);
    }

    @CultPacketHandler
    public void onInteractEntity(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        int action = packet.action() == InteractAction.ATTACK ? ACTION_ATTACK : ACTION_INTERACT;
        if (isCheckActive() && flag(writeAction(action)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    @CultPacketHandler
    public void onSpectatorAction(
            PacketReceiveEvent<ServerboundSpectatorAction> event,
            CultPlayer player,
            ServerboundSpectatorAction packet) {
        if (isCheckActive() && flag(writeAction(ACTION_SPECTATE_ENTITY)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        if (isCheckActive() && flag(writeAction(ACTION_USE)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    @Override
    public void onBlockPlace(BlockPlace place) {

        int action = place.isUseItem() ? ACTION_USE : ACTION_PLACE;
        if (isCheckActive() && flag(writeAction(action)) && shouldModifyPackets() && shouldCancel()) {
            place.resync();
        }
    }

    public boolean isCheckActive() {
        PacketEntity riding = player.compensatedEntities.getSelf().getRiding();
        return !player.vehicleData.wasVehicleSwitch // one tick off?
                && player.inVehicle()
                && riding != null
                && EntityTypeUtil.isBoat(riding.type)
                && player.compensatedEntities.vehicles.mayLocalPlayerControl(riding)
                && player.boatData.nextVehicleKeys.movingNoJump();
    }
}
