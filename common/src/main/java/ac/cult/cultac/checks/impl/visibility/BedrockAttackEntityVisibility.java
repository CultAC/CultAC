package ac.cult.cultac.checks.impl.visibility;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.FormerName;
import ac.cult.cultac.player.CultPlayer;

/** AttackEntityVisibility for a Bedrock client's attacks. */
@CheckData(
        name = "bAttackEntityVisibility",
        formerNames = @FormerName(name = "bAttackMobVisibility", until = 1),
        stableKey = "cult.bedrock.attack.mob.visibility",
        description = "Attacked a non-player entity through blocks on Bedrock Edition",
        experimental = true)
public final class BedrockAttackEntityVisibility extends BedrockAttackVisibility {
    public BedrockAttackEntityVisibility(CultPlayer player) {
        super(player);
    }
}
