package ac.cult.cultac.checks.impl.combat;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;

/** PlayerReach for a Bedrock client's attacks. */
public final class BedrockPlayerReach extends ReachCheck {
    public static final CheckInfo INFO = CheckInfo.builder()
            .name("bPlayerReach")
            .stableKey("cult.bedrock.player.reach")
            .description("Attacked a player from too far away on Bedrock Edition")
            .experimental(true)
            .build();

    public BedrockPlayerReach(CultPlayer player, ClientVisibilityProcessor visibility) {
        super(player, visibility, INFO);
    }

    @Override
    protected boolean targets(int type) {
        return type == EntityTypeIds.PLAYER;
    }

    @Override
    protected boolean judgesBedrock() {
        return true;
    }

    @Override
    protected Class<? extends Check> hitboxes() {
        return BedrockPlayerHitboxes.class;
    }
}
