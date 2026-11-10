package ac.cult.cultac.checks.impl.combat;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.player.CultPlayer;

/** EntityHitboxes for a Bedrock client's attacks. */
@CheckData(
        name = "bEntityHitboxes",
        stableKey = "cult.bedrock.entity.hitboxes",
        description = "Tried to hit a non-player entity outside its valid hitbox on Bedrock Edition",
        experimental = true)
public class BedrockEntityHitboxes extends Check implements CheckListener {
    public BedrockEntityHitboxes(CultPlayer player) {
        super(player);
    }
}
