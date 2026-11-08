package ac.cult.cultac.checks.impl.combat;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.player.CultPlayer;

/** PlayerHitboxes for a Bedrock client's attacks. */
@CheckData(
        name = "bPlayerHitboxes",
        stableKey = "cult.bedrock.player.hitboxes",
        description = "Tried to hit a player outside its valid hitbox on Bedrock Edition",
        experimental = true)
public class BedrockPlayerHitboxes extends Check implements CheckListener {
    public BedrockPlayerHitboxes(CultPlayer player) {
        super(player);
    }
}
