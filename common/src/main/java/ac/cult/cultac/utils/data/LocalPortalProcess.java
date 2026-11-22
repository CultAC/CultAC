package ac.cult.cultac.utils.data;

/**
 * The local player's portal cooldown and nether PortalProcessor, followed through each LocalPlayer#aiStep: whether
 * LocalPlayer#handlePortalTransitionEffect closed the open screen at the start of the last client tick.
 */
public final class LocalPortalProcess {
    /** Player#getDimensionChangingDelay. */
    private static final int DIMENSION_CHANGING_DELAY = 10;

    private int cooldown;
    private boolean insideThisTick;
    private boolean closedScreen;
    private boolean screenCloseTaken;

    /** A new LocalPlayer: no PortalProcessor, and the portal cooldown its CommonPlayerSpawnInfo carries. */
    public void onLocalPlayerCreated(int portalCooldown) {
        cooldown = portalCooldown;
        insideThisTick = false;
        closedScreen = false;
        screenCloseTaken = false;
    }

    /**
     * One LocalPlayer#aiStep. handlePortalTransitionEffect closes the screen when the last tick left the
     * PortalProcessor inside a nether portal, processPortalCooldown counts down, and the Entity#setAsInsidePortal of
     * this tick's NetherPortalBlock#entityInside restarts a running cooldown or marks the PortalProcessor inside.
     */
    public void onAiStep(boolean insideNetherPortal) {
        closedScreen = insideThisTick;
        insideThisTick = false;
        screenCloseTaken = false;
        if (cooldown > 0) cooldown--;
        if (!insideNetherPortal) return;
        if (cooldown > 0) cooldown = DIMENSION_CHANGING_DELAY;
        else insideThisTick = true;
    }

    /**
     * Whether the LocalPlayer#aiStep after the last {@link #onAiStep} starts with handlePortalTransitionEffect closing
     * the open screen; true at most once for that aiStep.
     */
    public boolean takeScreenCloseOfNextAiStep() {
        if (!insideThisTick || screenCloseTaken) return false;
        screenCloseTaken = true;
        return true;
    }

    /** Whether the last LocalPlayer#aiStep closed the screen for the nether portal the player stood in. */
    public boolean closedScreen() {
        return closedScreen;
    }
}
