package ac.cult.cultac.checks.impl.visibility;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.visibility.BedrockEye;
import ac.cult.cultac.utils.visibility.BedrockSight;
import java.util.ArrayList;
import java.util.List;

/**
 * PlaceVisibility for a Bedrock client's block uses. A use the player's input made names the block its pick hit and the
 * point on it, relative to the block; one the client's tick made names the block it builds on and, relative to that
 * block, the point the eye's pick hit, or zero when it names no point. A use is judged at its point, or on the whole
 * block without one, with the clicked block itself never in the way. A use the player's input made is judged as it
 * arrives, from the eye path like an attack; one the client's tick made is judged at the next tick end with the path to
 * that tick's position, and only flagged.
 */
@CheckData(
        name = "bPlaceVisibility",
        stableKey = "cult.bedrock.place.visibility",
        description = "Placed or interacted with a block through blocks on Bedrock Edition",
        experimental = true)
public final class BedrockPlaceVisibility extends BedrockVisibilityCheck {
    // A use's point on the block is three floats in [0, 1].
    private static final double CLICK_PRECISION = 1.0E-6;
    private static final int MAX_DEFERRED = 16;

    private record Deferred(BedrockSight.Leg leg, SimpleCollisionBox target, BlockPos block, String detail) {}

    private final List<Deferred> deferred = new ArrayList<>();

    public BedrockPlaceVisibility(CultPlayer player) {
        super(player);
    }

    /**
     * Judges a use on the world block {@code block}'s face {@code face} at {@code click}, relative to the block, or
     * null when the client sent none; {@code playerInput} when the player's input made it. True when it must be
     * dropped.
     */
    public boolean dropsUse(BlockPos block, int face, Vec3 click, boolean playerInput) {
        if (!judged()) return false;
        SimpleCollisionBox target = target(block, playerInput || !zero(click) ? click : null);
        String detail = block(block, face);
        if (!playerInput) {
            if (deferred.size() < MAX_DEFERRED) deferred.add(new Deferred(lastLeg(), target, block, detail));
            return false;
        }
        if (!hidden(List.of(lastLeg()), target, block)) return false;
        return flag(detail) && shouldModifyPackets();
    }

    /** Judges the uses the client's tick made since the last tick end, from the path through this tick. */
    public void endClientTick() {
        if (deferred.isEmpty()) return;
        List<Deferred> uses = List.copyOf(deferred);
        deferred.clear();
        if (!judged()) return;
        for (Deferred use : uses) {
            var next = new BedrockSight.Leg(
                    use.leg().to(), new Vec3(player.x, player.y, player.z), BedrockEye.ofOrAny(player));
            if (hidden(List.of(use.leg(), next), use.target(), use.block())) flag(use.detail());
        }
    }

    private static boolean zero(Vec3 click) {
        return click != null && click.x == 0 && click.y == 0 && click.z == 0;
    }

    // The point the pick hit, or the whole block when the client sent no point on it.
    private static SimpleCollisionBox target(BlockPos block, Vec3 click) {
        if (click == null
                || !(click.x >= 0 && click.x <= 1)
                || !(click.y >= 0 && click.y <= 1)
                || !(click.z >= 0 && click.z <= 1)) return cell(block);
        return around(
                new Vec3(block.getX() + click.x, block.getY() + click.y, block.getZ() + click.z), CLICK_PRECISION);
    }
}
