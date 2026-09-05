package ac.cult.cultac.checks.impl.prediction.stage;

import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class UncertaintyPipeline {
    // Please don't write stateful code - variables that affect subsequent simulations
    // Make it unmodifiable to try to make it a bit more idiot-proof.
    public static final List<UncertaintyHandler> MODIFIERS = Collections.unmodifiableList(Arrays.asList(
            new ElytraTransform(),
            new BoatTransform(),
            new PistonShulkerPush(),
            new CollisionModifier(),
            new StepTransform(), // Relies on collisions
            new Honey(), // This fucks up elytras.
            new BouncyBlock(), // Although elytra is technically after this, +Y movements don't affect XZ with elytra
            new Ladder(), // No climbing while using an elytra
            new XZBug(), // Only applies in X direction
            new FishingRod(), // No clue where this goes, but it's pre-tick, so I'll throw it here.
            new PointThree(),
            new Sneaking(),
            new StuckSpeed(),
            new BubbleColumn(),
            new SwimSpaceShift(),
            new AquaticUpdateSwim(),
            new Fireworks(),
            new FluidPush(),
            new InsideBlock(),
            new EntityPush(),
            new Riptide(),
            new PointThreeTesting() // We want to be within 0.03 of the target, not exact
    ));

    public static final List<UncertaintyHandler> MODIFIERS_FOR_SETBACKS = MODIFIERS.stream()
            .filter(modifier -> !(modifier instanceof PointThree || modifier instanceof XZBug)).collect(Collectors.toList());

}
