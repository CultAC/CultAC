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
    // Whether the movement of the tick being simulated judged the player's nether portal contact.
    private boolean contactJudged;
    // Whether insideThisTick follows a judged contact rather than one left from an earlier tick.
    private boolean insideKnown = true;

    /** A new LocalPlayer: no PortalProcessor, and the portal cooldown its CommonPlayerSpawnInfo carries. */
    public void onLocalPlayerCreated(int portalCooldown) {
        cooldown = portalCooldown;
        insideThisTick = false;
        closedScreen = false;
        screenCloseTaken = false;
        contactJudged = false;
        insideKnown = true;
    }

    /** The movement of the tick being simulated judged the player's nether portal contact. */
    public void onContactJudged() {
        contactJudged = true;
    }

    /**
     * One LocalPlayer#aiStep. handlePortalTransitionEffect closes the screen when the last tick left the
     * PortalProcessor inside a nether portal, processPortalCooldown counts down, and the Entity#setAsInsidePortal of
     * this tick's NetherPortalBlock#entityInside restarts a running cooldown or marks the PortalProcessor inside.
     * {@code portalUsable} is false where Entity#canUsePortal or Entity#isAffectedByBlocks rules the portal out.
     */
    public void onAiStep(boolean portalUsable, boolean insideNetherPortal) {
        closedScreen = insideThisTick;
        insideThisTick = false;
        screenCloseTaken = false;
        insideKnown = !portalUsable || contactJudged;
        contactJudged = false;
        insideNetherPortal &= portalUsable;
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

    /**
     * Whether the LocalPlayer#aiStep after the last {@link #onAiStep} may still start with
     * handlePortalTransitionEffect closing the open screen, without taking it; true too when no simulated movement
     * judged the portal contact that decides it.
     */
    public boolean mayCloseScreenOfNextAiStep() {
        return !screenCloseTaken && (insideThisTick || !insideKnown);
    }

    /** Whether the last LocalPlayer#aiStep closed the screen for the nether portal the player stood in. */
    public boolean closedScreen() {
        return closedScreen;
    }
}
