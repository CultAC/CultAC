package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

@CheckData(name = "BadPacketsL", stableKey = "grim.badpackets.invalid_dig", description = "Sent impossible dig packet")
public class BadPacketsL extends Check implements CheckListener {
    private static final Verbose V =
            Verbose.of("pos={mcpos}, face={sint}, sequence={sint}, action={digging_lower}");

    public BadPacketsL(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onPlayerAction(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerActionPacket packet) {
        final ServerboundPlayerActionPacket.Action action = packet.getAction();


        if (action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                || action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
                || action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK)
            return;

        // 1.8 and above clients always send digging packets that aren't used for digging at 0, 0, 0, face 0
        // 1.7 and below clients do the same, except use face 255 for RELEASE_USE_ITEM
        // as of https://github.com/ViaVersion/ViaRewind/commit/e7b0606e187afbccf98ef7c88d3f3af27fe11da3, ViaRewind maps the face to 0
        // let's allow both, just to be safe
        final int faceId = packet.getDirection().get3DDataValue();
        final boolean allowLegacyFace = player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_7_10)
                && action == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM;
        final boolean isValidFace = faceId == 0 || allowLegacyFace && faceId == 255;

        final BlockPos pos = packet.getPos();
        if (!isValidFace
                || pos.getX() != 0
                || pos.getY() != 0
                || pos.getZ() != 0
                || packet.getSequence() != 0
        ) {
            var buf = V.write(verbose())
                    .mcPos(pos.getX(), pos.getY(), pos.getZ())
                    .sint(faceId)
                    .sint(packet.getSequence())
                    .uint(VerboseTags.enumId(action));
            if (flag(buf) && shouldModifyPackets() && canCancel(action)) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }


    private boolean canCancel(ServerboundPlayerActionPacket.Action action) {
        return action != ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM
                && ((action != ServerboundPlayerActionPacket.Action.DROP_ITEM
                && action != ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS)
                || player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8));
    }
}
