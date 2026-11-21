package ac.cult.cultac.checks.type;

/**
 * Told, for every successfully decoded serverbound PLAY packet, when the player's camera may be an entity other than
 * the player, after the {@link DecodedPacketReceiveListener}s have observed that packet and before its
 * packet-specific receive handlers run.
 */
public interface CameraDetachListener extends CheckListener {
    void onCameraDetached();
}
