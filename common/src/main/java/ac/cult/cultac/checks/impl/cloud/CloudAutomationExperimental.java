package ac.cult.cultac.checks.impl.cloud;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.player.CultPlayer;

@CheckData(
        name = "CloudAutomationExperimental",
        stableKey = "cult.cloud.automation.experimental",
        description = "Flagged by Gladiator Cloud: automation (experimental)")
public class CloudAutomationExperimental extends CloudFlagCheck {
    public CloudAutomationExperimental(CultPlayer player) {
        super(player);
    }
}
