package ac.cult.cultac.utils.data;

/**
 * The Java client's Entity#isSwimming. Entity#updateSwimming recomputes it in every Entity#baseTick from the local
 * sprint flag, eye and fluid state, so the client swims a round trip before the server's shared flag says so.
 *
 * <ul>
 *   <li>ClientPacketListener#handleSetEntityData overwrites it with the server's shared flag 4 between ticks.
 *   <li>Player#updatePlayerPose reads it at the end of the tick; the server never sends the local player its pose.
 * </ul>
 *
 * Where the engine's fluid, eye or flying state is uncertain the model keeps the client swimming. A Bedrock client
 * holds only the server's flag.
 */
public final class ClientSwimState {
    private boolean swimming;
    private boolean flewLastTick;

    /** Entity shared flag 4 from self entity data, applied by its bundled proof. */
    public void onServerFlag(boolean swimming) {
        this.swimming = swimming;
    }

    /** A Java client tick's Entity#updateSwimming result and its Abilities#flying when the tick ended. */
    public void onClientTick(boolean swimming, boolean flying) {
        this.swimming = swimming;
        this.flewLastTick = flying;
    }

    /** ClientPacketListener#handleRespawn copies the old shared flags only for KEEP_ENTITY_DATA. */
    public void onRespawn(boolean keepEntityData) {
        if (!keepEntityData) swimming = false;
        flewLastTick = false;
    }

    /** ClientPacketListener#handleLogin creates a fresh LocalPlayer. */
    public void reset() {
        swimming = false;
        flewLastTick = false;
    }

    /** Entity#isSwimming's shared flag as the client holds it. */
    public boolean swimming() {
        return swimming;
    }

    /** Whether the client flew when its last tick ended, which the next Player#updateSwimming reads. */
    public boolean flewLastTick() {
        return flewLastTick;
    }
}
