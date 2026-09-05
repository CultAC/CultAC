package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class BedrockTravelInputControlTest {
    @Test
    public void actualSneakingScalesLavaMoveInput() {
        BedrockInputFrame frame = new BedrockInputFrame(
            1L,
            0.0F,
            0.0F,
            false,
            true,
            false,
            Set.of("SNEAKING", "SNEAK_CURRENT_RAW", "SNEAK_DOWN", "WANT_DOWN"));

        BedrockTravelInputControl.InputControlState control = BedrockTravelInputControl.resolve(
            state(),
            frame,
            false,
            BedrockTravelOptions.SprintTravelSpeedMode.ORDERED_ACTOR_FLAG,
            frame.intent(),
            context(Medium.LAVA),
            BedrockEffectState.NONE);

        assertEquals(0.3F, control.moveInputScale(), 1.0E-6F);
    }

    @Test
    public void liquidDescendWithoutActualSneakDoesNotScaleMoveInput() {
        BedrockInputFrame frame = new BedrockInputFrame(
            1L,
            0.0F,
            0.0F,
            false,
            true,
            false,
            Set.of("SNEAK_DOWN", "WANT_DOWN"));

        BedrockTravelInputControl.InputControlState control = BedrockTravelInputControl.resolve(
            state(),
            frame,
            false,
            BedrockTravelOptions.SprintTravelSpeedMode.ORDERED_ACTOR_FLAG,
            frame.intent(),
            context(Medium.LAVA),
            BedrockEffectState.NONE);

        assertEquals(1.0F, control.moveInputScale(), 1.0E-6F);
    }

    @Test
    public void waterDescendDoesNotScaleMoveInputFromSneakFlags() {
        BedrockInputFrame frame = new BedrockInputFrame(
            1L,
            0.0F,
            0.0F,
            false,
            true,
            false,
            Set.of("SNEAKING", "SNEAK_CURRENT_RAW", "SNEAK_DOWN", "WANT_DOWN"));

        BedrockTravelInputControl.InputControlState control = BedrockTravelInputControl.resolve(
            state(),
            frame,
            false,
            BedrockTravelOptions.SprintTravelSpeedMode.ORDERED_ACTOR_FLAG,
            frame.intent(),
            context(Medium.WATER),
            BedrockEffectState.NONE);

        assertEquals(1.0F, control.moveInputScale(), 1.0E-6F);
    }

    private static BedrockMovementState state() {
        return BedrockMovementState.fromPhysicalFeet(
            Vec3d.ZERO,
            Vec3d.ZERO,
            BedrockInputFrame.idle(0L),
            BedrockCollisionFlags.AIR,
            Medium.AIR);
    }

    private static BedrockMovementContext context(Medium medium) {
        return new BedrockMovementContext(
            BedrockEffectState.NONE,
            AttributeState.DEFAULT,
            new WorldContactState(medium, FluidState.NONE, new BlockCollisionWorld(List.of())),
            EquipmentState.NONE,
            EntityContactState.NONE,
            MovementModifierState.NONE,
            PlayerDimensionsState.DEFAULT);
    }
}
