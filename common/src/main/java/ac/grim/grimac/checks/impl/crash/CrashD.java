package ac.grim.grimac.checks.impl.crash;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.event.PacketSendEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.inventory.inventory.MenuType;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;

@CheckData(name = "CrashD", stableKey = "grim.crash.lectern", description = "Clicking slots in lectern window")
public class CrashD extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("clickType={clicktype}, button={sint}");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    private MenuType type = MenuType.UNKNOWN;
    private int lecternId = -1;

    public CrashD(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_14);
    }

    @GrimPacketHandler
    public void onOpenScreen(PacketSendEvent event, GrimPlayer player, ClientboundOpenScreenPacket packet) {
        if (!isApplicable()) return;
        this.type = MenuType.fromNms(packet.getType());
        if (type == MenuType.LECTERN) lecternId = packet.getContainerId();
    }

    @GrimPacketHandler
    public void onContainerClick(PacketReceiveEvent event, GrimPlayer player, ServerboundContainerClickPacket packet) {
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
