package ac.grim.grimac.checks.impl.vehicle;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.nmsutil.EntityTypeUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
import net.minecraft.world.entity.EntityType;

@CheckData(name = "VehicleE", stableKey = "grim.vehicle.spoofed_boat", experimental = true, description = "Sent boat paddle states while not in a boat")
public class VehicleE extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("vehicle=[{entity}|null]");

    public VehicleE(GrimPlayer player) {
        super(player);
    }


    @GrimPacketHandler
    public void onPaddleBoat(PacketReceiveEvent event, GrimPlayer player, ServerboundPaddleBoatPacket packet) {
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
