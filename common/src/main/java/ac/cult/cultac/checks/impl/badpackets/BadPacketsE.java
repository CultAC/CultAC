package ac.cult.cultac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.OrderedPacketReceiveListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

@CheckData(name = "BadPacketsE", stableKey = "cult.badpackets.invalid_position", description = "Sent too many movement packets without updating position")
public class BadPacketsE extends Check implements OrderedPacketReceiveListener {
    private static final Verbose V = Verbose.of("ticks={uint}");

    private int noReminderTicks;
    private final int maxNoReminderTicks;

    public BadPacketsE(CultPlayer player) {
        super(player);
        maxNoReminderTicks = player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8) ? 20 : 19;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getNmsPacket();
        if (packet instanceof ServerboundMovePlayerPacket.PosRot || packet instanceof ServerboundMovePlayerPacket.Pos) {
            noReminderTicks = 0;
            return;
        }

        if (packet instanceof ServerboundMovePlayerPacket) {
            if (!player.packetStateData.lastPacketWasTeleport && ++noReminderTicks > maxNoReminderTicks) {
                flag(V.write(verbose()).uint(noReminderTicks));
            }
            return;
        }

        // On a 1.21.2+ server every packet resets this counter while mounted.
        // Legacy STEER_VEHICLE packets are observed before ViaBackwards and call
        // handleLegacySteerVehicle(); translated modern input is not equivalent.
        if (player.inVehicle()) {
            noReminderTicks = 0;
        }
    }

    public void handleLegacySteerVehicle() {
        noReminderTicks = 0;
    }

    public void handleRespawn() {
        noReminderTicks = 0;
    }
}
