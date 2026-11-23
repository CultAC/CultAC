package ac.cult.cultac.checks.impl.crash;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSelectBundleItem;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "CrashI",
        stableKey = "cult.crash.invalid_bundle_slot",
        description = "Sent a bundle item selection with an invalid negative slot index")
public class CrashI extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("selectedItemIndex={sint}");

    public CrashI(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onSelectBundleItem(
            PacketReceiveEvent<ServerboundSelectBundleItem> event,
            CultPlayer player,
            ServerboundSelectBundleItem packet) {

        int selectedItemIndex = packet.selectedItemIndex();

        if (selectedItemIndex < -1) {
            flag(V.write(verbose()).sint(selectedItemIndex));
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
