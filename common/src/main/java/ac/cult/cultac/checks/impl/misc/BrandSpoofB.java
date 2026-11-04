package ac.cult.cultac.checks.impl.misc;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.player.CultPlayer;

@CheckData(
        name = "BrandSpoofB",
        stableKey = "cult.misc.brand_spoof_b",
        description = "Reported different client brands")
public final class BrandSpoofB extends Check implements CheckListener {
    private boolean reported;

    public BrandSpoofB(CultPlayer player) {
        super(player);
    }

    public void changedBrand() {
        if (!isEnabled() || reported || player.isBedrockMovement()) return;
        reported = true;
        flag(player.getClientClassification().display());
    }
}
