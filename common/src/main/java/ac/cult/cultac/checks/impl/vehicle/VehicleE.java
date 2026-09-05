package ac.cult.cultac.checks.impl.vehicle;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
import net.minecraft.world.entity.EntityType;

@CheckData(name = "VehicleE", stableKey = "cult.vehicle.spoofed_boat", experimental = true, description = "Sent boat paddle states while not in a boat")
public class VehicleE extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("vehicle=[{entity}|null]");

    public VehicleE(CultPlayer player) {
        super(player);
    }


    @CultPacketHandler
    public void onPaddleBoat(PacketReceiveEvent event, CultPlayer player, ServerboundPaddleBoatPacket packet) {
        final PacketEntity riding = player.compensatedEntities.getSelf().getRiding();
        final EntityType<?> vehicle = riding == null ? null : riding.type;

        if (!EntityTypeUtil.isBoat(vehicle)) {
            if (flag(V.write(verbose()).bool(vehicle != null).uint(vehicle == null ? 0 : BuiltInRegistries.ENTITY_TYPE.getId(vehicle))) && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
