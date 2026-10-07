package ac.cult.cultac.checks.impl.crash;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.inventory.MenuType;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;

@CheckData(name = "CrashD", stableKey = "cult.crash.lectern", description = "Clicking slots in lectern window")
public class CrashD extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("clickType={clicktype}, button={sint}");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(ProtocolVersion.V26_3.protocol());

    private MenuType type = MenuType.UNKNOWN;
    private int lecternId = -1;

    public CrashD(CultPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_14);
    }

    @CultPacketHandler
    public void onOpenScreen(
            PacketSendEvent<ClientboundOpenScreen> event, CultPlayer player, ClientboundOpenScreen packet) {
        if (!isApplicable()) return;
        this.type = MenuType.fromRegistryKey(packet.menuType());
        if (type == MenuType.LECTERN) lecternId = packet.containerId();
    }

    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent<InventoryClick> event, CultPlayer player, InventoryClick click) {
        if (!isApplicable()) return;
        int clickType = VerboseTags.enumId(click.clickType());
        int button = click.button();
        int windowId = click.windowId();

        if (type == MenuType.LECTERN && windowId > 0 && windowId == lecternId) {
            if (flag(V.write(verbose()).uint(clickType).sint(button))) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
