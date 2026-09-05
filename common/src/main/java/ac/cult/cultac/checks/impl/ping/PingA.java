package ac.cult.cultac.checks.impl.ping;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.player.CultPlayer;

/** Flag target for keepalive-delay detection. */
@CheckData(name = "PingA", stableKey = "cult.ping.keepalive_delay", description = "Delayed keep alive packets")
public class PingA extends Check implements CheckListener {
    public PingA(CultPlayer player) {
        super(player);
    }
}
