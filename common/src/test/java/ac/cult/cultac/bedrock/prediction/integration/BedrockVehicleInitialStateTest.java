package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * A capture of a donkey mounted in flowing water: on the client's first tick in it the donkey moves 0.014 along the
 * flow and ends the tick at 0.0112, from rest.
 */
public final class BedrockVehicleInitialStateTest {
    private static final Vec3 START = new Vec3(0.5, 64, 2.5);

    @Test
    public void aMountTheClientStartsPredictingStartsAtRestInAFlow() {
        var world = flow();
        var player = BedrockInitialStateFactory.resolve(context(null), BedrockInputFrame.idle(1), world, null, null);
        // The world flows here: an actor first seen in it carries the push.
        assertNotEquals(Vec3d.ZERO, player.velocity());

        var horse = Mockito.mock(PacketEntityHorse.class);
        horse.bedrockAttributes = ac.cult.cultac.bedrock.prediction.state.BedrockActorAttributes.EMPTY;
        var mount = BedrockInitialStateFactory.resolve(context(horse), BedrockInputFrame.idle(1), world, null, null);
        assertEquals(Vec3d.ZERO, mount.velocity());
    }

    private static SimulationContext context(PacketEntityHorse horse) {
        var context = Mockito.mock(SimulationContext.class);
        Mockito.when(context.getVehicle()).thenReturn(horse);
        Mockito.when(context.getStart()).thenReturn(START);
        return context;
    }

    /** A stone floor and a water source at z=0 flowing along +z, one level lower each block. */
    private static BedrockMovementContext flow() {
        var blocks = new ArrayList<PlacedBlockCollision>();
        blocks.add(PlacedBlockCollision.manual(
                new BlockPosition(-2, 63, -2),
                "minecraft:stone",
                "minecraft:stone",
                java.util.List.of(new WorldCollisionBox(-2, 63, -2, 3, 64, 8))));
        for (int z = 0; z <= 5; z++) {
            String water = z == 0 ? "minecraft:water[level=0]" : "minecraft:water[level=" + z + "]";
            blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(0, 64, z), water, "minecraft:water", java.util.List.of()));
        }
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.WATER, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
    }
}
