package ac.cult.cultac.checks.impl.combat;

import ac.cult.blocksim.data.ItemComponents;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.visibility.ClientVisibility;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;
import java.util.List;

/**
 * The vanilla distance bounds for one attack, captured when the attack is sent.
 *
 * <p>LocalPlayer#raycastHitResult picks with the active item's AttackRange component when present
 * ({@code picks}), falling back to an entity_interaction_range pick ({@code entityRange}).
 * Minecraft#startAttack then drops entity hits outside the main-hand item's AttackRange#isInRange
 * ({@code itemMin} to {@code itemLimit}), measured to a point on the target's bounding box.
 */
record AttackReach(double entityRange, double itemMin, double itemLimit, List<ClientVisibility.ItemPick> picks) {
    private static final double SQRT_3 = Math.sqrt(3);

    // MultiPlayerGameMode#attack sends the carried item before the attack, so the claimed slot is exact.
    static AttackReach capture(CultPlayer player, ClientVisibilityProcessor visibility) {
        double entityRange = player.compensatedEntities.getSelf().getEntityInteractionRange();
        List<ClientVisibility.ItemPick> picks = visibility.attackItemPicks();
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_11))
            return new AttackReach(entityRange, Double.NaN, Double.NaN, picks);
        var held = player.getInventory().getClientSelectedHeldItem();
        // Minecraft#startAttack stabs with a piercing weapon and never sends an attack, so no item range applies.
        if (!held.isEmpty() && held.components().has("minecraft:piercing_weapon"))
            return new AttackReach(entityRange, Double.NaN, Double.NaN, List.of());
        var range = held.isEmpty() ? null : ItemComponents.attackRange(held.components());
        if (range == null) return new AttackReach(entityRange, Double.NaN, Double.NaN, picks);
        // AttackRange#isInRange for a Player: the effective range widened by the hitbox margin, in float like vanilla.
        boolean creative = player.gamemode == GameMode.CREATIVE;
        float min = creative ? range.minCreativeReach() : range.minReach();
        float max = creative ? range.maxCreativeReach() : range.maxReach();
        return new AttackReach(entityRange, min - range.hitboxMargin(), max + range.hitboxMargin(), picks);
    }

    boolean hasItemLimit() {
        return !Double.isNaN(itemLimit);
    }

    /** An upper bound on the eye-to-box distance of any attack vanilla could send. */
    double maxClosestDistance() {
        if (hasItemLimit()) return itemLimit;
        double max = entityRange;
        // An item pick's view vector is at most sqrt(3) long, and its margin hit lies within
        // sqrt(3) * margin of the unexpanded box.
        for (ClientVisibility.ItemPick pick : picks) {
            max = Math.max(max, SQRT_3 * (pick.rayScale() + pick.hitboxMargin()));
        }
        return max;
    }
}
