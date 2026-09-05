package ac.cult.cultac.checks.impl.multiactions;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;

@CheckData(name = "MultiActionsD", stableKey = "cult.multiactions.inventory_close_while_moving", description = "Closed inventory while moving")
public class MultiActionsD extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("sprinting={bool}, sneaking={bool}, input={bool}");

    public MultiActionsD(CultPlayer player) {
        super(player);
    }


    @CultPacketHandler
    public void onContainerClose(PacketReceiveEvent event, CultPlayer player, ServerboundContainerClosePacket packet) {
        if (player.serverOpenedInventoryThisTick) return;

        boolean sprinting = MultiActionsC.isVerboseSprinting(player);
        boolean sneaking = MultiActionsC.isVerboseSneaking(player);
        boolean input = MultiActionsC.isVerboseInput(player);
        if (!sprinting && !sneaking && !input) return;

        // The client force-closes the inventory while inside a nether portal, sending this close
        // window packet even while moving. This only happens on 1.12.2 and newer clients.
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_12_2) && player.intersectedWithNetherPortal) return;

        // Don't cancel this packet, because it won't do anything except for making chests
        // look like they are still open (desynced),
        // and it can cause incompatibility issues with plugins
        flag(V.write(verbose()).bool(sprinting).bool(sneaking).bool(input));
    }
}
