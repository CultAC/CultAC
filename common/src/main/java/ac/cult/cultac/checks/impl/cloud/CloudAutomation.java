package ac.cult.cultac.checks.impl.cloud;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.player.CultPlayer;

@CheckData(
        name = "CloudAutomation",
        stableKey = "cult.cloud.automation",
        description = "Flagged by Gladiator Cloud: automation")
public class CloudAutomation extends CloudFlagCheck {
    public CloudAutomation(CultPlayer player) {
        super(player);
    }
}
