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
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
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
            // Bubble callbacks have already produced the carried velocity before this travel. Each candidate
            // must use its own Y, including zero (LivingEntity#getEffectiveGravity tests y <= 0).
            for (double y : new double[] {VELOCITY.y, 0.0D, PUSHED_Y}) {
                double gravity = y <= 0 ? SLOW_FALLING : GRAVITY;
                Vec3 legit = glide(player, y, gravity);
                assertEquals(0.0D, legit.distanceTo(transform(player, y, legit)), 1.0E-9D);
                Vec3 swapped = glide(player, y, y <= 0 ? GRAVITY : SLOW_FALLING);
                double offset = swapped.distanceTo(transform(player, y, swapped));
                assertTrue(offset > 0.01D, () -> "accepted " + swapped + " at " + offset);
            }
        });
    }

    /**
     * A fishing pull whose bobber position is hidden leaves the glider a deltaMovement range spanning zero. Every
     * velocity in it glides with the gravity LivingEntity#getEffectiveGravity gives its own Y: one just above zero on
     * the ordinary gravity, one at or below zero on slow falling's.
     */
    @Test
    void aRangeSpanningZeroGlidesEachSideWithItsOwnGravity() {
        withPlayer(player -> {
            var actor = mock(PacketEntity.class);
            actor.hasGravity = true;
            actor.gravity = GRAVITY;
            var entities = mock(CompensatedEntities.class);
            when(entities.getEntityInControl()).thenReturn(actor);
            when(entities.getSlowFallingAmplifier()).thenReturn(0);
            SimulationContext context = context();
            when(context.getEntities()).thenReturn(entities);
            PredVector start = new PredVector(VELOCITY.add(0, 0.044D, 0))
                    .withVelocityRange(new SimpleCollisionBox(0, -0.01D, 0, 0, 0.012D, 0));
            List<PredVector> glides = new java.util.ArrayList<>();
            for (PredVector candidate : ElytraTransform.candidates(context, List.of(start)))
                glides.add(new ElytraTransform()
                        .handleUncertainty(
                                player,
                                mock(ValidMovements.class),
                                mock(PredictionResult.class),
                                context,
                                null,
                                candidate,
                                Vec3.ZERO));
            for (double y : new double[] {start.y - 0.01D, 0.0D, 0.0039D, start.y + 0.012D}) {
                Vec3 legit = glide(player, y, y <= 0 ? SLOW_FALLING : GRAVITY);
                assertTrue(
                        glides.stream().anyMatch(glided -> holds(glided, legit)),
                        () -> "no glide holds " + legit + " from y " + y + " in " + glides);
            }
        });
    }

    private static boolean holds(PredVector glided, Vec3 velocity) {
        SimpleCollisionBox range = glided.getVelocityRange();
        if (range == null) return glided.distanceTo(velocity) < 1.0E-12D;
        return velocity.x >= glided.x + range.minX - 1.0E-12D
                && velocity.x <= glided.x + range.maxX + 1.0E-12D
                && velocity.y >= glided.y + range.minY - 1.0E-12D
                && velocity.y <= glided.y + range.maxY + 1.0E-12D
                && velocity.z >= glided.z + range.minZ - 1.0E-12D
                && velocity.z <= glided.z + range.maxZ + 1.0E-12D;
    }

    private static SimulationContext context() {
        SimulationContext context = mock(SimulationContext.class);
        when(context.usesFallFlyingMovement()).thenReturn(true);
        when(context.getWorldData()).thenReturn(mock(WorldData.class));
        when(context.getVersion()).thenReturn(ClientVersion.V_26_1);
        when(context.getXRot()).thenReturn(YAW);
        when(context.getYRot()).thenReturn(PITCH);
        return context;
    }

    private static Vec3 glide(CultPlayer player, double y, double gravity) {
        return ElytraTransform.getElytraMovement(
                player, new Vec3(VELOCITY.x, y, VELOCITY.z), ReachUtils.getLook(player, YAW, PITCH), PITCH, gravity);
    }

    private static Vec3 transform(CultPlayer player, double startingY, Vec3 end) {
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

        return new ElytraTransform()
                .handleUncertainty(
                        player,
                        mock(ValidMovements.class),
                        result,
                        context,
                        null,
                        new PredVector(new Vec3(VELOCITY.x, startingY, VELOCITY.z)),
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
