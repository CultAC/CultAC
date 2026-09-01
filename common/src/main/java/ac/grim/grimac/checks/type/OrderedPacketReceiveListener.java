package ac.grim.grimac.checks.type;

import ac.grim.grimac.network.event.PacketReceiveEvent;

/**
 * Observes the central, transaction-ordered PLAY packet stream once per packet.
 *
 * <p>This is the raw-NMS equivalent of the old PacketEvents packet-listener
 * callback. It is intentionally separate from {@code @GrimPacketHandler}:
 * implementations care about packets intervening in an ordering window, but
 * must not create one generated handler method for every concrete packet type.</p>
 */
public interface OrderedPacketReceiveListener extends CheckListener {
    void onPacketReceive(PacketReceiveEvent event);
}
