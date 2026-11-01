package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundContainerClose;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "MultiActionsD",
        stableKey = "cult.multiactions.inventory_close_while_moving",
        description = "Closed inventory while moving")
public class MultiActionsD extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("sprinting={bool}, sneaking={bool}, input={bool}");

    public MultiActionsD(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onContainerClose(
            PacketReceiveEvent<ServerboundContainerClose> event, CultPlayer player, ServerboundContainerClose packet) {
        if (player.serverOpenedInventoryThisTick) return;

        boolean sprinting = MultiActionsC.isVerboseSprinting(player);
        boolean input = MultiActionsC.isVerboseInput(player);
        if (!sprinting && !input) return;

        // The client force-closes the inventory while inside a nether portal, sending this close
        // window packet even while moving.
        if (player.intersectedWithNetherPortal) return;

        // Don't cancel this packet, because it won't do anything except for making chests
        // look like they are still open (desynced),
        // and it can cause incompatibility issues with plugins
        flag(V.write(verbose()).bool(sprinting).bool(false).bool(input));
    }
}
