package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

@CheckData(name = "BadPacketsD", stableKey = "grim.badpackets.invalid_pitch", description = "Sent an invalid rotation pitch outside the -90 to 90 range")
public class BadPacketsD extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("pitch={f32}");

    public BadPacketsD(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
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

    static void clampPitch(GrimPlayer player) {
        if (player.yRot > 90) player.yRot = 90;
        if (player.yRot < -90) player.yRot = -90;
    }
}
