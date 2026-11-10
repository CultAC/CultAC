package ac.cult.cultac.checks.impl.visibility;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.player.CultPlayer;

/** AttackPlayerVisibility for a Bedrock client's attacks. */
@CheckData(
        name = "bAttackPlayerVisibility",
        stableKey = "cult.bedrock.attack.player.visibility",
        description = "Attacked a player through blocks on Bedrock Edition",
        experimental = true)
public final class BedrockAttackPlayerVisibility extends BedrockAttackVisibility {
    public BedrockAttackPlayerVisibility(CultPlayer player) {
        super(player);
    }
}
