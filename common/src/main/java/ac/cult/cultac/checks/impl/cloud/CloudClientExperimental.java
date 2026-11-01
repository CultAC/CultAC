package ac.cult.cultac.checks.impl.cloud;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.player.CultPlayer;

@CheckData(
        name = "CloudClientExperimental",
        stableKey = "cult.cloud.client.experimental",
        description = "Flagged by Gladiator Cloud: client (experimental)")
public class CloudClientExperimental extends CloudFlagCheck {
    public CloudClientExperimental(CultPlayer player) {
        super(player);
    }
}
