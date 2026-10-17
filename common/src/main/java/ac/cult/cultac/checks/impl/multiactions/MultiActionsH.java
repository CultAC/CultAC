package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.*;
import ac.cult.cultac.protocol.value.*;

@CheckData(
        name = "MultiActionsH",
        stableKey = "cult.multiactions.inventory_move",
        description = "Moving while in an inventory",
        experimental = true)
public class MultiActionsH extends Check implements CheckListener, ClientTickEndListener {
    public MultiActionsH(CultPlayer player) {
        super(player);
    }

    private ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaAutoJump autoJump() {
        return player.checkManager.getListener(ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaAutoJump.class);
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        if (player.openWindow.mustBeOpen() && autoJump().hasManualMovementInput() && flag() && shouldModifyPackets()) {
            player.closeInventorySafely();
        }
    }
}
