package ac.cult.cultac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.inventory.inventory.MenuType;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;

@CheckData(name = "BadPacketsP", stableKey = "cult.badpackets.invalid_click", description = "Invalid window click packet", experimental = true)
public class BadPacketsP extends Check implements CheckListener {
    private static final Verbose V =
            Verbose.of("clickType={clicktype_lower}, button={sint}[, container={sint}]");

    private int containerType = -1;
    private int containerId = -1;

    public BadPacketsP(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onOpenScreen(final PacketSendEvent event, CultPlayer player, ClientboundOpenScreenPacket packet) {
        this.containerType = MenuType.fromNms(packet.getType()).getId();
        this.containerId = packet.getContainerId();
    }

    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent event, CultPlayer player, ServerboundContainerClickPacket packet) {
        final NmsPacketUtil.ContainerClickData data = NmsPacketUtil.readContainerClick(packet);
        final WindowClickType clickType = data.clickType();
        final int button = data.button();


        boolean flag = switch (clickType) {
            case PICKUP, QUICK_MOVE, CLONE -> button > 2 || button < 0;
            case SWAP -> (button > 8 || button < 0) && button != 40;
            case THROW -> button != 0 && button != 1;
            case QUICK_CRAFT -> button == 3 || button == 7 || button > 10 || button < 0;
            case PICKUP_ALL -> button != 0;
        };

        // Allowing this to false flag to debug and find issues faster
        if (flag) {
            boolean hasContainer = data.windowId() == containerId;
            if (flag(V.write(verbose()).uint(VerboseTags.enumId(clickType)).sint(button).bool(hasContainer).sint(containerType))
                    && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
