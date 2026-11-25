package ac.cult.cultac.checks.impl.sprint;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsC;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.*;
import ac.cult.cultac.protocol.value.*;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "SprintH",
        stableKey = "cult.sprint.inventory",
        description = "Sprinting while in an inventory",
        experimental = true)
public class SprintH extends Check implements PostPredictionListener, ClientTickEndListener {
    public SprintH(CultPlayer player) {
        super(player);
    }

    private static final Verbose V = Verbose.of("tick").or("start");

    private boolean movementKeySent;

    @CultPacketHandler
    public void onPlayerInput(
            PacketReceiveEvent<ServerboundPlayerInput> event, CultPlayer player, ServerboundPlayerInput packet) {
        movementKeySent |= packet.forward() || packet.backward() || packet.left() || packet.right() || packet.jump();
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        if (packet.action() == PlayerCommandAction.START_SPRINTING
                && moving()
                && player.openWindow.mustBeOpen()
                && flag(V.write(verbose(), 1))
                && shouldModifyPackets()) player.closeInventorySafely();
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        onTick();
        movementKeySent = false;
    }

    // LocalPlayer sends player_input only when its keys change.
    private boolean moving() {
        return movementKeySent || player.packetStateData.knownInput.moving();
    }

    private void onTick() {
        if (!moving()) return;
        boolean sprinting = player.packetStateData.knownInput.sprint()
                        && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_9)
                || MultiActionsC.isVerboseSprinting(player);
        if (sprinting && player.openWindow.mustBeOpen() && flag(V.write(verbose(), 0)) && shouldModifyPackets())
            player.closeInventorySafely();
    }
}
