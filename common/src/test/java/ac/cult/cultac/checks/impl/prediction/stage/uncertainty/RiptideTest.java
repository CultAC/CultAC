package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.TrigHandler;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.Test;

public class RiptideTest {
    /** TridentItem#releaseUsing adds one push to each carried delta; its later move can replace that delta. */
    @Test
    public void releasePushKeepsItsCarriedVelocityAndAddsTheStrengthOnce() {
        OfflinePlayers.installConfig();
        CultPlayer player = OfflinePlayers.player();
        try {
            SimulationContext context = mock(SimulationContext.class);
            when(context.getRiptideLevel()).thenReturn(3);
            when(context.getTrig()).thenReturn(new TrigHandler(player));
            when(context.getYRot()).thenReturn(-90.0F);
            PredVector carried = new PredVector(new Vec3(0.1, -0.005, 0.2));
            for (PredVector released : Riptide.releasePushes(context, List.of(carried))) {
                var pushed = Riptide.pushOf(context, released);
                assertSame(carried, pushed.carried);
                assertEquals(3.0, pushed.push.length(), 1.0E-6);
                assertEquals(carried.y + pushed.push.y, released.y, 0.0);
                assertEquals(carried.x + pushed.push.x, released.x, 0.0);
                assertEquals(carried.z + pushed.push.z, released.z, 0.0);
            }
        } finally {
            OfflinePlayers.close(player);
        }
    }
}
