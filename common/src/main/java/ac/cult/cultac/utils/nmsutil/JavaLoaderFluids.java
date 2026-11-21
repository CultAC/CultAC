package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.misc.ClientBrand;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.ClientClassification;
import ac.cult.cultac.player.CultPlayer;

/**
 * The fluid currents a mod loader's own Entity patches leave out. The loader is the one the client claims by its brand
 * or its registered channels, and no packet tells its build, so the engine keeps a candidate without the current
 * beside the vanilla one instead of choosing between them.
 */
public final class JavaLoaderFluids {
    private JavaLoaderFluids() {}

    /**
     * Whether LivingEntity#checkFallDamage's fluid refresh may have added no current. NeoForge 21.10.31 through
     * 21.11.x passes false to updateInWaterStateAndDoWaterCurrentPushing there, which still refreshes the water contact
     * (protocols 773 and 774; 26.1 calls vanilla's updateFluidInteraction again).
     */
    public static boolean mayRefreshWithoutCurrent(CultPlayer player) {
        if (mayApplyNoCurrent(player)) return true;
        ClientVersion version = player.getClientVersion();
        return (version == ClientVersion.V_1_21_9 || version == ClientVersion.V_1_21_11)
                && !player.isBedrockMovement()
                && claims(player, ClientClassification.Type.NEOFORGE);
    }

    /**
     * Whether Entity#updateFluidInteraction may have added no current at all. Forge 26.3 before 66.0.5 replaces the
     * water and lava pushes with EntityFluidInteraction#applyCurrentTo(Entity), which reads accumulators kept per
     * FluidType that no vanilla fluid has; its trackers still record the contact.
     */
    public static boolean mayApplyNoCurrent(CultPlayer player) {
        return player.getClientVersion() == ClientVersion.V_26_3
                && !player.isBedrockMovement()
                && claims(player, ClientClassification.Type.FORGE);
    }

    private static boolean claims(CultPlayer player, ClientClassification.Type loader) {
        if (player.pluginChannelManager.getChannelType() == loader) return true;
        ClientBrand brand = player.checkManager.getCheck(ClientBrand.class);
        return brand != null && brand.isHasBrand() && brand.getBrandType() == loader;
    }
}
