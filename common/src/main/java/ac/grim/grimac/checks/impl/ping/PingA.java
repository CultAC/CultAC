package ac.grim.grimac.checks.impl.ping;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.player.GrimPlayer;

/** Flag target for keepalive-delay detection. */
@CheckData(name = "PingA", stableKey = "grim.ping.keepalive_delay", description = "Delayed keep alive packets")
public class PingA extends Check implements CheckListener {
    public PingA(GrimPlayer player) {
        super(player);
    }
}
