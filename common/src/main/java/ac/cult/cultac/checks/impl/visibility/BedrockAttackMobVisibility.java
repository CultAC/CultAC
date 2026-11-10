package ac.cult.cultac.checks.impl.visibility;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.player.CultPlayer;

/** AttackMobVisibility for a Bedrock client's attacks. */
@CheckData(
        name = "bAttackMobVisibility",
        stableKey = "cult.bedrock.attack.mob.visibility",
        description = "Attacked a non-player entity through blocks on Bedrock Edition",
        experimental = true)
public final class BedrockAttackMobVisibility extends BedrockAttackVisibility {
    public BedrockAttackMobVisibility(CultPlayer player) {
        super(player);
    }
}
