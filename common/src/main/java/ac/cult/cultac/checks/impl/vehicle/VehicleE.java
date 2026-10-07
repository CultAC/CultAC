package ac.cult.cultac.checks.impl.vehicle;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPaddleBoat;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "VehicleE",
        stableKey = "cult.vehicle.spoofed_boat",
        experimental = true,
        description = "Sent boat paddle states while not in a boat")
public class VehicleE extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("vehicle=[{entity}|null]");

    public VehicleE(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onPaddleBoat(
            PacketReceiveEvent<ServerboundPaddleBoat> event, CultPlayer player, ServerboundPaddleBoat packet) {
        final PacketEntity riding = player.compensatedEntities.getSelf().getRiding();
        final int vehicle = riding == null ? -1 : riding.type;

        if (!EntityTypeUtil.isBoat(vehicle)) {
            if (flag(V.write(verbose()).bool(vehicle >= 0).uint(vehicle < 0 ? 0 : vehicle)) && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
