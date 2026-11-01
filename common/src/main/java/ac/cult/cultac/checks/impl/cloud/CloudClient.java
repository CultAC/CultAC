package ac.cult.cultac.checks.impl.cloud;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.player.CultPlayer;

@CheckData(name = "CloudClient", stableKey = "cult.cloud.client", description = "Flagged by Gladiator Cloud: client")
public class CloudClient extends CloudFlagCheck {
    public CloudClient(CultPlayer player) {
        super(player);
    }
}
