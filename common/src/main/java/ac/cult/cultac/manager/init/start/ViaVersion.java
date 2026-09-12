package ac.cult.cultac.manager.init.start;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.viaversion.ViaVersionUtil;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.minecraft.SharedConstants;

public class ViaVersion implements StartableInitable {

    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    @Override
    public void start() {
        if (!ViaVersionUtil.isAvailable) return;

        if (Via.getConfig().getValues().containsKey("fix-1_21-placement-rotation") && Via.getConfig().fix1_21PlacementRotation() && SERVER_VERSION.isOlderThan(ClientVersion.V_1_21)) {
            LogUtil.error("CultAC has detected that you are using ViaVersion with the `fix-1_21-placement-rotation` option enabled.");
            LogUtil.error("This option is known to cause issues with CultAC and may result in false positives and bypasses.");
            LogUtil.error("Please disable this option in your ViaVersion configuration to prevent these issues.");
        }

        ProtocolVersion blockedBelow = Via.getConfig().blockedProtocolVersions().blocksBelow();
        if (CultAPI.INSTANCE.getPluginManager().getPlugin("ViaBackwards") != null
                && (!blockedBelow.isKnown() || blockedBelow.olderThan(ProtocolVersion.v1_21_2))) {
            LogUtil.warn("CultAC has detected that ViaBackwards allows pre-1.21.2 clients.");
            LogUtil.warn("Pre-1.21.2 clients are difficult to check, are infrequently tested, and may cause issues");
            LogUtil.warn("You are recommended to add block-versions: [\"<1.21.2\"] to ViaVersion's config");
        }
    }
}
