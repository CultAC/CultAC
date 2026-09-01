package ac.grim.grimac.checks.impl.crash;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.DeadCheck;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.Packet;

@CheckData(name = "CrashI", stableKey = "grim.crash.invalid_bundle_slot", description = "Sent a bundle item selection with an invalid negative slot index")
@DeadCheck(reason = DeadCheck.Reason.WIRE_UNTRIGGERABLE, detail = "Paper 26.2's ServerboundSelectBundleItemPacket codec bounds-checks selectedItemIndex at decode; the packet never reaches checks from the wire.")
public class CrashI extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("selectedItemIndex={sint}");

    public CrashI(GrimPlayer player) {
        super(player);
    }


    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket")
    public void onSelectBundleItem(PacketReceiveEvent event, GrimPlayer player, Packet<?> packet) {

        int selectedItemIndex = ((net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket) packet).selectedItemIndex();

        if (selectedItemIndex < -1) {
            flag(V.write(verbose()).sint(selectedItemIndex));
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
