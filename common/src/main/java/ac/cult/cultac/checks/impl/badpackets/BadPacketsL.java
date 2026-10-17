package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(name = "BadPacketsL", stableKey = "cult.badpackets.invalid_dig", description = "Sent impossible dig packet")
public class BadPacketsL extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("pos={mcpos}, face={sint}, sequence={sint}, action={digging_lower}");

    public BadPacketsL(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        final PlayerAction action = packet.action();

        if (action == PlayerAction.START_DESTROY_BLOCK
                || action == PlayerAction.STOP_DESTROY_BLOCK
                || action == PlayerAction.ABORT_DESTROY_BLOCK) return;

        // 26.3 MultiPlayerGameMode#continueDestroyBlock sends this while still mining the same
        // target, with that target's position and the newly hit face. It is not a predicted
        // action: the three-argument ServerboundPlayerActionPacket constructor always sends
        // sequence 0. Its position and face are real, so only the sequence is fixed.
        if (action == PlayerAction.CHANGE_DESTROY_DIRECTION) {
            if (packet.sequence() != 0) flagInvalid(event, packet);
            return;
        }

        // 1.8 and above clients always send digging packets that aren't used for digging at 0, 0, 0, face 0
        final int faceId = packet.direction().ordinal();
        final boolean isValidFace = faceId == 0;

        final BlockPos pos = packet.position();
        if (!isValidFace || pos.getX() != 0 || pos.getY() != 0 || pos.getZ() != 0 || packet.sequence() != 0) {
            flagInvalid(event, packet);
        }
    }

    private void flagInvalid(PacketReceiveEvent<ServerboundPlayerAction> event, ServerboundPlayerAction packet) {
        final BlockPos pos = packet.position();
        var buf = V.write(verbose())
                .mcPos(pos.getX(), pos.getY(), pos.getZ())
                .sint(packet.direction().ordinal())
                .sint(packet.sequence())
                .uint(VerboseCodecs.digging(packet.action()));
        if (flag(buf) && shouldModifyPackets() && canCancel(packet.action())) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    private boolean canCancel(PlayerAction action) {
        return action != PlayerAction.RELEASE_USE_ITEM
                && action != PlayerAction.DROP_ITEM
                && action != PlayerAction.DROP_ALL_ITEMS;
    }
}
