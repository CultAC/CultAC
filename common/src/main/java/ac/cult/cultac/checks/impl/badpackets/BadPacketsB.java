package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.player.CultPlayer;

@CheckData(name = "BadPacketsB", stableKey = "cult.badpackets.ignored_rotation", description = "Ignored set rotation packet")
public class BadPacketsB extends Check implements CheckListener {
    public BadPacketsB(final CultPlayer player) {
        super(player);
    }
}
