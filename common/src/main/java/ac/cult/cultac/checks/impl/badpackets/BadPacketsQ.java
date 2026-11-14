package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.data.RidingJumpCharge;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;

@CheckData(
        name = "BadPacketsQ",
        stableKey = "cult.badpackets.invalid_horse_jump",
        description = "Sent a horse jump packet with an invalid entity, action, or boost value")
public class BadPacketsQ extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("boost={sint}, action={entityaction}, entity={sint}");
    private static final Verbose CHARGE = Verbose.of("boost={sint}, charged={sint}");
    // Below any data LocalPlayer#sendRidingJump can send.
    private static final int NO_CHARGE = -1000;

    public BadPacketsQ(final CultPlayer player) {
        super(player);
    }

    /**
     * A START_RIDING_JUMP whose data is not the charge LocalPlayer#aiStep built from the jump key, or a release the key
     * did not make ({@code charged} NO_RELEASE). True when the packet is to be corrected.
     */
    public boolean onReleaseBeyondCharge(int boost, int charged) {
        int expected = charged == RidingJumpCharge.NO_RELEASE ? NO_CHARGE : charged;
        return flag(CHARGE.write(verbose()).sint(boost).sint(expected)) && shouldModifyPackets();
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        final PlayerCommandAction action = packet.action();
        final int boost = packet.data();
        final int entity = packet.entityId();
        // you are able to send negative jump boost, how and why!?
        if (Math.abs(boost) > 100
                || entity != player.entityID
                || action != PlayerCommandAction.START_JUMPING_WITH_HORSE && boost != 0) {
            int actionId = VerboseTags.enumId(action);
            if (flag(V.write(verbose()).sint(boost).uint(actionId).sint(entity)) && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
