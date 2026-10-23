package ac.cult.cultac.checks.impl.prediction.stage;

import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class UncertaintyPipeline {
    // Please don't write stateful code - variables that affect subsequent simulations
    // Make it unmodifiable to try to make it a bit more idiot-proof.
    public static final List<UncertaintyHandler> MODIFIERS = Collections.unmodifiableList(Arrays.asList(
            new ElytraTransform(),
            new BoatTransform(),
            new PistonShulkerPush(),
            new FluidPush(true),
            new CollisionModifier(),
            new StepTransform(), // Relies on collisions
            new Ladder(), // No climbing while using an elytra
            new FishingRod(), // No clue where this goes, but it's pre-tick, so I'll throw it here.
            // LocalPlayer#aiStep's goDownInWater and Player#travel's swim pull precede Entity#move's back-off.
            new SwimInput(),
            new SneakEdge(), // Player#maybeBackOffFromEdge
            new Fireworks(),
            new FluidPush(),
            new InsideBlock(),
            new EntityPush(),
            new Riptide(),
            new PointThreeTesting() // A withheld position is within the movement threshold of the target
            ));
}
