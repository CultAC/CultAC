package ac.cult.cultac.checks.impl.crash;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;

@CheckData(name = "CrashF", stableKey = "cult.crash.button_crash", description = "Sent an inventory click with an invalid button or slot value")
public class CrashF extends Check implements CheckListener {
    private static final Verbose V =
            Verbose.of("clickType={clicktype}, button={sint}[, slot={sint}]");

    public CrashF(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onContainerClick(final PacketReceiveEvent event, CultPlayer player, ServerboundContainerClickPacket packet) {
        NmsPacketUtil.ContainerClickData click = NmsPacketUtil.readContainerClick(packet);
        WindowClickType clickType = click.clickType();
        int button = click.button();
        int windowId = click.windowId();
        int slot = click.slot();

        if ((clickType == WindowClickType.QUICK_MOVE || clickType == WindowClickType.SWAP) && windowId >= 0 && button < 0) {
            int clickTypeId = VerboseTags.enumId(clickType);
            if (flag(V.write(verbose()).uint(clickTypeId).sint(button).bool(false).sint(0))) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        } else if (windowId >= 0 && clickType == WindowClickType.SWAP && slot < 0) {
            int clickTypeId = VerboseTags.enumId(clickType);
            if (flag(V.write(verbose()).uint(clickTypeId).sint(button).bool(true).sint(slot))) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
