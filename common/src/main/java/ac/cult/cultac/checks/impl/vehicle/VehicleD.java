package ac.cult.cultac.checks.impl.vehicle;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EntityType;

@CheckData(name = "VehicleD", stableKey = "cult.vehicle.spoofed_jump", experimental = true, description = "Jumped in a vehicle that cannot jump")
public class VehicleD extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("vehicle=[{entity}|null]");

    public VehicleD(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerCommandPacket packet) {
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
