package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

@CheckData(name = "BadPacketsQ", stableKey = "grim.badpackets.invalid_horse_jump", description = "Sent a horse jump packet with an invalid entity, action, or boost value")
public class BadPacketsQ extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("boost={sint}, action={entityaction}, entity={sint}");

    public BadPacketsQ(final GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerCommandPacket packet) {
        final NmsPacketUtil.PlayerCommandData data = NmsPacketUtil.readPlayerCommand(packet);
        final NmsPacketUtil.PlayerCommandAction action = data.action();
        final int boost = data.data();
        final int entity = data.entityId();
        // you are able to send negative jump boost, how and why!?
        if (Math.abs(boost) > 100
                || entity != player.entityID
                || action != NmsPacketUtil.PlayerCommandAction.START_JUMPING_WITH_HORSE && boost != 0) {
            int actionId = VerboseTags.enumId(action);
            if (flag(V.write(verbose()).sint(boost).uint(actionId).sint(entity)) && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
