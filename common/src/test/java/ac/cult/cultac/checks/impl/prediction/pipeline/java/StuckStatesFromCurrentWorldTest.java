package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** applyEffectsFromBlocks reads the world the client ends its tick in, which can already hold a new cobweb. */
class StuckStatesFromCurrentWorldTest {
    private static final Vec3 MOVEMENT = new Vec3(0.0D, -0.05D, 0.0D);
    private static final Vec3 END = new Vec3(0.5D, 64.0D, 0.5D);

    @Test
    void aCobwebPlacedAlongTheLastMoveAddsItsStuckState() {
        CultPlayer player = player();
        try {
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            JavaPredictionCarry.State free = new JavaPredictionCarry.State(new Vec3(0.0D, -0.08D, 0.0D), 0.0D, null);
            JavaPredictionCarry carry =
                    new JavaPredictionCarry(player.compensatedEntities.getSelf(), 0.0D, List.of(free));
            PredictionResult last = lastMove(player);

            assertSame(carry, JavaMovementEngine.withStuckStatesFromCurrentWorld(player, last, carry));

            player.compensatedWorld.updateBlock(
                    0,
                    64,
                    0,
                    DataTables.defaults().registry().block("minecraft:cobweb").defaultState());
            JavaPredictionCarry stuck = JavaMovementEngine.withStuckStatesFromCurrentWorld(player, last, carry);

            assertEquals(2, stuck.states().size());
            assertSame(free, stuck.states().getFirst());
            assertEquals(free.velocity(), stuck.states().get(1).velocity());
            assertEquals(
                    Collisions.getCobwebStuckSpeed(player),
                    stuck.states().get(1).stuckSpeed());
        } finally {
            Object channel = player.user.getChannel();
            player.onRemove();
            if (channel instanceof EmbeddedChannel embedded) {
                embedded.runPendingTasks();
                embedded.runScheduledPendingTasks();
                embedded.finishAndReleaseAll();
            }
        }
    }

    private static PredictionResult lastMove(CultPlayer player) {
        SimulationContext context = mock(SimulationContext.class);
        when(context.getVehicle()).thenReturn(null);
        when(context.getEntities()).thenReturn(player.compensatedEntities);
        when(context.getStart()).thenReturn(END.subtract(MOVEMENT));
        when(context.getEnd()).thenReturn(END);
        when(context.getToActualPose())
                .thenAnswer(ignored -> GetBoundingBox.getBoundingBoxFromPosAndSize(END.x, END.y, END.z, 0.6F, 1.8F));
        when(context.getLastStuckSpeed()).thenReturn(new Vec3(1.0D, 1.0D, 1.0D));
        ValidMovements valid = mock(ValidMovements.class);
        when(valid.getClosestTrace()).thenReturn(MovementTrace.start(new PredVector(MOVEMENT)));
        PredictionResult last = mock(PredictionResult.class);
        when(last.getSimulationContext()).thenReturn(context);
        when(last.getValidMovements()).thenReturn(valid);
        when(last.getLegacyLikePredictionVector()).thenReturn(MOVEMENT);
        when(last.getAcceptedClosestToTarget()).thenReturn(MOVEMENT);
        when(last.getJavaCommittedMove())
                .thenReturn(new JavaFallDistance.Moved(0.0D, MOVEMENT, false, DesyncStatus.FALSE));
        return last;
    }

    private static CultPlayer player() {
        OfflineCultTestBootstrap.installConfig();
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), "Stuck_States"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        user.bind(mock(PlatformPlayer.class));
        return player;
    }
}
