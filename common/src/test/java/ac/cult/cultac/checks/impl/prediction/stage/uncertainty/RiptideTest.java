package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.TrigHandler;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

public class RiptideTest {
    /** TridentItem#releaseUsing moves the player up by 1.1999999F only when the client was on ground. */
    @Test
    public void releaseLiftsOnlyFromTheCarriedGroundState() {
        assertEquals(Set.of(0.0D), lifts(DesyncStatus.FALSE));
        assertEquals(Set.of((double) 1.1999999F), lifts(DesyncStatus.TRUE));
        assertEquals(Set.of(0.0D, (double) 1.1999999F), lifts(DesyncStatus.UNKNOWN));
    }

    private static Set<Double> lifts(DesyncStatus carriedOnGround) {
        OfflinePlayers.installConfig();
        CultPlayer player = OfflinePlayers.player();
        try {
            WorldData worldData = mock(WorldData.class);
            when(worldData.getCarriedOnGround()).thenReturn(carriedOnGround);
            SimulationContext context = mock(SimulationContext.class);
            when(context.getRiptideLevel()).thenReturn(1);
            when(context.isRiptideReleaseCertain()).thenReturn(true);
            when(context.getWorldData()).thenReturn(worldData);
            when(context.getTrig()).thenReturn(new TrigHandler(player));
            when(context.getStart()).thenReturn(new Vec3(0.0D, 0.0D, 0.0D));
            when(context.getFromActualPose()).thenReturn(new SimpleCollisionBox(-0.3D, 0.0D, -0.3D, 0.3D, 1.8D, 0.3D));
            double[] lift = {0.0D};
            doAnswer(call -> lift[0] = call.getArgument(0)).when(context).setRiptideGroundLift(anyDouble());
            when(context.getRiptideGroundLift()).thenAnswer(call -> lift[0]);
            PredictionResult result = mock(PredictionResult.class);
            when(result.getSimulationContext()).thenReturn(context);

            Set<Double> lifts = new TreeSet<>();
            for (PredVector released : Riptide.release(player, context, List.of(new PredVector(Vec3.ZERO)))) {
                lifts.add(new Riptide()
                        .handleMovementTrace(player, null, result, context, null, MovementTrace.start(released), null)
                        .positionOnlyDelta()
                        .y);
            }
            return lifts;
        } finally {
            OfflinePlayers.close(player);
        }
    }
}
