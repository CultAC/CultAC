package ac.grim.grimac.checks.impl.groundspoof;

import ac.grim.grimac.checks.BedrockSupported;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

//@CheckData(name="NoFall")
@BedrockSupported
public class NoFallExecutor extends Check implements CheckListener {
    public NoFallExecutor(GrimPlayer grimPlayer) { super(grimPlayer, CheckInfo.builder()
            .name("NoFall")
            .stableKey("grim.groundspoof.no_fall")
            .description("Sent an on-ground packet while not colliding with the ground")
            .setback(10)
            .build()); }

    private void handleMovePlayer(PacketReceiveEvent event, ServerboundMovePlayerPacket packet) {
        boolean forceGroundFalse = player.packetStateData.lastPacketWasTeleport;
        Boolean bedrockCanonicalGround = player.packetStateData.consumeBedrockTranslatedCanonicalGround();
        if (bedrockCanonicalGround != null) {
            // Bedrock packet normalization is independent of violation level:
            // the client authored only VERTICAL_COLLISION, while this Java bit
            // is Geyser's projection. Forward the canonical simulated state
            // even when the authored collision claim was legal.
            boolean desiredGround = forceGroundFalse ? false : bedrockCanonicalGround;
            if (desiredGround != packet.isOnGround()
                    && !player.isDisabled()
                    && !player.noModifyPacketPermission) {
                event.setNmsPacket(NmsPacketUtil.withOnGround(packet, player, desiredGround));
                event.markForReEncode(true);
            }
            return;
        }

        // The prediction based NoFall check (that runs before us without the packet)
        // has asked us to set the player's onGround status to the ground state the
        // simulation derived, instead of blindly inverting the client's claim.
        //
        // Also flip teleports because vanilla doesn't handle the teleports well.
        Boolean desiredOnGround = player.packetStateData.consumeDesiredOnGround();
        if (desiredOnGround != null && !forceGroundFalse && shouldModifyPackets()) {
            event.setNmsPacket(NmsPacketUtil.withOnGround(packet, player, desiredOnGround));
            event.markForReEncode(true);
        }
        if (forceGroundFalse) {
            if (shouldModifyPackets()) {
                event.setNmsPacket(NmsPacketUtil.withOnGround(packet, player, false));
                event.markForReEncode(true);
            }
        }
    }

    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        handleMovePlayer(event, packet);
    }
}
