package ac.cult.cultac.checks.impl.flight;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.util.WrapperPlayClientPlayerFlying;
import ac.cult.cultac.player.CultPlayer;

// This check catches 100% of cheaters.
public class FlightA extends Check {
    public FlightA(CultPlayer player) {
        super(player);
    }

    public void onPacketReceive(PacketReceiveEvent event) {
        // If the player sends a flying packet, but they aren't flying, then they are cheating.
        if (WrapperPlayClientPlayerFlying.isFlying(event) && !player.isFlying) {
            flag();
        }
    }
}
