package ac.cult.cultac.checks.impl.groundspoof;

import ac.cult.cultac.checks.BedrockSupported;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

//@CheckData(name="NoFall")
@BedrockSupported
public class NoFallExecutor extends Check implements CheckListener {
    public NoFallExecutor(CultPlayer cultPlayer) { super(cultPlayer, CheckInfo.builder()
            .name("NoFall")
            .stableKey("cult.groundspoof.no_fall")
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

    @CultPacketHandler
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
        handleMovePlayer(event, packet);
    }
}
