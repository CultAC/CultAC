package ac.cult.cultac.checks.impl.groundspoof;

import ac.cult.cultac.checks.BedrockSupported;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;

@BedrockSupported
public class NoFallExecutor extends Check implements CheckListener {
    public NoFallExecutor(CultPlayer cultPlayer) {
        super(
                cultPlayer,
                CheckInfo.builder()
                        .name("NoFall")
                        .stableKey("cult.groundspoof.no_fall")
                        .description("Sent an on-ground packet while not colliding with the ground")
                        .setback(10)
                        .build());
    }

    private void handleMovePlayer(PacketReceiveEvent<ServerboundMovePlayer> event, ServerboundMovePlayer packet) {
        boolean forceGroundFalse = player.packetStateData.lastPacketWasTeleport;
        // The prediction based NoFall check (that runs before us without the packet)
        // has asked us to set the player's onGround status to the ground state the
        // simulation derived, instead of blindly inverting the client's claim.
        //
        // Also flip teleports because vanilla doesn't handle the teleports well.
        Boolean desiredOnGround = player.packetStateData.consumeDesiredOnGround();
        if (desiredOnGround != null && !forceGroundFalse && shouldModifyPackets()) {
            event.replace(packet.withOnGround(desiredOnGround));
        }
        if (forceGroundFalse) {
            if (shouldModifyPackets()) {
                event.replace(packet.withOnGround(false));
            }
        }
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        handleMovePlayer(event, packet);
    }
}
