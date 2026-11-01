package ac.cult.cultac.checks.impl.cloud;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.player.CultPlayer;

@CheckData(name = "CloudCombat", stableKey = "cult.cloud.combat", description = "Flagged by Gladiator Cloud: combat")
public class CloudCombat extends CloudFlagCheck {
    public CloudCombat(CultPlayer player) {
        super(player);
    }
}
