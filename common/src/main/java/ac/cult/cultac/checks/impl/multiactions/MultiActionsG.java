package ac.cult.cultac.checks.impl.multiactions;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

@CheckData(name = "MultiActionsG", stableKey = "cult.multiactions.action_while_rowing", description = "Attacking or using items while rowing a boat", experimental = true)
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

    public MultiActionsG(CultPlayer player) {
        super(player);
    }

    private Verbose.Writer writeAction(int action) {
        return V.write(verbose(), action);
    }

    @CultPacketHandler
    public void onInteractEntity(PacketReceiveEvent event, CultPlayer player, ServerboundInteractPacket packet) {
        if (isCheckActive()
                && flag(writeAction(ACTION_INTERACT)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttack(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        if (isCheckActive()
                && flag(writeAction(ACTION_ATTACK)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }


    // 26.1 uses a required entity id; 26.2 also permits a spectator action without a target.
    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundSpectateEntityPacket")
    public void onSpectateEntity(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        onSpectatorAction(event, player, packet);
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket")
    public void onSpectatorAction(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        if (isCheckActive()
                && flag(writeAction(ACTION_SPECTATE_ENTITY)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent event, CultPlayer player, ServerboundUseItemPacket packet) {
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
