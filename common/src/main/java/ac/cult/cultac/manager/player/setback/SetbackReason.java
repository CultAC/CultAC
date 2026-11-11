package ac.cult.cultac.manager.player.setback;

/** Why a setback went to the client. */
public enum SetbackReason {
    /** A check's violation. */
    VIOLATION,
    /** A setback that does not simulate the next tick. */
    NON_SIMULATING,
    /** A forced resync of the client's position. */
    RESYNC,
    /** The client's latency. */
    LATENCY,
    /** A setback already sent goes again: the client ignored it in flight, or it goes again dismounted. */
    RESEND
}
