package ac.cult.cultac.checks.type;

import ac.cult.cultac.network.event.PacketReceiveEvent;

/**
 * Observes every successfully decoded serverbound PLAY packet once, before
 * packet-specific normal receive handlers run.
 *
 * <p>This carries listener state transitions that apply to all packet
 * types without generating one {@code @CultPacketHandler} per concrete packet.
 * It is separate from the post-route non-async callback phase.</p>
 */
public interface DecodedPacketReceiveListener extends CheckListener {
    void onDecodedPacketReceive(PacketReceiveEvent event);
}
