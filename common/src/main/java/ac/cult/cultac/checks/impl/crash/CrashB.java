package ac.cult.cultac.checks.impl.crash;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import org.bukkit.GameMode;

@CheckData(name = "CrashB", stableKey = "cult.crash.creative_while_not_creative", description = "Sent creative mode inventory click packets while not in creative mode")
public class CrashB extends Check implements CheckListener {
    public CrashB(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onSetCreativeModeSlot(PacketReceiveEvent event, CultPlayer player, ServerboundSetCreativeModeSlotPacket packet) {
        if (player.gamemode != GameMode.CREATIVE) {
            event.setCancelled(true);
            player.onPacketCancel();
            flag(); // Could be transaction split, no need to setback though
        }
    }
}
