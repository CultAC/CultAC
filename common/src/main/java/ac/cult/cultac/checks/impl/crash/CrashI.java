package ac.cult.cultac.checks.impl.crash;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.DeadCheck;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.Packet;

@CheckData(name = "CrashI", stableKey = "cult.crash.invalid_bundle_slot", description = "Sent a bundle item selection with an invalid negative slot index")
@DeadCheck(reason = DeadCheck.Reason.WIRE_UNTRIGGERABLE, detail = "Paper 26.2's ServerboundSelectBundleItemPacket codec bounds-checks selectedItemIndex at decode; the packet never reaches checks from the wire.")
public class CrashI extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("selectedItemIndex={sint}");

    public CrashI(CultPlayer player) {
        super(player);
    }


    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket")
    public void onSelectBundleItem(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {

        int selectedItemIndex = ((net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket) packet).selectedItemIndex();

        if (selectedItemIndex < -1) {
            flag(V.write(verbose()).sint(selectedItemIndex));
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
