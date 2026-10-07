package ac.cult.cultac.checks.impl.vehicle;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "VehicleD",
        stableKey = "cult.vehicle.spoofed_jump",
        experimental = true,
        description = "Jumped in a vehicle that cannot jump")
public class VehicleD extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("vehicle=[{entity}|null]");

    public VehicleD(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        if (packet.action() != PlayerCommandAction.START_JUMPING_WITH_HORSE) return;

        final PacketEntity riding = player.compensatedEntities.getSelf().getRiding();
        final int vehicle = riding == null ? -1 : riding.type;

        if (!EntityTypeUtil.isHorseFamily(vehicle)
                && !EntityTypeUtil.isType(vehicle, "nautilus")
                && !EntityTypeUtil.isType(vehicle, "zombie_nautilus")) {
            if (flag(V.write(verbose()).bool(vehicle >= 0).uint(vehicle < 0 ? 0 : vehicle)) && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
