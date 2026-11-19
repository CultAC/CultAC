package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementMath;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.TrigHandler;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.util.ArrayList;
import java.util.List;

/** TridentItem#releaseUsing's push; JavaRiptideRelease runs its grounded move before building the travel world. */
public final class Riptide {
    public static final String PUSH = "riptide push";
    public static final String GROUND_LIFT = "riptide ground lift";
    private static final float LIFT = 1.1999999F;

    private Riptide() {}

    /** Whether {@code velocity} carries this tick's push, which leaves the tick's LivingEntity#aiStep airborne. */
    public static boolean pushed(SimulationContext context, PredVector velocity) {
        return pushOf(context, velocity) != null;
    }

    /** This tick's push on {@code velocity}, or null without one. */
    static Pushed pushOf(SimulationContext context, PredVector velocity) {
        return context.getRiptideLevel() == 0 ? null : velocity.firstInLineage(Pushed.class);
    }

    /** A velocity carrying this tick's push, and the velocity the push was added to. */
    static final class Pushed extends PredVector {
        final PredVector carried;
        final Vec3 push;

        private Pushed(PredVector carried, Vec3 push, boolean vanilla) {
            super(carried.add(push), new JavaMovementMath(carried, vanilla), PUSH);
            this.carried = carried;
            this.push = push;
        }
    }

    /** Entity#push, independently of whether releaseUsing also runs its grounded move. */
    public static List<PredVector> releasePushes(SimulationContext context, List<PredVector> velocities) {
        float strength = 3.0F * ((1.0F + context.getRiptideLevel()) / 4.0F);
        List<PredVector> outputs = new ArrayList<>(velocities.size() * 2);
        boolean previous = context.getTrig().isVanillaMath();
        try {
            Boolean requested = context.getTrig().getPredictionMath();
            for (int mode = 0; mode < (requested == null ? 2 : 1); mode++) {
                boolean vanilla = requested != null ? requested : mode == 0;
                context.getTrig().setVanillaMath(vanilla);
                Vec3 push = push(context.getTrig(), context.getXRot(), context.getYRot(), strength);
                for (PredVector velocity : velocities) outputs.add(new Pushed(velocity, push, vanilla));
            }
        } finally {
            context.getTrig().setVanillaMath(previous);
        }
        return outputs;
    }

    private static Vec3 push(TrigHandler trig, float yRot, float xRot, float strength) {
        float xd = -trig.sin(yRot * (float) (Math.PI / 180.0)) * trig.cos(xRot * (float) (Math.PI / 180.0));
        float yd = -trig.sin(xRot * (float) (Math.PI / 180.0));
        float zd = trig.cos(yRot * (float) (Math.PI / 180.0)) * trig.cos(xRot * (float) (Math.PI / 180.0));
        float dist = (float) Math.sqrt(xd * xd + yd * yd + zd * zd);
        xd *= strength / dist;
        yd *= strength / dist;
        zd *= strength / dist;
        return new Vec3(xd, yd, zd);
    }

    /** How far the grounded lift moves {@code box}, scaled by a consumed stuck multiplier's {@code stuckY}. */
    public static double groundLift(CultPlayer player, SimpleCollisionBox box, double stuckY) {
        double attempted = LIFT * stuckY;
        double lift = Collisions.collide(player, box, 0, attempted, 0).y;
        // Entity#move retains even a tiny move when collision removed less than its squared-length threshold.
        return lift * lift > 1.0E-7 || attempted * attempted - lift * lift < 1.0E-7 ? lift : 0;
    }
}
