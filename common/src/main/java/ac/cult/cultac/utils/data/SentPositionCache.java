package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;

/**
 * LocalPlayer#sendPosition's position cache (xLast, yLast, zLast) and positionReminder. The client advances both once
 * per client tick, whether or not the server keeps the packet it sent.
 */
public final class SentPositionCache {
    private double x;
    private double y;
    private double z;
    private int ticksWithoutPosition;
    private double sentDistanceSquared;
    private int ticksWithoutPositionBeforeSent;
    private boolean tickSentMovement;

    /** A new LocalPlayer starts at zero; ClientPacketListener#handleMovePlayer does not write it. */
    public void onNewLocalPlayer() {
        x = y = z = 0.0;
        ticksWithoutPosition = 0;
    }

    /** The client tick's movement packet, at {@code position} when it has one. */
    public void onTickMovement(boolean hasPosition, Vec3 position) {
        tickSentMovement = true;
        if (hasPosition) onPositionSent(position);
        else ticksWithoutPosition++;
    }

    /** A client tick that ran no LocalPlayer#sendPosition because the player rides. */
    public void onTickVehicleMovement() {
        tickSentMovement = true;
    }

    /** Whether the current client tick sent its movement. */
    public boolean tickSentMovement() {
        return tickSentMovement;
    }

    /** Minecraft#tick ends the client tick, whether or not the server keeps its end; a tick without movement counts. */
    public void onClientTickEnd() {
        if (!tickSentMovement) ticksWithoutPosition++;
        tickSentMovement = false;
    }

    /** A sent position, replacing the cache without counting a tick. */
    public void onPositionSent(Vec3 position) {
        sentDistanceSquared =
                CultMath.square(x - position.x) + CultMath.square(y - position.y) + CultMath.square(z - position.z);
        ticksWithoutPositionBeforeSent = ticksWithoutPosition;
        x = position.x;
        y = position.y;
        z = position.z;
        ticksWithoutPosition = 0;
    }

    /** The squared distance of the last sent position from the cache it replaced. */
    public double sentDistanceSquared() {
        return sentDistanceSquared;
    }

    /** The ticks without a position before the last sent position. */
    public int ticksWithoutPositionBeforeSent() {
        return ticksWithoutPositionBeforeSent;
    }
}
