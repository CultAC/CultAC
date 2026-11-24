package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.enums.Pose;
import org.jetbrains.annotations.Nullable;

/**
 * The Java client's Entity#isSwimming. Entity#updateSwimming recomputes it in every Entity#baseTick from the local
 * sprint flag, eye and fluid state, so the client swims a round trip before the server's shared flag says so.
 *
 * <ul>
 *   <li>ClientPacketListener#handleSetEntityData overwrites it with the server's shared flag 4 between ticks.
 *   <li>Player#updatePlayerPose reads it at the end of the tick; the server never sends the local player its pose.
 * </ul>
 *
 * Where the engine's fluid or eye state is uncertain the model keeps the client swimming. A Bedrock client
 * holds only the server's flag.
 */
public final class ClientSwimState {
    private boolean swimming;
    // The pose of the ticks where a possible attack reset cleared Entity#isSwimming, while that client stays out.
    private @Nullable Pose exitPose;
    private boolean exitUnderwater;

    /** Entity shared flag 4 from self entity data, applied by its bundled proof. */
    public void onServerFlag(boolean swimming) {
        this.swimming = swimming;
        exitPose = null;
    }

    /**
     * A committed tick the engine swims in, where Player#causeExtraKnockback's setSprinting(false) may have run before
     * Entity#baseTick's updateSwimming, or that follows such a tick: the client out of the swim ended it in
     * {@code pose}, and its eye was {@code underwater} at the tick's start, which LocalPlayer#isUnderWater reads next.
     */
    public void onPossibleExit(Pose pose, boolean underwater) {
        exitPose = pose;
        exitUnderwater = underwater;
    }

    /** The client the engine swims with is the only one left. */
    public void endExit() {
        exitPose = null;
    }

    /** The pose the client left the swim in, or null when it cannot have. */
    public @Nullable Pose exitPose() {
        return exitPose;
    }

    /** LocalPlayer#isUnderWater of the client out of the swim, for its next Entity#updateSwimming. */
    public boolean exitUnderwater() {
        return exitUnderwater;
    }

    /** A Java client tick's Entity#updateSwimming result. */
    public void onClientTick(boolean swimming) {
        this.swimming = swimming;
    }

    /** ClientPacketListener#handleRespawn copies the old shared flags only for KEEP_ENTITY_DATA. */
    public void onRespawn(boolean keepEntityData) {
        if (!keepEntityData) swimming = false;
        exitPose = null;
    }

    /** ClientPacketListener#handleLogin creates a fresh LocalPlayer. */
    public void reset() {
        swimming = false;
        exitPose = null;
    }

    /** Entity#isSwimming's shared flag as the client holds it. */
    public boolean swimming() {
        return swimming;
    }
}
