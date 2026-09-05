package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.player.CultPlayer;

@CheckData(name = "BadPacketsN", stableKey = "cult.badpackets.invalid_teleport", description = "Ignored or failed to accept a required server teleport")
public class BadPacketsN extends Check implements CheckListener {
    public BadPacketsN(final CultPlayer player) {
        super(player);
    }
}
