package ac.cult.cultac.checks.impl.misc;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.ClientBrandText;
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

    public void changedBrand(String previous, String incoming) {
        if (!isEnabled() || reported || player.isBedrockMovement()) return;
        reported = true;
        flag(player.getClientClassification().display() + " previous=" + evidenceBrand(previous) + " incoming="
                + evidenceBrand(incoming));
    }

    private static String evidenceBrand(String brand) {
        String sanitized = ClientBrandText.sanitize(brand);
        if (sanitized == null) return "?";
        StringBuilder text = new StringBuilder(sanitized.length());
        for (int i = 0; i < sanitized.length(); i++) {
            char c = sanitized.charAt(i);
            text.append(Character.isLetterOrDigit(c) || " .:/-()".indexOf(c) >= 0 ? c : '?');
        }
        return text.toString();
    }
}
