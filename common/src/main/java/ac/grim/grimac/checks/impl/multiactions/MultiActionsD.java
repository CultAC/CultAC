package ac.grim.grimac.checks.impl.multiactions;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;

@CheckData(name = "MultiActionsD", stableKey = "grim.multiactions.inventory_close_while_moving", description = "Closed inventory while moving")
public class MultiActionsD extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("sprinting={bool}, sneaking={bool}, input={bool}");

    public MultiActionsD(GrimPlayer player) {
        super(player);
    }


    @GrimPacketHandler
    public void onContainerClose(PacketReceiveEvent event, GrimPlayer player, ServerboundContainerClosePacket packet) {
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
