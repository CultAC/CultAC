package ac.grim.grimac.checks.impl.prediction.stage.uncertainty;

import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.math.VectorUtils;
import net.minecraft.world.phys.Vec3;

public class InsideBlock implements UncertaintyHandler {
    @Override
    public PredVector handleUncertainty(GrimPlayer player, ValidMovements valid, PredictionResult result, SimulationContext context, PredictionResult lastContext, PredVector start, Vec3 end) {
        if (context.getWorldData().isMightBeInBlock()) {
            Vec3 movementsAllowed = VectorUtils.cutBoxToVector(end, new SimpleCollisionBox(-0.1, start.y, -0.1, 0.1, start.y, 0.1));
            Vec3 goodVector = VectorUtils.cutBoxToVector(end, new SimpleCollisionBox(start, movementsAllowed).sort());
            return start.with(goodVector, "block pushing");
        }
        return start;
    }
}
