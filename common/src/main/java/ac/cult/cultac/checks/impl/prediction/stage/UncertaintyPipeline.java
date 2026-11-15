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
            new FluidPush(true),
            new Honey(), // This fucks up elytras.
            new Ladder(), // No climbing while using an elytra
            new Sneaking(),
            new StuckSpeed(),
            new BubbleColumn(),
            new SwimSpaceShift(),
            new AquaticUpdateSwim(),
            new FluidPush(),
            new EntityPush()
            ));
}
