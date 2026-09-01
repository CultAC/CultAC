package ac.grim.grimac.checks.impl.multiactions;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.BlockPlaceCheck;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.nmsutil.EntityTypeUtil;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

@CheckData(name = "MultiActionsG", stableKey = "grim.multiactions.action_while_rowing", description = "Attacking or using items while rowing a boat", experimental = true)
public class MultiActionsG extends BlockPlaceCheck {
    private static final Verbose V = Verbose
            .of("action=interact")
            .or("action=attack")
            .or("action=spectateEntity")
            .or("action=use")
            .or("action=place"); // shape index == ACTION_* value

    private static final int ACTION_INTERACT = 0;
    private static final int ACTION_ATTACK = 1;
    private static final int ACTION_SPECTATE_ENTITY = 2;
    private static final int ACTION_USE = 3;
    private static final int ACTION_PLACE = 4;

    public MultiActionsG(GrimPlayer player) {
        super(player);
    }

    private Verbose.Writer writeAction(int action) {
        return V.write(verbose(), action);
    }

    @GrimPacketHandler
    public void onInteractEntity(PacketReceiveEvent event, GrimPlayer player, ServerboundInteractPacket packet) {
        if (isCheckActive()
                && flag(writeAction(ACTION_INTERACT)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttack(PacketReceiveEvent event, GrimPlayer player, Packet<?> packet) {
        if (isCheckActive()
                && flag(writeAction(ACTION_ATTACK)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }


    @GrimPacketHandler
    public void onSpectatorAction(PacketReceiveEvent event, GrimPlayer player, ServerboundSpectatorActionPacket packet) {
        if (isCheckActive()
                && flag(writeAction(ACTION_SPECTATE_ENTITY)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    @GrimPacketHandler
    public void onUseItem(PacketReceiveEvent event, GrimPlayer player, ServerboundUseItemPacket packet) {
        if (isCheckActive()
                && flag(writeAction(ACTION_USE)) && shouldModifyPackets()) {
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
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9) && !player.vehicleData.wasVehicleSwitch // one tick off?
                && player.inVehicle() && riding != null && EntityTypeUtil.isBoat(riding.type)
                && (player.boatData.nextVehicleForward != 0 || player.boatData.nextVehicleHoriz != 0);
    }

}
