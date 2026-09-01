package ac.grim.grimac.checks.impl.crash;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import org.bukkit.GameMode;

@CheckData(name = "CrashB", stableKey = "grim.crash.creative_while_not_creative", description = "Sent creative mode inventory click packets while not in creative mode")
public class CrashB extends Check implements CheckListener {
    public CrashB(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onSetCreativeModeSlot(PacketReceiveEvent event, GrimPlayer player, ServerboundSetCreativeModeSlotPacket packet) {
        if (player.gamemode != GameMode.CREATIVE) {
            event.setCancelled(true);
            player.onPacketCancel();
            flag(); // Could be transaction split, no need to setback though
        }
    }
}
