package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * A slow-falling glider over an upward bubble column: the column's push leaves the starting y anywhere from below to
 * above zero, and LivingEntity#getEffectiveGravity gives each starting y its own gravity.
 */
class ElytraTransformGravityTest {
    private static final double SLOW_FALLING = 0.01D, GRAVITY = 0.08D;
    private static final float YAW = -10.0F, PITCH = 20.0F;
    private static final Vec3 VELOCITY = new Vec3(0.3D, -0.05D, 0.6D);
    private static final double PUSHED_Y = VELOCITY.y + 0.1D;

    @Test
    void eachStartingYGlidesWithItsOwnGravity() {
        withPlayer(player -> {
            // A falling start glides with slow falling's gravity, a rising one (such as one a bubble column pushed
            // up last tick) with the full gravity.
            assertEquals(
                    0.0D,
                    glide(player, VELOCITY.y, SLOW_FALLING)
                            .distanceTo(transform(player, VELOCITY.y, glide(player, VELOCITY.y, SLOW_FALLING))),
                    1.0E-9D);
            assertEquals(
                    0.0D,
                    glide(player, PUSHED_Y, GRAVITY)
                            .distanceTo(transform(player, PUSHED_Y, glide(player, PUSHED_Y, GRAVITY))),
                    1.0E-9D);
            for (double startY : new double[] {VELOCITY.y, PUSHED_Y}) {
                Vec3 swapped = glide(player, startY, startY > 0.0D ? SLOW_FALLING : GRAVITY);
                double offset = swapped.distanceTo(transform(player, startY, swapped));
                assertTrue(offset > 0.01D, () -> "accepted " + swapped + " at " + offset);
            }
        });
    }

    private static Vec3 glide(CultPlayer player, double y, double gravity) {
        return ElytraTransform.getElytraMovement(
                player, new Vec3(VELOCITY.x, y, VELOCITY.z), ReachUtils.getLook(player, YAW, PITCH), PITCH, gravity);
    }

    private static Vec3 transform(CultPlayer player, double startY, Vec3 end) {
        WorldData worldData = mock(WorldData.class);
        SimulationContext context = mock(SimulationContext.class);
        when(context.usesFallFlyingMovement()).thenReturn(true);
        when(context.getWorldData()).thenReturn(worldData);
        when(context.getVersion()).thenReturn(ClientVersion.V_26_1);
        when(context.getXRot()).thenReturn(YAW);
        when(context.getYRot()).thenReturn(PITCH);
        PredictionResult result = mock(PredictionResult.class);
        when(result.getSimulationContext()).thenReturn(context);
        when(result.getPossibleGravity(anyDouble(), anyDouble())).thenAnswer(call -> {
            double min = call.getArgument(0), max = call.getArgument(1);
            if (min <= 0.0D && max > 0.0D) return List.of(SLOW_FALLING, GRAVITY);
            return List.of(max <= 0.0D ? SLOW_FALLING : GRAVITY);
        });

        PredictionResult lastResult = mock(PredictionResult.class);

        return new ElytraTransform()
                .handleUncertainty(
                        player,
                        mock(ValidMovements.class),
                        result,
                        context,
                        lastResult,
                        new PredVector(new Vec3(VELOCITY.x, startY, VELOCITY.z)),
                        end);
    }

    private static void withPlayer(Consumer<CultPlayer> test) {
        OfflineCultTestBootstrap.installConfig();
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), "Glide_Gravity"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        user.bind(mock(PlatformPlayer.class));
        try {
            test.accept(player);
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
}
