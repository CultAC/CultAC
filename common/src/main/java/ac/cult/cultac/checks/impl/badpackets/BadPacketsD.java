package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "BadPacketsD",
        stableKey = "cult.badpackets.invalid_pitch",
        description = "Sent an invalid rotation pitch outside the -90 to 90 range")
public class BadPacketsD extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("pitch={f32}");

    public BadPacketsD(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (event.answersTeleport()) return;

        if (!packet.hasRotation()) return;

        final float pitch = packet.pitch();
        if (pitch > 90 || pitch < -90) {
            if (flag(V.write(verbose()).f32(pitch)) && shouldModifyPackets()) {
                // Paper clamps the pitch the same way. Rewrite the current packet so NoFall's ground rewrite stays.
                event.replace(event.getPacket().withPitch(clampPitch(pitch)));
                // prevent other checks from using an invalid pitch
                clampPitch(player);
            }
        }
    }

    static float clampPitch(float pitch) {
        return Math.clamp(pitch, -90.0F, 90.0F);
    }

    static void clampPitch(CultPlayer player) {
        player.yRot = clampPitch(player.yRot);
    }
}
