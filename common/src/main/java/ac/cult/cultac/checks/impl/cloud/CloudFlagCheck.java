package ac.cult.cultac.checks.impl.cloud;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.player.CultPlayer;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * A broad category the cloud service flags a player under, named as the service names it, so the punishment groups
 * that select it act on its flags like on any check. A flag is one violation with empty verbose: these checks never
 * set back or cancel.
 */
public abstract class CloudFlagCheck extends Check implements CheckListener {
    private static final Map<String, Class<? extends CloudFlagCheck>> BY_FLAG = Map.of(
            "CloudCombat", CloudCombat.class,
            "CloudCombatExperimental", CloudCombatExperimental.class,
            "CloudAutomation", CloudAutomation.class,
            "CloudAutomationExperimental", CloudAutomationExperimental.class,
            "CloudClient", CloudClient.class,
            "CloudClientExperimental", CloudClientExperimental.class,
            "CloudExploit", CloudExploit.class,
            "CloudExploitExperimental", CloudExploitExperimental.class);

    protected CloudFlagCheck(CultPlayer player) {
        super(player);
    }

    /** One violation for a flag the service sent, on the player's packet thread. */
    public final boolean raise() {
        return flag();
    }

    /** The check the service's flag id names; null for an id this version does not know. */
    public static @Nullable Class<? extends CloudFlagCheck> of(String flag) {
        return BY_FLAG.get(flag);
    }
}
