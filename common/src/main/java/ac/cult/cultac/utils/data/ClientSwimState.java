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
    // A client left the swim in the latest such tick, from the engine's pose and so with the engine's eye.
    private boolean exitLeft;
    // A client already out of the swim stayed out, with its own eye.
    private boolean exitStayed;
    private boolean exitUnderwater;
    // The flag the server's shared flags gave the client out of the swim; Player#updatePlayerPose has not read it yet.
    private boolean exitSwimming;

    /**
     * Entity shared flag 4 from self entity data, applied by its bundled proof. It sets Entity#isSwimming of either
     * client, but neither pose changes before Player#updatePlayerPose.
     */
    public void onServerFlag(boolean swimming) {
        this.swimming = swimming;
        exitSwimming = swimming;
    }

    /**
     * A committed tick the engine swims in ends with a client out of the swim in {@code pose}: one that
     * Player#causeExtraKnockback's setSprinting(false) took out of it before Entity#baseTick's updateSwimming
     * ({@code left}), or one already out that stayed out ({@code stayed}), whose eye was {@code underwater} at the
     * tick's start. LocalPlayer#isUnderWater reads that eye next.
     */
    public void onPossibleExit(Pose pose, boolean left, boolean stayed, boolean underwater) {
        exitPose = pose;
        exitLeft = left;
        exitStayed = stayed;
        exitUnderwater = stayed && underwater;
        exitSwimming = false;
    }

    /** The client the engine swims with is the only one left. */
    public void endExit() {
        exitPose = null;
    }

    /** The pose the client left the swim in, or null when it cannot have. */
    public @Nullable Pose exitPose() {
        return exitPose;
    }

    /**
     * Whether LocalPlayer#isUnderWater holds, for its next Entity#updateSwimming, in some client out of the swim, given
     * whether it holds in any state the engine's client started that tick from.
     */
    public boolean exitUnderwaterInAny(boolean engineInAny) {
        return exitLeft && engineInAny || exitStayed && exitUnderwater;
    }

    /** Whether it holds in every client out of the swim, given whether it holds in every state of the engine's. */
    public boolean exitUnderwaterInEvery(boolean engineInEvery) {
        return (!exitLeft || engineInEvery) && (!exitStayed || exitUnderwater);
    }

    /** Entity#isSwimming of the client out of the swim, for its next Entity#updateSwimming. */
    public boolean exitSwimming() {
        return exitSwimming;
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
