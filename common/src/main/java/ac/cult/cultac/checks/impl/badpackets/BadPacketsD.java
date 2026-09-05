package ac.cult.cultac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

@CheckData(name = "BadPacketsD", stableKey = "cult.badpackets.invalid_pitch", description = "Sent an invalid rotation pitch outside the -90 to 90 range")
public class BadPacketsD extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("pitch={f32}");

    public BadPacketsD(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
        if (player.packetStateData.lastPacketWasTeleport) return;

        if (!packet.hasRotation()) return;

        final float pitch = packet.getXRot(player.xRot);
        if (pitch > 90 || pitch < -90) {
            // Ban.
            if (flag(V.write(verbose()).f32(pitch)) && shouldModifyPackets()) {
                // prevent other checks from using an invalid pitch
                clampPitch(player);

                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }

    static void clampPitch(CultPlayer player) {
        if (player.yRot > 90) player.yRot = 90;
        if (player.yRot < -90) player.yRot = -90;
    }
}
