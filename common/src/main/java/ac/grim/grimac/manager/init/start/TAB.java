package ac.grim.grimac.manager.init.start;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.viaversion.ViaVersionUtil;
import net.minecraft.SharedConstants;

public class TAB implements StartableInitable {

    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    @Override
    public void start() {
        if (GrimAPI.INSTANCE.getPluginManager().getPlugin("TAB") == null) return;
        if (!ViaVersionUtil.isAvailable) return;
        // I don't know when team limits were changed, 1.13 is reasonable enough
        if (SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_13))
            return;

        LogUtil.warn("GrimAC has detected that you have installed TAB with ViaVersion.");
        LogUtil.warn("Please note that currently, TAB is incompatible as it sends illegal packets to players using versions newer than your server version.");
        LogUtil.warn("You may be able to remedy this by setting `compensate-for-packetevents-bug` to true in the TAB config.");
    }
}
