package ac.cult.cultac.utils.latency;

/**
 * Whether new living entities interpolate linearly for this client: CultAC offered {@link #CHANNEL} before the
 * play login and the client declared it with minecraft:register. Both reset when the client reconfigures.
 */
public final class ClientInterpolationOptIn {
    /** The channel AntiStutter and Interpolation Fix declare when they switch living entities to linear lerps. */
    public static final String CHANNEL = "antistutter:linear_3";
    /** Interpolation Fix's own channel; CultAC never offers it. */
    public static final String INTERPOLATION_FIX_CHANNEL = "interpolationfix:linear_3";

    private boolean offered;
    private boolean declared;

    /** The client handled a play login preceded by CultAC's offer, or by none. */
    public void loginHandled(boolean offered) {
        this.offered = offered;
    }

    /** The client declared {@link #CHANNEL} in play. */
    public void declare() {
        declared = true;
    }

    /** The client unregistered {@link #CHANNEL} in play. */
    public void undeclare() {
        declared = false;
    }

    /** The client left play for configuration; its next play login decides the offer again. */
    public void configurationAcknowledged() {
        offered = false;
        declared = false;
    }

    public boolean isOffered() {
        return offered;
    }

    public boolean isDeclared() {
        return declared;
    }

    /** Whether a living entity created now uses vanilla's linear interpolation handler. */
    public boolean linearForNewEntities() {
        return offered && declared;
    }
}
