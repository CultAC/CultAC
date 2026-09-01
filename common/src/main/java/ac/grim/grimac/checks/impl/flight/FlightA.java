package ac.grim.grimac.checks.impl.flight;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.util.WrapperPlayClientPlayerFlying;
import ac.grim.grimac.player.GrimPlayer;

// This check catches 100% of cheaters.
public class FlightA extends Check {
    public FlightA(GrimPlayer player) {
        super(player);
    }

    public void onPacketReceive(PacketReceiveEvent event) {
        // If the player sends a flying packet, but they aren't flying, then they are cheating.
        if (WrapperPlayClientPlayerFlying.isFlying(event) && !player.isFlying) {
            flag();
        }
    }
}
