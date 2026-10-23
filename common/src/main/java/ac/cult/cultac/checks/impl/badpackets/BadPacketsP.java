package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.inventory.MenuType;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;

@CheckData(
        name = "BadPacketsP",
        stableKey = "cult.badpackets.invalid_click",
        description = "Invalid window click packet",
        experimental = true)
public class BadPacketsP extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("clickType={clicktype_lower}, button={sint}[, container={sint}]");

    private int containerType = -1;
    private int containerId = -1;

    public BadPacketsP(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onOpenScreen(
            final PacketSendEvent<ClientboundOpenScreen> event, CultPlayer player, ClientboundOpenScreen packet) {
        this.containerType = MenuType.fromRegistryKey(packet.menuType()).getId();
        this.containerId = packet.containerId();
    }

    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent<InventoryClick> event, CultPlayer player, InventoryClick data) {
        final WindowClickType clickType = data.clickType();
        final int button = data.button();

        boolean flag = switch (clickType) {
            case PICKUP, QUICK_MOVE, CLONE -> !isMouseButton(button);
            case SWAP -> (button > 8 || button < 0) && button != 40;
            case THROW -> button != 0 && button != 1;
            case QUICK_CRAFT -> button == 3 || button == 7 || button > 10 || button < 0;
            case PICKUP_ALL -> button != 0;
        };

        // Allowing this to false flag to debug and find issues faster
        if (flag) {
            boolean hasContainer = data.windowId() == containerId;
            if (flag(V.write(verbose())
                            .uint(VerboseTags.enumId(clickType))
                            .sint(button)
                            .bool(hasContainer)
                            .sint(containerType))
                    && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }

    /**
     * AbstractContainerScreen#mouseReleased sends a carried item's click with the released mouse button. GLFW reports
     * buttons 0 to GLFW_MOUSE_BUTTON_LAST (7); from 26.3 SDL reports a signed byte that getContainerClickButton maps
     * 1 to 0 and 3 to 1.
     */
    private boolean isMouseButton(int button) {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_26_3)) return button >= 0 && button <= 7;
        return button >= Byte.MIN_VALUE && button <= Byte.MAX_VALUE && button != 3;
    }
}
