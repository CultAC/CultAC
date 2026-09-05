package ac.cult.cultac.checks.impl.crash;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.inventory.inventory.MenuType;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;

@CheckData(name = "CrashD", stableKey = "cult.crash.lectern", description = "Clicking slots in lectern window")
public class CrashD extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("clickType={clicktype}, button={sint}");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

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
    public void onOpenScreen(PacketSendEvent event, CultPlayer player, ClientboundOpenScreenPacket packet) {
        if (!isApplicable()) return;
        this.type = MenuType.fromNms(packet.getType());
        if (type == MenuType.LECTERN) lecternId = packet.getContainerId();
    }

    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent event, CultPlayer player, ServerboundContainerClickPacket packet) {
        if (!isApplicable()) return;
        NmsPacketUtil.ContainerClickData click = NmsPacketUtil.readContainerClick(packet);
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
