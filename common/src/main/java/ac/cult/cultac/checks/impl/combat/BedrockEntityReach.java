package ac.cult.cultac.checks.impl.combat;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;

/** EntityReach for a Bedrock client's attacks. */
public final class BedrockEntityReach extends ReachCheck {
    public static final CheckInfo INFO = CheckInfo.builder()
            .name("bEntityReach")
            .stableKey("cult.bedrock.entity.reach")
            .description("Attacked a non-player entity from too far away on Bedrock Edition")
            .experimental(true)
            .build();

    public BedrockEntityReach(CultPlayer player, ClientVisibilityProcessor visibility) {
        super(player, visibility, INFO);
    }

    @Override
    protected boolean targets(int type) {
        return type != EntityTypeIds.PLAYER;
    }

    @Override
    protected boolean judgesBedrock() {
        return true;
    }

    @Override
    protected Class<? extends Check> hitboxes() {
        return BedrockEntityHitboxes.class;
    }
}
