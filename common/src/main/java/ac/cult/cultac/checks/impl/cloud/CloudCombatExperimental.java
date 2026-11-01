package ac.cult.cultac.checks.impl.cloud;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.player.CultPlayer;

@CheckData(
        name = "CloudCombatExperimental",
        stableKey = "cult.cloud.combat.experimental",
        description = "Flagged by Gladiator Cloud: combat (experimental)")
public class CloudCombatExperimental extends CloudFlagCheck {
    public CloudCombatExperimental(CultPlayer player) {
        super(player);
    }
}
