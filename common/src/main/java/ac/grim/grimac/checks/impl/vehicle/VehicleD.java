package ac.grim.grimac.checks.impl.vehicle;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.nmsutil.EntityTypeUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EntityType;

@CheckData(name = "VehicleD", stableKey = "grim.vehicle.spoofed_jump", experimental = true, description = "Jumped in a vehicle that cannot jump")
public class VehicleD extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("vehicle=[{entity}|null]");

    public VehicleD(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerCommandPacket packet) {
        if (NmsPacketUtil.readPlayerCommand(packet).action() != NmsPacketUtil.PlayerCommandAction.START_JUMPING_WITH_HORSE) return;

        final PacketEntity riding = player.compensatedEntities.getSelf().getRiding();
        final EntityType<?> vehicle = riding == null ? null : riding.type;


        if (!EntityTypeUtil.isHorseFamily(vehicle)
                && !EntityTypeUtil.isType(vehicle, "nautilus") && !EntityTypeUtil.isType(vehicle, "zombie_nautilus")) {
            if (flag(V.write(verbose()).bool(vehicle != null).uint(vehicle == null ? 0 : BuiltInRegistries.ENTITY_TYPE.getId(vehicle))) && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
