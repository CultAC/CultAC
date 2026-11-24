package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.misc.ClientBrand;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.ClientClassification;
import ac.cult.cultac.player.CultPlayer;

/**
 * The fluid currents a mod loader's own Entity patches leave out. The loader is the one the client claims by its brand
 * or its registered channels, and no packet it sends unasked tells its build. So a claimed loader is simulated as one
 * of two builds, the vanilla one and the one without the current, the way {@link ac.cult.cultac.utils.math.TrigHandler}
 * holds one of two trig tables: the build the player's last telling movement followed runs first, and the other only
 * when that one fails. A client runs one build for its whole session, so the first accepted movement only one build
 * explains ends the choice, as a build the client reported would.
 */
public final class JavaLoaderFluids {
    private final CultPlayer player;
    /** Whether the build being simulated is the claimed loader's without the current. */
    private boolean withoutCurrent;
    /** The build of the last accepted movement only one build explained: the player's, until one says otherwise. */
    private boolean preferredWithoutCurrent;
    /** Whether a current the two builds disagree on was computed since {@link #watch}. */
    private boolean decided;
    /** Whether the client's build is the one without the current; null while neither a report nor a move named it. */
    private Boolean known;

    public JavaLoaderFluids(CultPlayer player) {
        this.player = player;
    }

    /**
     * Whether LivingEntity#checkFallDamage's fluid refresh may have added no current. NeoForge 21.10.31 through
     * 21.11.x passes false to updateInWaterStateAndDoWaterCurrentPushing there, which still refreshes the water contact
     * (protocols 773 and 774; 26.1 calls vanilla's updateFluidInteraction again).
     */
    public boolean mayRefreshWithoutCurrent() {
        if (mayApplyNoCurrent()) return true;
        ClientVersion version = player.getClientVersion();
        return (version == ClientVersion.V_1_21_9 || version == ClientVersion.V_1_21_11)
                && !player.isBedrockMovement()
                && claims(ClientClassification.Type.NEOFORGE);
    }

    /**
     * Whether Entity#updateFluidInteraction may have added no current at all. Forge 26.3 before 66.0.5 replaces the
     * water and lava pushes with EntityFluidInteraction#applyCurrentTo(Entity), which reads accumulators kept per
     * FluidType that no vanilla fluid has; its trackers still record the contact.
     */
    public boolean mayApplyNoCurrent() {
        return player.getClientVersion() == ClientVersion.V_26_3
                && !player.isBedrockMovement()
                && claims(ClientClassification.Type.FORGE);
    }

    private boolean claims(ClientClassification.Type loader) {
        if (player.pluginChannelManager.getChannelType() == loader) return true;
        ClientBrand brand = player.checkManager.getCheck(ClientBrand.class);
        return brand != null && brand.isHasBrand() && brand.getBrandType() == loader;
    }

    /** Whether the claimed loader may be either build: no version it reported names one. */
    public boolean buildsApart() {
        return known == null && mayRefreshWithoutCurrent();
    }

    /**
     * Takes {@code withoutCurrent} as the client's build for the rest of its session, or null to tell the builds apart
     * by movement again.
     */
    public void know(Boolean withoutCurrent) {
        known = withoutCurrent;
        if (withoutCurrent != null) this.withoutCurrent = preferredWithoutCurrent = withoutCurrent;
    }

    /** The client's build once known, else null. */
    public Boolean known() {
        return known;
    }

    /** Whether the simulated build leaves out a current Entity#updateFluidInteraction would add to any entity. */
    boolean omitsCurrent() {
        if (!mayApplyNoCurrent()) return false;
        decided = true;
        return withoutCurrent;
    }

    /** Whether the simulated build leaves out the current LivingEntity#checkFallDamage's refresh would add. */
    boolean omitsRefreshCurrent() {
        if (!mayRefreshWithoutCurrent()) return false;
        decided = true;
        return withoutCurrent;
    }

    /** Simulates the build {@code withoutCurrent} names until {@link #restore} gets the returned one back. */
    public boolean select(boolean withoutCurrent) {
        boolean previous = this.withoutCurrent;
        this.withoutCurrent = known != null ? known : withoutCurrent;
        return previous;
    }

    public void restore(boolean previous) {
        withoutCurrent = known != null ? known : previous;
    }

    public boolean simulatesWithoutCurrent() {
        return withoutCurrent;
    }

    public boolean prefersWithoutCurrent() {
        return known != null ? known : preferredWithoutCurrent;
    }

    /** Keeps the build an accepted movement followed; everything outside a prediction simulates it from here on. */
    public void prefer(boolean withoutCurrent) {
        preferredWithoutCurrent = withoutCurrent;
    }

    /** Starts watching for a current the two builds disagree on. */
    public void watch() {
        decided = false;
    }

    /** Whether the builds may have moved apart since {@link #watch}. */
    public boolean decided() {
        return decided;
    }
}
