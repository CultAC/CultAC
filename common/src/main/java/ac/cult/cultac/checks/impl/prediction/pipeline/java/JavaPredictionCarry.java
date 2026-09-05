package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.PredictionCarry;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Complete states attached to the runner's existing next-tick velocity candidates. */
public record JavaPredictionCarry(PacketEntity actor, double fallDistance, List<State> states) implements PredictionCarry {
    public JavaPredictionCarry(PacketEntity actor, double fallDistance) { this(actor, fallDistance, List.of()); }

    public JavaPredictionCarry {
        requireDistance(fallDistance);
        states = List.copyOf(states);
    }

    public record State(Vec3 velocity, double fallDistance, Vec3 stuckSpeed) {
        public State { requireDistance(fallDistance); }
    }

    private static void requireDistance(double distance) {
        if (!Double.isFinite(distance) || distance < 0) throw new IllegalArgumentException("Invalid client fall distance");
    }
}
