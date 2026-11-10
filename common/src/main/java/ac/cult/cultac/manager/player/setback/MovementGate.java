package ac.cult.cultac.manager.player.setback;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;

/**
 * Which movements wait for the server's state.
 *
 * <ul>
 *   <li>A client moves only after accepting the teleport that placed it; a Java client's old player still moves until
 *       it reads a respawn.
 *   <li>No movement passes while a Cult correction is pending, until its answer.
 *   <li>A chunk counts as loaded once a ping sent after it is answered. Inside one that is not, movement is not
 *       checked, unless it is above, or more than 4 blocks from, the correction's target.
 * </ul>
 */
public final class MovementGate {
    /** The teleport state the gate waits on that the setback util still owns. */
    public interface Teleports {
        boolean mustAcknowledgeBedrockTransportTeleport();

        boolean hasUnacknowledgedSetbackVehicleTeleport();

        boolean isDebug();
    }

    private final CultPlayer player;
    private final CorrectionState corrections;
    private final Teleports teleports;
    // Sync to netty, a player MUST accept a teleport to spawn into the world
    // A teleport is used to end the loading screen.  Some cheats pretend to never end the loading screen
    // in an attempt to disable the anticheat.  Be careful.
    // We fix this by blocking serverbound movements until the player is out of the loading screen.
    // TODO: Inspect the join packets to ensure that the player has sent player loaded packet
    private boolean hasFullyLoaded = false;
    /** ClientPacketListener#handleRespawn: until the Java client reads a respawn, its old LocalPlayer ticks and moves. */
    private boolean respawnUnread = false;

    public MovementGate(CultPlayer player, CorrectionState corrections, Teleports teleports) {
        this.player = player;
        this.corrections = corrections;
        this.teleports = teleports;
    }

    /** Whether the client accepted the teleport that placed it after joining or respawning. */
    public boolean hasFullyLoaded() {
        return hasFullyLoaded;
    }

    public void setLoaded(boolean loaded) {
        hasFullyLoaded = loaded;
    }

    /** The client left its level; the next play login places it afresh. */
    public void levelLeft() {
        hasFullyLoaded = false;
        respawnUnread = false;
    }

    /** A respawn discards the placed player; a Java client's old LocalPlayer moves until it reads the respawn. */
    public void respawnSent(boolean javaClient) {
        hasFullyLoaded = false;
        respawnUnread = javaClient;
    }

    /** The Java client read its respawn; its new LocalPlayer waits for the placement teleport. */
    public void respawnRead() {
        respawnUnread = false;
    }

    /** After reading a respawn, LocalPlayer#tick sends nothing until the placement teleport is accepted. */
    private boolean awaitingSpawn() {
        return !hasFullyLoaded && !respawnUnread;
    }

    /** A corrected vehicle must still be checked before its pending setback can complete. */
    public boolean shouldBlockMovement(boolean validatingVehicleCorrection) {
        boolean block = (awaitingSpawn()
                        || (!validatingVehicleCorrection
                                && (corrections.isBlockingOffsets() || corrections.isPending())))
                && !insideUnloadedChunk();
        return tooFarFromUnloadedChunk() || block;
    }

    public boolean shouldBlockVehicleMovement(boolean simulatedCorrection) {
        if (tooFarFromUnloadedChunk()) return true;
        if (insideUnloadedChunk()) return false;

        // Vehicle packets move the controlled root entity, not the player body. SetbackBlocker has
        // already proved current server mount/control and active Cult vehicle setbacks separately.
        return awaitingSpawn() || (!simulatedCorrection && corrections.isPending());
    }

    /** A validated auth frame cannot authorize another Java movement while its correction is pending. */
    public boolean blocksBedrockTranslatedMovement() {
        return corrections.isPending()
                || teleports.mustAcknowledgeBedrockTransportTeleport()
                || teleports.hasUnacknowledgedSetbackVehicleTeleport();
    }

    /**
     * If a player is inside of an unloaded chunk AND are too far away from the teleport so that we shouldn't consider
     * their current position valid.
     * <p>
     * This prevents us from harassing players on teleport
     * <p>
     * PLEASE BE CAREFUL!!!
     * <p>
     * Unloaded chunks are the only exemption not bound by a teleport.
     * This is required to prevent people from disabling cult.
     */
    public boolean tooFarFromUnloadedChunk() {
        SetBackData required = corrections.required();
        if (required == null || !insideUnloadedChunk()) return false;
        // check if the player is too far away from the teleport vertically
        Vec3 teleportTarget = required.getTeleportData().getLocation();
        if (player.y > teleportTarget.y) {
            if (teleports.isDebug())
                LogUtil.info(player.getName() + " : T1 : y= " + player.y + " > " + teleportTarget.y);
            return true;
        }
        // only check x and z because of players falling through the world
        // can a hacked client actually abuse flying downwards in an unloaded chunk?
        Vec3 copy = new Vec3(teleportTarget.x, 0, teleportTarget.z);
        double distance = copy.distanceTo(new Vec3(player.x, 0, player.z));
        if (teleports.isDebug()) {
            LogUtil.info(player.getName() + " : T1 : dist= " + distance);
        }
        return distance > 4.0D; // this could probably be reduced further now that it's only checking x and z
    }

    /**
     * When the player is inside an unloaded chunk, they simply fall through the void which shouldn't be checked
     *
     * @return Whether the player has loaded the chunk and accepted a teleport to correct movement or not
     */
    public boolean insideUnloadedChunk() {
        CompensatedWorld.CachedChunk column =
                player.compensatedWorld.getChunk(CultMath.floor(player.x) >> 4, CultMath.floor(player.z) >> 4);

        // If true, the player is in an unloaded chunk
        return !player.isDisabled()
                && (column == null || column.getTransaction() >= player.lastTransactionReceived.get());
    }
}
