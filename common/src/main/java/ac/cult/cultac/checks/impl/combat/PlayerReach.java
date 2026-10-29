package ac.cult.cultac.checks.impl.combat;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;

public final class PlayerReach extends ReachCheck {
    public static final CheckInfo INFO = CheckInfo.builder()
            .name("PlayerReach")
            .stableKey("cult.combat.player.reach")
            .description("Attacked a player from too far away")
            .build();

    public PlayerReach(CultPlayer player, ClientVisibilityProcessor visibility) {
        super(player, visibility, INFO);
    }

    @Override
    protected boolean targets(int type) {
        return type == EntityTypeIds.PLAYER;
    }

    @Override
    protected Class<? extends Check> hitboxes() {
        return PlayerHitboxes.class;
    }
}
