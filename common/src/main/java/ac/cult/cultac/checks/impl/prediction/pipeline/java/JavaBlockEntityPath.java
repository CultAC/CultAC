package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;

/** The selected local actor's positions between block-entity tickers; unchanged positions need no entry. */
public record JavaBlockEntityPath(ClientBlockEntityPass pass, Vec3 start, List<Step> steps) {
    public record Step(int mover, Vec3 position) {}

    public Vec3 position(int mover, boolean after) {
        Vec3 position = start;
        for (Step step : steps) {
            if (step.mover() > mover || !after && step.mover() == mover) break;
            position = step.position();
        }
        return position;
    }
}
