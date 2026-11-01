package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Runs every probe through {@link BaseCollisionProbe} too and records each pair whose dump differs. */
public final class CheckingCollisionProbe extends CollisionModifier {
    private final BaseCollisionProbe base = new BaseCollisionProbe();
    private final Function<CollideAxisData, String> dump;
    public final List<String> mismatches = new ArrayList<>();
    public int probes;
    public int groundedProbes;

    public CheckingCollisionProbe(Function<CollideAxisData, String> dump) {
        this.dump = dump;
    }

    @Override
    public CollideAxisData probeCollisions(
            CultPlayer player,
            SimulationContext context,
            double minY,
            Vec3 target,
            Vec3 playerPos,
            SimpleCollisionBox attemptedMovementExtents,
            ProbeDimensions dimensions,
            JavaCollisionState actor,
            boolean followCandidateVerticalMovement,
            double horizontalInputReach) {
        CollideAxisData expected = base.probeCollisions(
                player,
                context,
                minY,
                target,
                playerPos,
                attemptedMovementExtents,
                dimensions,
                actor,
                followCandidateVerticalMovement,
                horizontalInputReach);
        CollideAxisData actual = super.probeCollisions(
                player,
                context,
                minY,
                target,
                playerPos,
                attemptedMovementExtents,
                dimensions,
                actor,
                followCandidateVerticalMovement,
                horizontalInputReach);
        probes++;
        if (context.getLastOnGround().determineOptimistically()) groundedProbes++;
        String want = dump.apply(expected);
        String got = dump.apply(actual);
        if (!want.equals(got)) mismatches.add(want + " != " + got);
        return actual;
    }
}
