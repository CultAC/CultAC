package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.event.PacketSendEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.inventory.inventory.MenuType;
import ac.grim.grimac.utils.inventory.inventory.WindowClickType;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;

@CheckData(name = "BadPacketsP", stableKey = "grim.badpackets.invalid_click", description = "Invalid window click packet", experimental = true)
public class BadPacketsP extends Check implements CheckListener {
    private static final Verbose V =
            Verbose.of("clickType={clicktype_lower}, button={sint}[, container={sint}]");

    private int containerType = -1;
    private int containerId = -1;

    public BadPacketsP(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onOpenScreen(final PacketSendEvent event, GrimPlayer player, ClientboundOpenScreenPacket packet) {
        this.containerType = MenuType.fromNms(packet.getType()).getId();
        this.containerId = packet.getContainerId();
    }

    @GrimPacketHandler
    public void onContainerClick(PacketReceiveEvent event, GrimPlayer player, ServerboundContainerClickPacket packet) {
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
