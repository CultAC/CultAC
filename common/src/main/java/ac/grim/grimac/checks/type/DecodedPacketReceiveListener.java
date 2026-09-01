package ac.grim.grimac.checks.type;

import ac.grim.grimac.network.event.PacketReceiveEvent;

/**
 * Observes every successfully decoded serverbound PLAY packet once, before
 * packet-specific normal receive handlers run.
 *
 * <p>This preserves legacy listener state transitions that apply to all packet
 * types without generating one {@code @GrimPacketHandler} per concrete packet.
 * It is deliberately separate from the post-route ordered receive phase.</p>
 */
public interface DecodedPacketReceiveListener extends CheckListener {
    void onDecodedPacketReceive(PacketReceiveEvent event);
}
