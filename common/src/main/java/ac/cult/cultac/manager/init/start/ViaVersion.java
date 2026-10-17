package ac.cult.cultac.manager.init.start;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.protocol.util.viaversion.ViaVersionUtil;
import ac.cult.cultac.utils.anticheat.LogUtil;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;

public class ViaVersion implements StartableInitable {

    @Override
    public void start() {
        if (ViaVersionUtil.isLegacyApiInstalled()) {
            LogUtil.error("Using unsupported ViaVersion 4.0 API, update ViaVersion to 5.0");
        }
        if (!ViaVersionUtil.isAvailable()) return;

        ProtocolVersion blockedBelow = Via.getConfig().blockedProtocolVersions().blocksBelow();
        if (CultAPI.INSTANCE.getPluginManager().getPlugin("ViaBackwards") != null
                && (!blockedBelow.isKnown() || blockedBelow.olderThan(ProtocolVersion.v1_21_2))) {
            LogUtil.warn("CultAC has detected that ViaBackwards allows pre-1.21.2 clients.");
            LogUtil.warn("Clients older than 1.21.2 are not supported and are disconnected when they join");
            LogUtil.warn("You are recommended to add block-versions: [\"<1.21.2\"] to ViaVersion's config");
        }
    }
}
